# A6 — Hotfix unificado de gameplay (XP, raids, ovos, PI, biomas, paridade)

**Branch:** `feature/a6-gameplay-hotfix-unified`  
**Base:** `master` @ `180a16e` (PR #132)  
**Gates:** `IMPLEMENTATION` → validation em andamento · **`DEPLOY_NO_GO`** · merge só com revisão humana.

Não executar merge/deploy sem autorização explícita.

---

## A. Diagnóstico (por bug)

| # | Bug | Causa raiz | Arquivos | Solução |
|---|-----|------------|----------|---------|
| 1 | XP mín. derrota caça | Lookup do combatente por `nth`/índice podia divergir do mon real; DTO/announcement não mostravam XP de derrota | `store.clj`, `battle.clj`, `pokemon-jvm-present.cjs` | Resolver por `id-pokemon`; `indices-ativos` no create-wild; anunciar só XP persistido; unidade `:flat9` na derrota |
| 2 | XP raid anunciado ≠ aplicado | Live lookup falhava sem id; `:atividade` podia alterar o grant flat canônico | `raid_store.clj` | Fallback por `collection-index`; grant `:flat9` (3/6); DTO `xp-pokemon` = aplicado |
| 3 | `!pk 1` / `!pk atk 1` atacam raid | `contexts=["raid"]` executava raid sem scope | `pokemon_http.cljs` | Raid só com `battle-scope="raid"`; mensagem educativa se unscoped |
| 4 | Ovo ganho / chocar sem PNG | Grant só texto; `!pk chocar` sem `renderHatchRevealCard` | `pokemon_http.cljs`, `pokemon-jvm-media.cjs`, `pokemon-jvm-present.cjs` | Tip incubação; PNG grant; hatch manual com reveal card |
| 5 | PI sem ovo incubando | `credit-daily-pi` antes de checar incubadoras | `egg_activity.clj` | Sem ovo elegível → `pi-granted=0`, sem consumir teto |
| 6 | Biomas acento/composto | `:area_unavailable` misturava desconhecido vs fechado; aliases parciais ausentes | `mundo.clj`, `hunt.clj`, present, bridge | Aliases canônicos; `:area_unknown` vs unavailable; `!pk biomas`; msgs educativas |
| 7 | Paridade chats | Catálogo/mundo já únicos; thematic por chat permanece admin | — | Sem forks de gameplay por `chatId` neste hotfix |

**Saves:** nenhuma migração de schema; nenhum write em produção; XP/PI só em TX canônicas idempotentes.

---

## B. Alterações principais

- Domínio: `battle.clj`, `egg_activity.clj`, `hunt.clj`, `mundo.clj`
- Persistência: `store.clj`, `raid_store.clj`, `bridge.clj`
- Node: `pokemon_http.cljs`, `pokemon-jvm-present.cjs`, `pokemon-jvm-media.cjs`
- Testes: `gameplay_hotfix_a6_test.clj`, ajustes egg/raid, `test/pokemon-a6-gameplay-hotfix.test.cjs`

---

## C. Auditoria ambiental (catálogo real)

**Biomas (6):** floresta, praia, caverna, cidade, lago, vulcao (Vulcão Rubro)  
**Climas (6 + ceu-limpo sintético):** ensolarado, chuvoso, nublado, ventania, neve, nevoeiro; off-Dia `ensolarado`→`ceu-limpo`  
**Aliases adicionados:** rubro/vulcao rubro→vulcao, verde/floresta verde→floresta, azul/praia azul→praia, etc.  
**Compostos testados:** vulcão/vulcao rubro, floresta verde, …  
**Colisões:** nenhuma inventada; `vulcao azul` → `:area_unknown`  
**Dia/noite:** períodos Madrugada/Dia/Entardecer/Noite; vulcão só no Dia  
**Paridade:** mesmas regras em todos os chats habilitados

---

## D. Testes (execução local)

| Suite | Resultado |
|-------|-----------|
| JVM `gameplay-hotfix-a6-test` | PASS (5 / 42) |
| JVM `egg-activity-a6-test` | PASS (14 / 52) |
| JVM `egg-fixes-a6-test` | PASS (9 / 88) |
| JVM raid defeat XP (solo + level) | PASS após flat9 + assert residual |
| Node `pokemon-a6-gameplay-hotfix.test.cjs` + a41 | PASS |
| Integração plena / PNG visual / concorrência multi-chat | Pendente CI no PR |

---

## E. Riscos e rollback

**Riscos:** jogadores sem ovo incubando deixam de ver/ganhar PI (intencional); comandos genéricos deixam de atacar raid (intencional); mensagens de bioma mudam.

**Rollback:** reverter o merge do PR; sem migration; recibos idempotentes evitam double-grant ao reprocessar.

**Estados:** `MERGE_NO_GO` até CI verde + review · `DEPLOY_NO_GO` até GO explícito do owner.
