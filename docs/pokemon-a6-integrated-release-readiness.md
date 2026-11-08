# Pokémon A6 — Release readiness / RC A+B+C+P0

**Atualizado:** 2026-11-08T06:25Z  
**Host alvo de build/homolog:** `odisseu-core` (linux/amd64)  
**Branch:** `rc/a6-abc-p0-integrated` (base `rc/a6-ai-drain-sandbox` @ `89321bd` + P0 `9d2a345` + ops `c5ab41d`)

---

## 0. Veredito — preparação RC integrada

| Campo | Valor |
|-------|-------|
| Base RC | `rc/a6-ai-drain-sandbox` — A+B+C (`21771ec`/`ca06cff`), IA dinâmica, graceful drain, manutenção |
| P0 gameplay | Commit `9d2a345` — raridade/captura, ginásio NPC, UI raid |
| Ops integrado | Commit `c5ab41d` — Chromium 156, hasSynced listeners, overlay HEALTH_BIND |
| Pins imagens | Commit de pin (este tip) — Image IDs inéditos Node+JVM |
| Chromium | Debian **156** (não Chrome 157) |
| HEALTH_BIND | `HEALTH_BIND_HOST=0.0.0.0` no overlay |
| hasSynced listeners | `whatsapp-startup.cjs` catch-up + `attachEventListeners` |
| Produção live (rollback) | Node `05b36c5a…` · JVM `ad0b7c1b…` — **não** alterada nesta prep |
| Deploy / hold / CI / flags | **Intocados** |
| **GO solicitar janela Node+JVM** | **GO** (imagens + sandbox + JVM descartável PASS) |

---

## 1. Commits e dependências

| Commit | Papel |
|--------|-------|
| `89321bd` | Tip `rc/a6-ai-drain-sandbox` (docs incidente WA) |
| `21771ec` / `ca06cff` | Merge A+B+C (raid party, captura, present) |
| `16b5987`…`fefab4c` | Drain + IA dinâmica + sandbox Chromium |
| `9d2a345` | **P0** raridade/ginásio/raid UI |
| `c5ab41d` | Dockerfile Chromium 156, startup catch-up, overlay HEALTH_BIND, docs/verify base |
| *(pin tip)* | Overlay/verify/docs com Image IDs homologados |

**Não reaplicar:** `e7488e4f` (Chrome 157), Node sem listeners (`74118090`).

---

## 2. Produção live (somente leitura — rollback)

| Camada | Image ID | Nota |
|--------|----------|------|
| Node | `sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c` | Chromium 156 + listeners; **sem** P0 present/ginásio roll do tip |
| JVM | `sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c` | `source-base=03a3dc7e` — **sem** A+B+C/P0 |

---

## 3. Imagens candidatas RC (mesmo git-sha/tree)

| Camada | Tag | Image ID |
|--------|-----|----------|
| Node | `odisseu-node:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64` | `sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade` |
| JVM | `odisseu-jvm:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64` | `sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c` |

**Labels (ambas):** `io.zapbot.git-sha=c5ab41dfcbf2f12602b13a6718ecbc8bc79f68e7` · `io.zapbot.content-tree=8428d03b5c7ce7c7654a5416af7fd6cf3ae2c854` · USER `1000:1000` · Chromium Debian **156.0.8078.79**.

IDs **≠** live (`05b36c5a` / `ad0b7c1b`) e **≠** bases thin (`446dfc2b` / `cf9a029c`).

---

## 4. Conteúdo da candidata RC (código)

- Gameplay A+B+C + P0 (domínio JVM + present/HTTP Node)
- `scripts/lib/whatsapp-startup.cjs` hasSynced + listeners
- Dockerfile Node: Debian chromium 156 + sandbox
- Overlay: `HEALTH_BIND_HOST=0.0.0.0`, UID 1000, seccomp, **sem** override de flags Pokémon
- Drain/maint (`a6-maintenance` / `a6-shutdown`) + IA dinâmica

---

## 5. Schemas / migrations

| Item | Status |
|------|--------|
| Novas migrations Datomic | **Não** (P0 usa party v2 EDN + slim raridade) |
| Compatibilidade save legado | Raridade ausente → classificar ou chance mítica |
| Backfill | **Não** |
| Reinício JVM descartável | Caps idênticos (88); sem linhas de schema duplicado |

---

## 6. Checklist de homologação

| Check | Resultado |
|-------|-----------|
| `clojure -M:test-a6-lote-a` | PASS |
| `clojure -M:test-a6-lote-b` (P0 + flow Datomic) | PASS (incl. 11/64 P0 + 1/11 flow) |
| `clojure -M:test-a6-lote-c` | PASS |
| `clojure -M:test-a6-homolog-abc` | **32 / 213** PASS |
| `clojure -M:test-m4-raid` | PASS |
| Node P0/lote-c/startup/health-bind/drain | **60/60** PASS |
| `npm run test:http` | **287/287** PASS |
| Imagens Node+JVM mesmo git-sha/tree | PASS |
| Sandbox AMD64 UID 1000 / CapEff 0 / NNP / sem `--no-sandbox` | PASS |
| HEALTH_BIND host só com `0.0.0.0` | PASS |
| ACL grant + pós-stop revert | PASS |
| JVM Datomic descartável + temáticos `true` + reopen | PASS |
| Contrato raridade/captura/ginásio (`npc-roll` GET, present, símbolos) | PASS |
| Overlay + verify pins | PASS (pins no tip) |
| Produção live intocada durante prep | PASS (`READY` + Image IDs live) |

**Nota residual de suite:** `npm run test:m4-jvm` tem **3** falhas de formatação legado (`seu time` / gates de troca) **fora** do escopo P0; não bloqueiam a RC A+B+C+P0 (present P0 e contratos HTTP verdes).

---

## 7. Evidências staging (descartável)

| Item | Valor |
|------|-------|
| Host | `odisseu-core` |
| Evidência local VM | `/tmp/a6-abc-p0-rc-homolog/` |
| Chromium | `Chrome/156.0.8078.79` · CapEff `0` · NoNewPrivs `1` · uid `1000` |
| JVM caps | 88 · restart CAPS_EQUAL |
| `GET /v1/gyms/npc-roll` | rota autenticada (400 `unknown_gym` com id de fixture; unauth 401; POST 405) |

---

## 8. Riscos residuais

| Risco | Mitigação |
|-------|-----------|
| JVM live ainda pré-A+B+C | Cutover **coordenado** Node+JVM |
| Raids abertas party-1 | Expiram; joins novos selam ≤3 |
| READY WA / Chromium | Manter 156 + listeners; não regressar a 157 |
| Flags temáticas | Overlay não redefine; default/env live preservados |
| Drift m4-jvm present legado | Fora do P0; acompanhar em follow-up de UX |

---

## 9. GO / NO-GO solicitar janela

| Pedido | Veredito |
|--------|----------|
| Preparar RC A+B+C+P0 (código+imagens) | **GO** (pronto) |
| Deploy / cutover agora | **NO-GO** |
| Solicitar janela **Node+JVM juntos** | **GO** |
| Janela só Node ou só JVM | **NO-GO** (P0 depende dos dois) |

---

## 10. Referências

- P0 audit: `docs/pokemon-a6-raid-gym-capture-audit.md`
- Incidente WA: `docs/a6-whatsapp-recovering-incident.md`
- Verify: `scripts/verify-a6-gameplay-rc-images.sh`
- Overlay: `docker-compose.a6-ai-drain-sandbox.overlay.yml`
