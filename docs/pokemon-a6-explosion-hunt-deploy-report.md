# A6 Explosion + Hunt + Raid — Relatório de Deploy (produção)

**Resultado:** **SUCCESS**  
**Stamp:** `20261108T222347Z`  
**Início:** `2026-11-08T22:24:24Z` · **Fim:** `2026-11-08T22:51:40Z`  
**Plano:** `docs/pokemon-a6-explosion-hunt-cutover-readiness.md`  
**Evidências host:** `/home/caiohclavico/odisseu-recovery/a6-explosion-hunt-raid-cutover-20261108T222347Z/`

---

## Identidade

| Campo | Valor |
|-------|-------|
| Commit executável | `2afd9975ef695163a4d8f98eed632c644431c72f` |
| Content-tree | `ad8a3cf41c4851237b535b15fad82e8e024b92f0` |
| Tip operacional | `948426ba2d79609936d2f71b63c248deb5c16029` (PR #61) |
| PRs | #59 · #60 · #61 |
| Fase | `a6-explosion-hunt-raid-integrated` |
| Schema | **035** (sem migration/backfill nesta janela) |
| Raridade | `:legado` (inalterada) |
| Rollback | **não executado** |

### Image IDs

| | Antes (Combat UX) | Depois (candidata) |
|--|-------------------|--------------------|
| Node | `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` | `sha256:77d0d9a255125c74d59168713dc025c42d9d189d9bd7278bf1813be981464d2b` |
| JVM | `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` | `sha256:9443b80134bf5f2bb47009354b705785f065ff438c31de10496621a35d2198d1` |

Tags finais:

- `odisseu-node:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64`
- `odisseu-jvm:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64`

---

## 1. Preflight

| Gate | Resultado |
|------|-----------|
| Imagens candidatas amd64 + labels `2afd997` / `ad8a3cf4` | PASS |
| Live Combat UX healthy · Restarts=0 | PASS |
| WhatsApp READY · chromium true | PASS |
| Bridge `up` · `maintenance.open` · `activeRequests=0` | PASS |
| Schema 035 / volumes Datomic+journal | PASS (pré-existente) |
| Disco 6.5G · inodes 5% | PASS |
| Bak overlay Combat UX + imagens rollback | PASS |
| Hold `true` · CI `disabled_manually` · runs in_progress=0 | PASS (não alterados) |
| Overlay efetivo já pinado candidata | PASS |

`PREFLIGHT_GO=PASS`

---

## 2. Maintenance / drain

| Etapa | Timestamp UTC | Evidência |
|-------|---------------|-----------|
| Node `enter` → quiescent | `22:24:42.920Z` | `logs/node-drain.json` · `activeOperations=0` |
| JVM `enter` → quiescent | `22:24:43.114Z` | `logs/jvm-drain.json` · `activeRequests=0` |

Freeze efetivo via endpoints existentes (`X-A6-Node-Maintenance-Token` / `X-A6-Maintenance-Token`). Sem encerramento abrupto. `DRAIN_STOP_ACL=PASS`.

---

## 3. JVM cutover

| Campo | Valor |
|-------|-------|
| Recreate start | `22:25:05Z` |
| StartedAt | `2026-11-08T22:25:07.661018733Z` |
| Image | `sha256:9443b801…2198d1` |
| Health | healthy · Restarts=0 |
| Schema 035 file | presente (`035-species-hp-formula.edn`) |
| Volumes | datomic / journal / maint preservados |
| Maint após boot | quiescent (marker durável) |
| Resume p/ boot Node | `22:25:54.293Z` → `phase=open` |

`JVM_GATE=PASS`  
Nota: a lista de capabilities do `/health` não anuncia `encounter-release`, mas a rota `/v1/encounter/release` está no bridge da imagem.

---

## 4. Node cutover

| Campo | Valor |
|-------|-------|
| Recreate start | `22:26:41Z` |
| StartedAt | `2026-11-08T22:26:45.028507841Z` |
| Image | `sha256:77d0d9a2…464d2b` |
| READY_AT | `2026-11-08T22:27:26Z` |
| READY persist | 3 amostras / ~90s = READY |
| Chromium | **156.0.8078.79** |
| UID | **1000:1000** |
| CapEff | `0000000000000000` |
| NoNewPrivs | 1 |
| Seccomp / cap_drop ALL / Privileged=false | OK |
| LocalAuth | volume data preservado (sem QR) |
| Maint até reopen | quiescent |

`NODE_GATE=PASS`

---

## 5. Reopen

| Etapa | Timestamp | Resultado |
|-------|-----------|-----------|
| Node `resume` | `2026-11-08T22:29:15.246Z` | `phase=open` |
| Health pós-resume | imediato | WA READY · JVM up/open |
| Flags permanentes / hold / CI | — | **inalterados** |

`RESUME=PASS` · `OBSERVE_START=2026-11-08T22:29:15Z`

---

## 6. Smoke (não destrutivo)

Sem mutação em saves de jogadores reais; sem segundo cliente Datomic no storage live.

| Verificação | Resultado |
|-------------|-----------|
| `politica-recompensa-captura-versao = streak-unit-v1` | PASS |
| `hp-por-jogador = 320` | PASS |
| Explosion `fainted-x` (both-down) | PASS (código live) |
| `/v1/encounter/release` no bridge | PASS |
| `chance-surto = 25` | PASS |
| Node `liberar` UX (`pokemon-jvm-present.cjs`) | PASS |
| `formatRaidStart` exportado | PASS |
| Labels git/tree/fase nas duas imagens | PASS |
| Health Node/JVM pós-cutover | PASS |

`VALIDATE=PASS`  
Smoke de mutação (Explosion in-game / caçada real / raid visual) permanece para observação orgânica — não executado em contas reais.

---

## 7. Monitoramento 20 minutos

| Métrica | Valor |
|---------|-------|
| Janela | `22:29:15Z` → `22:51:09Z` |
| Samples | **40** |
| READY samples | **40** |
| Fail samples | **0** |
| Node/JVM Restarts | **0** / **0** |
| Bridge | `up` · `open` · `activeRequests=0` (amostras) |
| Disco | ~6.5–7G livres · inodes OK |
| errors.log | vazio |
| Rollback | não necessário |

`STABILITY_20M_PASS`

---

## 8. Estado final do jogo

| Item | Estado |
|------|--------|
| Node/JVM | candidatas `2afd997` · healthy |
| WhatsApp | READY · chromium true |
| Bridge | up · open |
| Datomic | íntegro · schema 035 · sem migration nesta janela |
| Species-HP | preservado |
| One-Mon / HP chefe 320 / surto 25% / raridade `:legado` | preservados no código live |
| Explosion HP0 / liberar / streak-unit-v1 / raid 1º frame | implantados |
| Hold produção | ativo |
| CI/CD | `disabled_manually` |
| Imagens rollback Combat UX | preservadas no host |

---

## 9. Erros / incidentes

Nenhum erro crítico. Nenhum rollback.

Desvio não bloqueante: capability string `encounter-release` ausente no payload `/health` (rota presente).

---

## 10. GO/NO-GO final

| Critério de sucesso | Status |
|---------------------|--------|
| Imagens candidatas em execução | **GO** |
| Node + JVM healthy | **GO** |
| WhatsApp READY | **GO** |
| Datomic + schema 035 íntegros | **GO** |
| Maintenance encerrada com segurança | **GO** |
| Smoke (contratos) aprovado | **GO** |
| Sem duplicação/perda observada | **GO** |
| Monitoramento 20 min sem falha crítica | **GO** |

### Veredito

**CUTOVER_RESULT=SUCCESS** · **GO** produção A6 Explosion + Hunt + Raid (`2afd997`).
