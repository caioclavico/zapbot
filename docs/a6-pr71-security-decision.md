# Decisão de segurança — PR #71 (Node final)

Data: 2026-11-10  
PR: https://github.com/caioclavico/zapbot/pull/71  
Base: `a6/live-baseline-8ef41c8` @ `9465477…` (inclui PR #70)

## Resumo executivo

O PR #71 reduz superfície npm (sharp/adm-zip) e endurece o download de sprites. **Não elimina** os 4 Critical Debian nem o Chromium 156 High herdados.  
**Recomendação:** integrar o código à live-baseline (**GO para merge de código**, se CI verde e homologação administrativa).  
**Implantação Node-only permanece NO-GO** até o responsável aceitar formalmente os riscos residuais do digest final — esta decisão **não** registra essa aceitação.

## Inventário Critical (individual)

| CVE | Pacote | Debian | Aplicabilidade na imagem | Caminho plausível | Mitigações | Fix bookworm | Residual | Recomendação |
|---|---|---|---|---|---|---|---|---|
| CVE-2026-6653 | `libxml2 2.9.14+dfsg-1.3~deb12u6` | postponed / minor | **Sistema** presente; Sharp 0.35.5 usa **xml2 2.15.4 embutido** para SVG interno | XML/SVG malicioso no decoder **do sistema** (não no path Sharp). Remoto de sprite rejeita SVG/XML + magic raster | Allowlist HTTPS+path PokeAPI; reject SVG/XML/HEIF; streaming ≤2MiB; HEIF blocked | Sem fix bookworm | **Mitigado** no path Sharp/sprites; **residual** no .so Debian (Chromium/outros) | Aceitar só com decisão explícita; não auto-aceitar |
| CVE-2026-58016 | `libglib2.0-0 2.74.6-2+deb12u9` | postponed | Presente (Chromium deps) | Parser XML de introspecção D-Bus; container sem socket D-Bus montado nos ensaios | Cap-drop, no D-Bus, non-root | Sem fix bookworm | **Residual / baixa exposição** evidenciada | Pendente decisão admin |
| CVE-2025-7458 | `libsqlite3-0 3.40.1-2+deb12u2` | no-dsa/minor | Presente | SQL arbitrário — app não expõe SQL | Sem interface SQL | Sem fix bookworm | **Não aplicável com evidência de ausência de SQL arbitrário** (finding permanece no scanner) | Não filtrar do Trivy; pendente admin |
| CVE-2023-45853 | `zlib1g 1:1.2.13.dfsg-1` | ignored (MiniZip) | Pacote fonte associado | MiniZip não construído no bookworm segundo Debian | N/A | N/A Debian | **Falso positivo comprovado** pelo tracker Debian; scanner ainda reporta | Manter visível; não “aceitar” silenciosamente |

## High prioritários

| Grupo | Status | Notas |
|---|---|---|
| sharp librsvg/libheif (GHSA-wq5f / GHSA-rgj7) | **Corrigido** | sharp 0.35.5 + block HEIF |
| adm-zip Highs | **Corrigido** | override 0.6.1 |
| Chromium 156 (~84 advisories × pacotes) | **Residual** | Máximo apt bookworm-security; sandbox/seccomp/UID1000 mitigam impacto, **não** exploração de renderer WhatsApp |
| Outros Debian / puppeteer transitivos | **Residual / mitigado por não-exposição** | Sem entrada remota A6 identificada além do browser |

Classificação rigorosa: delta Trivy **não** é aceitação.

## Correções adicionais nesta homologação

- Streaming com teto de bytes (`lerCorpoLimitado`).
- Magic bytes PNG/JPEG/WEBP/GIF; rejeição HEIF.
- Allowlist de **path** PokeAPI (não só hostname).
- `limitInputPixels` no decode Sharp de buffers remotos.
- Workflow CI atualizado à tip `9465477` e permitindo lockfile de segurança; testes de mídia no CI.

## Proposta de aceitação residual (rascunho — NÃO aprovada)

Se o responsável decidir promover Node-only no futuro, a aceitação deve citar:

1. Digest/Image ID exatos da imagem rebuildada pós-HEAD final.
2. Lista Critical/High residuais (Chromium 156 + Critical Debian).
3. Mitigações operacionais (seccomp, non-root, hold, announcer suspended/disabled).
4. Data/assinatura administrativa.

**Este documento não assina essa aceitação.**

## Vereditos recomendados

- **A. Integração PR #71:** GO (código), condicionado a CI verde no HEAD.
- **B. Implantação Node-only:** NO-GO.
