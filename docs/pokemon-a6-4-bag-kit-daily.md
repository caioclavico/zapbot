# A6.4 — `!pk mochila kit` / `!pk mochila diario` (JVM)

## Regras (legado `loja.cljs`)

- **Kit:** 10× pokebola, 1× por treinador (`chat|player`), exige 10 vagas, sem pending.
- **Diário:** 3–7 bolas (seq), sorteio 60/30/10, fruta-dourada a cada 7 dias; overflow → `recompensas-pendentes`; calendário `MISSOES_TIMEZONE`.

## Gate de produção

`POKEMON_BAG_KIT_DAILY_ENABLED` — **habilitado em Core** após GO 2026-10-29 (ver `docs/pokemon-a6-4-bag-kit-daily-deploy-checkpoint.md`).
Default em exemplo de env continua `false` até backfill em outros ambientes.

### Backfill (sem conceder itens)

```bash
# 1) Extrair flags do Cassandra (RO)
python3 scripts/a64-bag-kit-daily-backfill.py \
  --roster ~/odisseu-recovery/private-roster-a64-prod-12.edn \
  --user-chat '…' --user-player '…' \
  --cqlsh-docker <cassandra-container> \
  --assume-kit-for-all \
  -o /tmp/bag-flags.json

# 2) Dry-run / apply no Datomic (cópias ou GO no Core)
clojure -M -m odisseu.smoke.bag-flags-apply \
  --storage /path/datomic-local --flags /tmp/bag-flags.json
# --apply
```

## APIs

- `POST /v1/bag/kit` (alias de onboarding kit com checagem de espaço)
- `POST /v1/bag/daily`
- Migration `027-bag-daily.edn`
