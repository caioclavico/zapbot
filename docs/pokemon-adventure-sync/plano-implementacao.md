# Plano incremental de implementação

Plano atualizado — 10/10/2026. A Fase 2 de persistência anterior está implementada
localmente; a Etapa A da nova mecânica por tempo/pontos também está isolada.
Os caminhos de fases futuras são propostas; autenticação, comandos e mobile
ainda não existem. Não há autorização para deploy, DDL de produção, secrets ou reinícios.
O app Flutter será um projeto novo, cuja localização/repositório ainda precisa
ser escolhida; `adventure-sync-app/` abaixo é apenas nome proposto.

Consultar [arquitetura](arquitetura.md), [modelo](modelo-dados.md),
[contrato](api.md) e [riscos](riscos-e-seguranca.md) antes de cada fase.

## Divisão real das fases, atualizada em 10/10/2026

1. Análise/arquitetura: os cinco documentos, concluída.
2. Ovos/incubadoras, persistência e testes por distância: implementação anterior.
   Etapa A da nova mecânica por tempo/pontos: núcleo puro implementado, ainda sem persistência.
3. Comandos WhatsApp e integração de notificações, ainda não implementados.
4. API de sincronização/autenticação e consolidação de dados móveis, pendente.
5. Aplicativo Flutter e HealthKit/Health Connect: cancelado; não haverá aplicativo externo.
6. Sincronização em segundo plano: cancelada; a experiência será somente WhatsApp.

### Commits sugeridos por fase

| Fase | Commit sugerido |
|---|---|
| 1 — Documentação | `docs(pokemon): plan adventure sync architecture` |
| 2 — Ovos e incubadoras | `feat(pokemon): implement egg incubation domain` |
| 3 — Comandos WhatsApp | `feat(pokemon): add egg commands and notifications` |
| 4 — API e vinculação | `feat(pokemon): implement secure step sync API` |
| 5 — Aplicativo Flutter | `feat(mobile): add step tracking companion app` |
| 6 — Sincronização | `feat(mobile): implement background step sync` |

Os títulos não autorizam commit, push, deploy ou início da fase seguinte.
HealthKit/Health Connect em primeiro plano integra a Fase 5; a Fase 6 trata
background e recuperação eventual, preservando os critérios abaixo.

A documentação anterior chamava de Fase 2 os comandos. O escopo mais recente
prevalece: esta entrega não inclui comandos, vínculo, API ou dados de saúde.
Esta fase usa incrementos internos já validados com ID estável. A consolidação
de 24 h/revisões descrita para dados móveis é responsabilidade da fase 4; não
aplica um segundo atraso a incrementos que já foram consolidados.


## Portões prévios

Antes de código de produção, aprovar: conta por grupo, catálogo/recompensas,
limites, modelo atual sem IVs, consolidação de 24 h, offline de até 7 dias, retenção
e uso de dados para fitness. Validar viabilidade HealthKit/lojas com um spike
isolado; o sucesso técnico não garante aprovação para um app de jogo.
Definir endpoint HTTPS/domínio e responsável por certificados sem expor a API
interna. Confirmar RF/DC/versão Cassandra em etapa futura autorizada, não aqui.

Todas as fases usam flags propostas desabilitadas por padrão e testes sem dados
reais de jogadores. `PERFORMANCE_METRICS=false` permanece como está. Nenhuma
fase libera automaticamente a próxima em produção.

## Fase 2 — domínio de ovos e durabilidade

**Dependências:** regras aprovadas, desenho de CAS e fixtures de espécies.

Arquivos implementados nesta entrega:

- **Novos** `pokemon-service/src/zapbot/pokemon/ovos.cljs`,
  `adventure_catalog.cljs`, `adventure.cljs`: regras puras, distribuição e
  fachada interna na fila existente; progresso fica em `ovos.cljs`.
- **Novo** `pokemon-service/src/zapbot/adventure_store.cljs`: consultas por chave,
  CAS, diário e projeções, pelo driver e guarda read-only existentes.
- **Novo** `scripts/cassandra/adventure-sync-v1.cql`: schema aditivo para revisão;
  script de verificação separado. Nada de DDL automático no startup.
- Existentes `pokemon-service/src/zapbot/armazenamento.cljs`, `treinador.cljs`,
  `core.cljs`: interface limitada de repositório, entrada tipada na mesma fila
  e entrega com receipt durável; isolar essas adaptações em diffs pequenos.
- **Novos testes** `pokemon-service/test/zapbot/pokemon/ovos_test.cljs`,
  `pokemon-service/test/pokemon_service/adventure_fixture.cljs` e
  `pokemon-service/test/adventure-cassandra.integration.cjs`.
- `pokemon-service/assets/egg-species-v1.json`: snapshot local versionado das
  28 espécies permitidas, sem download para concluir nascimento.
- `entry.cljs`, `shadow-cljs.edn`, `config.cljs` e `.env.example`: exports
  internos, bootstrap/prontidão e `ADVENTURE_SYNC_ENABLED=false`.

Critérios de aceitação:

1. Metas exatas de 2/5/10 km, máximo de ovos, usos/slot e capacidade da coleção.
2. Pesos válidos, soma 100%, espécie do allowlist, resultado determinístico por
   ovo/replay, modelo atual sem IVs e nível 1 sem mudar fórmulas antigas.
3. Duas ativações/syncs concorrentes não consomem slot/ovo duas vezes.
4. Inteiros seguros, janela temporal, incubação, replay e watermark cobertos.
   Amostras móveis/revisões/exclusões/limites diários e dívida ficam para Fase 4;
   o produtor desta interface já fornece incrementos validados e consolidados.
5. Falha antes/depois de CAS, após inserir Pokémon e antes de projetar histórico
   converge ao mesmo resultado; timeout LWT permanece incerto até confirmação.
6. Doação/transferência depois de nascimento não permite reapresentar hatchId.
7. Guardas de persistência impedem snapshot incerto de ser sobrescrito por uma
   jogada seguinte. Testes com combate, captura e troca sem alterar suas regras.
8. Payload limitado a 512 KiB com backpressure; sem caches globais de saúde.
9. Schema e pendências recuperados antes da prontidão; shutdown durante esse
   bootstrap não reabre admissão e não abandona escritas já iniciadas.

**Riscos:** LWT oneroso, snapshots legados por chat, replay de entrega e
compatibilidade de rollback. Testar concorrência real no Cassandra de teste;
fakes não validam isolamento/consistência do cluster.

## Fase 3 — comandos e mensagens WhatsApp

**Dependências:** fase 2 e fluxo de confirmação definido; pareamento pode usar
fixtures locais até a API móvel existir, sem publicar tokens funcionais.

Arquivos previstos:

- `pokemon-service/src/zapbot/pokemon/core.cljs`, `ajuda.cljs`,
  `pokemon-service/src/pokemon_service/entry.cljs` e `shadow-cljs.edn`.
- **Novo** `pokemon-service/src/zapbot/pokemon/adventure_link.cljs` para comandos
  tipados de aprovação/revogação e resolução da conta-alvo original.
- `pokemon-service/runtime/service.cjs`, `src/zapbot/pokemon_http.cljs` e testes
  de contrato para evento determinístico, ACK e entrega privada segura.
- Documentação de comandos e testes de domínio/HTTP existentes.

Comandos propostos:

| Comando | Finalidade |
|---|---|
| `!pk ovos` | Listar categoria, progresso confirmado/provisório e status |
| `!pk ovos incubar <id>` | Ativar ovo na incubadora permanente |
| `!pk incubadoras` | Slots/usos sem mudar inventário legado |
| `!pk ovos chocar <id>` | Tentar entrega pronta se houver vaga |
| `!pk ovos historico` | Últimos nascimentos, sem dados de caminhada |
| `!pk sync vincular <codigo>` | Associar solicitação do app ao autor/chat |
| `!pk sync confirmar <nonce>` | Aprovar privadamente o vínculo original |
| `!pk sync dispositivos` / `revogar <id ou todos>` | Gestão sem tokens/número como senha |

Critérios: comandos existentes continuam com mesmo contrato; DM não muda
escopo da coleção; outra pessoa/admin não confirma vínculo alheio; grupo não
recebe nonce/token/saúde; evento duplicado não duplica Pokémon; WhatsApp offline
mantém histórico/outbox; textos legíveis com fallback sem imagem obrigatória.

**Riscos:** identidade `@lid` versus `@c.us`, limite de DM, capacidade/combates,
mensagem duplicada na janela envio/receipt. Falhar fechado sem novo QR.

## Fase 4 — API de sincronização e autenticação

**Dependências:** fases 2/3, decisões de segurança, endpoint HTTPS planejado e
schema novo validado apenas em ambiente de teste.

Arquivos previstos:

- **Novos** `pokemon-service/runtime/adventure-api.cjs`, `adventure-auth.cjs` e
  `adventure-validation.cjs`; nomes são módulos dentro do servidor existente.
- `pokemon-service/runtime/main.cjs`, `service.cjs`, `shutdown.cjs`,
  `pokemon-service/src/pokemon_service/entry.cljs`, `config.cljs`,
  `shadow-cljs.edn` e `pokemon-service/.env.example`.
- **Novos** `pokemon-service/test/adventure-api.test.cjs`,
  `adventure-auth.test.cjs`, `adventure-recovery.test.cjs` e testes CLJS de CAS.
- `.env.example`/documentação recebem apenas nomes/defaults futuros, nunca
  segredos. Dependência criptográfica/auth escolhida após revisão, não inventada
  como sistema proprietário. Reutilizar crypto Node para aleatoriedade/hash.

Critérios: certificados obrigatórios no cliente; escopos segregados; API_TOKEN
não funciona no app e token móvel não funciona em `/commands`; códigos expiram,
aprovação é necessária; refresh/replay/revogação/troca concorrentes seguros;
batchId e intervalos deduplicam após crash/TTL; corpo/lotes/rate limit e 429
cobertos; read-only bloqueia todas as novas escritas; parada drena sync e
repositório preservando erros/timeout atuais; nada de retries de efeito incerto.

**Riscos:** exposição pública, tentativa de vinculação, abuso de recursos,
persistência incerta. TLS/regras de exposição exigem autorização separada
antes de qualquer alteração de infraestrutura. Nem essa fase faz deploy.

## Fase 5 — aplicativo Flutter e integração de saúde

### Base Flutter, inicialmente com fontes simuladas

**Dependências:** contrato fase 4 estabilizado e decisão de repositório.

Arquivos propostos no projeto Flutter ainda inexistente:

```text
adventure-sync-app/pubspec.yaml
adventure-sync-app/lib/main.dart
adventure-sync-app/lib/auth/{pairing,token_store}.dart
adventure-sync-app/lib/sync/{api_client,outbox,window_store}.dart
adventure-sync-app/lib/health/health_source.dart
adventure-sync-app/lib/screens/{pairing,progress,permissions}.dart
adventure-sync-app/test/{auth,sync,offline}_test.dart
```

Dependências a avaliar: Flutter/Dart estáveis compatíveis, HTTP, SQLite/outbox
local e armazenamento seguro. Pin de versões após spike, lockfile versionado e
análise de manutenção/licença. Ainda não existe decisão de versão Flutter.

Critérios: vincular/revogar com servidor falso; estado da conta/ovos é do
servidor; offline conserva páginas/batchId; reiniciar app não duplica sync;
apenas um refresh ativo; nenhum segredo em analytics/crash/log/backup; app
explica atraso sem prometer sync imediato; interfaces acessíveis de consentimento.

**Riscos:** perda do cache local, troca de device e restauração de tokens por
backup. Não resetar o crédito servidor ao reinstalar.

### HealthKit e Health Connect em primeiro plano

**Dependências:** base Flutter, aparelhos físicos, revisão de uso/consentimento
e spike de capabilities/plugin. Pode rodar em paralelo ao desenho das fases
iniciais para descobrir limitações antes de grandes investimentos.

Arquivos propostos:

- `lib/health/health_connect_source.dart`, `healthkit_source.dart`,
  `window_normalizer.dart`, testes de mudança/exclusão e agregação.
- `android/app/src/main/AndroidManifest.xml`, eventual adaptador Kotlin.
- `ios/Runner/Info.plist`, `Runner.entitlements`, eventual adaptador Swift.
- `integration_test/health_foreground_test.dart` e fixtures sintéticas.

Spike do pacote `health`: conferir versões nativas, IDs, tipos, agregação de
origens, filtragem manual, paginação, permissões e exposições de changes/anchors.
Usar platform channel quando faltar suporte; não pressupor UUID por si só
evita sobreposição de iPhone/Watch ou múltiplos apps Android.

Critérios: só READ de tipos necessários; Health Connect ausente tem orientação;
permissão parcial/negada não quebra jogos; dados antigos anteriores ao vínculo
não viram crédito; passos e distância no mesmo período não são somados; teste
em iPhone+Watch e Android com dois produtores não duplica caminhada; alterações
e exclusões revisam o intervalo correto; nenhuma localização/GPS solicitada.

**Riscos:** origem genérica de distância Android, permissões parcialmente
observáveis iOS, dados manuais e app fraudulento, adequação de publicação.
Bloquear modo medido se a semântica não puder ser demonstrada; MVP usa passos.

## Fase 6 — sincronização em segundo plano e testes reais de recuperação

**Dependências:** fases anteriores, consentimento de testadores, telefone físico
e homologação segregada. Não usar dados salvos dos jogadores de produção.

Arquivos propostos:

- Worker Kotlin/WorkManager e tarefa Flutter de execução curta quando viável.
- Swift observer/anchored query, setup no lançamento e completion handler.
- `lib/sync/background_sync.dart`, retomada no foreground, `integration_test/`
  e documentação de bateria, atraso e privacidade.

Critérios: caminhada registrada com tela bloqueada é importada eventualmente;
sem acesso a HealthKit bloqueado, retomar ao desbloquear/abrir sem perda dentro
da janela; modo economia, force-stop, sem rede 1/3/7 dias, token de changes
expirado, reboot, relógio alterado, revogação e troca de celular testados; app
não mantém serviço infinito nem polling intenso; CPU/memória/energia observados
em homologação; reboot/crash do backend em cada checkpoint não duplica prêmio.

**Riscos:** quotas/OEMs, entrega não imediata, dados após 7 dias inelegíveis,
limitações de loja. Não transformar atraso em erro do WhatsApp nem aumentar
indefinidamente a janela/retensão sem revisar custo e privacidade.

## Matriz mínima de falhas antes de liberar

| Falha simulada | Resultado esperado |
|---|---|
| Mesmo lote, IDs diferentes, intervalos iguais | Uma contagem por intervalo |
| Duas contas/dispositivos tentando vínculo | Só autor correto após confirmação; coletor único |
| Timeout/crash antes ou depois do CAS | Confirmar pelo diário; sem incremento cego |
| Crash após coleção antes de histórico/outbox | Receipt da conta impede segundo Pokémon |
| Pokémon já doado, intenção reapresentada | Não conceder outro; receipt continua válido |
| Estoque cheio ou jogador ocupado | Intenção pronta preservada, sem consumo extra |
| ACK perdido/envio incerto WhatsApp | Reenvio com ID estável; possível mensagem repetida, prêmio único |
| Revogação competindo com upload | CAS verifica epoch e não aceita crédito após corte |
| Excluir/corrigir dados/reler após TTL | Revisão/dívida/watermark, sem recompensa retroativa duplicada |
| Shutdown com writes pendentes | Drenagem observável e falha real propagada; sem sucesso forçado |

## Verificações previstas em cada entrega de código

Executar os scripts existentes após instalar dependências pelo lockfile, em
ambiente isolado (com Node/JDK adequados) e com `PERFORMANCE_METRICS=false`:

```sh
# Diretório pokemon-service
npm test
npm run test:domain
npm run build

# Raiz do Zapbot
npm test
npm run test:http
npm run build

# Projeto Flutter futuro
flutter analyze
flutter test
```

São comandos de validação para entregas de código. Os resultados locais da
Fase 2 ficam na seção abaixo. Testes Android/iOS físicos e de cluster replicado
continuam pendentes; contratos usam transporte e identidades sintéticos. Não montar `.wwebjs_auth`, `.env`
ou Cassandra de produção em testes Docker. Em schemas novos, testar criação em
base vazia e coexistência com fixtures legadas, sem dados reais.

## Validação local da Fase 2 — 10/10/2026

- Serviço Pokémon: 54 testes Node.js e 220 testes ClojureScript, com 1.316
  assertions ClojureScript; nenhuma falha/erro. São 30 testes novos de ovos.
- Odisseu: 72 testes Node.js/contratos e 208 testes ClojureScript, com 1.303
  assertions ClojureScript; nenhuma falha/erro.
- Compilação dos testes e builds release dos dois projetos passaram, usando
  Node 22/JDK 17 em containers locais sem dados ou sessões reais. Permanecem
  avisos preexistentes de inferência/depreciação do compilador/dependências.
- Cassandra **5.0.9 descartável**, RF=1, rede Docker interna: schema aditivo,
  CREATE IF NOT EXISTS repetido preservando fixture legada, bindings,
  LOCAL_SERIAL/CAS, duplicidade concorrente, isolamento, nascimento e histórico.
  Resposta perdida após CAS real da coleção foi recuperada sem nova entrega;
  doação posterior seguida de replay também não reconcedeu Pokémon.
- `PERFORMANCE_METRICS=false` usado nas suítes; testes existentes da flag
  continuam cobrindo habilitação/desabilitação e logs essenciais.
- `git diff --check`, links locais e JSON do catálogo de 28 espécies passaram.

O teste Cassandra é opt-in: `pokemon-service/test/adventure-cassandra.integration.cjs`,
com `ADVENTURE_TEST_HOST=zapbot-adventure-cassandra-validation-v2` e o build
`target/domain.cjs` final. Esse hostname é reservado ao container descartável
local; o script não aceita IP/VM. As quatro tabelas do schema são criadas em
keyspace de teste novo, junto com fixtures sintéticas das tabelas legadas.
Não usar credenciais, mounts ou conectividade de produção nesses testes.

Cobertura nova inclui inventário/capacidade, distribuição fixada por ovo,
modelo sem IVs, interrupção antes/depois da entrega, histórico incompleto,
perda de cache no restart, snapshot antigo de outro chat, PC legado/ativo,
CAS concorrente, schema incompatível, shutdown no bootstrap e drenagem das
projeções mesmo quando uma delas falha. TTL/janela com relógio recuado é coberto
por teste simulado; não se esperaram 14 dias no Cassandra real.

Limitações: RF=1 local não valida cluster replicado/failover; requer um writer
Pokémon. Recibos permanentes crescem e podem atingir 512 KiB, bloqueando novas
operações em vez de perder dedupe. Não existe política móvel de fontes/revisões
nesta fase; IDs distintos para a mesma caminhada são responsabilidade do futuro
produtor. Não foram executados deploy, DDL de produção, commit ou push.

## Liberação e rollback futuros

Somente após autorização: schema aditivo, build assinado/versionado, API privada
preservada, entrada HTTPS e flags desligadas; homologação completa; pilotos
opt-in; habilitar somente contas aprovadas. Pokémon, Cassandra e sessão
WhatsApp existentes continuam sob seus processos atuais.

Se falhar: parar novas admissões do recurso, preservar intents/receipts e
drenar; reparar pelo diário sem ressorteio; não apagar tabelas/coleções/sessões.
Validar compatibilidade da versão anterior com receipts de nascimento antes
de rollback binário. Desligar feature não autoriza ignorar writes pendentes.
Commit/push/deploy, migração e secrets são ações futuras e separadas.
