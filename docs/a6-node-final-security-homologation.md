# A6 — Segurança, imagem Node final e homologação Node-only

Data: 2026-11-10  
Base integrada: `a6/live-baseline-8ef41c8` @ `9465477e49584db28bf81bb12412325445537a21` (PR #70)  
Branch de correção: `fix/a6-node-final-security-homolog`

**Deploy não executado.** Hold de produção preservado. JVM/schema 035/WhatsApp live não foram tocados.

## Veredito

| Eixo | Status |
|---|---|
| **A. Código e imagem** | **HOMOLOGADO** (gates locais + imagem AMD64 reconstruída) |
| **B. Solicitação de implantação Node-only** | **NO-GO** até decisão administrativa explícita sobre riscos residuais Critical/High (Chromium 156 + libxml2/glib/sqlite/zlib Debian) |

## Preflight

| Item | Evidência |
|---|---|
| PR #70 mergeado | `9465477…` em `a6/live-baseline-8ef41c8` |
| Tree da base | `36b5ed3e4b1d36df601b54071db6bb2c41d7b8e9` |
| Imagem candidata anterior | `sha256:b7237d056d1da0fdfdb621ead08116197d2753e794829b95dda9382ada6488aa` (pré-HEAD final; **não** reutilizada) |
| Live Node (pin local conhecido) | `odisseu-node:…8ef41c8…` Image ID `a87abb519681…` |
| Base usada | Somente `a6/live-baseline-8ef41c8` — sem RC announcer, sem `master`, sem merge do PR #69 |

## Análise de segurança

### libxml2 / Sharp / SVG

| Achado | Detalhe |
|---|---|
| Sistema `libxml2` | `2.9.14+dfsg-1.3~deb12u6` (Debian bookworm). CVE-2026-6653 **sem fix bookworm** (postponed). |
| Sharp SVG | Sharp **não** usa o libxml2 do sistema. Em `0.35.5` o bundle reporta `xml2: 2.15.4` e `rsvg: 2.63.2` (faixa corrigida upstream). |
| Caminho remoto | `baixarBuffer` baixa sprites de hosts allowlisted (`raw.githubusercontent.com`, `cdn.jsdelivr.net`); rejeita SVG/XML por content-type e magic; limite 2 MiB; `redirect: error`. |
| SVG interno | Continuam sendo gerados em memória (arena/Pokédex/placeholder) e rasterizados — necessário ao jogo. |
| HEIF | `sharp.block({ operation: ['VipsForeignLoadHeif'] })` no boot do módulo de mídia. |

### Chromium

| Item | Valor |
|---|---|
| Pacote | `chromium` / `chromium-common` / `chromium-sandbox` `156.0.8078.79-1~deb12u1` |
| Disponibilidade apt | **Candidato máximo** em bookworm-security (sem sucessor mais novo) |
| Runtime | UID `1000:1000`, sem `--no-sandbox` no bundle, perfil `docker/chromium-seccomp.json` |
| Probe AMD64 | headless `about:blank` exit 0 sob seccomp + cap-drop + read-only + tmpfs |
| Residual | Dezenas de advisories High sem `FixedVersion` na base Trivy; superfície remota WhatsApp Web permanece |

### Demais Critical (sistema Debian — inalterados)

| CVE | Pacote | Notas |
|---|---|---|
| CVE-2026-58016 | `libglib2.0-0` | Bookworm postponed; sem D-Bus no container de teste |
| CVE-2025-7458 | `libsqlite3-0` | Requer SQL arbitrário; app não expõe |
| CVE-2026-6653 | `libxml2` | Ver acima; Sharp não consome este .so |
| CVE-2023-45853 | `zlib1g` | MiniZip; Debian ignora para o pacote bookworm |

Nenhum desses riscos foi aceito automaticamente.

## Correções mínimas aplicadas

1. `sharp` **0.35.5** (lockfile) — remove GHSA librsvg/libheif High no node-pkg.
2. `overrides.adm-zip` **0.6.1** — remove advisories High do zip transitivo.
3. Harden de download de sprites em `scripts/lib/pokemon-jvm-media.cjs` (allowlist, rejeição SVG/XML, tamanho, bloqueio HEIF).
4. Testes novos: `test/pokemon-jvm-media-security.test.cjs`.

Sem alteração de gameplay, economia, raids, saves, contratos JVM ou schema 036.

## Imagem final

| Campo | Valor |
|---|---|
| Plataforma | `linux/amd64` |
| Image ID / manifest | `sha256:6bcb881e8c040ba79fb2ec70cacb35ce52f694810fc6418b2604421aa9d286d3` |
| Config digest | `sha256:35b82523f7f7cae691502c94887406acc1b715c4b3e08d86bc4a32b84d7b9def` |
| Tag local (mutável) | `zapbot:a6-node-final-security-9465477e4958-amd64` |
| Tar SHA-256 | `89ba1fe837230dbbf19150123a67c71c14a7f172434a0df8e43235b6eaa3bfce` |
| Base Node | `node:22.23.4-bookworm-slim@sha256:aaa4b5baf1ecff3b283486bb1f90405cdda225a3e6f79660d2c559fa510a7292` |
| Base JDK build | `eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69` |
| Chromium | `156.0.8078.79-1~deb12u1` |
| Sharp no runtime | `0.35.5` (`rsvg 2.63.2`, `heif 1.23.5`, `xml2 2.15.4`) |

Artefatos leves: `artifacts/a6-node-final-security-20261110T201146Z/` (tar **não** versionado).

## Trivy comparativo (CRITICAL+HIGH)

Scanner: Trivy `0.76.2`, DB v2 atualizada em 2026-11-10. Mesma base para live pin e imagem final.

| | Live pin `8ef41c8` | Final |
|---|---:|---:|
| Ocorrências C+H | 403 | 397 |
| Critical | 4 | 4 |
| High | 399 | 393 |
| Advisories High únicos | 154 | 148 |
| Delta | — | **0 added / 6 removed** |

Removidos: `GHSA-wq5f-xc86-pv6w` (sharp/librsvg), `GHSA-rgj7-g3m4-5g8c` (sharp/libheif), 4 advisories `adm-zip`.

Matriz: `artifacts/…/trivy/matrix-crit-high.csv`.

## Testes

| Suite | Resultado |
|---|---|
| Media security + media | 13/13 PASS |
| Announcer | 32/32 PASS |
| Recursos/manutenção | 87/87 PASS |
| HTTP/JVM contratos | 287/287 PASS |
| Chromium AMD64 + seccomp | PASS |
| Controle `suspended` + READY sim | `status=suspended`, transport null, zero envios |
| Persistência volume / orphan lock | PASS |
| SIGKILL + volumes | PASS |
| Rollback live + `RELEASE_ANNOUNCER_ENABLED=false` | PASS (flag verificada antes do start lógico) |
| Schema 036 no bundle Node | ausente (`has036: false`) |

## Compatibilidade JVM / schema 035

Imagem Node-only: sem migration, sem mudança de economia/raids/saves/recibos. Contratos HTTP existentes 287/287. Não houve acesso a Datomic real mutável.

## Rollback seguro

A imagem live antiga **só** pode iniciar com `RELEASE_ANNOUNCER_ENABLED=false` aplicado e verificado **antes** do start. Caso contrário: **manter Node parado** (NO-GO de rollback “cego”). Ver `docs/a6-node-only-rollback-runbook.md`.

## Próximo gate para READY de deploy

1. Revisão/homologação administrativa do PR desta branch.
2. Decisão explícita por escrito sobre riscos residuais Chromium + Critical Debian.
3. Preflight operacional do runbook Node-only (overlay, seccomp, UID 1000, volume controle).
4. Somente então promover o veredito B para READY — **fora deste trabalho**.
