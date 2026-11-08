# Pokémon A6 — Homologação integrada A+B+C / Release readiness

**Atualizado:** 2026-11-08T03:22Z
**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Escopo:** fechar pré-requisitos operacionais da RC gameplay. **Sem deploy produção, migrations, backfill, alteração de saves, remoção de deploy hold ou reabilitação de CI/CD.**

---

## 0. Veredito

| Pergunta | Resposta |
|----------|----------|
| Overlay aponta para RC gameplay `e7488e4f`? | **SIM** |
| Sandbox Chromium comprovado em AMD64 **nativo** neste Image ID? | **SIM** (`odisseu-core` x86_64) |
| Ensaio descartável deploy/rollback (compose, ACL, JVM health, pin rollback)? | **PASS** |
| Sessão WhatsApp de teste independente? | **`WA_READY_TEST_NOT_AVAILABLE`** |
| Pedir autorização de janela de produção? | **GO CONDICIONAL** — único pendente operacional documentado é a ausência de READY em sessão WA de teste; não bloqueia indefinidamente a decisão administrativa |
| Executar deploy agora? | **NO-GO** (exige GO humano da janela + hold ainda ativo) |

Hold/CI: `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · workflow `CI/CD` = `disabled_manually` (**intocados**).
`POKEMON_THEMATIC_EVENTS_ENABLED=false`. Overlay **não** redefine ginásios/raids/eventos.

---

## 1. Artefatos aprovados (inalterados — sem rebuild)

| Artefato | Valor |
|----------|-------|
| Commit integrado | `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` |
| Content tree | `7645f59f7445f58d0f733aa578a4296bd342fa63` |
| Node tag | `odisseu-node:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Node Image ID | `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| Node tar SHA-256 | `878ba298cba5e4434225032e108fb95fc139926c7242ed383e08ae4998e706e7` |
| JVM tag | `odisseu-jvm:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| JVM Image ID | `sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| JVM tar SHA-256 | `c048e8e8c517aef87dbe4ddfe4d0616a5869e96ec138676e59331d6ec460787e` |

**Não** há digest de registry verificado. O overlay **não** usa `image: …@sha256:…`. A janela deve `docker load` o tar e exigir `docker image inspect <tag> --format '{{.Id}}'` == Image ID acima (`scripts/verify-a6-gameplay-rc-images.sh`).

Rollback Node breakglass preservado na documentação do overlay:

- Tag `odisseu-node:a6-drain-breakglass-20261106-1d00dd01`
- Image ID `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`

---

## 2. Overlay corrigido

Arquivo: `docker-compose.a6-ai-drain-sandbox.overlay.yml`

| Requisito | Status |
|-----------|--------|
| Tag RC gameplay | `…21771ec372eb-amd64` |
| Image ID documentado (local, não registry) | `e7488e4f…` |
| Sem pin `fefab4c` / `ce429153` | Removidos |
| Sem `image: odisseu-node@sha256:…` inventado | Garantido + check |
| UID 1000 + cap_drop ALL + NNP + seccomp + stop_grace 60s | Preservados |
| Volumes/sessão WhatsApp | Não redefinidos (herdam base) |
| Flags ginásio/raid/temático | Ausentes no overlay |
| Rollback breakglass `1d00dd01` | Mencionado no cabeçalho |

Verificador: `scripts/homolog-compose-overlay-check.sh` → **PASS** (inclui `thematic_stays_false`, `no_legacy_fefab4c_tag`, `no_fake_registry_digest_pin`, `breakglass_mentioned_for_rollback`).
`scripts/verify-a6-gameplay-rc-images.sh` → **PASS** (recusa IDs legados ce429153 / 2bbca089 / 1d00dd01 como candidata).

---

## 3. Sandbox AMD64 nativo (`e7488e4f`)

| Campo | Valor |
|-------|-------|
| Host | `odisseu-core` · `uname -m=x86_64` · Docker `amd64` 29.9.1 |
| Image ID carregado | `sha256:e7488e4f…` (**match** exato) |
| Harness | `scripts/test-chromium-sandbox.cjs` |
| Isolamento | `--network none`, volumes descartáveis, sem mount de sessão WA / Datomic |
| Seccomp file SHA-256 | `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10` |
| Chrome | `Chrome/157.0.8103.38` |
| `chrome://sandbox` | Layer1 Namespace · PID/Net **Yes** · Seccomp-BPF **Yes** · **“You are adequately sandboxed.”** |
| UID | 1000 |
| CapEff | `0` |
| NoNewPrivs | 1 |
| Seccomp_filters | browser `1` · renderer `2` |
| `--no-sandbox` | ausente (`noSandboxFlag: false`) |
| Persistência recreate | **PASS** |
| Produção | `odisseu-a6-node` / `odisseu-a6-jvm` **não** recriados; imagem candidata **removida** após o ensaio |

Evidências: `docs/evidence-a6-gameplay-rc-e7488e4f/`.

Nota: emulação macOS AMD64 permanece **inválida** como substituto (já documentado). A prova autoritativa é esta.

Correção mínima de *harness* no ensaio (não na imagem): tmpfs `/home/node` com `uid=1000,gid=1000` para crashpad/HOME — falha inicial foi só operacional do probe `--read-only`/HOME root-owned.

---

## 4. WhatsApp de teste

**`WA_READY_TEST_NOT_AVAILABLE`**

Paths verificados (local + `odisseu-core`): ausentes.
Nenhuma sessão de produção foi copiada, montada ou usada. Nenhum QR de produção solicitado.
READY / injeção whatsapp-web.js / restart com sessão de teste **não** executados.

Risco residual explícito para a janela: validar autenticação+READY com sessão de **teste** nova/autorizada **ou** aceitar formalmente o risco de primeiro READY só na cutover (com preflight SESSION_ACCESS_DENIED/ACL já ensaiados).

---

## 5. Ensaio descartável execução + rollback

`scripts/a6-gameplay-rc-disposable-rehearsal.sh` → **PASS**

| Etapa | Resultado |
|-------|-----------|
| Verify Image IDs | PASS |
| Compose overlay check | PASS |
| SESSION_ACCESS_DENIED pré-ACL | PASS |
| ACL inválida ainda nega | PASS |
| Máscara efetiva + pós-stop revert | PASS |
| Persistência de marker após revert ACL | PASS |
| JVM RC health/bridge (`:18093`, Datomic descartável) | PASS |
| Temática `false` no container JVM | PASS |
| Node probe UID 1000 sem sessão WA | PASS |
| Simulação pin rollback → breakglass `1d00dd01` | PASS |

Drain unitário (código): `a6-shutdown`/`a6-maintenance`/invariants **16/16** (suíte prévia mantida).

---

## 6. Observação RO em produção (não alterada)

No momento do ensaio AMD64, `odisseu-core` listava:

- `odisseu-a6-node` — **unhealthy**, imagem `…chromium155-getchatfix-…16b5987…` (≠ RC gameplay)
- `odisseu-a6-jvm` — healthy, breakglass `ad0b7c1b`

Nenhum recreate/restart/deploy foi feito por esta tarefa. A unhealthy do Node live é **contexto operacional** para a janela futura, não regressão desta RC.

---

## 7. Sequência de janela (preparada — não executada)

1. Confirmar hold + workflow disabled.
2. Snapshot ACL; backup volumes.
3. `docker load` tars · `verify-a6-gameplay-rc-images.sh`.
4. Pré-drain Node (token existente).
5. Overlay já pinado na tag gameplay; conferir Id.
6. Up Node → health/UID/READY (sessão autorizada).
7. JVM só se a janela incluir · Image `cf9a029c…` · sem migration/reset.
8. Rollback Node → breakglass `1d00dd01…` sem apagar sessão/data.

---

## 8. Pendências restantes

| # | Pendência | Bloqueia pedido de janela? |
|---|-----------|---------------------------|
| 1 | `WA_READY_TEST_NOT_AVAILABLE` | **Não** (risco explícito; decisão administrativa) |
| 2 | Node live atualmente unhealthy (imagem distinta) | Contexto da janela — investigar no preflight live |
| 3 | Disco `/` em `odisseu-core` (~85% após prune de **build cache** apenas) | Ops de capacidade antes do load dos tars |
| 4 | 3 fails legados `test:m4-jvm` (base `16b5987`) | Não |

---

## 9. GO / NO-GO

- **GO CONDICIONAL para solicitar autorização** de uma futura janela de implantação (pré-requisitos overlay + sandbox nativo + ensaio rollback fechados).
- **NO-GO para executar** a implantação até GO humano da janela, com hold ainda ativo e temática off.
- Risco WA de teste ausente deve constar no pedido de autorização.

---

## 10. Referências

- Evidência sandbox: `docs/evidence-a6-gameplay-rc-e7488e4f/`
- Overlay: `docker-compose.a6-ai-drain-sandbox.overlay.yml`
- Verify: `scripts/verify-a6-gameplay-rc-images.sh`
- Homolog overlay: `scripts/homolog-compose-overlay-check.sh`
- Rehearsal: `scripts/a6-gameplay-rc-disposable-rehearsal.sh`
- PR gameplay: https://github.com/caioclavico/zapbot/pull/36
