# Checkpoint — Insígnias/conquistas da master no `!pk treinador`

**Quando:** 2026-10-31 ~15:25–15:30 UTC  
**Host:** odisseu-core · `35.192.113.146`

## Escopo

| Incluído | Excluído |
|----------|----------|
| Catálogo master (12 conquistas + PE) | Economy ledger / `economy_store` |
| Lista no `!pk treinador` + ginásios + título | Migrations |
| Títulos com nomes master (`!pk titulo`) | Cassandra |

## Imagens

| | Tag |
|--|-----|
| JVM / Node | `a64-insignias-master-20261031T152533Z-amd64` |
| Rollback | `a64-joy-slim-imgs-20261031T143209Z-amd64` |

**Nota:** `store.clj` no Core só ganhou `:title` (cirúrgico). Não subir o `store.clj` local completo (tem `economy_store` ainda não deployado).

## Pós-deploy

- JVM / Node **healthy**
- `!pk treinador` mostra 🏅 Conquistas N/12 e 🏛️ Ginásios
- `!pk titulo` lista nomes master
