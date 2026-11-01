# A6 — Fase 12.3.2: Deploy do hotfix de raides

**VM:** `odisseu-core` (`35.192.113.146`, hostname `odisseu-core`)  
**RC base:** Fase 12.3.1 (`docs/pokemon-a6-raid-hotfix-fase12.3.1.md`)  
**Código autorizado:** `e4dffcf257702b1355a0f9f05ed9b450d20eb300`  
**Artefatos:** `~/odisseu-recovery/a6-fase12.3.2-deploy-20261101T161618Z/`  
**Backup:** `~/odisseu-recovery/a6-periodic-backup-20261101T161618Z/` (alias `a6-fase12.3.2-backup-…`)  
**Snaps:** `a6-fase12.3.2-pre-snap-…` / `a6-fase12.3.2-post-snap-…`

---

## Status final: **SUCCESS**

Deploy coordenado JVM→Node do RC `a6-raid-hotfix1231-20261101T161034Z-1eede78e365c` concluído. Gates de preflight PASS, backup≡pre-snap, health/WA READY sem QR, Onix sem caixa preta, smoke controlado sem recompensas artificiais, ledger sem `WRITER_FENCE`/`Compare failed`. Raides automáticas OFF; grupo piloto exclusivo; sem migrate/backfill/auto-heal da raide histórica afetada.

---

## 1. Horários UTC

| Evento | UTC |
| --- | --- |
| Preflight RO | `2026-11-01T16:16:21Z` |
| Transferência tars + SHA gate | ~`16:17`–`16:18Z` |
| Backup warm + pre-snap + restore proof + `docker load` | `16:19:00Z`–`16:19:31Z` |
| Freeze Node | `2026-11-01T16:19:58Z` (`node_status=exited`) |
| Recreate JVM | Started `2026-11-01T16:20:01Z` → healthy |
| Recreate Node | Started `2026-11-01T16:21:17Z` → healthy |
| WhatsApp Autenticado | `2026-11-01T16:21:41Z` |
| WhatsApp READY (sem QR) | `2026-11-01T16:21:44Z` |
| Post-snap (pré-smoke) | ~`16:22Z` |
| Smoke open→join→start→atk→expire-idle | `16:23:58Z` … `16:26:21Z` |
| Validação final | `2026-11-01T16:26:21Z` |

---

## 2. Imagens

### Implantadas

| Container | Tag | Image ID |
| --- | --- | --- |
| `odisseu-a6-jvm` | `odisseu-jvm:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` | `sha256:04f22e2bb33f13216f9913f932fc7b42ad4c70a174983a1c795176e0322300b2` |
| `odisseu-a6-node` | `odisseu-node:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` | `sha256:f1833b2772459a7f7a79c55d15492eb4ab9053f539fcef8470467e28feb6ffbd` |

SHA gate (`docker load` ≡ RC autorizado): **PASS**.

### Anteriores (rollback preparado)

| | Imagem |
| --- | --- |
| JVM | `odisseu-jvm:a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` · `sha256:82e1a06cb00dc85d89eeaa885c75d7fec3e5e6f8dd9e040f5bebba00398394c3` |
| Node | `odisseu-node:a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` · `sha256:dbcb6c0213c266a5d8ab7923a8d4e5113da25fb8300fb415cb93822ed908a695` |

Path: `…/a6-fase12.3.2-deploy-…/rollback/` (compose + `.env` + `previous-versions.txt`).

---

## 3. Gates (preflight)

| Gate | Resultado |
| --- | --- |
| Hostname `odisseu-core` + stack `odisseu-a6-*` | **PASS** |
| Live pré-deploy = RC 12.2 (`82e1a06c…` / `dbcb6c02…`) | **PASS** |
| Tar SHA-256 ≡ RC 12.3.1 | **PASS** |
| Image IDs carregados ≡ autorizados | **PASS** |
| Health JVM `up`, `raidAutoTickEnabled=false` | **PASS** |
| Datomic A6 `odisseu-m90-core` / `core` em `odisseu-a6-cutover-datomic` | **PASS** |
| Raides ativas piloto (API RO) | **PASS** (`ACTIVE=0`) |
| Outbox pendente | **PASS** (vazia) |
| Env piloto / auto OFF / `POKEMON_JVM_ALLOW_DOCKER_HOST=true` | **PASS** |
| Node RC contém `group-allowlist.cjs` (Fase 11.4) | **PASS** |
| Disco (~15 GiB livre) / memória | **PASS** |
| Volume A5.6 presente (não tocado) | **PASS** |
| Backup warm + restore ≡ pre-snap | **PASS** (`RESTORE_EQUIV_PRE=true`) |

Nenhum gate falhou → deploy **não** BLOCKED.

---

## 4. Congelamento e deploy

1. Outbox revalidada vazia → Node parado (`freeze-node.txt`).  
2. Rollback registrado (imagens 12.2 + compose/env).  
3. Compose patch: tags `a6-raidfix-…5b9a97e60228` → `a6-raid-hotfix1231-…1eede78e365c`.  
4. **Sem** `down -v`, **sem** migrate, **sem** alteração de volumes/saves manuais.  
5. Recreate **somente** `odisseu-a6-jvm` → healthy, sistema `odisseu-m90-core`, mounts cutover intactos.  
6. Recreate **somente** `odisseu-a6-node` → WA Autenticado + READY sem QR; sessão bind `a6-cutover-session-from-core`.  
7. Comandos liberados após health Node↔JVM.

### Env mantido (obrigatório)

```env
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
STRICT_USER_ALLOWLIST=0
POKEMON_RAID_AUTO_TICK=false
ODISSEU_BRIDGE_RAID_AUTO_TICK=false
POKEMON_JVM_ALLOW_DOCKER_HOST=true
```

Confirmado no container Node pós-deploy (`POKEMON_JVM_URL=http://odisseu-a6-jvm:18090`).

---

## 5. Backup e integridade canônica

| Item | Valor |
| --- | --- |
| Backup warm stamp | `20261101T161618Z` |
| Manifest-of-manifest SHA-256 | `bb645013d746b01c7b6839e6ec6870018e0931759b5d0eb281042f506a973bc5` |
| Restore proof (backup file SHA ≡ pre-snap Datomic) | **PASS** (`RESTORE_EQUIV_PRE`, diff=0) |
| Pre↔post snap (pós recreate, pré-smoke) | core idêntico; divergência só `platform/db.log` + `platform/log.idx` (boot JVM) |
| A5.6 / T0 | **não tocados** |

Smoke posterior gerou escritas legítimas de raide (open/join/start/attack/idle-expire). Moedas: **0→0**; `coinsOperational` permaneceu ausente (`None→None`) — sem crédito artificial. Revisões avançaram só pelo fluxo operacional (`p1` 182→185, `p2` 1→2).

---

## 6. Health e WhatsApp

| Check | Resultado |
| --- | --- |
| `odisseu-a6-jvm` Docker health | `healthy` (desde `16:20:01Z`) |
| `odisseu-a6-node` Docker health | `healthy` (desde `16:21:17Z`) |
| Bridge `GET /health` | `ok=true`, `status=up`, `raidAutoTickEnabled=false` |
| Node ↔ JVM | **PASS** |
| WhatsApp | Autenticado `16:21:41Z` · READY `16:21:44Z` · **sem novo QR** |
| Outbox final | `events: []` |

---

## 7. Autorização (grupo piloto)

| Caso | Resultado |
| --- | --- |
| `buildAuthorizedGroups` | `["120363427684548487@g.us"]` |
| chat piloto | `true` |
| chat desconhecido | `false` |
| `STRICT_USER_ALLOWLIST` | `0` / `strict=false` |
| Raides automáticas | **OFF** |

---

## 8. Onix (renderização)

Cartão live via `renderRaidBattleCard` no Node implantado:

- PNG `image/png` (~70 KiB)  
- Amostra padding `(470,70)` ≈ `[43,42,114,255]` (fundo ginásio, **não** preto)  
- Evidência: `…/meta/onix-card-live.png`, `onix-render.txt` → `ONIX_ALPHA_OK`

DTO de abertura (`ginasio=pedra`): chefe Onix com  
`imagem=…/official-artwork/95.png` → `BOSS_IMAGEM_OK`.

---

## 9. Smoke de raide (controlado, sem vitória)

Jogadores piloto: `207700373848240@lid` (CaioSmoke), `5511946342101@c.us` (PeerSmoke).

| Critério | Resultado |
| --- | --- |
| Open ginásio `pedra` + artwork Onix | **PASS** |
| Join (auto para P1 — idx 80 `raid_liga_mismatch`; P2 idx 0) | **PASS** |
| Start → combate · `suaVez` XOR · sem `suaVez?` | **PASS** |
| Um ataque (`moveIndex=1`) · turno avança · HP 440→305 · fase permanece `combate` | **PASS** |
| Vitória / captura / créditos | **não executados** |
| Cleanup `POST /v1/battles/expire-idle` (`force`, agora futuro) | **PASS** (`expired` raid smoke; `final_raid=None`) |
| Moedas / operacional inalterados | **PASS** |
| Logs `writer_fence` / `Compare failed` / “Conflito ao gravar” | **ausentes** na janela |
| Contrato golpe fatal | validado no RC sintético 12.3.1 (`nil` operacional → CAS `nil→after`); **não** forçado até 0 HP em produção |

Raide histórica afetada pré-hotfix: **não** cancelada nem premiada.

---

## 10. Ledger / economia

- Sem conflitos de fence/CAS no smoke.  
- Saves smoke mantêm `coinsOperational=None` (estado cutover `unknown-pending` sem attr) — o hotfix cobre o primeiro crédito no golpe fatal; não houve liquidação neste smoke.  
- Outbox vazia antes e depois.

---

## 11. Rollback

Não acionado. Procedimento: reverter **somente** imagens Node/JVM para tags 12.2 acima; **nunca** restaurar backup antigo sobre progresso novo (smoke já escreveu ops).

---

## 12. Restrições mantidas

- Sem migrations / backfill  
- Sem habilitar raides automáticas  
- Sem ampliar `ALLOWED_GROUP_IDS`  
- Sem `down -v` / exclusão de volumes  
- Sem auto-heal da raide histórica  
- Sem tocar A5.6 / T0 além do recreate Node com sessão bind existente  

---

## 13. Erros e riscos residuais

| Item | Notas |
| --- | --- |
| Join por `collectionIndex=80` | `raid_liga_mismatch` (nível 13 vs liga iniciante) — esperado; fallback `auto` OK |
| Cancel em combate | `409 raid_cannot_cancel` — esperado; cleanup via expire-idle |
| Saves com operacional ausente | Continuam válidos; primeiro crédito de raide fatal usará CAS `nil→after` |
| Notificações idle-timeout do smoke | Podem constar no outbox/journal de notificação; sem crédito de moedas |

---

## Evidências

`~/odisseu-recovery/a6-fase12.3.2-deploy-20261101T161618Z/meta/`:

- `preflight-live.txt`, `loaded-images.txt`, `tar-sha-gate.txt`, `freeze-node.txt`  
- `backup-mom.txt`, `../backup-proof/RESTORE_PROOF.txt`  
- `jvm-after.txt`, `node-after.txt`, `node-env.txt`, `wa-logs.txt`, `allowlist-after.txt`  
- `onix-render.txt`, `onix-card-live.png`  
- `smoke-*.json`, `save-p{1,2}*.json`, `outbox-*.json`, `final-status.txt`
