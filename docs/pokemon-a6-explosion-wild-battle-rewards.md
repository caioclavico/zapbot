# A6 — Explosion P0 + Caçadas livres com sequência de capturas

**RC integrada:** `github/rc/a6-abc-p0-integrated` @ `2afd997` (tree `ad8a3cf4…`)  
**PRs:** [#59](https://github.com/caioclavico/zapbot/pull/59) feature · [#60](https://github.com/caioclavico/zapbot/pull/60) homolog testes  
**Feature tip histórico:** `8f3c271` (sobre base `284c87c` → merge `09dc5b5`)  
**Política de raridade:** inalterada (`:legado`)  
**Imagens:** `odisseu-node/jvm:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64`  
**Sem deploy / migrations / backfill / cutover neste passo.**

### Simulação 10k (homolog RC)

`hunt-streak-sim-a6-test` PASS — política `streak-unit-v1` com valores inteiros; capturador médio ~17.98 XP / ~9.98 💰 (teto 30+); liberador 0 moedas; sem bônus percentuais.

## 1. Explosion — causa-raiz e correção

### Sintoma
Pokémon que usava Explosion e vencia podia voltar à equipe com HP cheio.

### Causa-raiz (ginásio)
Em `resolve-faint` (kind `:gym`), quando Explosion derruba o adversário e o usuário no mesmo golpe, o fluxo entrava por `fainted-side = :o` (checagem do HP do oponente primeiro). O índice do atacante **não** era adicionado a `fainted-x` antes de `enviar-proximo`. O sync pós-vitória (`sync-colecao-from-gym-battle`) só zera HP de:

- índices em `fainted-x`
- o ativo atual
- time inteiro na derrota

O mon que explodiu saía da reserva sem estar em `fainted-x` e **preservava o HP pré-batalha**.

### Correções
1. Em `both-down?`, marcar `fainted-x` sempre que HP de X for 0, mesmo se o nocaute reportado foi `:o`.
2. Snapshot embutido: `autodesmaio` também grava `:pokemons atk :hp-atual 0`.
3. `create-wild-battle`: HP inicial não usa `(or hp-x …)` (0 era tratado como ausente).
4. PvP leave/cancel: sync de HP usa `some?` (HP 0 é legítimo).
5. Captura pós-vitória aplica XP no fighter da batalha com HP do snapshot (`:hp :x`), preservando desmaio após level-up.

### Caça selvagem
O caminho wild já persistia HP 0 no turno da vitória; reforçado com testes de captura/liberar + level-up.

### Critério
Qualquer Pokémon que vence com Explosion retorna à equipe com **HP atual = 0**, permanece desmaiado após restart, e só cura via Joy/itens.

## 2. Caçada — captura opcional

### Fluxo
1. Combate selvagem (inalterado).
2. Ao vencer → **XP Pokémon + PE treinador de batalha** creditados na hora (`recompensas-batalha-concedidas?`).
3. Oferece capturar **ou** liberar.
4. `!pk liberar` / `POST /v1/encounter/release` encerra sem captura, **preserva** recompensas de batalha e **zera** sequência.
5. `!pk sair` durante combate = desistência (sem vitória); em aguardando-captura, preferir `liberar`.
6. Bolsa cheia: libera automaticamente (mesmo efeito de liberar na sequência).

### Comandos
| Ação | Comando |
|------|---------|
| Caçar | `!pk cacar` |
| Capturar | `!pk capturar <bola>` |
| Liberar | `!pk liberar` |
| Desistir | `!pk sair` |
| Ajuda | `!pk ajuda cacar` |

## 3. Sequência — recompensas unitárias (`streak-unit-v1`)

Valores = **total da captura** na faixa (não acumulam base + bônus).

| Sequência após captura | XP | PE | 💰 |
|---:|---:|---:|---:|
| 1–2 | 2 | 1 | 0 |
| 3–4 | 3 | 2 | 0 |
| 5–9 | 5 | 3 | 1 |
| 10–19 | 8 | 5 | 3 |
| 20–29 | 12 | 7 | 6 |
| 30+ | 15 | 9 | 10 |

- Sem recompensa-base de moedas.
- PE de captura/batalha credita `:save/vitorias-treinador` (componente do XP treinador).
- Fuga/falha involuntária **não** zera sequência.
- Liberar / desistir / bolsa cheia **zeram**.
- Raids/ginásios/PvP não alteram a sequência.
- Um encontro incrementa no máximo uma vez; recibos idempotentes.

### Batalha (comum)
`recompensa-batalha-caca`: XP = raridade+1 (comum→3), PE = 1, moedas = 0.

## 4. Valores antigos vs novos

| Evento | Antes | Agora |
|--------|-------|-------|
| Vitória + aguardando | XP deferido à captura | XP/PE de batalha na vitória |
| 1ª captura | XP raridade+bônus (~3) + 💰≥2 | +2 XP +1 PE +0 💰 |
| 10ª captura | raridade + seq + 💰~12 | +8 XP +5 PE +3 💰 |
| Liberar / bolsa cheia | seq intacta (bolsa) | seq → 0 |
| Fuga involuntária | seq → 0 | seq preservada |

## 5. Simulação (10 000 encontros)

Política pura (sem mudar chance de captura). Ver `pokemon.tests.hunt-streak-sim-a6-test`.

- **Sempre libera:** só XP/PE de batalha; 0 moedas.
- **Sempre captura:** maior XP/PE; moedas médias &lt; 11 (teto +10).
- **Alterna:** quebra streak → bem menos moedas que always-capture.
- **p=0,25 / p=0,85:** interpolam entre liberar e capturar.

**GO recomendado** para consolidar a tabela candidata: captura longa é claramente mais vantajosa; treinar só batalhando permanece válido; teto de moedas linear e previsível.

## 6. Persistência e idempotência

- Datomic canônico: `:pokemon/hp-atual`, `:save/sequencia-capturas`, `:save/maior-sequencia-capturas`, `:save/vitorias-treinador`, ledger de moedas.
- Flags de batalha: `recompensas-batalha-concedidas?`, `capture-outcome`.
- Recibos: `battle-turn`, `encounter-capture`, `encounter-release`.
- Retry com mesmo `operation-id` não duplica.

## 7. Testes

- `explosion-autodesmaio-a6-test`, `explosion-win-persist-a6-test`
- `lote-b-mechanics` (persist HP 0)
- `hunt-streak-sim-a6-test` (≥10k)
- Ajustes: `capture_m33`, `encounter_m44`, `hunt_m45`
- Smoke: `clojure -M:test-a6-lote-b`

## 8. Riscos residuais

- PE de caça usa `vitorias-treinador` (sem atributo `pe-cacas` novo) — documentado; não muda schema.
- Mensagens de vitória anunciam XP/PE de batalha só após o turno commitado.
- CLJS legado fora do caminho JVM não foi alterado além do dispatch `liberar`.

## 9. Compatibilidade de saves

- Saves existentes: sequência e HP intactos; nova política só em operações futuras.
- Sem migration/backfill.
