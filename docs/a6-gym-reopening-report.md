# Reabertura global dos desafios de ginásio A6

Data: 2026-11-06
Status: **BLOCKED — desafios permanecem OFF**

## Escopo

A autorização mais recente permite reabertura global para os grupos que o Odisseu já autoriza. Não restringir ao piloto. Ela não cobre pagamentos por permanência, migrations, alteração manual de saves ou balanceamento. Nenhuma configuração live foi alterada e nenhum container foi reiniciado nesta execução.

## Preflight remoto read-only

Verificado novamente em `odisseu-core`:

| Gate | Resultado |
| --- | --- |
| Host / arquitetura | `odisseu-core`, `x86_64` / AMD64 |
| Node | healthy; Image ID `sha256:5023873836935272c7fcea4a58a7b00de683121cf571c329a6e2e4081277a42b` |
| WhatsApp / Chromium | READY / conectado; health Node HTTP 200 |
| JVM / bridge | healthy; health HTTP 200; `status=up` |
| JVM requests em voo | `activeRequests=0`; não enumera batalhas persistidas |
| Desafios | `POKEMON_GYM_CHALLENGES_ENABLED=false` em Node e JVM |
| Permanência/economia | `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false` em ambos |
| Raids automáticas | `POKEMON_RAID_AUTO_TICK=true` e `ODISSEU_BRIDGE_RAID_AUTO_TICK=true` em ambos; health JVM informa raid auto tick habilitado |
| Eventos temáticos | capability presente, mas health não informa último/ próximo tick nem estado agregado; execução saudável não comprovada |
| Release Announcer | `RELEASE_ANNOUNCER_ENABLED=false` em ambos |
| Pokémon schema 034 | confirmado no restore cold isolado do P0; sem consulta nova ao banco live |
| Saves | último agregado documentado no P0: 94 reparos, dois HP zero legítimos preservados, caso ambíguo excluído; não há fotografia agregada atual pós-retomada |
| JVM Image ID | `sha256:f63800695178bfb8d7295ae035c4f9497a728d6ef67eda09924e30a189b4969d` |

Não houve restart, migration, escrita ou acesso a saves individuais.

## GitHub deploy hold

O `master` atual é `efed6e622a59d8fe5f13a0e9f99f1a20177f3158`, sem branch protection (`protected=false`). O workflow atual tem condição fail-closed: `deploy-odisseu` só executa se `vars.ODISSEU_PRODUCTION_DEPLOY_HOLD == 'false'`; valor ausente ou `true` bloqueia o job.

Consulta atual ao GitHub encontrou zero runs `in_progress`, `queued` e `waiting`. O valor efetivo da variável não pôde ser lido: a conexão GitHub disponível recusa a rota de Actions Variables e o `gh` CLI não está instalado. Portanto, o hold efetivo continua **UNKNOWN**; zero runs ativos não prova contenção administrativa. O método necessário é leitura por um administrador do repositório em **Settings → Secrets and variables → Actions → Variables**, sem alterar o valor. Não usei reruns históricos como evidência.

## Token JVM de manutenção

O token de manutenção JVM previamente exposto continua considerado comprometido. Não foi repetido, usado, rotacionado ou reativado nesta tarefa.

Uso confirmado no código:

- `ODISSEU_BRIDGE_MAINTENANCE_TOKEN` é lido por `apps/odisseu/src/odisseu/bridge/http_server.clj` e protege `/ops/maintenance/*` via header `X-A6-Maintenance-Token`.
- `docker-compose.odisseu-core.yml` injeta o valor-fonte externo no JVM.
- O valor-fonte está fora do repositório e não foi lido.
- Node usa token distinto (`ODISSEU_NODE_MAINTENANCE_TOKEN`), não implicado na exposição.
- Não foram encontrados outros consumidores codificados no repositório.

A rotação requer autorização operacional específica e possivelmente restart coordenado. O plano sem segredos permanece: gerar CSPRNG diretamente no cofre, atualizar a fonte Compose e os consumidores autorizados, conter/drain Node usando apenas seu token distinto, aguardar JVM `activeRequests=0`, recriar somente a JVM, validar a credencial nova e rejeição da antiga sem imprimir valores, invalidar a versão comprometida e nunca restaurar o segredo antigo como rollback. **Nenhum passo da rotação foi executado.**

## Auditoria agregada Datomic

A bridge live não possui rota agregada read-only para batalhas de ginásio. `activeRequests=0` mede requests em voo, não estado persistido.

A inspeção de schema/código confirma:

- Batalha e receipt de início são gravados na mesma transação Datomic.
- Transações Datomic são atômicas; não há transação parcialmente confirmada.
- `operation-receipt` registra resultados idempotentes; não possui estado `pending`.
- Batalhas abertas são estado de domínio persistido e podem ser retomáveis após restart.
- O schema de batalha não tem campo de criação; `battle/last-activity-at` só permite idade da última atividade, não prova quando a batalha foi criada.

Preparei `scripts/a6-gym-state-audit.clj`, uma consulta agregada read-only. Ela verifica a existência do DB antes de conectar, não cria bancos, não aplica schema, não transaciona e não imprime IDs. Resume batalhas abertas/inconsistentes, receipts de início e vínculo, métricas agregadas de HP/espécie/nível/XP, ocupações e presença das migrations 033/034. Um teste com fixtures sintéticas em Datomic descartável passou (`SAFE AGGREGATE FIXTURE PASS`).

A consulta **não foi executada no Datomic live**. A VM não tem Clojure CLI; iniciar um segundo cliente Datomic Local diretamente no volume RW ativo pode interferir com o JVM writer. Sem um caminho reader já homologado ou um mecanismo aprovado dentro do processo JVM, a auditoria de batalhas, receipts e saves atuais permanece **UNKNOWN**. O script não deve ser apontado ao volume live até que a operação de leitura concorrente seja aprovada. Nenhuma reconciliação automática foi feita.

## Gate global e grupos

A entrada canônica JVM `bridge_tx/start-gym-challenge!` aplica `POKEMON_GYM_CHALLENGES_ENABLED` antes de chamar o store; não depende apenas do presenter. Com a flag `true`, o gate não restringe grupos individualmente. A autorização geral continua a cargo do Node.

Testes locais atuais confirmam `ALLOW_ALL_GROUPS=true` permite chats `@g.us`, mantém `@c.us` bloqueado e exige metadados confiáveis para `@lid`. Nenhuma allowlist foi alterada. Como o usuário autorizou global, não há gate piloto.

## Testes locais

No clone isolado `/private/tmp/zapbot-a6-pokedex-frame`:

- M4.8/M4.7/M3.5: **28 testes, 193 asserções, 0 falhas/erros**; smoke HTTP bridge local passou.
- Gate A6, integridade HP, batalha HP, recompensas de permanência e migration 034: **23 testes, 86 asserções, 0 falhas/erros**, com Datomic descartável.
- Autorização de grupo e descoberta WhatsApp: **35 testes aprovados**.
- Consulta agregada Datomic nova: fixture descartável passou, sem saída de identificadores.

Nenhum desafio real foi executado. Permanência continua OFF, raids ON, Announcer OFF.

## Decisão e ações mínimas restantes

**BLOCKED.** Os desafios permanecem `false` em Node/JVM. Gates ainda desconhecidos:

1. Administrador confirma o valor efetivo do deploy hold e a contenção administrativa; não mudar para `false`.
2. Disponibilizar execução agregada read-only por cliente/rota já homologada, sem abrir um segundo writer Datomic, e comparar integridade atual com a referência P0.
3. Confirmar ticks/eventos e próxima programação por interface read-only.
4. Autorizar separadamente a rotação do token JVM e sua janela coordenada; não usar o token comprometido.

Depois desses gates, a flag global pode ser aplicada conforme autorização já dada, via mecanismo de configuração aprovado, com Node/JVM coordenados e manutenção/drain válido. Se isso exigir recreate, fazer somente na janela autorizada. Recompensas de permanência seguem OFF; nenhuma migration ou alteração manual de save.

Smoke real de batalha permanece separado e exige consentimento do treinador. Até lá, não afirmar sucesso operacional.

## Contenção emergencial

Se houver evidência de HP indevidamente zerado, dano duplicado, corrupção ou falha de idempotência, manter desafios OFF nos dois serviços e preservar evidências sanitizadas. Não curar nem reparar saves automaticamente.

---

## GO operacional: rotação do token e auditorias (2026-11-06 14:50 UTC)

### Gates administrativos e estado live

- GitHub Actions: a leitura de runs retornou **0 execuções ativas** entre 266 registros consultados. Isso não prova o valor da variável de proteção.
- `ODISSEU_PRODUCTION_DEPLOY_HOLD`: **UNKNOWN**. A integração GitHub rejeitou a leitura administrativa da variável (`HTTP 400`, endpoint não permitido). Nenhuma configuração foi alterada. Até um administrador confirmar o valor efetivo como bloqueado, não se faz restart nem rotação.
- SSH read-only confirmou host `odisseu-core`, arquitetura `x86_64`. `sudo` não está disponível para a conta operacional em modo não interativo; não tentei contornar essa limitação. A conta Docker existente permitiu apenas consultas de metadados.
- Containers: Node e JVM em execução e `healthy`. Imagens correspondem às IDs registradas acima. O healthcheck recente do Node informa `READY` e Chromium ativo. GET local de `/health` da JVM informou `up`, manutenção `open`, `activeRequests=0`, plataforma ativa e raid tick habilitado. `recoveryCatalogReady=false` foi observado e requer avaliação antes de qualquer operação de recuperação dependente desse catálogo.
- Flags efetivas em ambos: desafios `false`; recompensas de permanência `false`; ticks de raid `true`; Announcer `false`; todos os grupos existentes continuam configurados como antes. Nenhum valor secreto foi lido ou impresso.
- Mounts observados: sessão WhatsApp permanece montada no Node; volume Datomic permanece montado na JVM. Espaço no filesystem raiz: 12 GB livres (75% utilizado). Nenhum volume foi alterado.

### Auditoria Datomic e caminho read-only

Não executei cliente Datomic adicional contra o volume ativo. A versão implantada não expõe uma rota agregada para batalhas/receipts/saves. `activeRequests=0` é somente uma amostra de requests HTTP ativos; não prova ausência de batalhas retomáveis, jobs, operações persistidas ou writers externos. Não consultei nem reconciliei registros de jogador.

Preparei no clone uma extensão local, ainda não implantada: `GET /ops/maintenance/a6-gym-state-audit`. Ela usa a conexão Datomic já aberta pela JVM, exige o token de manutenção, retorna somente contagens/flags agregadas, não cria DB/schema, não transaciona e não devolve IDs ou payloads. A requisição adquire/libera o gate existente para aparecer no drain; quando a manutenção não aceita novas operações, a rota responde 503. A extensão altera código JVM e requer imagem/RC separado e autorização operacional adicional antes de qualquer uso em produção.

Verificação local: namespace da JVM carregado com sucesso; smoke JVM HTTP + Datomic descartável passou (`PASS maintenance HTTP persistence/restart/resume; isolated Datomic; no external transport`), incluindo autorização, resposta agregada, ausência de novos datoms e recusa após fechamento da admissão. O teste exige loopback; execução no sandbox foi negada pelo ambiente e a repetição local autorizada passou. Nenhum teste acessou produção.

O primeiro patch local foi isolado na branch `codex/a6-gym-audit-endpoint`, commit `bea6780341fe7fe12d80d998f9f1f155fc020bd8`, tree `23bd2fe70c538afa190c6d76985fc1ed99c672a5`. O RC foi depois ampliado, testado e empacotado em imagem JVM AMD64 local; os artefatos finais estão no adendo abaixo e em [a6-gym-audit-rc.md](a6-gym-audit-rc.md). Não foi transferido nem implantado.

### Token de manutenção

O token anteriormente exposto segue tratado como comprometido. Não foi consultado, repetido, usado, rotacionado ou invalidado nesta execução. O hold efetivo está desconhecido e não há prova de quiescência global: `activeRequests=0` não cobre transações/jobs JVM, handlers Node, batalhas persistidas nem writers externos. Portanto, reiniciar JVM/Node para substituir a credencial seria inseguro nesta janela e não foi feito. Também não gerei nem armazenei token novo, pois não há cofre conectado aqui para entrega segura.

A rotação permanece pendente até: (1) administrador confirmar `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` e ausência de runs concorrentes; (2) operação definir contenção de writers externos; (3) drain suportado e verificável, inclusive operações JVM, ser confirmado; (4) janela segura e cofre operacional de secrets estarem disponíveis. Depois, atualizar os consumidores de forma coordenada, validar token novo e rejeição do anterior, sem fallback ao segredo comprometido.

### Estado de scheduler/eventos e saves

As flags dos ticks automáticos permanecem ON, sem mudança. Esta execução confirmou health/flags, mas não consultou o próximo horário, duplicações, ticks dos eventos temáticos ou estado do Bag kit: o runtime atual não oferece consulta agregada read-only apropriada e não foi iniciado outro processo Datomic. A auditoria atual de saves e batalhas também permanece UNKNOWN; a referência P0 continua sendo histórica, não uma fotografia atual.

### Decisão

**BLOCKED — ginásios permanecem OFF.** Nenhuma rotação, restart, deploy, migration, backfill ou escrita em dados ocorreu. Próximas ações mínimas: um administrador confirmar o valor do deploy hold; estabelecer contenção/drain global verificável; disponibilizar o RC JVM separado da rota agregada preparada; em janela autorizada, fazer a rotação coordenada; então executar a auditoria usando a conexão existente e revisar os agregados. Recompensas de permanência continuam OFF. A autorização de reabertura global não foi usada nesta operação.

### Adendo — RC de auditoria local concluído

Código final `61cca09e583c31a2f21da0adcb5470e8f207d42e`, tree `973b7e53bb5d98fdca250ab54e5b2baf393c07ce`. Imagem JVM AMD64 `sha256:72f920bff5050bc756aecb45133cc062d0511cca19c7303e6519d3d642af166e`; tar SHA-256 `ef2401c139f3d19ef3d55409e7045ce697e0c65cd08c2098d1ddb365cd2511d0`. Smoke de imagem real, testes HTTP/JVM/Datomic descartáveis e regressões pertinentes passaram. Nenhum secret foi usado; nenhuma operação live foi executada.

**Status do RC: CONDITIONAL.** O hold GitHub continua UNKNOWN, o token de manutenção segue comprometido e não rotacionado, e a auditoria live não ocorreu. Ginásios e recompensas de permanência permanecem OFF. Ver [relatório técnico completo](a6-gym-audit-rc.md).
