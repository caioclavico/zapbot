# A6 — PvP até 3 Pokémon / substituição obrigatória

**Branch:** `feat/a6-three-pokemon-battle-continuation` (PR #94)  
**Base tip:** `d083414` (#92)  
**Status implementação:** código + testes locais; sem merge/deploy.

## Contrato entregue

- PvP: até 3 Pokémon distintos por treinador; após nocaute entra `aguardando-substituicao`.
- Troca obrigatória via `!pk trocar` / `escolher` — **não** consome cota voluntária nem cooldown de 60s.
- Sem mon elegível (HP/faixa/limite 3) → derrota imediata (comportamento 1×1 preservado).
- Caça `:wild`: desmaio continua encerrando a batalha; sem substituição.

## Testes

- `clojure -M:test-a3-pvp` — pass (incl. substituição obrigatória + KO sem reserva)
- `pokemon.tests.pvp-forced-switch-a6-test` — domínio + regressão wild
- `node --test test/pokemon-jvm-a3.test.cjs` — present/erros

## Fix colateral

- Bônus de vitória PvP passa a creditar moedas no **ledger operacional** (`economy-store`), alinhado às leituras de `:coins`.
