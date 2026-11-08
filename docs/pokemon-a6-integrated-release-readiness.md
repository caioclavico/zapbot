# Pokémon A6 — Homologação integrada A+B+C / Release readiness

**Data:** 2026-11-08 (UTC)
**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Branch:** `fix/a6-gameplay-audit-impl`
**Escopo:** homologação local integrada. **Sem deploy, migrations live, backfill, rebase destrutivo, reset ou alteração de produção.**

---

## 1. Commits / base de cada componente

| Componente | Commit / estado | Notas |
|------------|-----------------|-------|
| **Base RC (drain + sandbox + imagem Node)** | `16b5987` — *Homologa a imagem combinada A6 nos gates de sandbox, ACL, Compose e GitHub* | HEAD da branch = tip de `rc/a6-ai-drain-sandbox` |
| **Lotes A+B+C (gameplay audit)** | **Working tree não commitada** sobre `16b5987` | 17 modificados + 16 untracked (docs/testes/smokes) |
| **Schema temático (pré-existente)** | Migration `migrations/pokemon/032-thematic-events.edn` já na base | **Nenhuma migration nova** neste worktree |
| **Auditoria detalhada** | `docs/pokemon-a6-gameplay-audit.md` | Lotes A/B/C + taxas de captura aprovadas |

`git merge-base HEAD rc/a6-ai-drain-sandbox` → `16b5987` (branch tipada na RC).

---

## 2. Arquivos modificados / novos

### Modificados (17)

| Área | Arquivos |
|------|----------|
| Config | `.env.example`, `deps.edn` |
| Domínio | `battle.clj`, `capture.clj`, `damage.clj`, `moves.clj`, `raid.clj`, `shop.clj` |
| App | `bridge_tx.clj` |
| Infra | `raid_store.clj`, `store.clj` |
| Node | `pokemon-jvm-media.cjs`, `pokemon-jvm-present.cjs`, `pokemon-pokeapi.cjs` |
| Testes legados ajustados | `battle_m34_test.clj`, `raid_m36_test.clj`, `fixtures/legacy_capture.edn` |

### Novos (16)

| Tipo | Arquivos |
|------|----------|
| Docs | `docs/pokemon-a6-gameplay-audit.md`, `docs/pokemon-a6-integrated-release-readiness.md` |
| Smokes JVM | `a6_lote_a_integrity.clj`, `a6_lote_b_mechanics.clj`, `a6_lote_c_present.clj`, `a6_homolog_abc.clj` |
| Testes JVM | `lote_a/b/c_*`, `homolog_abc_integrated_a6_test.clj`, `raid_faint_continue_*`, `explosion_*`, `moves_level_offer_*`, `capture_rarity_frambo_*` |
| Testes Node | `pokemon-a6-gameplay-present.test.cjs`, `pokemon-a6-lote-c-present.test.cjs`, `pokemon-a6-sprite-placeholder.test.cjs` |

**Não tocados (compatibilidade preservada):** `a6-shutdown.cjs`, `a6-maintenance.cjs`, drain/sandbox Compose/Dockerfile/GitHub workflows, código de IA dinâmica.

---

## 3. Confirmação A/B/C na mesma base

| Lote | Marcador verificado no tree | Status |
|------|----------------------------|--------|
| **A1** | `__raid-party-v1`, auto-troca, `participante-vivo?` | Presente |
| **A2** | `normalize-ball` aliases, `qtyBall`, shop aliases | Presente |
| **A3/A4** | `POKEMON_THEMATIC_EVENTS_ENABLED=false` documentado; claim idempotente | Presente |
| **B1** | `raid/calcular-dano` × tipos; wild `resolver-ataque` | Presente |
| **B2** | `:autodesmaio` battle+raid+pokeapi | Presente |
| **B3** | `aplicar-ofertas-por-nivel` unwrap `:registro` | Presente |
| **B4** | Frambo prepare sem debitar (store) | Presente |
| **B5** | Tabela captura A6 aprovada (78…3 / raid 35…4) | Presente |
| **C1–C3** | placeholder claro, team `ensurePokemonSprite`, mensagens canônicas, `chances` no confirm-turn | Presente |

---

## 4. Suítes executadas e resultados

| Suite | Comando | Resultado |
|-------|---------|-----------|
| Homolog A+B+C integrada | `clojure -M:test-a6-homolog-abc` | **PASS** — 32 testes / **213 asserts** |
| Lote A isolado (subset) | incluso no homolog | PASS |
| Lote B isolado (subset) | incluso no homolog | PASS |
| Lote C JVM | incluso no homolog | PASS |
| Fase 12.8.1 temática | incluso no homolog | PASS |
| Node present/sprites/Lote C | `node --test test/pokemon-a6-*.test.cjs` | **PASS** 3/3 |
| Resources / WA / bootstrap | `npm run test:resources` | **PASS** 80/80 |
| A6 shutdown + maintenance + AI drain invariants | `node --test test/a6-shutdown|maintenance|ai-drain-sandbox-invariants` | **PASS** 16/16 |
| M3 raid (+ regressões) | `clojure -M:test-m3-raid` | **PASS** |
| M4 capture + HTTP smoke | `clojure -M:test-m4-capture` | **PASS** (encounter/battle/capture + HTTP M4.4) |
| M4 bridge HTTP loopback | `clojure -M:test-m4-bridge` | **PASS** `{:ok true}` |

**Não executado (proposital):** Docker build/push, deploy, migrations live, backfill, ativação de flag temática em prod, `test:ai` completo além dos invariants de sandbox/drain já cobertos.

---

## 5. Sequência real homologada (Datomic descartável)

`pokemon.tests.homolog-abc-integrated-a6-test` (flag temática **só no processo de teste** via `with-redefs`):

1. **Missão evento** `noite-sombras` → tick → progresso → **claim**
2. **Idempotência** do mesmo `operation-id` → `:duplicate true`
3. **Inventário** recebe `grande-bola` canônica
4. **Captura** com Grande Bola (roll 0) → debita 1
5. **Batalha wild** → `aguardando-captura?` + `chances-painel` coerente
6. **Raid** join party ≥2 → ataque → **faint + troca** → fase `combate` (não derrota)
7. **Reopen** store no mesmo storage-dir → evento ativo + party + inventário preservados

Raid com reserva e present/sprites validados também nas suítes A/C dedicadas.

---

## 6. Eventos temáticos / flags / timers (sem ativar produção)

| Item | Estado homologado |
|------|-------------------|
| `POKEMON_THEMATIC_EVENTS_ENABLED` | Default **`false`** em `.env.example` e código |
| Schedule | `enabled=false` no ensure; admin/tick só em testes com redef |
| Persistência | Ocorrências + `:save/missoes-eventos-edn` sobrevivem reopen (provado) |
| Schema | `032-thematic-events` já na árvore RC — **não** reexecutar migration neste GO |
| Produção | **Não** alterar flag/env/schedule nesta etapa |

---

## 7. Schema / config / dependências

| Dependência | Necessário para release candidata? | Ação |
|-------------|-------------------------------------|------|
| Migration **032** temática | Já esperada na RC | Verificar presença no ambiente alvo; **não** rodar de novo se já aplicada |
| Migration nova A/B/C | **Não** (party embutida em EDN) | Nenhuma |
| Env novos | Só documentação `POKEMON_THEMATIC_EVENTS_ENABLED=false` | Manter off |
| Node deps (sharp etc.) | Já usados pela media A6 | Sem bump neste diff |
| Imagem combinada drain/sandbox | Tip `16b5987` | Gameplay audit **empilha** sem sobrescrever |

**Divergências:** nenhuma de schema introduzida. Divergência operacional residual = working tree uncommitted vs tip RC.

---

## 8. Idempotência e integridade de saves

| Fluxo | Evidência |
|-------|-----------|
| Claim evento | Receipt + duplicate no homolog e `fase1281-thematic-store-test` |
| Captura / raid attack | operation-id + receipts (suites M3/M4) |
| Reopen | Save `a`, inventário, party de raid e progresso de missão intactos |
| Pending bag | Testado em fase1281 (mochila cheia → pending); sem backfill |

---

## 9. Problemas encontrados na homologação

| # | Problema | Resolução |
|---|----------|-----------|
| 1 | Assert rígido esperava reserva nome `"B"`; na sequência integrada a coleção já tinha Squirtle → troca para Squirtle | Assert ajustado: não-`"A"`, HP>0, `trocou?`, fase combate |
| 2 | (Histórico Lote B) unwrap de `oferecer-golpe` | Já corrigido antes desta homologação |
| 3 | Nenhum outro FAIL nas suítes listadas na §4 | — |

---

## 10. Riscos residuais (por gravidade)

### Alta (operacional / produto se mal configurado)

| Risco | Mitigação | GO? |
|-------|-----------|-----|
| Ativar eventos temáticos sem schedule+tick validados | Flag default off; checklist GO humano | Bloqueia só o GO de *eventos*, não o da imagem gameplay |
| Confusion `!pk eventos` (surge) vs `!pk missoes evento` | Docs | Aceitável |

### Média

| Risco | Mitigação |
|-------|-----------|
| Raids **legadas** com party de 1 | Esperar expirar; novos joins ≤3 |
| Estoque histórico `great-ball` | Aliases UI/input; remap **não** executado |
| Recompensas em **pending** (mochila cheia) | `!pk mochila resgatar` |
| Golpes slimados sem `:autodesmaio` | Re-enrich / reaprender |
| Learnset PokeAPI incompleto no JVM | Candidatos tipo/básico ou `golpes-candidatos` |
| Reidratação de sprites / cache CDN | Placeholder claro; ops de CDN fora deste corte |

### Baixa / intencional

| Risco | Nota |
|-------|------|
| STAB ausente no dano | Desenho A6 |
| Crit fora da fórmula de raid | Desenho A6 |
| Fallback `chanceComBola` no Node se DTO sem `chances` | Confirm-turn agora envia `chances`; fallback residual |
| Preview de emoji de tipo no menu de golpes (Node) | Não altera dano do motor |

---

## 11. Plano de integração Git (seguro)

1. **Commitar** o working tree em `fix/a6-gameplay-audit-impl` (mensagem focada em A+B+C + homolog), **sem** `--amend` do tip RC `16b5987`.
2. **Não** apagar worktrees; **não** `reset --hard`; **não** force-push em `main`/`rc/*`.
3. Abrir PR **para** `rc/a6-ai-drain-sandbox` (ou branch de release candidata derivada dela).
4. CI: rodar pelo menos `:test-a6-homolog-abc`, Node `pokemon-a6-*.test.cjs`, `test:resources`, smokes M3 raid / M4 capture já usados.
5. Review: confirmar que o diff **não** toca drain/maintenance/Compose/imagem.
6. Merge **non-ff** preferível (merge commit) para preservar tip `16b5987` rastreável.
7. Tag candidata só **após** merge + smoke staging (ex. `rc/a6-gameplay-audit-rc1`) — fora deste documento executar.

---

## 12. Plano de rollback

| Cenário | Ação |
|---------|------|
| Regressão gameplay pós-merge | Reverter o merge commit do PR audit; redeploy tip anterior `16b5987` |
| Bug só em party/raid | Feature permanece; raids legadas naturally expire; hotfix pontual em `raid.clj`/`raid_store.clj` |
| UI/sprites | Rollback só dos artefatos Node `pokemon-jvm-media/present` se isolável |
| Eventos temáticos | Manter/`garantir` `POKEMON_THEMATIC_EVENTS_ENABLED=false` — kill-switch imediato |
| Saves | Sem migration → sem downgrade de schema; EDN party v1 compatível com decode legado |

---

## 13. GO / NO-GO — construção de release candidata

### Veredito: **GO CONDICIONAL** para *construir* uma release candidata (imagem/tag a partir desta branch), **NO-GO** para *ativar* eventos temáticos ou *deploy produção* neste momento.

| Critério | Status |
|----------|--------|
| A+B+C presentes na mesma base `16b5987` | GO |
| Drain / maintenance / AI sandbox intactos | GO |
| Suítes homolog + regressões críticas PASS | GO |
| Sem migration nova / sem backfill | GO |
| Flag temática permanece off | GO |
| Working tree ainda **não commitada** | Condição: commit + PR antes do build oficial |
| Deploy / prod | **NO-GO** até smoke staging + GO humano explícito |

### Próxima etapa recomendada (objetiva)

1. **Commit** das alterações A+B+C + docs + testes neste worktree.
2. **PR** → `rc/a6-ai-drain-sandbox` com checklist de smoke staging (raid party 2, captura Grande Bola, `!pk time`, turno com efetividade).
3. Após merge CI verde: **tag RC** da imagem combinada (drain+sandbox+gameplay).
4. Staging smoke; só então GO de produção.
5. Eventos temáticos: GO **separado**, depois de validar schedule+tick, ainda com flag default off até autorização.

---

## 14. Referências rápidas

- Auditoria por lote: `docs/pokemon-a6-gameplay-audit.md`
- Homolog runner: `clojure -M:test-a6-homolog-abc`
- Taxas de **captura** aprovadas (não aparecimento): wild 78/58/28/14/5/3 · raid 35/35/35/20/8/4 · bolas/Frambo ×1/1.5/2/1.5 · teto 95%
