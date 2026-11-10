# A6 PR #68 — homologação Node-only

**Data:** 2026-11-10

**Veredito:** **NO-GO para implantação da imagem atual**. A candidata passa nos ensaios isolados, mas sua base de código diverge da Node em produção e a imagem ainda carrega riscos Critical herdados sem aceitação específica para este digest.

## Artefato candidato

- RC base: `a53287f81eb9f97864947a4052f8ee8ba315e3ff` (merge do PR #68).
- Commit de endurecimento: `b84d502cac9c2fe59aff667b60e550303311a4e3`.
- Content-tree: `1e11802f826ee730dc1edda2ae246bc69e30b882`.
- Imagem: `zapbot@sha256:832888201b7ceefa9f2d3d2bcd4409057c370d74b8c432516cf8e8730326b12a`.
- ID de imagem/config: `sha256:832888201b7ceefa9f2d3d2bcd4409057c370d74b8c432516cf8e8730326b12a`.
- Tar local: `/private/tmp/a6-pr68-final-b84d502.tar`.
- SHA-256 do tar: `0b8eaf49cb57990996e2f251c9be59312713c4e81e81a8b9f4e4783a16afeb84`.
- Plataforma e identidade: `linux/amd64`, UID:GID `1000:1000`.
- Node `v22.23.4`; Chromium `156.0.8078.79-1~deb12u1`; `libbrotli1 1.0.9-2+deb12u1`.
- Bases fixadas por digest no Dockerfile: Temurin JDK 21 e Node 22 Bookworm slim. O pacote Chromium é resolvido pelo repositório Debian no momento do build; a imagem final é imutável, mas uma reconstrução posterior não é bit a bit determinística sem pin/snapshot do repositório APT.
- Removidos npm/npx do estágio final; mantidos Node, Chromium e dependências de runtime.

## Preflight live read-only

VM `odisseu-core`, AMD64. No fim dos ensaios: Node e JVM `healthy`; healthcheck passivo do Node retornou `status=ok`, `whatsapp=READY`, `chromium=true`. Nenhum container ou volume de produção foi recriado ou alterado.

- Node live: `sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7`, revisão `8ef41c8da1c28bfb69d57bade81a5a41e9225d44`, árvore `849129daf4cf12d14de69097c23dfebfdb20bebc`.
- JVM live: `sha256:5cfca31b18c4cbf652280a01df39deaa2744cf0a6c93cff2cdb292d24aaa94f2`.
- A imagem live usa restart policy `no`. Inspeção não encontrou units/timers systemd ou entradas cron nomeadas para Odisseu/Compose. Isso não prova ausência de toda automação externa; exclusividade e hold devem ser confirmados de novo antes de qualquer janela.
- CI/CD `disabled_manually` e deploy hold `true` constam de atestação administrativa anterior, mas não foram verificados de forma independente nesta rodada. GitHub CLI local não conseguiu consultar a API; integração não expõe as variáveis do repositório.
- Schema 035 é o estado operacional informado; não foi feita consulta Datomic nesta rodada. Esta alteração Node não adiciona chamadas JVM, endpoints, atributos ou migrations. A suspensão local consulta apenas o arquivo persistente em `/app/data` antes do bootstrap/envio.

## Bloqueio de proveniência/base

O código live tem revisão `8ef41c8da1c28bfb69d57bade81a5a41e9225d44`; a candidata foi construída do RC `a53287f...`. A comparação GitHub entre as revisões está **divergida** (base comum `fdf6c593...`; live 139 commits à frente e RC 7 commits à frente). O live inclui o hotfix #65 `tx_datoms_conflict` e outras alterações posteriores. Portanto, não se pode promover esta imagem sem primeiro integrar a suspensão #68 e o endurecimento sobre uma base que preserve o código live e repetir o build/regressões correspondentes. Não foi feita essa integração ampla nesta homologação.

## Testes

- `npm run test:release-announcer`: **34/34 PASS**.
- Build AMD64 via buildx concluído; ClojureScript compilou; bundle `target/main.js` presente.
- Smoke no host AMD64: runtime Node executou e healthcheck passivo retornou `ok` com `WHATSAPP_TRANSPORT=disabled`, rede `none`, sem portas e volume descartável.
- Chromium/Puppeteer/LocalAuth em container descartável no host AMD64: **PASS** com UID 1000, `cap-drop=ALL`, `no-new-privileges`, perfil seccomp live, `network=none`, rootfs read-only e volumes descartáveis. Foi necessário montar `HOME`/XDG e `/tmp` graváveis para Crashpad; não foi preciso enfraquecer seccomp/capabilities.
- `chrome://sandbox`: namespaces PID e network ativos; Seccomp-BPF ativo; “You are adequately sandboxed”. Processo Node: `NoNewPrivs=1`, `Seccomp=2`, um filtro. Renderers: UID 1000, `NoNewPrivs=1`, `Seccomp=2`, **dois filtros**, evidenciando filtro Chromium além do filtro do runtime.
- ACL descartável equivalente aos mounts atuais permitiu criação de perfil LocalAuth por UID 1000. Nenhuma sessão WhatsApp real foi montada ou iniciada.
- Controle Announcer em volume descartável: inicializado como `suspended`, lido em processo/container posterior e mantido após recriação. Simulação de `onWhatsAppReady` com `RELEASE_ANNOUNCER_ENABLED=true` retornou `suspended`, com zero chamadas de transporte.
- Runtime rollback live testado em container descartável com `RELEASE_ANNOUNCER_ENABLED=false`: READY simulado resultou `disabled`, zero envios. A imagem antiga não tem o arquivo de controle persistente; a barreira de rollback é a configuração false aplicada ao criar o container. O procedimento de recuperação exige o overlay fixo abaixo; inicialização automática continua `no`.
- Volumes de teste e arquivos temporários da VM foram removidos; a imagem candidata permanece apenas no cache Docker para revisão. Espaço final reportado: ~21 GB livres. Serviços live permaneceram healthy/READY.

O primeiro probe com rootfs read-only falhou porque HOME/XDG não eram graváveis para Crashpad. Repetido com tmpfs explícito para esses diretórios, sem mudar políticas, passou. O runtime live não usa rootfs read-only, mas o overlay deve preservar os mounts/tempfiles necessários.

## Segurança OCI — Trivy local

Scanner Trivy `0.76.2`, DB `2`, `UpdatedAt=2026-11-09T19:03:15Z`; as duas imagens foram comparadas com a mesma base e chave normalizada `tipo/pacote/versão/CVE/severidade/fixedVersion`.

| Findings | Live | Candidata | Delta candidato |
|---|---:|---:|---:|
| Critical | 4 | 4 | 0 |
| High | 343 | 259 | 0 |
| Medium | 454 | 379 | 0 |
| Low | 398 | 344 | 0 |
| Unknown | 6 | 6 | 0 |
| Total | 1205 | 992 | 0 novos; 213 removidos |

Os quatro Critical persistentes não estão aceitos por esta homologação e não são considerados resolvidos:

| CVE / pacote live=candidata | Versão | Situação/alcance a validar |
|---|---|---|
| CVE-2026-58016 `libglib2.0-0` | `2.74.6-2+deb12u9` | Debian bookworm marca vulnerável/postponed; DoS ao processar XML de introspecção D-Bus malformado. A imagem não monta o socket D-Bus do host, mitigando o caminho observado, sem provar inexistência de todo uso local. |
| CVE-2025-7458 `libsqlite3-0` | `3.40.1-2+deb12u2` | Bookworm marca vulnerável; exige execução de SQL arbitrário, com possível DoS/divulgação de memória. Chromium usa SQLite para perfil; a alcançabilidade por conteúdo web não foi provada nem descartada. |
| CVE-2026-6653 `libxml2` | `2.9.14+dfsg-1.3~deb12u6` | Bookworm marca vulnerável/postponed; UAF/DoS com XML crafted. Caminho de dados remoto para este parser não foi provado nem descartado. |
| CVE-2023-45853 `zlib1g` | `1:1.2.13.dfsg-1` | Trivy associa a CVE do MiniZip; o rastreador Debian diz que o binário zlib do Bookworm não inclui o MiniZip vulnerável e o pacote `minizip 1.1-8+deb12u1` está corrigido. Mantido no relatório, sem supressão manual. |

Também permanecem 259 High herdados (nenhum novo pelo diff normalizado); a decisão/mitigação de cada advisory de runtime continua separada. O sandbox completo e UID não-root reduzem impacto, não eliminam advisories. **Aceitação anterior de CVEs para outro digest não se aplica a este digest.** Revisão/aceitação administrativa atual dos riscos OCI é necessária antes de deploy.

## Rollback preparado (não executado)

Overlays locais em `/private/tmp` pinam imagem por digest e preservam mounts/serviços do Compose base por merge:

- Candidata: `zapbot@sha256:832888201b7ceefa9f2d3d2bcd4409057c370d74b8c432516cf8e8730326b12a`, `RELEASE_ANNOUNCER_ENABLED=true`, control path `/app/data/release-announcer-control.json`.
- Rollback: `odisseu-node@sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7`, `RELEASE_ANNOUNCER_ENABLED=false`.

A candidata deve receber o arquivo `suspended` antes de iniciar. Não limpar nem reescrever lock/estado existente. Validar status antes e depois da recriação. Não retomar publicações nesta janela. Se o overlay rollback não puder ser aplicado/verificado, manter o Node parado; não iniciar a imagem antiga com o valor base true. O estado false persistido na configuração do container impede envio mesmo sem o novo controle; restart policy `no` evita relançamento Docker automático. Os demais serviços permanecem com a imagem live atual.

## Pendências que mantêm NO-GO

1. Integrar a proteção sobre código correspondente à revisão live atual (sem perder #65 e alterações posteriores), construir e repetir os testes afetados.
2. Obter avaliação/aceitação administrativa para advisories Critical/High herdados deste digest ou correção compatível.
3. Verificar schema 035 e hold/CI/run concorrentes no preflight da janela.
4. PR #69 está em draft; `RC PR validation` passou, mas ainda não há revisão de código. Sem merge ou deploy nesta etapa.

Nenhuma migration, alteração de save, intent/receipt, restart ou envio foi executado.
