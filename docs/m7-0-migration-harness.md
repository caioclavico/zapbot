# M7.0 — Harness Cassandra → Datomic (módulos não-Pokémon)

**Status:** harness sintético para **bloqueio** + **admins-conhecidos**.  
**Sem** cutover, dual-write, dados reais ou alteração de produção.  
**Checkpoint base:** `1ba503c`

## Executar

```bash
./scripts/test-m7-0-platform.sh
# ou: clojure -M:test-m7-0-platform
```

Storage descartável: `.datomic-local/m70-platform` (gitignored).  
Fixture: `fixtures/m7-0/cassandra-dump-synthetic.edn` (`:meta {:synthetic true}`).

## Arquitetura (modular)

```
modules/platform/
  domain/          — shapes bloqueio/admins (espelho CLJS)
  application/     — ports BlockStore / AdminStore (sem KV genérico)
  infrastructure/
    datomic/       — schema + store Datomic Local
    migration/     — export/import/compare sintético
  tests/           — contratos M7.0
migrations/platform/001-bloqueio-admins.edn
```

Runtime Node (`zapbot.armazenamento` + Cassandra) **não** é substituído nesta etapa.

## O que os testes cobrem

- Isolamento por chat  
- Idempotência `operation-id` (+ mismatch de payload)  
- Persistência após reopen da conexão  
- Promote/demote admins  
- Export → import → equivalência vs fixture  
- Reimport sem duplicar  
- Recusa storage M5/prod e dump não-sintético  

## Timers / TTL / efeitos externos

Documentados no inventário; validação de timer de lembretes fica para M7.x do domínio lembretes (não implementado aqui). Pokémon permanece isolado (`modules/pokemon` + storages M5/M6).

## Próximo (M7.1)

Ensaio de adaptador Node só em **staging** para bloqueio/admins, com feature flag e Datomic Local — ainda sem produção.
