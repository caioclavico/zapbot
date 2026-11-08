# Pokémon A6 — Auditoria de jogabilidade

**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Branch:** `fix/a6-gameplay-audit-impl` (base `rc/a6-ai-drain-sandbox` @ `16b5987`)
**Escopo:** correções locais homologadas. **Sem deploy, migrations live, backfill ou alteração de saves/flags de produção.**

## Caminho canônico

WhatsApp → Node (`pokemon_http` + `pokemon-jvm-present/media`) → JVM HTTP `:18090` → `modules/pokemon` (domínio + Datomic).

O monólito CLJS legado (`src/zapbot/pokemon/core.cljs`) **não** é fonte de verdade A6.

STAB: **ausente no dano** por desenho A6 (só preferência de moveset). Documentado e mantido.

---

## Riscos residuais do Lote A (registro — sem migration/backfill)

| Risco | Impacto | Mitigação atual | Ação futura |
|-------|---------|-----------------|-------------|
| **Raids legadas com party de 1** | Joins já em combate decodificam como party unitária; desmaio único ainda encerra aquela raid. | Novos joins selam até 3; legado só até o fim da raid aberta. | Nenhuma migration; esperar raids expirarem. |
| **Estoque legado `great-ball` / `super-bola`** | Debito de captura usa ID canônico `grande-bola`; qty só em ID legado não é consumida. | Aliases de input + `qtyBall` soma aliases na UI. | Remap opcional de inventário (não executado). |
| **Mochila cheia → pending** | Recompensa vai para pending; indisponível na captura até resgate. | `!pk mochila resgatar`. | Sem backfill. |
| **Eventos temáticos off / schedule / tick** | UI vazia se flag off — dados Datomic **não** se perdem. | Default **false** preservado; `.env.example` documenta. | GO humano: flag + schedule + tick. |
| **`!pk eventos` ≠ missões temáticas** | Surge 6h ≠ `!pk missoes evento`. | Docs. | UX/copy opcional. |

---

## Lote A — Integridade

**Status:** aprovado (`clojure -M:test-a6-lote-a` — 15 testes / 98 asserts).

| Item | IMPLEMENTADO | TESTADO | PENDENTE |
|------|--------------|---------|----------|
| **A1** Raid party / faint | Party ≤3, auto-troca | domínio + Datomic | Joins legados 1 mon |
| **A2** Grande Bola | aliases + UI + qtyBall | present + claim→captura | Remap `great-ball` |
| **A3** Eventos pós-restart | Persistência OK; flag doc | reopen + fase1281 | Ativação env/tick |
| **A4** Claim evento | Idempotente | claim+captura | Backfill histórico |

---

## Lote B — Mecânicas

**Status:** aprovado, **incluindo a tabela de taxas de captura A6** (não confundir com taxas de *aparecimento*/spawn).

### Aprovação humana — taxas de **captura** (não de aparecimento)

Registrado em 2026-11-07. Valores = chance de sucesso ao lançar a bola (Pokébola / base de raid), **não** probabilidade de o Pokémon aparecer na caça.

| Raridade | Wild (captura, centro) | Raid (captura) |
|----------|------------------------|----------------|
| comum | **78%** | **35%** |
| incomum | **58%** | **35%** |
| raro | **28%** | **35%** |
| épico | **14%** | **20%** |
| lendário | **5%** | **8%** |
| mítico | **3%** | **4%** |

**Multiplicadores de captura** (após a base; teto 95%): Pokébola ×1 · Grande Bola ×1.5 · Ultra Bola ×2 · Frambo ×1.5.

| Item | IMPLEMENTADO | TESTADO | PENDENTE |
|------|--------------|---------|----------|
| **B1** Dano/efetividade | Raid + wild/gym × tipos; sem STAB | 2×/imune RNG fixo | Crit fora da raid |
| **B2** Explosion | Autodesmaio + party | domínio + Datomic HP 0 | Re-enrich slimados |
| **B3** Learn @5 | Unwrap `:registro` | multi-marco + XP | Learnset completo JVM |
| **B4** Frambo | Prepare sem debitar | encounter Datomic | Prepares antigos |
| **B5** Raridade/bolas | Tabela A6 aprovada | snapshot + attempt | — |

---

## Lote C — Apresentação

**Status:** concluído e homologado (`clojure -M:test-a6-lote-c` + testes Node).

### C1. Sprites / caixa preta (`!pk time`, batalhas, raids)

| | |
|---|---|
| **Causa-raiz** | Placeholder opaco escuro; DTO sem URL; composite sem `blend: 'over'` no time. |
| **Arquivos** | `pokemon-jvm-media.cjs` (`svgPlaceholder`, `spriteProporcional`, `renderTeamGrid`, arenas) |
| **Correção** | Placeholder xadrez claro; `ensureAlpha` + contain transparente; `ensurePokemonSprite` no time; `blend: 'over'`; cache LRU 48. |
| **IMPLEMENTADO** | Sim — caça, raid e `!pk time`. |
| **TESTADO** | Placeholder + `renderTeamGrid` sem imagem → PNG não-preto (`nonBlack` ≫ 0). |
| **PENDENTE** | Rehydrate depende de PokeAPI/rede; espécie inválida permanece no placeholder claro. |

### C2. Transparência, fallback e cache

| | |
|---|---|
| **IMPLEMENTADO** | Alpha no resize; fallback placeholder; cache de download. |
| **TESTADO** | Node media/placeholder/team grid. |
| **PENDENTE** | Invalidação de cache CDN em produção (ops). |

### C3. Mensagens de combate / raid / captura

| | |
|---|---|
| **Causa-raiz** | Raid sem golpe/efetividade; fallback de turno sem nome; KO sem `chances` canônicas; “Super Bola”. |
| **Arquivos** | `damage.clj` (`mensagem-ataque` / `last-action-ataque`), `battle.clj`, `bridge_tx.clj`, `pokemon-jvm-present.cjs` |
| **Correção** | Nome traduzido + 💥/🛡️/🚫/🎯; HP antes→depois; crítico/status via mensagem do motor; Explosion/autodesmaio; troca de party; qty real de bolas; `chances` no confirm-turn; present **não recalcula dano**. |
| **IMPLEMENTADO** | Sim. |
| **TESTADO** | JVM last-action + Node present/raid/captura/KO. |
| **PENDENTE** | Menu de golpes ainda usa tabela de tipos local só para *preview* de emoji (não altera dano). |

#### Exemplos de mensagens reais (fixtures de teste)

**Raid (super efetivo + troca + Explosion):**
```
⚔️ Usou *Lança-Chamas*!
💥 É super efetivo!
🔄 Seu Pokémon desmaiou — próximo da party entrou!
💀 Seu Pokémon desmaiou após o golpe!
💥 Dano 42 · revide 10
❤️ Chefe: 142→100 / 200
🐾 Seu ativo: HP 88
```

**Wild (mensagem canônica do motor):**
```
🔮 *Brasas* de *Charmander* causou 35 de dano em *Oddish*!
💥 É super efetivo!
🎯 Acerto crítico!
```

**Captura (qty real):**
```
🔴 Pokébola: 12 · chance 70%
🔵 Grande Bola: 4 · chance 95%
🟡 Ultra Bola: 2 · chance 95%
```

#### Evidência de sprites

- Placeholder / URL inválida: PNG com padrão xadrez claro (`nonBlack` > 10 no raw).
- `!pk time` sem `imagem`: `renderTeamGrid` gera PNG (`nonBlack` ≈ 370k pixels não-pretos no teste); rehydrate tenta PokeAPI e cai no placeholder claro.

---

## Homologação local executada

```
clojure -M:test-a6-lote-a     15 testes / 98 asserts  PASS
clojure -M:test-a6-lote-b     23 testes / 95 asserts  PASS
clojure -M:test-a6-lote-c      7 testes / 30 asserts  PASS

node --test test/pokemon-a6-lote-c-present.test.cjs      PASS
node --test test/pokemon-a6-gameplay-present.test.cjs    PASS
node --test test/pokemon-a6-sprite-placeholder.test.cjs  PASS
```

**Não executado:** build Docker, deploy, migrations live, backfill, alteração de flags de produção.

## Plano de lançamento (GO humano)

1. **Lote A** — aprovado; smoke prod: raid solo 2 mons + claim → captura `2`/`great`.
2. **Lote B** — taxas de **captura** aprovadas (tabela acima); smoke lendário + Explosion + level-up @5.
3. **Lote C** — aprovado local; smoke `!pk time` + turno wild/raid + menu de captura com qty.
4. Ativar `POKEMON_THEMATIC_EVENTS_ENABLED=true` só com schedule+tick validados.
5. Sem backfill / remap `great-ball` até autorização explícita.

## Preservação produção

Nenhuma imagem trocada, nenhum restart JVM/Node/WhatsApp, nenhum save alterado, nenhuma flag de produção mudada nesta etapa.

---

## P0 pós-cutover (2026-11-08)

Escopo: correções locais. **Sem deploy / migration / backfill / flags / saves live.**

| Bug | IMPLEMENTADO | TESTADO | PENDENTE |
|-----|--------------|---------|----------|
| **HP raid → `!pk time`** | `raid-party-hp-txs` em ataque + leave; XP com HP do snapshot (sem full heal) | `raid-hp-sync-p0-a6-test` (ataque/leave/vitória) | Deploy JVM; smoke prod |
| **`? — Poder 0`** | `golpe-normalizado` sempre; Wartortle string-keys; status 0 ≠ ref quebrada | slim Wartortle + status | Parties já seladas: rejoin |
| **UI raid = caça** | emoji classe + “causou N” + `linhaGolpe` | Node P0 + fase12 + lote-c | Deploy Node |
| **Grande/Ultra qty + %** | `bola-aliases` + `inv-qty` + `inventory-adjust-ball-tx`; `ballMapGet`; % motor | UI camelCase + débito `great-ball` | Deploy Node+JVM |
| **Pokédex moldura** | `!pk pokedex N` → `renderPokedexCard` | pokedex species tests | Deploy Node |

### Arquivos

| Papel | Path |
|-------|------|
| HP sync | `raid_store.clj`, `battle_leave_store.clj`, `store.clj` |
| Golpes | `battle.clj` |
| Estoque/débito | `capture.clj`, `bridge_reads.clj`, `store.clj` |
| Present / pokedex | `pokemon-jvm-present.cjs`, `pokemon-jvm-media.cjs`, `pokemon_http.cljs` |
| Testes | `raid_hp_sync_p0_a6_test.clj`, `pokemon-a6-raid-gym-capture-p0.test.cjs` |

### Homologação local

`clojure -M:test-a6-lote-a|b|c` + `homolog-abc` PASS · Node P0/raid/pokedex PASS.

Drain / IA / sandbox / hold: **inalterados**. Imagens candidatas: ver readiness §10.
