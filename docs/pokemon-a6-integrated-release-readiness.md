# Pokémon A6 — Release readiness / RC A+B+C+P0

**Atualizado:** 2026-11-08T06:10Z  
**Host alvo de build/homolog:** `odisseu-core` (linux/amd64)  
**Branch:** `rc/a6-abc-p0-integrated` (base `rc/a6-ai-drain-sandbox` @ `89321bd` + P0 `9d2a345`)

---

## 0. Veredito — preparação RC integrada

| Campo | Valor |
|-------|-------|
| Base RC | `rc/a6-ai-drain-sandbox` — A+B+C (`21771ec`/`ca06cff`), IA dinâmica, graceful drain, manutenção |
| P0 gameplay | Commit `9d2a345` — raridade/captura, ginásio NPC, UI raid |
| Chromium | Debian **156** (não Chrome 157) |
| HEALTH_BIND | `HEALTH_BIND_HOST=0.0.0.0` no overlay |
| hasSynced listeners | `whatsapp-startup.cjs` catch-up + `attachEventListeners` |
| Produção live (rollback) | Node `05b36c5a…` · JVM `ad0b7c1b…` — **não** alterar nesta prep |
| Deploy / hold / CI / flags | **Intocados** |
| **GO solicitar janela Node+JVM** | Após Image IDs homologados juntos (ver §10) |

---

## 1. Commits e dependências

| Commit | Papel |
|--------|-------|
| `89321bd` | Tip `rc/a6-ai-drain-sandbox` (docs incidente WA) |
| `21771ec` / `ca06cff` | Merge A+B+C (raid party, captura, present) |
| `16b5987`…`fefab4c` | Drain + IA dinâmica + sandbox Chromium |
| `9d2a345` | **P0** raridade/ginásio/raid UI |
| *(integrado)* | Dockerfile Chromium 156, startup catch-up, overlay HEALTH_BIND, docs/verify |

**Não reaplicar:** `e7488e4f` (Chrome 157), Node sem listeners (`74118090`).

---

## 2. Produção live (somente leitura — rollback)

| Camada | Image ID | Nota |
|--------|----------|------|
| Node | `sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c` | Chromium 156 + listeners; **sem** P0 present/ginásio roll do tip |
| JVM | `sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c` | `source-base=03a3dc7e` — **sem** A+B+C/P0 |

---

## 3. Conteúdo da candidata RC (código)

- Gameplay A+B+C + P0 (domínio JVM + present/HTTP Node)
- `scripts/lib/whatsapp-startup.cjs` hasSynced + listeners
- Dockerfile Node: Debian chromium 156 + sandbox
- Overlay: `HEALTH_BIND_HOST=0.0.0.0`, UID 1000, seccomp, **sem** override de flags Pokémon
- Drain/maint (`a6-maintenance` / `a6-shutdown`) + IA dinâmica

---

## 4. Schemas / migrations

| Item | Status |
|------|--------|
| Novas migrations Datomic | **Não** (P0 usa party v2 EDN + slim raridade) |
| Compatibilidade save legado | Raridade ausente → classificar ou chance mítica |
| Backfill | **Não** |

---

## 5. Checklist de homologação (staging / descartável)

| Check | Critério |
|-------|----------|
| Testes P0 + flow Datomic | PASS local |
| Homolog A+B+C | PASS local |
| Node present/HTTP/startup | PASS local |
| Imagens Node+JVM mesmo git-sha/tree | Após build |
| Sandbox AMD64 UID 1000 / CapEff 0 / NNP / ACL pós-stop | Após ensaio |
| JVM Datomic descartável + temáticos `true` + reopen | Após ensaio |
| Contrato raridade/captura/ginásio/mensagens | Após ensaio Node↔JVM |
| Overlay + verify pins | Image IDs inéditos ≠ live |

---

## 6. Riscos residuais

| Risco | Mitigação |
|-------|-----------|
| JVM live ainda pré-A+B+C | Cutover **coordenado** Node+JVM |
| Raids abertas party-1 | Expiram; joins novos selam ≤3 |
| READY WA / Chromium | Manter 156 + listeners; não regressar a 157 |
| Flags temáticas | Overlay não redefine; default/env live preservados |

---

## 7. GO / NO-GO solicitar janela

| Pedido | Veredito |
|--------|----------|
| Preparar RC A+B+C+P0 (código+imagens) | **EM CURSO** |
| Deploy / cutover agora | **NO-GO** |
| Solicitar janela **Node+JVM juntos** | **GO só após** Image IDs + sandbox + JVM descartável PASS |
| Janela só Node ou só JVM | **NO-GO** (P0 depende dos dois) |

---

## 8. Referências

- P0 audit: `docs/pokemon-a6-raid-gym-capture-audit.md`
- Incidente WA: `docs/a6-whatsapp-recovering-incident.md`
- Verify: `scripts/verify-a6-gameplay-rc-images.sh`
- Overlay: `docker-compose.a6-ai-drain-sandbox.overlay.yml`
