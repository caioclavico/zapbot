# M6.4 — Preparação para staging autenticado

**Status:** limpeza + simulação sem WhatsApp real. Chip novo aguardando operadora.  
**Base:** `82f0541` (+ hardenings desta etapa)

---

## Staging pronto?

| Item | Estado |
|---|---|
| Path sessão `.wwebjs_auth_staging` / `clientId=staging` | OK |
| Gates anti-produção / anti-QR automático | OK |
| `DEV_GROUP_ID` obrigatório no boot staging-like | OK (fail-fast) |
| Allowlist `STAGING_ALLOWED_USERS` | OK (opcional) |
| Lembretes/eventos/admins só no chat autorizado | OK |
| Bridge loopback + FileLock | OK |
| Simulação JVM (sem Chromium) | `./scripts/staging-sim.sh` |
| Chromium READY + comandos WhatsApp | **bloqueado** (chip) |

---

## Checklist — iniciar M6.3 autenticado (quando a linha funcionar)

### Antes do QR / sessão

- [ ] Chip/conta **somente de teste** (não produção)
- [ ] `DEV_GROUP_ID` = grupo de teste criado para staging
- [ ] (Opcional) `STAGING_ALLOWED_USERS` com JIDs dos testadores
- [ ] `.env.staging`: `POKEMON_ADAPTER=jvm`, `POKEMON_SERVICE_URL=` vazio
- [ ] Tokens bridge alinhados (`ODISSEU_BRIDGE_TOKEN` = `POKEMON_JVM_TOKEN`)
- [ ] Confirmar `WWEBJS_DATA_PATH=.wwebjs_auth_staging` (nunca `.wwebjs_auth`)

### Autenticação

- [ ] `export STAGING_WHATSAPP_CONFIRM=1`
- [ ] Se primeira vez: `export STAGING_ALLOW_QR=1` e escanear QR **da conta de teste**
- [ ] Terminal 1: `./scripts/staging-up.sh jvm`
- [ ] Terminal 2: `npm run build && ./scripts/staging-up.sh node`
- [ ] Health: `curl -s http://127.0.0.1:3001/health` → `whatsapp=READY`, `chromium=true`

### Comandos no DEV_GROUP (dados sintéticos Datomic)

- [ ] `!pk treinador` / `!pk save` / ativar A↔B
- [ ] `!pk cacar` → atacar → capturar
- [ ] `!pk joy` (enviar/coletar se houver ferido)
- [ ] `!loja` / `!mochila`
- [ ] `!pk ginasio` (overview/time conforme disponível)
- [ ] Reiniciar só Node; confirmar READY + estado
- [ ] Reiniciar só JVM; comando deve falhar fail-closed e voltar após up
- [ ] `./scripts/staging-measure.sh` — RSS Node, Chromium, JVM

### Não fazer

- Escanear QR com conta de produção
- Montar `.wwebjs_auth` de produção
- Apontar para Cassandra/Oracle/GCP de produção
- Cutover / dual-write

---

## Comandos úteis agora (sem chip)

```bash
./scripts/staging-sim.sh          # JVM + smoke + reinício + gates
./scripts/staging-audit.sh
./scripts/staging-resilience.sh   # M6.2
```
