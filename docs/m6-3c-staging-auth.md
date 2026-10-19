# M6.3C — Autenticação WhatsApp Business de staging

**Branch:** `refactor/modular-monolith-datomic`  
**Checkpoint base:** `d977789`  
**Gates globais:** `cutover-ready? = false` · `rollback-recon-available? = false`

---

## Objetivo

Autenticar a sessão de **teste** (segundo número / WhatsApp Business) e
descobrir o `DEV_GROUP_ID` do grupo exclusivo, **sem** processar mensagens e
**sem** tocar produção.

---

## Isolamento confirmado

| Item | Valor |
|---|---|
| `APP_ENV` | `staging` |
| `POKEMON_ADAPTER` | `jvm` |
| `PLATFORM_ADAPTER` | `datomic` |
| Sessão | `.wwebjs_auth_staging` + `clientId=staging` |
| Produção | `.wwebjs_auth` **nunca** montado |
| Cassandra prod | não usado (`cassandra-boot-enabled?` false com datomic+jvm) |
| Bridge | `127.0.0.1:18090` · storage `.datomic-local/m61-staging` |

---

## Fluxo seguro

### 1) Bootstrap (QR + descoberta de grupo)

```bash
./scripts/staging-bootstrap-group.sh
```

- Sobe JVM (Datomic Local) + Node em `STAGING_BOOTSTRAP_GROUP=1`
- Exibe QR — escanear **somente** com a conta de teste
- Em READY: lista grupos (id + nome) em
  `~/odisseu-recovery/m6-3c-private/groups.json`
- **Não** registra histórico, **não** responde, **não** inicia Pokémon/lembretes

### 2) Confirmar grupo

1. Escolha o grupo de testes no JSON.
2. Em `.env.staging`:
   ```
   DEV_GROUP_ID=<id@g.us>
   STAGING_GROUP_CONFIRMED=1
   ```
3. Remova / não exporte `STAGING_BOOTSTRAP_GROUP`.
4. Opcional: `STAGING_ALLOWED_USERS=...`

### 3) Staging completo

```bash
export STAGING_WHATSAPP_CONFIRM=1
./scripts/staging-up.sh jvm          # terminal 1
./scripts/staging-up.sh node         # terminal 2 (sessão já persistida)
curl -s http://127.0.0.1:3001/health
./scripts/staging-measure.sh
# Comandos só no DEV_GROUP_ID confirmado
```

Sem `STAGING_GROUP_CONFIRMED=1` o Node **aborta** (exit 7), mesmo com
`DEV_GROUP_ID` preenchido.

---

## Checklist de testes (após confirmação)

- [ ] Health `whatsapp=READY`, `chromium=true`
- [ ] `!pk treinador` / saves A/B
- [ ] Caça → captura; joy/loja/ginásio conforme disponível
- [ ] Reinício Node; reinício JVM (fail-closed)
- [ ] Medição RSS Node / Chromium / JVM
- [ ] Allowlist (se definida) rejeita remetentes fora dela

---

## Não fazer

- Escanear QR com o número principal
- Montar `.wwebjs_auth` de produção
- Subir bot “aberto” com `DEV_GROUP_ID` vazio
- Cutover / dual-write / Cassandra remoto / Oracle / GCP
- Commit/push automático
