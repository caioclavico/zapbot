# A6 — Fase 5.4: Homologação local da stack isolada

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-10-31 / 2026-11-01  
**Modo:** Mac local · Datomic sintético em volumes `odisseu-a6-staging-*` · HTTP real Node↔JVM  
**Sem:** produção, Core A5.6, SSH remoto, WhatsApp/QR, merge, migrate em banco existente  

---

## Decisão: **CONDITIONAL**

A stack A6 isolada homologou no Mac com fixtures sintéticas e transporte WhatsApp **desativado**. Critérios HTTP de raides/economia/slots/isolamento/restart passaram. Condicionantes: outbox sem eventos reais (tick global OFF por design) e raide pré-restart não reapareceu sob `agora=Date.now()` (expiração por relógio de parede).

| Critério | Status |
| --- | --- |
| Preflight Mac / portas / sem volume A5.6 | **PASS** |
| Compose + fail-closed | **PASS** |
| JVM health `127.0.0.1:18091` | **PASS** |
| Node HTTP-only (`WHATSAPP_TRANSPORT=disabled`) | **PASS** |
| Node → JVM `/jvm-health` | **PASS** |
| Criar/inscrever raides + isolamento grupos | **PASS** |
| Expiração lobby → combate | **PASS** |
| Kill switch tick OFF | **PASS** |
| Outbox pending/ack (API; fila vazia) | **CONDITIONAL** |
| Restart + trainer persistido | **PASS** |
| Economia idempotente | **PASS** |
| Save-slots com raide ativa | **PASS** |
| Sem WA/QR/sessão real | **PASS** |
| Containers A6 parados; volumes preservados | **PASS** |

---

## 1. Preflight

| Item | Resultado |
| --- | --- |
| RAM Mac | 16 GiB |
| Disco `/` | ~170 GiB livres |
| Portas `18091` / `3002` | livres antes do up |
| Volumes A5.6 | nenhum neste Mac |
| `docker compose … config` | OK |
| `scripts/a6-staging-static-audit.sh` | FAIL=0 |
| Fail-closed | `ALLOW_ALL_GROUPS=false`, `STAGING_GROUP_CONFIRMED=0`, `POKEMON_RAID_AUTO_TICK=false`, `STAGING_ALLOW_QR=0` |

---

## 2. Stack local

### Identidade

| | Valor |
| --- | --- |
| Project | `odisseu-a6-staging` |
| System / DB | `odisseu-a6-staging` / `core-a6-staging` |
| Volumes | `odisseu-a6-staging-{datomic,journal,maint,data}` |
| JVM | `odisseu-a6-staging-jvm` · `127.0.0.1:18091` |
| Node | `odisseu-a6-staging-node` · `127.0.0.1:3002` |
| Token | sintético em `.env.a6-staging` (gitignored) |

### WhatsApp desativado

- `WHATSAPP_TRANSPORT=disabled` → boot HTTP-only (sem Client/LocalAuth/QR/Chromium).
- `/health` → `{"status":"ok","whatsapp":"DISABLED","chromium":false,"transport":"disabled"}`.
- Logs: `[boot] WHATSAPP_TRANSPORT=disabled — sem QR, sem LocalAuth, sem Chromium.`
- Nenhuma sessão `.wwebjs_auth*` de Core/prod montada.

### JVM readiness (trecho)

```json
{"ok":true,"data":{"status":"up","raidAutoTickEnabled":false,"allowTestRng":true,"platformEnabled":true}}
```

Mounts JVM: apenas `odisseu-a6-staging-{datomic,journal,maint}` — **sem** a56.

---

## 3. Testes integrados

Script: `scripts/a6-local-homolog-fase5.4.cjs` (cliente `pokemon-jvm-client` → `18091` + probes Node `3002`).

### Run pós-fix (12/12) + pós-restart (13/13)

| Teste | Resultado |
| --- | --- |
| jvm-health | PASS `status=up` |
| node-health-http-only | PASS `DISABLED` |
| node-to-jvm-probe | PASS |
| raid-auto-tick-kill-switch-off | PASS `note=raid-auto-tick-disabled` |
| raid-open-chat-a | PASS (Onix / pedra) |
| raid-join-two-players | PASS `n=2` |
| group-isolation | PASS ids/ginásios distintos A≠B |
| lobby-expire-auto-start | PASS `fase=combate` |
| outbox-pending-readable | PASS `events=0` |
| outbox-ack | PASS (fila vazia com tick OFF) |
| economy-idempotent-coins | PASS `dup=true` coins estáveis |
| save-slots-with-raid | PASS slot B criado; raide permanece `combate` |
| state-after-restart | PASS trainer recuperado no chat anterior |

### Restart

```bash
docker restart odisseu-a6-staging-jvm
docker restart odisseu-a6-staging-node
# volumes NÃO removidos
```

Após restart: JVM/Node healthy de novo; `/jvm-health` OK; trainer do chat pré-restart recuperável. Raide ativa do chat anterior veio `fase=none` sob relógio real (lobby/combate com `agoraMs` sintético já distante do wall-clock) — estado de treinador/saves no volume sintético permanece.

---

## 4. Segurança observada

- Sem conexão WhatsApp / QR / autenticação real.
- Sem cópia de sessão, saves ou credenciais do Core.
- Datomic só no volume Docker local `odisseu-a6-staging-datomic` (system/db A6).
- Migrations aplicadas **somente** no DB novo `core-a6-staging` no first boot do volume vazio.
- Tick automático global OFF (`raidAutoTickEnabled=false`, `POKEMON_RAID_AUTO_TICK=false`).
- Hosts Docker allowlist: `odisseu-a6-jvm` além de `odisseu-jvm`.

---

## 5. Encerramento

```bash
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node stop
# NÃO usou: docker compose down -v
```

Containers `odisseu-a6-staging-{jvm,node}` → Exited. Volumes `odisseu-a6-staging-*` **preservados** para inspeção.

---

## 6. Artefatos / mudanças de suporte

| Arquivo | Papel |
| --- | --- |
| `WHATSAPP_TRANSPORT=disabled` + `HEALTH_BIND_HOST` | boot Node HTTP-only |
| `scripts/a6-local-homolog-fase5.4.cjs` | suíte HTTP integrada |
| `.env.a6-staging` | secrets sintéticos (gitignored) |
| clients JVM allowlist `odisseu-a6-jvm` | Node→JVM no Compose A6 |
| `scripts/healthcheck.js` | aceita `whatsapp=DISABLED` |

---

## 7. Riscos residuais

1. **Outbox sem eventos:** com kill switch OFF a fila fica vazia; ack foi exercitado só no caminho “fila vazia”. Entrega end-to-end de outbox exige tick ON **apenas** neste lab sintético numa fase seguinte.
2. **Raide × wall-clock:** fixtures usam `agoraMs` sintético; após restart, leitura com `Date.now()` não reconstrói a mesma fase de combate.
3. **Activate save durante combate** não bloqueou neste run (slot B ativou com raide ainda `combate`) — comportamento a documentar/revisar se a política de produto exigir fence mais rígida.
4. Imagem Node local inclui build reconciliado; **não** promover para Core sem checklist 5.x.

---

## 8. Como repetir (local)

```bash
# Preflight
bash scripts/a6-staging-static-audit.sh

# JVM → health
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging up -d --build odisseu-a6-jvm
curl -fsS http://127.0.0.1:18091/health

# Node HTTP-only
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node up -d --build odisseu-a6-node
curl -fsS http://127.0.0.1:3002/health
curl -fsS http://127.0.0.1:3002/jvm-health

# Homolog
ODISSEU_BRIDGE_TOKEN=… node scripts/a6-local-homolog-fase5.4.cjs

# Parar sem apagar volumes
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node stop
```
