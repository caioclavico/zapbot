# Odisseu A6 — RC isolado: trocas Pokémon (UX + public-id)

**Status:** READY  
**Gerado:** 2026-11-04  
**Escopo:** exclusivamente o sistema de trocas (`#N` público, mensagens amigáveis, migration aditiva).  
**Proibições respeitadas:** sem deploy, sem migration/backfill em produção, sem alteração de saves, sem restart/recreate, sem mexer em raid-auto / regras de grupo / Announcer / perfil WhatsApp / mensagens reais.  
**Workspace do desenvolvedor** (`a6-save-slots` / working tree misto) **preservado** — todo o trabalho deste RC vive em worktree isolado.

---

## 1. Base operacional escolhida

| Camada | Revisão / artefato |
| --- | --- |
| Node (produção hotfix) | `5088b5b43ea504f9b6f841ebc077ce970901b4e7` — *fix A6 dispatcher and group-only allow-all* |
| Node image implantada | `odisseu-node:a6-critical-5088b5b` · `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` |
| JVM (produção 2026-11-03) | imagem operacional `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` (sem schema de trocas públicas) |
| Clone/hotfix de referência | `/private/tmp/zapbot-node-12835` (detached `5088b5b`) |
| Worktree RC | `/private/tmp/zapbot-trades-rc-a6` · branch `rc/a6-trades-ux` |

**Por que não `a6-save-slots`:** HEAD local mistura trocas com missões, novidades, raides, economia e Announcer; a migration candidata `030-trade-public-id` colide com a linha operacional (`030-raid-auto` já aplicada).

Dispatcher corrigido do hotfix `5088b5b` permanece intacto na base do RC.

---

## 2. Arquivos e commits integrados

### Origem do código de trocas

Commits de domínio no tree sujo (referência, **não** cherry-pick cego):

- `fd5a347` — schema/domínio/store de public-id  
- `9df9380` / `a6a983a` — docs/testes/router (parcialmente reaplicados)

Integração no RC: patches **cirúrgicos** sobre `5088b5b`, fundindo public-id no `trade_store` operacional (gates finos preservados: `trade_forbidden`, `trade_action_forbidden`, `trade_save_mismatch`, `stale_writer_fence`).

### Árvore RC (somente trocas)

| Área | Arquivos |
| --- | --- |
| Migration | `migrations/pokemon/033-trade-public-id.edn` |
| Schema registry | `modules/pokemon/.../datomic/schema.clj` (+033) |
| Domínio | `modules/pokemon/.../domain/trade.clj` |
| Store | `modules/pokemon/.../datomic/trade_store.clj` |
| Ports / facade | `ports.clj`, `store.clj` (`resolve-trade-id`) |
| Bridge HTTP | `modules/pokemon/.../http/bridge.clj` |
| Node UX | `src/zapbot/pokemon_http.cljs`, `scripts/lib/pokemon-jvm-present.cjs` |
| Ajuda | `src/zapbot/router.cljs` (só linha `pk troca`) |
| Testes | `trade_public_id_test.clj`, `test/pokemon-trade-ux.test.cjs`, mocks em `pokemon_http_test.cljs` |
| Doc | este arquivo |

### Commits RC (preenchidos após commit)

| Commit | Conteúdo |
| --- | --- |
| `02f6d5f` | migration 033 + domain/store/ports/bridge + testes Clojure |
| `1d8f794` | pokemon_http + present + router + testes UX/CLJS |
| `f4e0972` | `docs/pokemon-a6-trades-rc-isolation.md` (rascunho) |
| `1f2b395` | digests AMD64 + status READY |

SHA final e content-tree: ver §7.

---

## 3. Mudanças excluídas (de propósito)

Não vieram do working tree misto:

- Missões / novidades / professor / ajuda geral além da linha de troca  
- Raides automáticas, lobby, Frambo, Release Announcer  
- Economia / bag-kit / capacity / idle  
- Save-slots UI além do necessário ao gate de trocas já existente  
- Qualquer alteração de allowlist / `ALLOW_ALL_GROUPS` / perfil WA  

**Dependência indispensável preservada da base:** taxonomia fina de autorização de trocas já presente em `5088b5b` (não substituir pelo store “cru” de `fd5a347`).

---

## 4. Migration definitiva: **033**

| Candidata suja | Linha operacional | Decisão |
| --- | --- | --- |
| `030-trade-public-id.edn` | `030-raid-auto.edn` **ocupada** | **033-trade-public-id.edn** |

Fila operacional confirmada no worktree base:

`… → 029-raid-lobby → 030-raid-auto → 031-raid-capture-frambo → 032-thematic-events → **033-trade-public-id**`

### Validação do schema (aditivo)

- `:trade/public-id` (long) — opcional em entities antigas  
- `:trade/public-key` (string, unique identity) = `{chatId}|{n}` — unicidade no escopo chat  
- `:trade-seq/chat-id` (unique) + `:trade-seq/next` — contador CAS por grupo  
- Inicialização concorrente do contador coberta pelos testes de create simultâneo  
- Reexecução idempotente via registro `odisseu.schema/migration-id` (padrão A6)  
- Saves existentes: sem alteração de atributos de save  
- Propostas legadas sem `public-id`: resolvíveis pelo id canônico; **não** há backfill silencioso de `#N` histórico (evita colisão)

**Não aplicada em produção.**

---

## 5. Compatibilidade de propostas antigas

| Caso | Comportamento |
| --- | --- |
| Proposta criada pós-033 | Tem `public-id` / `public-key`; UX mostra `#N` |
| Proposta pré-migration (sem public-id) | Aceita/confirma/recusa/cancela via id canônico se o cliente ainda o enviar; listagem não inventa `#N` |
| Contador ausente no chat | Create inicializa `trade-seq` com CAS |
| Resolução `#N` / `N` | Sempre scoped ao `chatId` da requisição — não cruza grupos |
| Aceitar troca de outro grupo | Falha (`trade_not_found` / forbidden) — chat + public-key |

---

## 6. Resultados dos testes

Ambiente: Datomic Local descartável / Node local. **Nenhum** contato com produção.

| Suite | Resultado | Nota |
| --- | --- | --- |
| Clojure trocas (`trade-a441`, `trade-http-a443`, `trade_public_id`) | **25 tests · 110 pass · 0 fail** | recovery harness `trade-process-recovery` é write!/verify! (0 deftest) |
| Node UX `test/pokemon-trade-ux.test.cjs` | **4/4 pass** | sem IDs canônicos nas mensagens |
| Group allowlist + discovery + staging isolation | **42/42 pass** | política de grupos intacta |
| CLJS completo RC | **236 tests · 1407 asserts · 1 fail · 3 errors** | |
| CLJS completo base `5088b5b` | **236 tests · 1404 asserts · 1 fail · 3 errors** | mesmas falhas preexistentes |
| Diff de regressão | **nenhuma falha nova** | +3 asserts de trocas; fail/error idênticos (missões apresentação + perfil professor) |

Falhas preexistentes (base = RC):

1. `comandos-de-missoes-preservam-o-filtro-de-apresentacao` — 3 ERROR  
2. `perfil-professor-titulo-amizade-rota-evento-whatsapp` — 1 FAIL  

Cobertura transacional exercitada via suites Clojure (create concorrente / public-id / resolução / gates / idempotência de operation-receipt). E2E Node→bridge→Datomic completo **não** foi reexecutado neste isolamento (harness `scripts/test-pokemon-trade-e2e.cjs` disponível; fora do corte mínimo após 110 pass de domínio).

---

## 7. Artefatos RC / imagens

| | Valor |
| --- | --- |
| Branch | `rc/a6-trades-ux` |
| Worktree | `/private/tmp/zapbot-trades-rc-a6` |
| Git SHA (código + doc base nas imagens) | `f4e0972046d360f29081c36184beaa23cd590e09` |
| Content-tree (labels das imagens) | `a76b14aeb865e677ac4cf867ee6419acee287402` |
| Git SHA (HEAD branch, docs digests) | `5e351111939da806271b9c1985b8cdc8a5d7cc03` |
| Content-tree HEAD | `adfa5401eacbbbe9b290318da5323545d7f5d13f` |
| RC dir | `/Users/caioclavico/odisseu-recovery/a6-trades-rc-20261104T132934Z/` |
| Node tag | `odisseu-node:a6-trades-20261104T132934Z-f4e0972046d3-amd64` |
| Node Image ID | `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` |
| JVM tag | `odisseu-jvm:a6-trades-20261104T132934Z-f4e0972046d3-amd64` |
| JVM Image ID | `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` |
| Arch | linux/amd64 · labels `fase=a6-trades-ux` · mesmo git-sha/content-tree |
| Tar SHA-256 | ver `checksums/IMAGES.sha256` (Node `226a0171…`, JVM `cf0f30e5…`) |

Produção atual (rollback app):

| | Tag / digest |
| --- | --- |
| Node | `odisseu-node:a6-critical-5088b5b` · `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` |
| JVM | digest operacional `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` (sem 033) |

---

## 8. Plano de deploy (somente com GO explícito — **não executar agora**)

1. Confirmar produção healthy: Node READY no hotfix `5088b5b`, JVM healthy, raid-auto conforme política atual, allowlist intacta.  
2. Backup A6 (warm/cold) + registrar propostas `invited`/`accepted` abertas.  
3. `docker load` imagens RC Node+JVM; conferir digests/labels (`git-sha`, `content-tree`, `fase`).  
4. Cutover JVM **antes** do Node (schema 033 precisa existir antes do create com public-id).  
5. Aplicar migration **033** no storage operacional (procedimento padrão A6 schema apply — **fora deste RC**).  
6. Cutover Node; smoke controlado em grupo piloto: `!pk troca` / `!pk trocas` / aceitar / confirmar — sem mensagens em massa.  
7. Verificar ausência de operationId/JID/UUID nas respostas; menções reais presentes.

### Rollback de aplicação (schema aditivo)

- Reverter tags compose para Node `a6-critical-5088b5b` e JVM pré-RC.  
- Atributos `:trade/public-id`, `:trade/public-key`, `trade-seq/*` **permanecem** no Datomic (inofensivos para o código antigo que ignora public-id).  
- **Não** remover attrs; **não** reescrever propostas.  
- Propostas criadas só no RC continuam com public-id; o Node antigo pode voltar a exibir id canônico se alguém listar — risco UX, não de inventário.

---

## 9. Riscos residuais

| Risco | Mitigação / nota |
| --- | --- |
| Propostas pendentes durante cutover | Completar ou deixar expirar (5 min) antes da janela; ou aceitar que mid-flight usa resolução canônica |
| Contador vs propostas históricas sem `#N` | Sem backfill automático — evita colisão |
| Create concorrente no mesmo chat | CAS em `trade-seq/next` + unique `public-key` |
| Confirmação concorrente / retry | revision CAS + `operation-receipt` idempotente |
| Cutover sem migration 033 | JVM nova + Node criando `#N` falhariam create — migration obrigatória no GO |
| E2E bridge HTTP não re-rodado neste isolamento | Domínio+UX+CLJS cobertos; recomendar e2e antes do GO |

---

## 10. Decisão

**READY**

RC isolado, reproduzível e testado, contendo somente trocas. Imagens AMD64 Node/JVM geradas com labels `git-sha`/`content-tree` alinhados a `f4e0972`.

Antes do GO de produção (autorização explícita separada): smoke E2E Node→bridge→Datomic em ambiente descartável e migration 033 no storage operacional.

Bloqueadores de código: **nenhum**.  
**Nenhum deploy / migration / restart foi executado neste isolamento.**
