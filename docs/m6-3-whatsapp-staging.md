# M6.3 — Staging integrado com WhatsApp de teste

**Status:** M6.3C em andamento — bootstrap de QR + descoberta de `DEV_GROUP_ID`.  
**Branch:** `refactor/modular-monolith-datomic`  
**Checkpoint base:** `d977789`  
**Ver também:** `docs/m6-3c-staging-auth.md`

---

## Isolamento de sessão

| Ambiente | `WWEBJS_DATA_PATH` | `WWEBJS_CLIENT_ID` | Perfil LocalAuth |
|---|---|---|---|
| Produção | `.wwebjs_auth` (default) | _(nil)_ | `session/` |
| Staging | `.wwebjs_auth_staging` | `staging` | `session-staging/` |

Compose monta **somente** `./.wwebjs_auth_staging` → `/app/.wwebjs_auth` (nunca o bind de produção).

Gates em `scripts/staging-up.sh node` / `staging-integrated.sh`:

1. Recusa `WWEBJS_DATA_PATH=.wwebjs_auth`
2. Exige `STAGING_WHATSAPP_CONFIRM=1`
3. Sem diretório `session-staging`, exige `STAGING_ALLOW_QR=1` (só conta de **teste**)
4. Exige `DEV_GROUP_ID` preenchido
5. **Não** inicia QR automaticamente sem esses flags

---

## Como fornecer sessão de teste

1. Conta WhatsApp **independente** da produção.
2. Opção A — copiar perfil já autenticado de teste para:
   `.wwebjs_auth_staging/session-staging/`
3. Opção B — QR controlado:
   ```bash
   # preencha DEV_GROUP_ID no .env.staging
   export STAGING_WHATSAPP_CONFIRM=1
   export STAGING_ALLOW_QR=1
   ./scripts/staging-up.sh jvm          # terminal 1
   npm run build && ./scripts/staging-up.sh node   # terminal 2 — escaneie QR da conta de TESTE
   ```
4. Depois, com sessão persistida:
   ```bash
   export STAGING_WHATSAPP_CONFIRM=1
   ./scripts/staging-integrated.sh
   ```

---

## Validação com Chromium READY (checklist)

Quando a sessão de teste existir:

- [ ] Health Node `whatsapp=READY` + `chromium=true` em `127.0.0.1:3001/health`
- [ ] Medir RSS Node, Chromium, JVM (`./scripts/staging-measure.sh`)
- [ ] `!pk treinador` / saves A/B no `DEV_GROUP_ID`
- [ ] Caça → batalha → captura (dados sintéticos no Datomic staging)
- [ ] Joy / loja / ginásio (comandos JVM disponíveis)
- [ ] Reinício só Node; reinício só JVM; persistência
- [ ] JVM down → erro fail-closed; `POKEMON_SERVICE_URL` vazio (sem writer Cassandra)
- [ ] Mídia/mensagens sem mutação duplicada (`operationId`)

---

## Sem sessão (caminho atual)

```bash
./scripts/staging-integrated.sh
# exit 10 + relatório em ~/odisseu-recovery/m6-3-private/
```

Executa: JVM health, smoke sintético M6.1, confirmação fail-closed ao derrubar JVM.  
**Não** declara prontidão de produção.

---

## Go / no-go

| Critério | Estado |
|---|---|
| Sessão teste isolada + READY medido | **NO-GO** (bloqueio aberto) |
| Fail-closed JVM sem Cassandra Pokémon | OK (código + sim) |
| Bridge loopback / um writer | OK |
| Cutover produção | **NO-GO** |
