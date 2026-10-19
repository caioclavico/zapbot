# M7.1 — Bloqueio e admins via Datomic (somente staging)

**Status:** integração funcional em staging. Produção permanece Cassandra.  
**Base:** `3a50896`  
**Sem dual-write. Sem cutover de produção.**

## Contratos públicos preservados (Node)

| API | Comportamento |
|---|---|
| `!bloquear` / `!desbloquear` | mesmos args (`tudo`, `jogos`, comando, `listar`) |
| `bloqueio/bot-bloqueado?` | sync no atom (cache) |
| `bloqueio/comando-bloqueado?` | sync no atom |
| `bloqueio/autorizado?` | admin config / grupo / fallback conhecidos |
| `admins/lembrar-admin!` | promote + persist |
| `admins/esquecer-admin!` | demote + persist |
| `admins/admin-conhecido?` | promise (Cassandra: atom; Datomic: bridge) |

## Seleção de backend

| Env | Valor | Efeito |
|---|---|---|
| `APP_ENV` | `staging` \| `development` | permite `PLATFORM_ADAPTER=datomic` |
| `APP_ENV` | `production` | **força** `cassandra` mesmo se datomic pedido |
| `PLATFORM_ADAPTER` | `datomic` | bridge JVM; fail-closed |
| `PLATFORM_ADAPTER` | `cassandra` (default) | legado |

## Endpoints JVM (`/v1/platform/*`)

Bearer obrigatório. Wire usa **listas** (chatId como valor — evita camelCase em JIDs).

- `GET /v1/platform/blocks`
- `POST /v1/platform/blocks/bot`
- `POST /v1/platform/blocks/commands` (`action=block|unblock`)
- `GET /v1/platform/admins`
- `POST /v1/platform/admins/remember|forget|known`

DB Datomic separado no mesmo storage: `ODISSEU_PLATFORM_DB=platform`.

## Staging local

```bash
# .env.staging
PLATFORM_ADAPTER=datomic
PLATFORM_JVM_URL=http://127.0.0.1:18090
PLATFORM_JVM_TOKEN=<mesmo ODISSEU_BRIDGE_TOKEN>

./scripts/staging-up.sh jvm
# Node: PLATFORM_ADAPTER=datomic npm start / staging-up node
./scripts/test-m7-1-platform.sh
```

## Testes

```bash
./scripts/test-m7-0-platform.sh
./scripts/test-m7-1-platform.sh
./scripts/staging-sim.sh
```

## Não feito

- Cutover produção  
- Dual-write  
- Migração de dados reais  
- Enquetes/rank/lembretes (próximo M7.2 sugerido: **enquetes**)
