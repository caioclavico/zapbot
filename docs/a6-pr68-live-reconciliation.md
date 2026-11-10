# A6: integração da suspensão do Announcer sobre o código live

Data: 2026-11-10

## Decisão

**Imagem pronta para revisão; NO-GO para promoção a produção neste momento.** A integração preserva a árvore live e não introduz findings Trivy: live e candidata têm exatamente o mesmo conjunto de vulnerabilidades. A candidata ainda contém findings Critical/High herdados; eles não foram aceitos neste trabalho. O responsável de segurança precisa aprovar um plano de mitigação/aceitação antes de qualquer promoção.

Nenhum container live foi reiniciado ou recriado. Nenhum volume de produção, save, intent ou receipt foi alterado. Nenhuma mensagem foi enviada. O hold e o estado do CI/CD não foram modificados.

Verificação passiva final após os ensaios: health `ok`, WhatsApp `READY`, Chromium `true`; Node/JVM `healthy`, ambos com `RestartCount=0`.

## Origem e estratégia

- Revisão comprovada da imagem Node ativa: `8ef41c8da1c28bfb69d57bade81a5a41e9225d44`.
- Tree live: `849129daf4cf12d14de69097c23dfebfdb20bebc`.
- Evidência: labels OCI da imagem apontada pelo container live, confirmadas por `docker inspect`; tar de `docker save` dessa imagem foi escaneado localmente.
- PR #68 foi integrado seletivamente a partir de `a53287f81eb9f97864947a4052f8ee8ba315e3ff`. A RC inteira diverge do live; não foi mesclada.
- Branch: `fix/a6-pr68-live-reconcile`.
- Commit: `029022954bc596ed51759770b9182d6128735b9f`.
- Tree: `8434d3f699762cdc185c83a9a836b66b72d91c92`.
- Pai direto: revisão live `8ef41c8...`.
- Integração restrita ao controle persistente fail-closed, bootstrap/runtime/transporte, configuração Node e testes. O código usa APIs Node existentes e não acrescenta dependências.
- `Dockerfile`, Compose, módulos Pokémon, raids, eventos, economia, bridge JVM e migrations permanecem como no live. A verificação de diff encontrou zero alterações em `modules/pokemon`, `apps/odisseu` e `migrations`.
- PR #69 foi preservado. Seus ajustes de Docker/UID não foram copiados: o Dockerfile live já fixa Node 22.23.4 e Chromium 156, roda como `1000:1000`, proíbe flags de sandbox desativado, e o Compose live já referencia o perfil seccomp versionado. Nenhuma alteração do PR #69 foi necessária para esta integração.

## Imagem e proveniência

Imagem candidata construída de `0290229...`, com o Dockerfile live inalterado:

| Identificador | Valor |
|---|---|
| Plataforma | `linux/amd64` |
| Docker Image ID observado após load | `sha256:b7237d056d1da0fdfdb621ead08116197d2753e794829b95dda9382ada6488aa` |
| OCI manifest digest | `sha256:b7237d056d1da0fdfdb621ead08116197d2753e794829b95dda9382ada6488aa` |
| Config digest | `sha256:9d2b7419d56e6b1bc49b3d68fef3f0c5dbe432a9312a12999c859a7516fc4299` |
| SHA-256 do tar `docker save` | `2bc5b3504b9aeca9af7fc2b72e572bbf7e0cb1286568847152b51435b2e58977` |
| Tag local de teste (referência mutável) | `zapbot:a6-pr68-live-0290229-amd64` |
| Node / Chromium | `22.23.4` / `156.0.8078.79-1~deb12u1` |
| UID/GID | `1000:1000` |
| Base runtime | `node:22.23.4-bookworm-slim`, digest `sha256:aaa4b5baf1ecff3b283486bb1f90405cdda225a3e6f79660d2c559fa510a7292` |
| Base de build | `eclipse-temurin:21-jdk-jammy`, digest `sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69` |

O manifesto OCI/labels apontam para commit e tree acima. O lockfile não mudou. A imagem de teste foi removida do Docker cache da VM após a validação; o tar local e o registro de proveniência permanecem.

## Preservação funcional

O patch mantém o `core.cljs` live, incluindo graceful drain e o fluxo de manutenção A6. A única ligação ClojureScript adicional passa o caminho configurado do controle ao bootstrap do Announcer. A configuração padrão aponta para `/app/data/release-announcer-control.json`; o estado ausente/inválido é fail-closed. O controle é relido no bootstrap, sob lease e imediatamente antes do envio. `transport_uncertain` continua exigindo revisão/reconciliação e nunca habilita retry cego.

Não houve alteração de contrato Node/JVM, schema 035, Datomic, gameplay ou dados. O teste HTTP que valida os contratos JVM passou em 287/287.

## Testes

| Verificação | Resultado |
|---|---|
| `npm run build` | PASS; 882 avisos de inferência ClojureScript já existentes |
| Announcer direcionado | PASS, 32/32 |
| HTTP e contratos JVM | PASS, 287/287 |
| `npm test` candidata | 1 falha + 3 erros em `pokemon_http_test.cljs` |
| `npm test` base live limpa | Mesmos 1 falha + 3 erros, nas mesmas asserções; 236 testes/1.407 asserções. Classificadas como preexistentes, não regressão da integração |
| AMD64 nativo, container descartável | PASS; imagem carregada sob tag exclusiva e removida depois |
| UID e limites | UID 1000; rede `none`; capabilities `ALL` removidas; `no-new-privileges`; rootfs read-only; mounts de teste descartáveis |
| Chromium/Puppeteer/CDP | PASS com perfil `docker/chromium-seccomp.json`; namespaces user/PID/network distintos entre browser e renderer; renderer `Seccomp=2`, dois filtros contra um no browser, `NoNewPrivs=1`, `CapEff=0`; `chrome://sandbox` reportou namespace e Seccomp-BPF ativos; encerramento limpo |
| Persistência de perfil/controle | PASS após recriar o container usando os mesmos volumes descartáveis |
| READY simulado e suspensão | PASS antes e depois da recriação; resultado `suspended`, zero chamadas ao transporte |
| Rollback isolado | Imagem live antiga executada com `RELEASE_ANNOUNCER_ENABLED=false`; READY simulado retornou `disabled`, zero chamadas ao transporte. Se a configuração false não puder ser garantida no rollback, o procedimento seguro é manter Node parado |
| Efeitos sobre produção | Node/JVM permaneceram healthy, `RestartCount=0`; nenhum mount live foi usado pelo teste |

O perfil Docker padrão bloqueou a criação de namespace e o primeiro probe Chromium falhou com `EPERM`. A repetição usou apenas o perfil seccomp restrito e versionado, cujo acréscimo para `clone` é filtrado por `CLONE_NEWPID`; não houve `SYS_ADMIN`, `--privileged`, seccomp irrestrito ou socket Docker dentro do container.

## Trivy e risco residual

Versão: Trivy `0.76.2`. Live e candidata foram escaneadas localmente a partir de arquivos `docker save`, com a mesma base pública Trivy DB v2 (atualizada em `2026-11-10T01:02:55Z`), sem filtros de severidade e sem enviar imagem/SBOM para serviço externo. O scanner encontrou zero secrets.

| Severidade | Ocorrências por pacote | Advisories únicos | Delta candidata vs live |
|---|---:|---:|---:|
| Critical | 4 | 4 | 0 |
| High | 343 | 140 | 0 |
| Medium | 454 | 201 | 0 |
| Low | 398 | 194 | 0 |
| Unknown | 22 | 10 | 0 |

Os 343 registros High incluem 84 advisories de Chromium repetidos nos pacotes `chromium`, `chromium-common` e `chromium-sandbox` (252 ocorrências); os restantes são bibliotecas Debian e 11 ocorrências npm. A tabela completa de findings, com advisory, componente, versão, fix/status e delta, está em [`docs/evidence/a6-pr68-live-vs-candidate-trivy-20261110.csv`](evidence/a6-pr68-live-vs-candidate-trivy-20261110.csv). O scan completo não filtrou severidades; a comparação registrou também Medium, Low e Unknown.

Findings Critical e avaliação da exposição:

| Advisory | Componente/versão | Avaliação específica |
|---|---|---|
| CVE-2026-58016 | `libglib2.0-0 2.74.6-2+deb12u9` | NVD dá score 9.1; Debian bookworm classifica como postponed/minor e descreve DoS no parser de XML de introspecção D-Bus. Não há socket D-Bus montado no container nem caminho do Announcer para esse parser; continua herdado, sem patch bookworm identificado. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-58016) |
| CVE-2025-7458 | `libsqlite3-0 3.40.1-2+deb12u2` | Requer execução de SQL arbitrário para explorar o overflow; a aplicação não expõe SQL arbitrário. Debian bookworm marca no-DSA/minor; nenhuma correção bookworm identificada. [Debian](https://security-tracker.debian.org/tracker/CVE-2025-7458) |
| CVE-2026-6653 | `libxml2 2.9.14+dfsg-1.3~deb12u6` | NVD score 9.8; Debian bookworm descreve UAF/DoS como postponed/minor. O código live gera SVGs internos com Sharp; a possibilidade de alcançabilidade via dependência de SVG não foi descartada definitivamente. Sem correção bookworm identificada. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6653) |
| CVE-2023-45853 | `zlib1g 1:1.2.13.dfsg-1` | A vulnerabilidade está em MiniZip; Debian informa que bookworm não constrói/fornece o componente MiniZip afetado e ignora o CVE para esse pacote. Classificado como false positive por associação do scanner ao pacote-fonte, sem ignorar o finding. [Debian](https://security-tracker.debian.org/tracker/CVE-2023-45853) |

Exposição High prioritária: os 84 advisories de Chromium 156 não têm `FixedVersion` reportada nessa base e o navegador carrega conteúdo remoto do WhatsApp. O sandbox reduz impacto de comprometimento de renderer, mas não elimina exploração de renderer. `sharp 0.35.3` tem 2 High (libheif/librsvg) com fixes informados em `0.35.4`/`0.35.5`; o caminho principal do jogo rasteriza SVG gerado internamente, mas a revisão não declara o pacote impossível de explorar. `adm-zip 0.6.0` (4 High) é dependência de `cassandra-driver` e o ambiente usa Datomic; `basic-ftp`, `extract-zip` e `js-yaml` pertencem ao caminho de ferramentas/configuração do Puppeteer, sem caminho remoto de entrada identificado no fluxo A6. Essas dependências continuam no artefato e não foram atualizadas nesta integração.

**Conclusão de risco:** não há regressão de CVE nem nova superfície de dependência, mas o risco residual herdado não está aceito nesta solicitação. Em especial, Chromium é uma superfície exposta a conteúdo remoto e libxml2 via processamento de SVG merece análise/patch separado. Portanto, a imagem pode seguir para revisão de código e triagem de segurança; a promoção exige decisão explícita de risco ou atualização pontual seguida de novo build/scan.

## Volume e rollback

O container live usa UID `1000:1000`. A inspeção somente leitura encontrou ACL `user:ubuntu:rwx` no volume `/app/data` e no mount LocalAuth; com o UID do host/container `1000`, os caminhos de persistência necessários são compatíveis sem `chown`. O teste em volume novo confirmou criação e persistência do arquivo de controle como UID 1000. Não foram escritos arquivos de teste nesses mounts reais.

Rollback de código para o digest live somente com `RELEASE_ANNOUNCER_ENABLED=false` aplicado e verificado antes de iniciar o Node. A imagem live antiga não entende o marker persistente do PR #68. Se não for possível impor a flag false, não iniciar a imagem antiga; manter Node parado e preservar o volume de controle até recuperação.

## GitHub e próximos gates

Estado desta execução: PR #70 aberto em draft contra `a6/live-baseline-8ef41c8`, branch-base criada exatamente em `8ef41c8...`. O PR está mergeable, sem conflitos conhecidos, mas ainda não tem workflow run, check de CI ou revisão; esses gates permanecem pendentes. PR #69 permaneceu intacto. CI/CD e hold de produção não foram alterados. Não foi executado merge.

Antes de promover: obter CI aplicável e revisão no PR #70, decisão do responsável de segurança sobre os findings herdados e preflight do overlay com seccomp/UID e mount persistente do controle explicitamente conferidos. Sem essas aprovações, manter NO-GO para produção.

## Validação final do PR #70 (2026-11-10)

- Reconsulta GitHub: PR aberto, draft, mergeable, base `a6/live-baseline-8ef41c8` (`8ef41c8da1c28bfb69d57bade81a5a41e9225d44`) e HEAD `1dcef41458053164ae8f63b836b7a7f8f8607a79`; sem conflitos reportados. Diff e árvore foram revisados; o patch funcional continua restrito ao controle Announcer e integração preservada sobre o live.
- Foi preparado `.github/workflows/a6-live-reconcile-validation.yml`, um workflow de validação somente leitura e sem secrets, publicação, ambientes protegidos, deploy ou credenciais de produção. Ele só roda em push da branch do PR e pull request contra a base live; verifica a base exata e os caminhos protegidos, instala dependências travadas e executa Announcer, recursos/manutenção, contratos HTTP/JVM e build. O workflow de produção existente não é acionado por esta branch/base.
- Execução local no ambiente isolado: Announcer 32/32 PASS; recursos/manutenção 87/87 PASS com loopback permitido; HTTP/JVM 287/287 PASS; `npm run build` exit 0 (avisos de inferência/depreciação já existentes). O teste amplo continua documentado como 1 falha + 3 erros idênticos no baseline live limpo e na candidata; sem regressão atribuível ao patch.
- A prova anterior de rollback permaneceu: imagem live antiga recebeu `RELEASE_ANNOUNCER_ENABLED=false` antes de iniciar o container descartável; bootstrap/readiness simulados retornaram `disabled` e zero chamadas de transporte. Não se usou sessão WhatsApp real.
- A análise Trivy não mudou porque workflow e documentação não alteram a imagem nem dependências. Permanecem 4 Critical e 343 ocorrências High (140 advisories únicos) herdados, com delta zero; nenhum risco foi aceito ou reclassificado. O NO-GO de promoção permanece até decisão de segurança específica.
- Revisões humanas independentes continuam ausentes. Na verificação atual o PR ainda está draft, sem reviews e sem runs/checks GitHub associados ao HEAD. O CI remoto ficará verificável após o push deste workflow; até então os resultados são execuções locais. O PR não foi marcado ready e não foi feito merge.
- Próxima etapa Git: commit e push do workflow/evidência; aguardar o check remoto e revisão humana. Merge apenas após esses gates e homologação explícita da integração. Nenhuma branch temporária foi excluída.
