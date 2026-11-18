# Ledger operacional de moedas (A6 / migration 028)

## Modelo

| Campo | Significado |
|---|---|
| `:save/economy-status` | `trusted` \| `unknown-pending` \| `reconciled` |
| `:save/coins-operational` | Saldo líquido A6 (créditos − débitos) — **gastável comprovado** se histórico unknown |
| `:save/coins-operational-earned` | Lifetime créditos A6 |
| `:save/coins-operational-spent` | Lifetime débitos A6 |
| `:save/coins-historical-status` | `unknown` \| `declared` \| `reconciled` \| `rejected` \| `none` |
| `:save/coins-historical-amount` | Só após declared/reconciled **manual** |
| `:save/coins` | Legado: espelho em trusted/reconciled; **placeholder opaco** em unknown-pending |
| `:coin-ledger/*` | Linha append-only por `{save}\|{operation-id}\|{role}` (idempotente) |

## Política de saldo disponível

1. **unknown-pending:** `spendable = coins-operational`. Nunca `:save/coins`.
2. **trusted:** `spendable = coins-operational` (bootstrap inicial copia `:coins` → operacional).
3. **reconciled:** `spendable = operacional + historical-amount` (só se historical-status=reconciled).
4. Crédito operacional **não** promove `economy-status` nem libera histórico.
5. **Nunca** auto-somar V1 + V2 + operacional + histórico unknown.
6. Débitos (loja, relearn, expansões) usam só `spendable`; kit/diário não debitam moedas.

## Fontes de crédito (valores master preservados)

| Fonte | Moedas | Source ledger |
|---|---|---|
| Captura OK | `2 + min(10, seq)` | `capture` |
| Vitória bolsa cheia | **0** (+ XP raridade+1) | — |
| PvP vitória | 10 | `pvp` |
| Ginásio 1ª / revanche | 100 / 25 | `gym` |
| Permanência >6h | 50 | `gym-permanence` |
| Raid | 50 | `raid` |
| Missões semanais | catálogo | `mission` |
| Admin seed | delta | `admin` |

Idempotência: `operation-receipt` + `:coin-ledger/key` único.

## Wiring

- Domínio: `pokemon.domain.economy`
- TX: `pokemon.infrastructure.datomic.economy-store/apply-delta-tx`
- Schema: `migrations/pokemon/028-economy-ledger.edn` (lista em `schema/migration-files`)

## Produção

**Não aplicar migrate/deploy/backfill sem GO explícito.**

Backfill sugerido (só com GO):

- Slots `a`/`b` trusted sem status → bootstrap trusted (operacional = `:save/coins` atual).
- `recovery-*` → `unknown-pending`, operacional 0; **não** copiar `:save/coins`.

## Rollback

1. Reverter imagem JVM/Node.
2. Attrs 028 são aditivos — não apagar.
3. Código antigo ignora attrs novos; dual-write trusted em `:save/coins` mantém compatibilidade de leitura legada se rollback parcial.

## Ensaio local (cópia descartável)

Sistema Datomic Local: `odisseu-economy-ledger` / `.datomic-local/economy-ledger-tests`  
(além de regressões shop/hunt/gym/capture/capacity em DBs de teste isolados).

| Cenário | Resultado |
|---|---|
| Treinador novo + crédito 100 + compra poção | OK (100→80 operacional; espelho `:save/coins`) |
| Recovery unknown + placeholder 999 + compra | `economy_unreliable` (spendable 0) |
| Recovery + crédito operacional 50 + compra | OK (50→30); placeholder 999 **intacto**; status permanece `unknown-pending` |
| Saldo insuficiente (15 vs expansão 200) | `insufficient_coins` |
| Reward duplicado (mesmo operation-id) | `:duplicate true`; saldo inalterado |
| Reinício JVM (reopen store) | operacional preservado |
| Vitória bolsa cheia | XP Pokémon sim; moedas 0 (hunt) |
| Ginásio / loja / captura regressão | OK |

**Schema 028 no Core:** aplicado 2026-10-31 (relatório `docs/pokemon-a6-4-028-schema-migrate-report.md`).  
**Ainda não:** deploy da app com wiring · backfill.

## Checklist Go/No-go produção

- [x] Ensaio em cópia descartável OK (novo + recovery + crédito/débito/idempotência/restart)
- [x] Integridade live pós-028: players/saves/pokemon/coins-sum inalterados; ledger entities = 0
- [x] Nenhum backfill automático de moedas V1/V2 neste código
- [x] GO explícito para migrate 028 no Core — **executado**
- [ ] GO explícito para deploy da imagem
- [x] Plano de rollback comunicado (cold `a64-028-cold-20261031T060914Z`)
- [x] Sem alterações Cassandra / aliases / grupos nesta entrega
