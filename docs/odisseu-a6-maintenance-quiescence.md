# Odisseu A6 — manutenção e quiescência

**Decisão: CONDITIONAL**
**Escopo executado:** implementação e homologação local, no clone `/private/tmp/zapbot-announcer-a6-rc-final`. Nenhuma conexão ou alteração em produção foi realizada. Não houve backup cold, parada/restart live, migration, deploy ou envio WhatsApp real.

## Resultado

Foi implementado um gate durável de admissão e drain no bridge JVM e um tracker de operações do lado Node. Em ambiente descartável, a JVM HTTP real recusou operações enquanto estava em manutenção, preservou Datomic durante restart e só reabriu após comando explícito de retomada. Os testes locais de recursos e os smokes de bridge, lembretes, raid e scheduler passaram.

O gate ainda não basta para certificar quiescência global em produção: o contador de operações Node é local ao processo; clientes Datomic diretos que contornem o bridge não são bloqueados; e o cold backup/restore operacional não foi executado nem homologado nesta fase. Portanto, não usar a implementação como autorização para backup ou cutover sem satisfazer as pré-condições abaixo.

## Entradas mutáveis mapeadas

| Entrada/fluxo | Início e rastreamento | Limite de persistência/proteção |
|---|---|---|
| Mensagens e comandos WhatsApp (incluindo caçadas, batalhas, captura, recompensas e trocas) | `message_create` é rastreado no Node desde o início do processamento assíncrono até a conclusão; comando novo durante drain recebe resposta amigável | Mutação passa pelo bridge JVM; todas as rotas autenticadas, exceto health e manutenção, são recusadas antes do dispatch quando o gate está fechado |
| Mudanças administrativas WhatsApp tratadas pelo Node | Handler acompanhado pelo tracker Node | Drenadas antes de confirmar entrada do Node em manutenção |
| Eventos/polls de raid, evento temático e entrega relacionada | Tick completo, incluindo claim/envio/ack, acompanhado pelo tracker Node | Mutação persistente no bridge também fica bloqueada; não houve alteração no scheduler ou na política de raides |
| Lembretes Datomic e Cassandra | Ciclo de poll, claim, envio e conclusão acompanhado pelo tracker; ticks assíncronos aguardados | Bridge fecha novas operações durante drain; semântica e receipts de lembretes permanecem separados dos anúncios |
| Sweeper JVM de batalha ociosa | Operação usa o mesmo gate de requests da JVM | Aguarda ou é recusado quando a JVM está em drain |
| Chamadas internas Node → JVM/bridge | Contadas como requests ativos na JVM; requests iniciados pelo Node são abrangidos pelo tracker dos respectivos fluxos | O gate JVM é autoritativo para rotas do bridge |
| Outros produtores JVM/Datomic | Um writer JVM por armazenamento é esperado no arranjo atual; o lock de writer existe no servidor configurado | **Não comprovado para clientes Datomic que conectem diretamente e escrevam fora do bridge**; devem ser excluídos por preflight |

O inventário de chamadas e seus limites está restrito ao código local deste clone e não prova ausência de outros processos ou clientes na VM.

## Mecanismo implementado

### JVM

Novo `maintenance_gate.clj` grava `open`, `draining` ou `quiescent` em `bridge-maintenance.edn`, com escrita temporária, `fsync` do arquivo e rename atômico. A admissão fecha antes de aguardar requests já ativos. Se o timeout vencer, permanece `draining` e não declara quiescência. Na inicialização, estado persistido é respeitado; estado malformado ou temporário abandonado falha fechado.

Rotas internas do bridge:

- `GET /ops/maintenance/status`
- `POST /ops/maintenance/enter?timeoutMs=...`
- `POST /ops/maintenance/resume`

Controle requer token dedicado `X-A6-Maintenance-Token`, distinto do token normal do bridge. Logs não registram seu valor. Enquanto fechado, requests autenticados de jogo recebem 503 antes de parse/dispatch; `/health` e endpoints de manutenção permanecem acessíveis. Leitura de jogo também fica temporariamente indisponível, por segurança.

### Node

Novo `scripts/lib/a6-maintenance.cjs` mantém marker durável separado, em `ODISSEU_NODE_MAINTENANCE_FILE`, e controle com `ODISSEU_NODE_MAINTENANCE_TOKEN`. O endpoint de controle no servidor local de saúde usa `X-A6-Node-Maintenance-Token`. O tracker cobre dispatcher WhatsApp, eventos administrativos, pollers de Pokémon/raids/eventos e lembretes.

O compose local monta os markers nos volumes de dados/manutenção já existentes e exige tokens dedicados. O `.env.odisseu-core.example` contém apenas placeholders vazios. Essa configuração foi preparada no clone e não aplicada em nenhum ambiente.

## Recuperação e garantias

1. Fechar admissão e persistir `draining`.
2. Aguardar o contador ativo chegar a zero. Timeout mantém estado fechado; operador aborta o backup.
3. Persistir `quiescent` somente depois do drain.
4. Após crash/restart, o estado fechado é mantido e exige retomada explícita.
5. Retomar JVM primeiro e Node depois, somente após validação do estado e do fim da operação de manutenção.

Operações já aceitas são aguardadas, não descartadas nem confirmadas antecipadamente. O gate não repete operações e recusa novas chamadas antes da transação. Transações Datomic continuam atômicas; se o processo cair antes de confirmar quiescência, o procedimento deve parar e reconciliar os IDs idempotentes existentes, sem replay automático.

**Limitação importante:** contador Node é por processo. A leitura do marker compartilhado faz outros processos observarem o fechamento, mas não agrega operações já ativas em todos eles. A garantia exige exatamente uma instância Node/WhatsApp no perfil e uma JVM writer, sem produtores Datomic externos. Escalar Node requer contador/lease distribuído durável antes de considerar o gate global.

Os writes fazem `fsync` do arquivo temporário e rename atômico, mas não sincronizam o diretório pai; durabilidade perante perda abrupta de energia/filesystem não foi demonstrada. Foram testados crashes/restarts de processos, não falha física do host.

## Testes executados

| Verificação | Resultado |
|---|---|
| `node --test test/a6-maintenance.test.cjs` — estado ativo, drain, timeout fail-closed, restart de processo, marker inválido/temporário e segundo processo | PASS, 6/6 |
| `npm run test:resources` | PASS, 69/69 |
| Build `npm run build` após as alterações | PASS, build concluído (105 arquivos; avisos de compilação registrados) |
| Smoke JVM HTTP real + Datomic Local descartável: token incorreto, health, enter/drain, recusas 503, contagem Datomic invariável, restart e resume explícito | PASS |
| JVM filha com PID distinto reutilizando o mesmo estado Datomic/marker | PASS; estado fechado persistiu após restart físico de processo |
| `clojure -M:test-m4-bridge` | PASS |
| `clojure -M:test-m7-5-lembretes` | PASS; lembretes M7 e smoke bridge sem falhas |
| `clojure -M:test-m4-raid` | PASS; regressões de raid/gym e smoke HTTP |
| `clojure -M:test-raid-auto-cadence-fase1282` | PASS; 66 testes, 361 assertions, zero falhas/erros |
| `npm run test:http` | **FAIL parcial:** 285/286 passaram; `playable-save-activation.test.cjs`, “real eligibility gate blocks each impediment and isolates the peer”, falhou com `Missing expected rejection`. Não houve alteração de saves nesta tarefa; permanece regressão impeditiva a investigar, sem evidência aqui para atribuir sua origem ao gate de manutenção. |
| `git diff --check` | PASS |

O teste HTTP amplo não ficou verde; não se declara regressão preexistente sem comparação reproduzida contra uma base limpa nesta execução. O E2E WhatsApp/Node real não foi feito: transporte real não foi utilizado e o harness não enviou mensagens.

## Relação com backup cold e schema 007

Nenhum backup foi criado nesta fase. Não foi executado o script operacional de cold backup, parada de JVM, cópia/restore de volumes ou verificação da schema 007 na produção. Também não foi aplicada migration. Schema 007 continua fora da validação operacional desta entrega.

### Procedimento proposto para homologação futura (não executado)

1. Confirmar host, saúde, volumes, backup anterior e ausência de operações críticas pendentes.
2. Confirmar uma instância Node e uma JVM writer; excluir conexões Datomic diretas ou produtores externos.
3. Colocar Node em `draining`; verificar contador zero e status persistido.
4. Colocar JVM em `draining`; aguardar requests/sweeper ativos; exigir estado `quiescent`.
5. Se qualquer timeout, marker inconsistente ou divergência ocorrer, abortar sem parar processos nem iniciar cópia cold.
6. Seguir o procedimento de backup Datomic homologado, com janela/parada explicitamente autorizada, manifesto, checksums e restore em cópia isolada. Não copiar arquivos ativos de forma inconsistente.
7. Consultar schema 007 somente na cópia isolada e comparar atributos; não aplicar schema durante essa inspeção.
8. Validar backup/restore e health. Manter Announcer desabilitado e allowlist piloto intacta.
9. Retomar JVM primeiro e Node depois; confirmar health e ausência de replay/duplicação.

Isso é um plano, não uma autorização para executar. A operação cold concreta precisa ser compatível com a semântica de backup do Datomic instalado e ter procedimento de restauração validado.

## Rollback

- Antes de entrar em manutenção: nenhuma mudança operacional; abortar livremente.
- Timeout ou falha ao entrar: manter gates fechados, não iniciar backup, investigar requests e operações idempotentes.
- Durante manutenção: não remover marker manualmente nem iniciar segundo writer. Corrigir causa e usar endpoint autenticado de resume após confirmar que JVM e Node estão consistentes.
- Falha após atualização futura: restaurar imagens/configuração anteriores somente após validar compatibilidade e sem restaurar um banco antigo sobre dados novos. Preservar markers e evidências até reconciliação.

## Arquivos locais alterados

- `.env.odisseu-core.example`
- `docker-compose.odisseu-core.yml`
- `package.json`
- `apps/odisseu/src/odisseu/bridge/http_server.clj`
- `apps/odisseu/src/odisseu/smoke/maintenance_quiescence.clj`
- `modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj`
- `modules/pokemon/infrastructure/pokemon/infrastructure/http/maintenance_gate.clj`
- `scripts/lib/a6-maintenance.cjs`
- `src/zapbot/core.cljs`
- `src/zapbot/lembretes.cljs`
- `src/zapbot/pokemon_http.cljs`
- `src/zapbot/whatsapp_saude.cljs`
- `test/a6-maintenance.test.cjs`

Não houve mudança de schema ou migration, regras econômicas, saves, grupos, sessão WhatsApp ou configuração live. No momento daquele registro, as mudanças estavam locais e sem commit; os commits temáticos foram criados na revisão de 2026-11-04 abaixo. `node_modules` aparece como symlink não rastreado preexistente no clone; não foi incluído no trabalho.

## Gates para avançar

Status permanece **CONDITIONAL** até: (1) a falha de save na suíte HTTP ser explicada; (2) pré-flight operacional confirmar topologia de writer único e ausência de Datomic bypass; (3) homologação do procedimento cold com restore em cópia isolada; (4) teste integrado do endpoint de manutenção Node no processo/compose real descartável; e (5) revisão/integração do gate no protocolo operacional de quiescência. Não há base para declarar READY para backup cold ou deploy.

---

## Homologação complementar — auditoria de writers e backup cold

**Resultado desta revisão: BLOCKED para quiescência global e backup cold homologado.** O bloqueio é arquitetural/operacional; não é resolvido pela correção do fixture nem pelo teste de cópia bem-sucedido.

### Inventário de writers Datomic

| Caminho | Capacidade de escrita | Cobertura pelo gate |
|---|---|---|
| JVM principal (`odisseu.bridge.http-server`) | Abre o storage Datomic Local, cria Pokémon e platform stores, e mantém o lock writer `.odisseu-writer.lock` | O lock impede uma segunda instância do entrypoint que usa esse mecanismo. As rotas autenticadas da bridge e o sweeper de batalha usam `maintenance_gate` |
| Rotas HTTP da bridge | Chamam funções dos stores Pokémon e platform, cujos módulos fazem `d/transact` | Coberto antes do dispatch de rotas e por request ativo; tick interno do sweeper também adquire o gate |
| Schedulers e pollers do runtime Node | Raids/eventos/lembretes produzem chamadas ao bridge | Pollers instrumentados pelo tracker Node e persistência protegida pela bridge JVM. O tracker Node não agrega contadores de múltiplos processos |
| Scripts operacionais com `datomic.client.api` | `scripts/a64_apply_028_schema_only.clj`, `a6_032_thematic_rehearsal.clj`, `a6_033_trade_public_rehearsal.clj` e `a6_fase7_schema_rehearsal.clj` contêm escrita Datomic direta | **Não consultam nem adquirem o marker da manutenção.** São utilitários que devem operar exclusivamente em banco descartável; se apontados ao storage ativo após a parada da JVM, podem escrever |
| Pipelines/drills de cutover, import e migration | Namespaces em `apps/odisseu/src/odisseu/cutover/` e `modules/*/migration/` criam clientes/conexões e têm transações diretas | Não integrados ao gate; não são iniciados pelo runtime normal, mas continuam writers possíveis se executados com o storage live |
| Clientes Datomic independentes | Um processo Datomic Local adicional pode conectar diretamente | Teste descartável: a segunda JVM não conectou enquanto outra mantinha o `.lock` nativo do banco. Após encerramento do writer, o lock é liberado e uma nova conexão torna-se possível. O marker da bridge não controla essa conexão |
| Serviço opcional `pokemon-service`/Cassandra | Stores Node próprios possuem operações CQL de escrita | A URL do serviço está vazia no compose local auditado; configuração efetiva de produção não foi lida nesta fase. Não usar isso como evidência do estado remoto |

Os módulos de store concentram `d/transact`, mas não fazem aquisição do gate por conta própria. Sua proteção depende do chamador. Há portanto caminhos locais de ferramenta que contornam o gate. O lock Datomic por database protege contra concorrência enquanto uma conexão writer continua aberta; ele não substitui manutenção durante a janela após encerramento do writer.

### Falha `playable-save-activation.test.cjs`

Reproduzida no clone e em archive limpo do commit-base `79ec9ac8fa3b8ac4688ddc4271e3e3f732746efb`: ambos falhavam no teste `real eligibility gate blocks each impediment and isolates the peer`, com `Missing expected rejection` na asserção para `activity_raid_capture_pending`.

A promise esperada rejeitar era `service.activate('g','p','b', ...)`. O resultado real era resolução bem-sucedida, porque o fixture de raid em fase `vitoria` tinha `participantes.p = {}`. A regra canônica só considera captura pendente quando o participante causou dano positivo, ainda tem tentativas e a janela está aberta. Essa regra aparece tanto em `save-activity-eligibility.cjs` como no domínio de raids; o módulo e o teste já eram iguais à base antes deste trabalho, e o gate de manutenção não participa de `PlayableSaveActivation`.

Correção: fixture agora representa participação elegível com `dano: 1`; foi adicionado teste negativo explícito para vitória com `dano: 0`. Não houve alteração de código de produção, save, saldo ou estado de raide.

Resultado: `node --test pokemon-service/test/playable-save-activation.test.cjs pokemon-service/test/save-activity-eligibility.test.cjs` passou 26/26. `npm run test:http` passou 287/287 quando executado com permissão de loopback local. A execução sob sandbox restrito não é evidência válida para as suítes que abrem sockets.

### Ensaio Datomic Local de snapshot/restore

Foi executado um ensaio limitado e descartável, sem operação de produção:

1. O smoke Datomic Local criou dados sintéticos e encerrou sua JVM.
2. Após o processo escritor sair, `snapshot-storage!` copiou o storage para outro diretório temporário.
3. `restore-storage!` restaurou para um terceiro diretório novo.
4. Uma JVM nova consultou o banco restaurado e confirmou os mesmos campos sintéticos (`moedas=250`, `nivel=3`).
5. Foi calculado manifesto SHA-256 para 3 arquivos; hash do manifesto: `dc2131f8a596ebd7f5a1576bb1700dca4a1b3401a02867ea1c4dca310f5fa35b`.

Esse resultado prova apenas cópia cold simples e leitura após restore em diretório isolado. Uma nova JVM também conseguiu abrir o storage original após o processo criador encerrar, confirmando que clientes voltam a poder conectar quando o writer libera o lock. **Não** inclui operação concorrente real drenada pelo gate, comparação canônica abrangente, checksum/manifesto produzido pelo helper, falha/interrupção no meio de snapshot ou restore, nem validação de backup do volume de produção.

O helper atual `pokemon.migration.backup/snapshot-storage!` remove recursivamente o destino anterior antes de copiar. `restore-storage!` remove recursivamente o destino de restauração antes de copiar. Ambos copiam diretamente para o destino final, sem estágio atômico, manifesto interno ou verificação pós-cópia. Por isso não foi induzida uma interrupção destrutiva: o helper não oferece isolamento/recuperação adequados para afirmar que backup incompleto ou restore interrompido falham com segurança. Este é um gate pendente, não um PASS.

### Bootstrap e contenção da primeira implantação

A produção atual não possui os novos endpoints/markers; portanto não se pode chamar o gate antes de instalar a versão que o contém. Um plano futuro exigiria contenção externa antes do primeiro deploy:

1. Bloquear ingressos de comandos no nível do transporte/proxy e impedir novos jobs/polls; preservar as mensagens aceitas em fila durável ou provar que nenhuma foi aceita em voo.
2. Parar schedulers e produtores independentes e inventariar os processos/conexões com acesso ao volume Datomic. Parar apenas Node não prova que todos os writers cessaram.
3. Aguardar drenagem verificável de requisições existentes; a produção atual não fornece o novo contador persistido, então esse critério ainda não está operacionalizado.
4. Se não houver prova de zero operações, abortar. Não parar a JVM nem copiar storage.
5. Com uma versão que ofereça o gate instalada e validada, fechar Node e bridge, aguardar drain/quiescent, impedir clientes operacionais, encerrar o writer e só então executar o cold snapshot.
6. Verificar manifesto e restaurar para storage isolado; auditar schema 007 somente nessa cópia.
7. Retomar JVM e validar health antes de reabrir Node; Announcer permanece desabilitado.

Os passos 1–4 ainda carecem de mecanismo/procedimento comprovado para o bootstrap sem gate. Esta versão não autoriza a primeira implantação nem o backup cold.

### Gates de falha ainda sem homologação

| Cenário solicitado | Resultado |
|---|---|
| Cliente secundário tenta abrir o mesmo DB enquanto Datomic Local writer mantém lock | FAIL-CLOSED comprovado em duas JVMs descartáveis |
| Cliente/script após writer ser encerrado | Pode conectar; scripts ignoram marker. Requer contenção externa; gate atual não garante bloqueio |
| Processo interno ativo e drain | Tracker/gate testados; não foi acoplado a uma transação Datomic real lenta durante snapshot |
| Timeout de drain/restart durante manutenção | Marcador permanece `draining`; smoke anterior aprovado |
| Interrupção em backup/restore, backup parcial e manifesto inválido | Não homologado; helper não tem staging/manifesto e sobrescreve destino |
| Reinício inesperado durante a janela cold | Persistência dos markers em restart de processo demonstrada; recovery após crash de host/energia não demonstrada |

### Artefatos e decisão

Naquele complemento ainda não havia commit, content-tree ou imagens. O ajuste de fixture foi justificado e limitado a teste; código de produção do Announcer/gate permaneceu sem mudança naquele passo.

**Decisão final: BLOCKED para READY de quiescência, backup cold e deploy consolidado.** Para desbloquear, é necessária uma estratégia de exclusão verificável de todo acesso ao storage durante o período sem writer, além de um backup/restore com staging, manifesto, validação e ensaios de interrupção. A primeira implantação também precisa de contenção externa e drain comprovado antes de qualquer substituição da JVM atual.

---

## Fechamento local da Fase — 2026-11-04

Esta seção substitui as descrições anteriores do helper de backup: o helper foi endurecido depois da revisão complementar. A decisão de produção continua **BLOCKED** porque os mecanismos locais não são uma fronteira de segurança contra qualquer processo que tenha acesso direto ao volume Datomic.

### Contenção implementada e limite comprovado

- `odisseu.persistence.datomic/client` agora lê o marker canônico `<storage>/.a6-maintenance/bridge-maintenance.edn` antes de admitir novos clientes do repositório. `draining`, `quiescent`, EDN inválido, diretório ilegível e escrita temporária abandonada falham fechados. A bridge usa uma opção de controle explícita para iniciar em manutenção; nesse estado ela só conecta bancos existentes e rejeita `RESET`/seed.
- A bridge exige que seu diretório de marker seja o diretório canônico dentro do storage, evitando que a configuração operacional e os scripts observem markers diferentes. O Compose local foi alinhado. Isso não altera configuração live.
- Os scripts/rehearsals encontrados usam em geral `persistence/client` e, portanto, são recusados quando iniciados com o marker fechado. Há utilitários de baixo nível e código que chama `datomic.client.api` diretamente; um processo que já construiu um client antes do marker, um processo que deliberadamente contorne o construtor central, um writer externo ou uma escrita direta de arquivos não é revogado pelo marker.
- O helper de snapshot adquire `.odisseu-writer.lock` quando presente e todos os locks `.lock` de bancos Datomic encontrados, mantém esses locks durante a cópia e falha se encontrar writer ativo ou inventário vazio/instável. Um processo JVM concorrente que tenta abrir o banco existente durante a cópia foi negado pelo lock Datomic. Os locks são advisory e não bloqueiam processos com permissão para editar arquivos do volume diretamente; tampouco provam que ninguém criará um banco novo fora dos entrypoints conhecidos.
- Portanto, a prova local cobre entrypoints do repositório que respeitam o construtor central e clientes Datomic Local concorrentes para os bancos existentes. Ela **não prova contenção global de todos os processos da VM** nem bootstrap seguro da primeira manutenção. O supervisor/ACL da produção ainda precisa impedir launch de scripts/containers e acesso de escrita ao volume durante a janela. Sem essa barreira, não parar a JVM e não iniciar backup.

### Backup e restore

`pokemon.migration.backup/snapshot-storage!` agora:

1. recusa origem ausente, paths sobrepostos e destino já existente;
2. usa um staging sibling único e não publica staging incompleto;
3. obtém locks do writer e dos bancos antes de copiar;
4. recusa links simbólicos e arquivos especiais;
5. copia permissões/mtime, força arquivos e diretórios quando suportado, gera manifesto EDN com inventário, tamanhos, permissões e SHA-256;
6. valida o staging contra o manifesto e publica somente com rename atômico; filesystem sem rename atômico é rejeitado;
7. preserva backups anteriores e deixa staging incompleto para inspeção/remoção explícita.

Arquivos voláteis de lock Datomic e o lock do writer não entram no conteúdo restaurável nem no manifesto. São locks de runtime, não estado de banco; copiá-los poderia interferir com locks do processo atual.

`restore-storage!` valida manifesto e hashes, restaura primeiro em staging e só publica em destino ausente ou vazio. Destino não vazio é recusado, nunca apagado. A restauração para um storage operacional ocupado ou sobre saves mais novos não é suportada pelo helper; exige procedimento separado, serviços parados, contenção externa e revisão humana. O destino vazio é publicado atomicamente, sem remoção prévia. `discard-staging!` é explícito e limita a limpeza a um staging filho direto com nome esperado.

### Ensaios descartáveis executados

| Cenário | Resultado observado |
|---|---|
| JVM Datomic independente mantém conexão ativa; snapshot tenta começar | Recusado por lock ativo |
| Outro processo tenta conectar/escrever banco existente durante cópia | Negado; probe registrou `external-lock-denied=true` e `external-denied=true` |
| Cópia cold para diretório novo, manifesto e verificação | PASS; manifesto conferido, 5 entradas no fixture sintético |
| Destino de backup já existente | Recusado; backup válido anterior continuou verificável |
| Destino inesperado criado durante a cópia, antes da publicação | Recusado no ponto de rename; arquivo do destino concorrente preservado |
| Interrupção injetada após cópia de arquivo / falha simulada de disco | Não publicado; staging incompleto permaneceu isolado para descarte explícito |
| Interrupção injetada após escrita temporária do manifesto | Não publicado; staging permaneceu isolado |
| Restore interrompido depois de copiar arquivo | Não publicado; diretório operacional não foi substituído |
| Checksum adulterado | Verificação e restore recusados; destino não foi criado |
| Restore em destino novo | PASS; processo JVM independente consultou o valor sintético `77` |
| Restore sobre destino não vazio | Recusado; valor persistido `77` no destino não foi alterado |
| Marker `draining`, inválido ou temporário abandonado | Novo `persistence/client` recusado; bypass de controle usado apenas pelo bootstrap da bridge no smoke |
| Bridge fecha admissão, reinicia com mesmo storage, continua fechada e retoma por endpoint autenticado | PASS no smoke HTTP loopback descartável |

O cenário de falha de disco foi simulado por exceção injetada no ponto de cópia; não foi provocado ENOSPC real. Não houve backup, restore, acesso ou escrita em produção.

### Regressões executadas nesta revisão

- `clojure -M -e "(require 'pokemon.migration.backup 'odisseu.persistence.datomic 'odisseu.bridge.http-server)"`: namespaces carregados.
- `clojure -M:smoke-a6-cold-backup`: PASS, 12 cenários reportados, banco descartável e processos separados.
- Smoke HTTP de manutenção JVM/bridge: PASS; marker/drain/restart/resume em loopback, Datomic descartável, sem transporte WhatsApp.
- `node --test test/a6-maintenance.test.cjs`: PASS, 6/6.
- `npm run test:http`: PASS, 287/287.
- `clojure -M:test-m4-bridge`: PASS, bridge JVM + Datomic local/HTTP.
- `clojure -M:test-m7-5-lembretes`: PASS; M7.0–M7.5, receipts/restart e smoke sem envio real.
- `clojure -M:test-m4-raid`: PASS, regressões de raid/gym e smoke HTTP.
- `clojure -M:test-raid-auto-cadence-fase1282`: PASS, 66 testes, 361 assertions.
- `npm run build`: PASS, 105 arquivos; compilador emitiu 856 avisos de inferência/depreciação, sem erro.
- `git diff --check`: PASS.

A primeira tentativa dos smokes JVM sem autorização de socket foi bloqueada pelo sandbox (`Operation not permitted`); a repetição autorizada usou somente loopback e passou. Um primeiro processo do smoke de manutenção imprimiu PASS, mas não encerrou prontamente por executor/future; o cleanup passou a chamar `shutdown-agents`, e a execução repetida imprimiu PASS e encerrou normalmente.

### Bootstrap da primeira manutenção e decisão

A produção ainda não tem o marker/gate em todas as imagens. A primeira instalação precisa começar com contenção fora da aplicação: bloquear o ingresso de novas mutações e pollers no supervisor, impedir inicialização de quaisquer jobs/scripts/containers com acesso ao volume e restringir a escrita no volume Datomic a um executor operacional de manutenção. Em seguida, inventariar processos/conexões, drenar o que foi aceito, parar a JVM e só então usar o helper cold. O ambiente desta execução não contém o supervisor da VM nem evidência de ACL capaz de impedir um processo root/Docker ou ferramenta direta de acessar o volume. Esse gate não pode ser inferido dos testes locais.

Sequência futura, ainda não autorizada: contenção externa verificável → drain → parada confirmada da JVM → backup cold e manifesto → restore de verificação em storage isolado → auditoria somente leitura de schema 007 na cópia → autorização operacional separada para transferência/migration/deploy → atualização mantendo `RELEASE_ANNOUNCER_ENABLED=false` → validação → retomada. Qualquer writer não inventariado, lock ativo, volume inesperado, hash divergente ou timeout aborta antes da cópia/deploy.

**Status final: BLOCKED para backup cold, bootstrap e deploy em produção.** A cópia/restore segura está homologada no Datomic descartável; contenção global do host, primeira implantação sem gate e estado live do schema 007 não estão comprovados. Nenhuma migration, imagem, deploy, backup ou restore de produção foi executado. `node_modules` não foi incluído.

### Commits locais auditáveis

- Gate durável e integração Node/JVM: `4c068bcaa0c77e619cc436330bc95e5cd522b5b1`.
- Snapshot/restore Datomic e admission guard: `e1f22f2aafb9a6f5a6d48fd95d3d983869c86b55`.
- Corrida de criação do destino de backup coberta no ponto de publicação: `5450e49aa4ea46403d89214cc2dffd88359efa13`.
- Fixture de elegibilidade de captura com dano zero: `e1713a821821445c2c371a086a2c422fb3fb6a0a`.
- Relatório versionado em commit documental separado após os commits de código. Nenhuma imagem foi reconstruída; não há content-tree ou Image ID novo.

As mudanças de gate afetam Node e JVM; portanto as imagens A6 existentes não contêm estes commits e não podem ser reutilizadas como artefatos desta implementação. Uma futura homologação e preparação de deploy exigiria reconstruir/verificar ambas as imagens contra o commit de código correspondente. O fixture de captura altera somente testes e não exige imagem.

O único item local ainda não rastreado é `node_modules`, symlink preexistente e fora do escopo; não foi staged. O clone não foi publicado nem integrado ao workspace original.

---

## Revisão final de contenção operacional — 2026-11-04

Esta revisão aplica o modelo de ameaça A/B/C solicitado. Ela acrescenta prova do host Docker local descartável e não altera a conclusão sobre produção: **BLOCKED para backup cold, bootstrap ou deploy em produção**. O ambiente disponível é o Docker Desktop desta estação, não a VM `odisseu-core`; não houve acesso remoto nem mudança em serviços, volumes ou credenciais de produção.

### Modelo e fronteira observada

| Grupo | Identidade/capacidade | Resultado e limite |
|---|---|---|
| A — aplicação e scripts conhecidos | Entry points que usam `odisseu.persistence.datomic/client`, bridge, jobs e scripts identificados no repositório | O marker canônico recusa novas construções durante `draining`/`quiescent`; a bridge drena requests já admitidos. Isso cobre somente entry points cooperativos. Um client criado antes do marker não é revogado pelo marker; por isso o procedimento exige drain e encerramento ordenado do writer antes do snapshot. |
| B — processos sem privilégio administrativo | Processo com UID separado, sem acesso ao socket Docker e sem mount/permissão de escrita no storage | Na estação local, o socket é `srwxr-xr-x`, pertencente ao usuário operacional, sem permissão para `other`; o probe como UID 65534 recebeu `DOCKER_SOCKET_CONNECT_DENIED_BY_ACL` ao tentar conectar. Container adicional sem mount não encontrou o storage sintético, e mount read-only rejeitou escrita. Isso demonstra a ACL e isolamento do host local, não a configuração da VM. |
| C — root, usuário com acesso ao socket Docker ou controle equivalente do supervisor | Pode lançar container privilegiado, montar o volume ou alterar o próprio supervisor | Grupo confiável da janela. Não se reivindica proteção contra esse grupo. A contenção exige exclusividade operacional: o executor C declara/retém a janela e não inicia outro writer até retomada. |

Inventário local observado:

- `docker context show` retornou `desktop-linux`; daemon Docker Desktop local, arquitetura `aarch64`, Docker 29.8.2. Isso não é evidência da VM AMD64.
- O usuário local é membro de `admin` e dono do socket Docker. Portanto, nesta máquina ele pertence ao grupo C. O teste de UID 65534 foi separado e limitado a um `connect()` ao socket; não enviou requisição Docker.
- Havia um container pré-existente chamado `festive_lehmann`, com volume Datomic montado em RW. Não foi iniciado, parado, alterado nem removido. Sua presença reforça que inventário e ownership do host precisam preceder qualquer manutenção; ele não é atribuído à produção.
- O workflow de deploy contém `concurrency: production-odisseu`; `scripts/deploy-service.py` usa lock exclusivo não bloqueante sob diretório privado do serviço. Isso serializa chamadas pelo deployer conhecido, mas não impede comandos Docker diretos de C. Não foi encontrado `schedule:` no workflow auditado. Os scripts locais `odisseu-core-backup.sh`/`restore.sh` não formam uma fronteira por si: o restore legado remove o destino com `rm -rf` e não deve ser usado para esta operação.
- Não foi possível inspecionar nesta fase membros do socket Docker, units systemd, timers/cron, processos e ACLs da VM. Esses pontos permanecem desconhecidos, não presumidos ausentes.

### Procedimento mínimo de contenção

Para a topologia observada, sem pretender proteger contra C:

1. O operador C adquire a janela operacional e pausa pelo supervisor existente as pipelines de deploy e todos os jobs/scripts conhecidos que possam criar writers. Registrar quais foram pausados e como serão retomados; não alterar ACL permanente.
2. Fechar a admissão na aplicação/bridge instalada e aguardar `activeRequests=0`/`quiescent`; timeout ou marker inconsistente aborta.
3. Parar ordenadamente Node e demais produtores. Confirmar que processos que usam o storage terminaram; manter os schedulers/pipelines pausados.
4. Parar a JVM writer. Confirmar processos e mounts conhecidos, locks Datomic livres e volume correto. Como C controla Docker, o lock advisory não impede C de iniciar outro container; a exclusividade da janela pelo supervisor/operador é parte necessária da garantia.
5. Executar snapshot cold pelo helper para destino novo; validar manifesto/checksums. Fazer restore apenas em volume isolado e validar leitura/estado canônico. Nunca restaurar backup antigo por cima do volume operacional.
6. Manter manutenção fechada até a verificação. Retomar JVM primeiro, confirmar health e schema/estado; então Node e pollers. Reabrir admissão explicitamente. Liberar pipelines e jobs por último.
7. Em qualquer writer não inventariado, lock ativo, divergência de volume, falha/timeout, checksum inválido ou estado incerto, abortar antes de copiar/retomar e manter a janela fechada até reconciliação.

Esse procedimento é executável no ambiente local descartável e define os passos para a primeira manutenção, mas sua aplicação na produção depende de uma inspeção read-only do supervisor e dos principals da VM. A produção atual ainda não tem comprovadamente o gate instalado; portanto o primeiro fechamento deve usar somente contenção externa do supervisor e drain operacional comprovado. Parar Node isoladamente não prova quiescência.

Para o bootstrap especificamente, não se deve chamar um endpoint/marker que ainda não existe na versão ativa. A sequência externa é: C pausa deploy e jobs de escrita no supervisor; bloqueia novas entradas no ponto de ingresso sem descartar mensagens já aceitas; observa e drena o trabalho em voo por mecanismo operacional verificável; para ordenadamente os produtores; inventaria e para a JVM; comprova que não restou processo com o volume aberto; só então roda o helper cold. **Não há nesta execução evidência de uma fila/contador externo que prove o drain da versão atualmente implantada.** Se a inspeção read-only não encontrar esse sinal e um mecanismo de suspensão do ingresso, o bootstrap permanece BLOCKED e não se para/copia o Datomic. O gate novo passa a cobrir manutenção subsequente após sua instalação, não prova retroativamente o estado anterior.

### Evidência Docker descartável desta revisão

Todos os volumes e containers listados aqui foram criados com prefixo exclusivo `a6-maint-20261104` e foram removidos ao final. A verificação final não encontrou containers ou volumes com esse prefixo. O container preexistente `festive_lehmann` não foi tocado.

| Cenário | Evidência | Resultado |
|---|---|---|
| Writer cooperativo durante manutenção | Novo processo JVM usando o construtor central e o mesmo storage recebeu `datomic_maintenance_active` | PASS |
| Processo adicional sem acesso autorizado ao socket | Probe UID 65534 não conectou ao socket Docker; container sem mount não acessou o storage sintético | PASS no host local |
| Mount explicitamente read-only | Tentativa de escrita no volume sintético foi recusada | PASS |
| Principal writer encerrado e novo writer | JVM HTTP real foi parada; novo processo com mesmo volume foi iniciado e health passou | PASS |
| Marker atravessa restart físico | Após restart no mesmo volume, status HTTP autenticado retornou `quiescent`, `activeRequests=0` | PASS |
| Tentativa de client Datomic de baixo nível durante cold snapshot | Processo JVM separado que ignorava o marker foi bloqueado pelo lock nativo Datomic mantido pelo helper durante snapshot | PASS para DB existente enquanto lock adquirido; não é controle de launch |
| Cold snapshot | Helper executado dentro de container contra volume Datomic descartável, com lock mantido; cópia e manifesto verificaram 8 entradas | PASS |
| Restore isolado | Restore para diretório novo dentro de volume separado; JVM real abriu o storage restaurado e passou health | PASS |
| Retomada controlada | Estado restaurado consultado como `quiescent`; endpoint de manutenção autenticado mudou para `open`; status subsequente confirmou `open`, `activeRequests=0` | PASS |
| Tentativa de iniciar writer adicional sob contenção | Construtor central bloqueado pelo marker; processo direto bloqueado pelo lock enquanto o helper segurava o snapshot | PASS dentro desses limites |
| Reentrada de deployer/root ou processo com Docker socket durante janela | Não foi bloqueada por ACL de volume; pertence ao grupo C | Fora da garantia declarada; exclusividade do operador/supervisor é obrigatória |

O lock Datomic é uma proteção de concorrência sobre bancos existentes enquanto adquirido; não bloqueia um principal C de criar outro container nem protege contra escrita direta no volume. A prova não usa o lock como substituto do supervisor. Não foram testados host de produção, systemd/cron da VM, falha física de disco/host nem ENOSPC real.

Houve duas falhas no harness por montagem em caminhos que colidiam com `VOLUME /data/datomic-local` da imagem. Foram corrigidos os mounts e o destino de restore (`.../dbroot` dentro do volume); não se perdeu nem alterou dado de origem. O restore final foi verificado e retomado como acima. Esses incidentes foram falhas de configuração do harness, não aprovação omitida.

### Código e artefatos locais

- Código congelado: commit `7d55616a35a927bcf1a0efe98848913faa6a6e32`; content-tree `1a03ad0bc8769ddcf08b0b7c90210ee79cf95b33`.
- Imagem JVM construída do código desse commit/tree: `sha256:efc7e4578209984ee2d80d8e3c1cc2f4d4f3c70e8f259747018c96ceeb9d27d5`, `linux/amd64`.
- Imagem Node construída do mesmo código/tree: `sha256:dd27dc57b34c7ba30eec58db64ed9529a483d50b47f3013f7f99d2e1cacec1bd`, `linux/amd64`.
- Smoke JVM da imagem, smoke Node (`node --check` e import de `whatsapp-web.js`), build e suítes listadas acima passaram. O build AMD64 do Node precisou limitar os processadores ativos da JVM de build via Dockerfile temporário fora do repositório; o Dockerfile do projeto e a árvore de código não mudaram. A auditoria npm do build reportou vulnerabilidades no conjunto de dependências; nenhuma atualização foi feita nesta fase.
- As imagens são artefatos locais apenas; não foram publicadas, transferidas ou implantadas. Seus labels não contêm Git SHA/content-tree, então a relação é sustentada pelo comando/contexto de build registrado, não por uma label criptográfica dentro da imagem.

### Decisão e gates restantes

**Local A/B: PASS dentro do modelo de confiança e topologia descartável descritos. Produção/bootstrap: BLOCKED.** Antes de autorizar manutenção/backup/deploy na VM, ainda é necessário read-only confirmar: principal do serviço; todos os usuários/grupos com acesso ao Docker socket; mounts e containers Datomic; unidades/timers/cron e scripts de escrita; pipelines e mecanismo para pausá-los; capacidade de impedir que B lance processos com acesso ao storage; e operador C responsável pela janela exclusiva. Se algum writer não puder ser suspenso ou se a janela exclusiva de C não puder ser garantida, abortar.

Não foi executado deploy, restart, migration, backup cold nem restore em produção; não houve mudança em saves, volumes, configurações, sessão WhatsApp, grupos, raides ou Release Announcer. O Announcer permanece sem ativação. As imagens existentes anteriores não incluem estes commits, portanto não são substitutas dos artefatos locais acima.

---

## Auditoria read-only do supervisor real — 2026-11-04

**Decisão: BLOCKED para bootstrap de manutenção, backup cold e deploy.** A conexão histórica read-only foi restabelecida; os resultados abaixo substituem qualquer inferência baseada apenas no Docker Desktop local. Nenhum container, serviço, volume, ACL, migration ou dado foi alterado. Nenhum comando de jogo foi executado.

### Identidade e acesso

- `hostname=odisseu-core`, `uname -m=x86_64`; metadata GCE também confirmou a instância `odisseu-core`, IP externo `35.192.113.146`.
- Docker Engine `29.9.1`, `x86_64`, root dir `/var/lib/docker`; `docker.service` está ativo e habilitado. `LiveRestore=false`.
- Socket Docker: `root:docker`, modo `0660`. O grupo `docker` tem `caiohclavico`; esse usuário não é membro de `sudo`, mas acesso ao Docker equivale ao controle administrativo do supervisor e pertence ao grupo C. `ubuntu` é membro de `sudo`, também grupo C. O usuário operacional não tem sudo sem senha. O serviço `zapbot-deploy` não existe localmente nesta VM.
- A leitura direta dos mountpoints de volume foi negada ao usuário operacional. Isso é coerente com a proteção de arquivos para B, mas não limita C: um membro de `docker` pode iniciar container e montar os volumes. Não alterei ACLs nem fiz uma tentativa de escrita em produção.

### Containers, processos e mounts

| Container | Estado/health | Imagem e Image ID | Restart | Mounts relevantes |
|---|---|---|---|---|
| `odisseu-a6-node` | running / healthy | `odisseu-node:a6-trades-20261104T132934Z-f4e0972046d3-amd64`, `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` | `no` | Sessão WhatsApp bind RW e volume de dados RW; sem mount Datomic |
| `odisseu-a6-jvm` | running / healthy | `odisseu-jvm:a6-trades-20261104T132934Z-f4e0972046d3-amd64`, `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` | `no` | Único container ativo com volume `odisseu-a6-cutover-datomic` RW; também journal e maintenance RW |
| `odisseu-core-node` | exited 0 | tag A64 antiga, imagem preservada | `unless-stopped` | Sessão e volume de dados legados, separados do A6 ativo |
| `odisseu-core-jvm` | exited 143 | tag A64 antiga, imagem preservada | `unless-stopped` | Volume Datomic legado `odisseu-core-a56-datomic`, separado do A6 ativo |

Os containers não estão em modo privileged e não declaram `CapAdd`, mas executam como root dentro dos respectivos namespaces. O processo Java observado corresponde à JVM ativa; Node/Chromium correspondem ao container Node ativo. Não apareceu segundo processo Java/JVM no inventário de processos. O Node estava healthy; este preflight não confirmou independentemente o estado funcional `WhatsApp READY` (o health check Docker não prova essa condição, e a busca booleana por sinal recente no log não encontrou correspondência).

Os volumes ativos A6 e os volumes do par A64 parado são distintos; não há mount compartilhado do Datomic atual entre os containers listados. Volumes `odisseu-core-*` também continuam cadastrados, mas não aparecem montados nos containers listados. Isso não impede C de montar qualquer volume ou criar outro container.

Variáveis não secretas consultadas seletivamente: `ALLOW_ALL_GROUPS=true`, `POKEMON_RAID_AUTO_TICK=false` e `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`. Não imprimi valores de outras variáveis. Nenhuma flag foi alterada.

### Supervisores, cron, deploy e entrypoints de escrita

- Serviços ativos incluem `docker.service`, `containerd.service`, `cron.service`, `google-guest-agent.service`, `google-osconfig-agent.service` e `unattended-upgrades.service`. Não há unit/timer com nome Odisseu/Zapbot na lista consultada. Timers observados são de manutenção do sistema/Google (apt, sysstat, logrotate, fstrim, OS Login etc.) e não identificam um writer do jogo. O timer de usuário observado é `launchpadlib-cache-clean`.
- A crontab do usuário operacional não foi observada. O conteúdo da crontab root não é legível sem privilégios; `sudo -n` falhou por exigir senha. Os arquivos de cron de sistema visíveis são os jobs padrão `e2scrub_all` e `sysstat`, além de `/etc/crontab`. **Não é possível excluir jobs root ou de outros usuários sem completar essa inspeção privilegiada.**
- A aplicação ativa tem um writer Datomic JVM. As rotas Node mutáveis chegam a ele pelo bridge. O Node não monta Datomic. Scripts operacionais como `scripts/a6_batch_rehearsal.clj`, `scripts/a6_recovery_rehearsal.clj`, rotinas de cutover/migration e `scripts/odisseu-core-backup.sh` podem abrir JVM/clientes ou containers auxiliares; alguns contornam o bridge. `scripts/odisseu-core-restore.sh` e os helpers de staging contêm operações de remoção/substituição de storage; não são mecanismo de contenção e não devem ser usados como restore de manutenção. Esses entrypoints são executáveis por C e não existe ACL que impeça C de iniciá-los.
- O workflow no checkout da VM declara `push`, `pull_request` e `workflow_dispatch`; jobs de produção usam o environment `production` e concorrência `production-odisseu`. Push pode implantar se `AUTO_DEPLOY_ENABLED=true`; o estado dessa variável e as regras de aprovação do environment não foram acessíveis por SSH. A concorrência serializa jobs do GitHub, mas não bloqueia Docker direto nem scripts locais.
- O workflow aponta SSH para `35.238.24.225` e usuário `zapbot-deploy`, enquanto o metadata desta VM confirma `35.192.113.146`; `zapbot-deploy` está ausente localmente. Não conectei ao endereço divergente. A identidade do alvo CI/CD precisa ser reconciliada antes da janela. O helper `deploy-service.py` espera diretórios privados `/etc/zapbot-deploy/odisseu` e `/var/lib/zapbot-deploy/odisseu`, que estão ausentes nesta VM; portanto seu lock `deploy.lock` não foi comprovado como lock operacional efetivo aqui.
- Os containers A6 atuais têm restart policy `no`; o Docker daemon não os iniciaria segundo essa política após saída/reboot. Os dois containers A64 parados mantêm `unless-stopped`; seu histórico de parada manual não foi recuperado (eventos Docker não estavam disponíveis), então comportamento após reinício do daemon não pode ser certificado. Atualizações/serviços de sistema e principals C são caminhos de controle que devem ser coordenados. Nenhum restart foi executado.

### Classificação A/B/C e cobertura

| Grupo | Evidência na VM | Cobertura/limite |
|---|---|---|
| A — aplicação/scripts conhecidos | Node e JVM A6 ativos; scripts de migração, recovery, backup e restore presentes | O gate novo não está comprovadamente instalado na versão ativa. Scripts diretos podem contornar o bridge; precisam ser suspensos pelo operador e o writer encerrado depois de drain. |
| B — processos sem privilégios administrativos | Socket `0660 root:docker`; principal Docker explicitamente enumerado; mountpoints não legíveis pelo usuário operacional | DAC impede acesso direto sem root/grupo Docker, e B sem socket não pode criar container autorizado. A conclusão é por ACLs e memberships; não foi feita escrita de teste no host. |
| C — operadores administrativos confiáveis | `caiohclavico` no grupo Docker; `ubuntu` no sudo; agentes root/supervisor | Podem contornar marker/ACL, montar o volume ou lançar writers. A garantia depende da exclusividade operacional de C durante a janela; não há defesa técnica contra C. |

### Bootstrap da primeira manutenção

A versão ativa não fornece o gate/contador de drain novo. Não há sinal observado que permita provar que os handlers WhatsApp e requests mutáveis já aceitos terminaram. Health `healthy`, silêncio em logs, container parado ou lock Datomic livre não equivalem a drain. Também não foi comprovado controle de todas as crontabs nem do pipeline configurado para o endereço divergente.

Uma janela futura só poderá começar após um método suportado, externo e verificável para: (1) suspender novos ingressos mutáveis sem descartar trabalho já aceito; (2) observar o término de todo o trabalho Node/bridge em voo; (3) pausar polls, scripts, CI/CD e updates do sistema; (4) confirmar que somente o operador C designado pode iniciar containers; (5) parar Node/produtores e JVM em ordem segura; e (6) manter essa contenção até acabar snapshot e restore isolado. O procedimento não pode usar `docker stop` imediato como substituto de drain. Se a versão atual não tiver contador ou mecanismo upstream que preserve/termine o trabalho aceito, é necessária uma janela de contenção coordenada com observabilidade de operações; sem isso, não se para a JVM nem se copia o volume.

**Critérios de aborto:** pipeline não pausado ou alvo ainda ambíguo; crontab root/usuários relevantes não auditada; container legado com possibilidade de restart não resolvida; writer/client externo em execução; ausência de prova de zero operações em voo; volume/mount inesperado; ou impossibilidade de assegurar exclusividade do operador C. Não iniciar backup warm como substituto de backup cold consistente.

### RC e decisão

O RC local foi verificado sem alterações de código nesta fase:

- Código: `7d55616a35a927bcf1a0efe98848913faa6a6e32`; content-tree `1a03ad0bc8769ddcf08b0b7c90210ee79cf95b33`.
- As imagens locais correspondentes continuam `linux/amd64`: Node `sha256:dd27dc57b34c7ba30eec58db64ed9529a483d50b47f3013f7f99d2e1cacec1bd`; JVM `sha256:efc7e4578209984ee2d80d8e3c1cc2f4d4f3c70e8f259747018c96ceeb9d27d5`.
- O commit `5088b5b` do dispatcher corrigido e `b9dd584` de autorização de trocas são ancestrais do código RC; os commits de base das trocas, UX e migration `033` também estão na árvore. Domínio/store de troca, rotas, testes e migrations `020`/`033` estão presentes. Inspeção do `on-message` no RC confirmou o catch de exceções síncronas e de promises rejeitadas introduzido pelo dispatcher; o arquivo também contém diferenças posteriores esperadas de integração do Announcer e do gate de manutenção, portanto não é byte-a-byte igual ao commit `5088b5b`.
- As imagens ativas na VM são diferentes das imagens locais de manutenção/Announcer. Nenhuma imagem RC foi transferida ou implantada; não houve migration 007 nem ativação do Announcer.

**Status final: BLOCKED.** A contenção de B pela ACL observada é plausível e o inventário identifica o writer atual, mas não existe bootstrap comprovado contra os caminhos A/C na janela inicial. Faltam drain mensurável da versão ativa, leitura autorizada das crontabs/controle root, reconciliação do alvo CI/CD e um lock operacional efetivo. Não modificar produção até esses gates serem resolvidos em procedimento autorizado.
