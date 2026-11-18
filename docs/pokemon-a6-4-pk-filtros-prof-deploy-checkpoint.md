# Checkpoint — `!pk time planta|repetidos` + professor lote

**Quando:** 2026-10-31 ~14:02–14:06 UTC  
**Autorização:** GO deploy (chat)  
**Host:** odisseu-core · `35.192.113.146`

## Escopo

| Incluído | Excluído |
|----------|----------|
| Alias `planta`→grass + normalização de tipos | Economy ledger wiring |
| Filtro `repetidos` / `duplicados` / `dup` | Migrations / reimport |
| `!pk professor enviar 1,2,3` (até 50, 1 token) | Joy slim bed (ainda local) |
| JVM+Node rebuild | Rollback destrutivo |

## Imagens

| | Tag |
|--|-----|
| JVM / Node | `a64-pk-filtros-prof-20261031T140229Z-amd64` |
| Rollback | `a64-leave-fullbag-20261030T200619Z-amd64` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-a64-pk-filtros-prof-20261031T140229Z-amd64`  
Meta: `~/odisseu-recovery/a64-pk-filtros-a64-pk-filtros-prof-20261031T140229Z-amd64/`

## Pós-deploy

- JVM / Node **healthy**
- Smoke present no container: `planta` + `repetidos` OK; help com `enviar 1,2,3`
- Volumes a56 preservados
