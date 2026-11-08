# Pokémon A6 — Auditoria de jogabilidade

**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Branch:** `fix/a6-raid-one-mon-ux` (base tip integrada `b9ae76c`)
**Escopo:** A+B+C+P0 + Balance A + revisão 1-mon/UX/catálogo. Live RO auditado antes das mudanças. **Sem deploy/migrations/backfill/saves/flags.** HP de espécie: proposta quantitativa pendente de aprovação.

## Caminho canônico

WhatsApp → Node (`pokemon_http` + `pokemon-jvm-present/media`) → JVM HTTP `:18090` → `modules/pokemon` (domínio + Datomic).

O monólito CLJS legado (`src/zapbot/pokemon/core.cljs`) **não** é fonte de verdade A6.

STAB: **ausente no dano** por desenho A6 (só preferência de moveset). Documentado e mantido.

---

## Riscos residuais do Lote A (registro — sem migration/backfill)

| Risco | Impacto | Mitigação atual | Ação futura |
|-------|---------|-----------------|-------------|
| **Raids legadas com party >1** | Dados da party preservados; `avancar-party` **não** promove reservas; faint do ativo elimina o treinador. | Novos joins selam `max-party=1`; legado só até o fim da raid aberta. | Nenhuma migration; esperar raids expirarem. |
| **Estoque legado `great-ball` / `super-bola`** | Debito de captura usa ID canônico `grande-bola`; qty só em ID legado não é consumida. | Aliases de input + `qtyBall` soma aliases na UI. | Remap opcional de inventário (não executado). |
| **Mochila cheia → pending** | Recompensa vai para pending; indisponível na captura até resgate. | `!pk mochila resgatar`. | Sem backfill. |
| **Eventos temáticos off / schedule / tick** | UI vazia se flag off — dados Datomic **não** se perdem. | Default **false** preservado; `.env.example` documenta. | GO humano: flag + schedule + tick. |
| **`!pk eventos` ≠ missões temáticas** | Surge 6h ≠ `!pk missoes evento`. | Docs. | UX/copy opcional. |

---

## Lote A — Integridade

**Status:** aprovado (`clojure -M:test-a6-lote-a` — 15 testes / 98 asserts).

| Item | IMPLEMENTADO | TESTADO | PENDENTE |
|------|--------------|---------|----------|
| **A1** Raid party / faint | **1 mon/treinador**, sem auto-troca | domínio + Datomic + one-mon | Legado party>1 preservado, sem promover |
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

Cutover P0 já em produção (ver §P0). Nesta etapa de balanceamento/UX: **nenhum** deploy/restart/save/flag/migration/backfill; hold/CI intocados.

---

## P0 pós-cutover (2026-11-08)

**Produção:** **SUCCESS** — Node `bec910bf…` · JVM `9e87bb6f…` · commit `6cef8f43…` · READY ≥20 min.  
Evidência: `a6-p0-hp-ui-cutover-20261108T074953Z`. Migrations/backfill/flags/saves **não** alterados.

| Bug | IMPLEMENTADO | TESTADO | PRODUÇÃO |
|-----|--------------|---------|----------|
| **HP raid → `!pk time`** | `raid-party-hp-txs` em ataque + leave; XP com HP do snapshot (sem full heal) | `raid-hp-sync-p0-a6-test` | JVM live `9e87bb6f` |
| **`? — Poder 0`** | `golpe-normalizado` sempre; Wartortle string-keys; status 0 ≠ ref quebrada | slim Wartortle + status | JVM live; parties já seladas: rejoin |
| **UI raid = caça** | emoji classe + “causou N” + `linhaGolpe` | Node P0 + fase12 + lote-c | Node live `bec910bf` |
| **Grande/Ultra qty + %** | `bola-aliases` + `inv-qty` + `inventory-adjust-ball-tx`; `ballMapGet`; % motor | UI camelCase + débito `great-ball` | Node+JVM live |
| **Pokédex moldura** | `!pk pokedex N` → `renderPokedexCard` | pokedex species tests | Node live |

### Arquivos

| Papel | Path |
|-------|------|
| HP sync | `raid_store.clj`, `battle_leave_store.clj`, `store.clj` |
| Golpes | `battle.clj` |
| Estoque/débito | `capture.clj`, `bridge_reads.clj`, `store.clj` |
| Present / pokedex | `pokemon-jvm-present.cjs`, `pokemon-jvm-media.cjs`, `pokemon_http.cljs` |
| Testes | `raid_hp_sync_p0_a6_test.clj`, `pokemon-a6-raid-gym-capture-p0.test.cjs` |

### Homologação / cutover

Local: `clojure -M:test-a6-lote-a|b|c` + `homolog-abc` PASS · Node P0/raid/pokedex PASS.  
Produção: drain → cold WA/Datomic → ACL → JVM→resume→Node → RO contracts → **STABILITY_20M_PASS**.  
Hold/CI-CD inalterados. Detalhes: `docs/pokemon-a6-integrated-release-readiness.md`.

---

## Balanceamento geral + UX combate (pós-P0 live)

**Atualizado:** 2026-11-08  
**Live auditado:** Node `bec910bf…` / JVM `9e87bb6f…` @ `6cef8f43` (cutover P0 **confirmado** READY/healthy).  
**Escopo desta entrega:** bugs de estado/UI/golpes/faint **implementados localmente**. **Sem deploy.** Parâmetros amplos de dificuldade: **proposta quantitativa — aguardando aprovação**.

### Diagnóstico live vs RC

| Item | Estado |
|------|--------|
| Deploy P0 | **Em produção** (`bec910bf`/`9e87bb6f`) |
| Bugs novos (não cobertos pelo P0) | Vitória presa em `!pk raid`; barras HP ausentes na raid; raridade do jogador na caça; ordem de reserva no faint; golpes `?` residuais em snapshots/UI |
| Já corrigido no P0 mas ainda visível | Parties de raid seladas **antes** do P0 mantêm golpes slim antigos até leave/rejoin |

### Causas-raiz confirmadas

| Bug | Causa |
|-----|-------|
| `!pk raid` preso em vitória | `raid-dto`/`raid-visivel?` tratavam fase `vitoria` como ativa durante **toda** a janela `captura-expira`, mesmo após captura resolvida; ponteiro não era limpo |
| Golpe `?` | Normalização incompleta (nome vazio/`?`); Node `linhaGolpe` fallback literal `?`; snapshots de raid não re-normalizavam no decode/DTO |
| Faint “mon errado” | `avancar-party` escolhia o **primeiro** índice com HP>0, não o próximo na ordem de entrada |
| Barras HP / UI raid ≠ caça | `formatRaidTurnBlock` usava `HP N` textual; sem `barraHp`; raridade do chefe inconsistente |
| Raridade do jogador na caça | Layout com traço residual; reforçado para omitir raridade em `pokemonX` |
| Dano SE “explodindo” raids | Fórmula raid **sem variação** + clamp base≤160 × efetividade **depois** do clamp; solo raro HP=220 → hit SE típico (~182) ≈ **83%** do chefe |
| Dano “sempre igual” | Caça: `U{0..10}` em `damage.clj`. Raid: **determinística** (sem RNG) |

### Correções implementadas (código local)

| Área | Mudança |
|------|---------|
| Lifecycle | `visivel-para?` / `captura-pendente?` / `deve-liberar-ponteiro?`; vitória só no menu se **este** jogador tem captura pendente; retract do ponteiro em captura resolvida / derrota |
| Faint | `avancar-party` percorre a ordem de entrada com wrap |
| Golpes | `golpe-normalizado` público + nomes sem `?`; renorm no decode da party e no DTO; Node `nomeGolpeUi` |
| UI | Barras `barraHp` na raid; raridade só no chefe/adversário; rodapé de vitória condicional (`capturar` vs `raid`/`time`/`mochila`) |

### Exemplos finais (trechos)

**Raid turno (unificado):**
```
👾 *Onix* — Raro
[██████░░░░] 140/220
🐾 *Wartortle*
[███████░░░] 67/100
👉 Vez de *Ash*
1. 🔮 *Water Gun* (Água, poder 40) 🔥 → `!pk raid atacar 1`
```

**Vitória com captura pendente:**
```
🏆 *RAID CONCLUÍDA!*
…
🎯 *CAPTURA DISPONÍVEL*
`!pk raid capturar`
📌 *Próximos comandos*
`!pk raid capturar` · `!pk mochila` · `!pk time`
```

**Sem raid ativa:**
```
🏛️ Nenhuma raid em andamento neste chat.
Abrir: !pk raid abrir
```

### Balanceamento — Proposta A **aplicada** (aprovada 2026-11-08)

Parâmetros efetivos no domínio:

| Parâmetro | Valor |
|-----------|-------|
| `hp-por-jogador` | **320** (era 220) |
| Variação de dano | **85–100%** da base (`:raid-damage-var` ∈ `[0,16)`) |
| Ordem | base clamp → × variação → × efetividade (tipos **integrais**) |
| RNG | `pokemon.domain.rng/roll` injetável; produção sorteia 1× por ataque |
| Idempotência | receipt persiste `dano`/`variacao-pct`/`base-variada`; retry **não** re-rola |
| Fora de escopo | golpes fixos/%/status; STAB; caça (`damage-var` U{0..10} inalterado) |

Fórmula: `floor(15+0.6*poder*(atk/def))` clamp `[10,160]`, × pct, × efetividade (sem STAB).  
HP chefe: `320 × n_jogadores × fator` (duo raro = **640**).

| Cenário (solo, atk80 def50 poder80) | Neutro | Super×2 | % HP chefe (SE) | Turnos est. (só SE) |
|-------------------------------------|--------|---------|-----------------|--------------------|
| Antes HP 220 @100% | 91 | 182 | **83%** | ~2 |
| Depois HP 320 @100% | 91 | 182 | 57% | 2 |
| Depois HP 320 @85% | 77 | 154 | 48% | 3 |

Simulação local (`raid-balance-sim-a6-test`): solo/duo SE e neutro **vencíveis**; neutro alonga (~+1–2 turns); SE solo @85% fecha em &lt;8 turns. HP extra **não** torna raids impossíveis no perfil típico.

### Testes (homologação local pós-Proposta A)

| Suite | Resultado |
|-------|-----------|
| `:test-a6-lote-a` | PASS |
| `:test-a6-lote-b` (+ lifecycle + balance-sim) | PASS |
| `:test-a6-lote-c` | PASS |
| `:test-a6-homolog-abc` | PASS (32 / 213) |
| `:test-m4-raid` (JVM + smoke HTTP) | PASS |
| `:test-raid-lobby-fase2` | PASS (24 / 80) |
| Node `pokemon-a6-raid-gym-capture-p0.test.cjs` | PASS (8) |
| `npm run test:http` | PASS (287) |

### Imagens

Construir Node+JVM a partir do **mesmo commit** integrado na RC (labels `io.zapbot.git-sha` + `content-tree`). Overlay pin atualizado **sem** apply/deploy.

### Riscos residuais

- Parties de raid **já abertas** na live podem ainda exibir golpes antigos até leave/rejoin.
- Golpe verdadeiramente irrecuperável (sem nome/slug/tipo) fica marcado — sem backfill.
- Mons fracos vs chefes tanque ficam um pouco mais longos (intencional); perfis extremos ainda podem perder.
- Sem smoke destrutivo em saves reais; **sem deploy**.

### GO/NO-GO (Proposta A — histórico)

| Critério | Estado |
|----------|--------|
| Bugs objetivos + Proposta A no código | **GO** para integrar/build RC |
| Pedir janela de deploy produção | **NO-GO** até revisão humana dos Image IDs + hold |
| Deploy / migrations / flags / saves | **NO-GO** (proibido neste ciclo) |

---

## Revisão raids — 1 Pokémon / UX / catálogo / HP (2026-11-08)

**Branch:** `fix/a6-raid-one-mon-ux` (base integrada `b9ae76c` / RC `rc/a6-abc-p0-integrated`)  
**Live RO inspecionado antes das mudanças:** Node `002c83e8…` / JVM `e044cce1…` @ `043b846` (Balance A live).  
**Escopo:** bugs objetivos + regra 1-mon + catálogo manual + UX. **Fórmula global de HP de espécie: proposta quantitativa apenas — não aplicada.**  
**Sem deploy / migrations / backfill / saves / flags / WA / hold / CI.**

### Causas-raiz

| Item | Causa |
|------|-------|
| Troca automática / 3 mons | `max-party=3` + `avancar-party` promovendo próximo HP>0 |
| 3 barras de HP | Composição DTO + `formatRaidAttack`/`formatRaidStart` re-renderizando barra do chefe além de `formatRaidTurnBlock` |
| Raridade só no chefe / `?` | UI omitia raridade do jogador; fallbacks literais |
| Vitória presa / rodapé fraco | Footer não lia `data.capturaPendente`; copy incompleta |
| Empty `!pk raid` | Mensagem curta/sem inventário real de comandos |
| Frambo → cartão-presente | `INSUFFICIENT_ITEM` sempre usava copy de presente |
| Chefes manuais repetitivos | `open-raid!` caía no asé fixo `boss-do-ginasio` (5 templates) |
| HP espécie “igual” | `suavizar-stat` com compressão **0.5** empurra tudo para ~75 antes do nível |

### Alterações implementadas

| Área | Mudança |
|------|---------|
| Domínio | `max-party=1`; `montar-party` só 1º slot; `avancar-party` no-op; faint → `:eliminado?`; coop continua se outro vivo; HP partido persiste; sem auto-cura pós-raid |
| Legado | Party>1 preservada no Datomic; **não** promove reservas; novas raids não criam multi |
| Open manual | `select-boss-for-ginasio` (catálogo A6) com fallback legado; chefe explícito nos testes |
| Present | 2 barras via `barraHp`; raridade chefe+jogador; `formatRaidEmpty`; footer captura (pendente/concluída/expirada/sem direito); Frambo item-aware |
| Catálogo | Elegíveis **571** (raro 423 / épico 34 / lendário 91 / mítico 23) vs **5** templates fixos |

### Exemplos reais de mensagem

**Turno (exatamente 2 barras + raridades):**
```
👾 *Onix* — 🔵 Raro
[██████░░░░] 140/220
🐾 *Wartortle* — 🟢 Incomum
[███████░░░] 67/100
👉 Vez de *Ash*
1. 🔮 *Water Gun* (Água, poder 40) 🔥 → `!pk raid atacar 1`
```

**Vitória + captura pendente:**
```
🎯 *CAPTURA DISPONÍVEL*
`!pk raid capturar` — ver bolas / chances
`!pk raid capturar 1|2|3` — Poké / Grande / Ultra
`!pk raid fruta` — preparar Frambo (+chance)
📌 *Próximos comandos*
`!pk raid capturar`
`!pk mochila`
```

**Sem raid ativa:**
```
🏛️ *Nenhuma raid em andamento neste grupo.*
📌 *Comandos*
`!pk raid` · `!pk raid abrir` · `!pk raid abrir <ginasio>`
`!pk raid entrar` · `!pk raid entrar auto` · `!pk raid entrar <n>`
`!pk raid iniciar` · `!pk time` · `!pk mochila`
```

**Frambo indisponível:**
```
🍓 *Você não possui Frutas Frambo!*
🎒 Confira sua mochila com `!pk mochila`.
```

### HP por espécie — auditoria + proposta (**não aplicada**)

Fórmula atual: `HP = round(suavizar(base) × 1.03^(nível−1))` com  
`suavizar(v) = round(75 + 0.5×(v−75))`.

| Espécie | Base | Nv | Perfil | HP atual | HP prop (comp **0.85**) | Δ |
|---------|-----:|---:|--------|---------:|------------------------:|--:|
| Magikarp | 20 | 10 | frágil | 63 | 37 | −26 |
| Pidgey | 40 | 10 | frágil | 76 | 59 | −17 |
| Pikachu | 35 | 10 | frágil | 72 | 53 | −19 |
| Eevee | 55 | 10 | equilibrado | 85 | 76 | −9 |
| Wartortle | 59 | 15 | equilibrado | 101 | 92 | −9 |
| Charizard | 78 | 25 | equilibrado | 157 | 159 | +2 |
| Gyarados | 95 | 20 | resistente | 149 | 161 | +12 |
| Snorlax | 160 | 20 | resistente | 207 | 258 | +51 |
| Chansey | 250 | 20 | tanque | 286 | 393 | +107 |
| Shedinja | 1 | 20 | especial | 67 | 21 | −46 |
| Mewtwo | 106 | 30 | lendário | 214 | 238 | +24 |

Spread nv10 Magikarp→Chansey: **atual 63→213** · **proposta 37→292**.

**Política se aprovada:** não reescrever HP de saves; aplicar só em **novos** encontros/starters; evolução/level-up continuam `×1.03` sobre o valor persistido; sem bônus por raridade; impacto em caça/raid/ginásio/PvP/cura documentado antes do GO. **Aguardando aprovação humana.**

### Balanceamento 1-mon × Proposta A (HP 320, var 85–100%)

HP chefe **não** reduzido. Simulação (`raid-balance-one-mon-sim-a6-test`):

| Cenário | Vitória? | Turnos (est.) |
|---------|----------|---------------|
| Solo frágil SE @85% | sim* | ~3 |
| Solo equilibrado SE @100% | sim | ~2 |
| Solo tanque SE @85% | sim | ~3 |
| Solo neutro @85% | sim | ~5 |
| Coop 2 / 3 / 5 SE @85% | sim | ~5 / 7 / 11 |
| Coop 3 neutro @85% | sim | ~13 |
| Lendário fator 1.4 · coop 3 SE @85% | fechável | ~9 hits (HP 1344) |

\*Perfil extremo frágil+neutro ainda pode perder — intencional.

### Chefes manuais

| | Antes | Depois |
|--|------:|-------:|
| Fonte | 5 templates `chefes-ginasio` | `raid_boss_catalog.edn` via `select-boss-for-ginasio` |
| Elegíveis | 5 | **571** |
| Captura | taxas aprovadas intactas | intactas (sem mudança) |

### Compatibilidade

- Raids em andamento: sem swap novo; dados multi preservados até expirar.
- Saves: HP/inventário **não** alterados.
- Flags / IA dinâmica / drain / sandbox / hold / CI: intocados.
- Reconstrução **Node + JVM** necessária (present + domínio Clojure).

### Testes (esta revisão)

| Suite | Resultado |
|-------|-----------|
| `:test-a6-lote-a` (+ one-mon) | PASS (21 testes / 116 asserts) |
| `:test-a6-lote-b` (+ one-mon-sim) | PASS |
| `:test-a6-lote-c` | PASS |
| `:test-a6-homolog-abc` | PASS (**42** / **247**) |
| `:test-m4-raid` + HTTP | PASS |
| `:test-raid-lobby-fase2` | PASS (24 / 80) |
| Node `pokemon-a6-raid-gym-capture-p0` + `pokemon-a6-raid-one-mon-ux` | PASS (**17**/17) |

### GO/NO-GO integração

| Critério | Estado |
|----------|--------|
| 1-mon + UX + Frambo + catálogo + regressões | **GO** — merged PR [#43](https://github.com/caioclavico/zapbot/pull/43) @ `b0f0677` |
| Imagens Node+JVM AMD64 | **GO** — `617525d9…` / `4a75eaa2…` (tree `f3866c5e…`) |
| Pedido de janela de deploy | **GO para preparar** (overlay pinado; **sem** apply) |
| Fórmula global de HP de espécie | **NO-GO** (proposta só; aguarda aprovação) |
| Deploy / migrations / saves / flags | **NO-GO** (proibido neste ciclo) |

**Rollback live (RO 2026-11-08):** Node `002c83e8…` / JVM `e044cce1…` (Balance A).
