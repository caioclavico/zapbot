# Imagem combinada A6 — drain, conteúdo dinâmico e sandbox

**Decisão: READY FOR DEPLOY — condicionada somente à autorização final da janela de cutover.** Homologação dos quatro gates restantes concluída em 2026-11-07T21:00Z. Não houve deploy, restart live, ACL live, alteração de Datomic/saves/ginásios/economia/raids/eventos, nem mudança de CI/CD ou deploy hold. Nenhum token de manutenção foi criado, lido ou registrado. A imagem só de IA (`sha256:2bbca089…`) permanece proibida.

## Homologação final (gates)

| Gate | Resultado | Evidência |
| --- | --- | --- |
| Sandbox AMD64 nativo neste Image ID | **PASS** | `odisseu-core` `x86_64`, Docker `amd64 29.9.1`; tar `ff3c6f2e…` → Image ID `sha256:ce429153…`; `chrome://sandbox` = Namespace + Seccomp-BPF **Yes** + “You are adequately sandboxed”; renderer `Seccomp_filters=2` > browser `1`; UID 1000; `CapEff=0`; `NoNewPrivs=1`; CDP/Puppeteer; recreate de volumes descartáveis; drain hashes idênticos à produção. Prod `odisseu-a6-node`/`odisseu-a6-jvm` permaneceram healthy sem recreate. |
| ACL UID 1000 | **PRONTA (não aplicada live)** | RO dos mounts reais: sessão bind `…/a6-cutover-session-from-core` raiz `1001:1002`/`0700`, descendentes majoritariamente `0:0` `0700`/`0600`; data volume `odisseu-a6-cutover-data` `0:0`/`0755`; marker `a6-maintenance.json` `0:0`/`0600` phase `open`; histórico IA ausente. Ensaio descartável com ownership equivalente: negado antes da ACL → PASS após `setfacl` → histórico `0600` uid 1000 → revert nega de novo. Host sem pacote `acl` (só `libacl.so.1`); instalar `acl` na janela. Comandos em `docs/evidence-a6-homolog-ce429153/evidence/acl-commands.sh`. |
| Compose produção/cutover | **PRONTO (não aplicado live)** | Live usa projeto `odisseu-a6-cutover`, arquivo `…/a6-cutover-runtime-20261101T141408Z/docker-compose.a6-cutover-runtime.yml` (sem user/seccomp/cap_drop/stop_grace). Overlay mínimo: `docker-compose.a6-ai-drain-sandbox.overlay.yml` (user 1000, cap_drop ALL, NNP, seccomp, stop_grace 60s, imagem combinada). Check isolado `scripts/homolog-compose-overlay-check.sh` **PASS** (flags Pokémon preservadas). `docker-compose.production.yml` do repo também recebeu a estrofe de segurança; não é o compose live da VM Core. |
| GitHub | **PASS (reverificar imediatamente antes do deploy)** | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true`; workflow `CI/CD` (`.github/workflows/deploy.yml`) `disabled_manually`; `Pokémon storage progression CI` ativo; runs `in_progress`/`queued`/`waiting`/`pending` = **0**. Nada foi alterado no GitHub. |

### Riscos residuais

- Trivy da imagem combinada: 4 CRITICAL / 95 HIGH (já registrados; não zerados).
- Pacote `acl` precisa ser instalado no host antes do `setfacl` live.
- `docker/chromium-seccomp.json` precisa existir no working_dir do compose live (ou ajustar o path do overlay).
- Reiniciar a verificação GitHub e o preflight de exclusividade de janela no momento do cutover.
- Overlay não altera flags Pokémon; o operador não deve reabrir desafios/raids/eventos nesta janela Node-only.

### Plano de rollback

Voltar o serviço `odisseu-a6-node` para `odisseu-node:a6-drain-breakglass-20261106-1d00dd01` / `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`, sem apagar `.wwebjs_auth_a6_cutover` nem `odisseu-a6-cutover-data`. Restaurar ACL a partir do snapshot `getfacl` se a janela as tiver aplicado. JVM/Datomic fora do rollback.

### GO / NO-GO

**READY FOR DEPLOY**, condicionado somente à autorização final da janela de cutover Node-only. Bloqueios técnicos dos quatro gates estão fechados. Não executar deploy nesta entrega documental.

---

# Registro anterior — imagem combinada (pré-homologação)

**Decisão histórica: NO-GO operacional (substituída pela homologação acima).** A imagem AMD64 abaixo foi construída localmente antes da prova nativa. Não houve deploy naquela etapa.

As seções anteriores a este bloco, preservadas mais abaixo, descrevem a linhagem só de IA (`sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865` e candidatas anteriores). Essa imagem não contém `a6-maintenance`/`a6-shutdown` e **não deve seguir sozinha**.

## Identidade

| Campo | Valor |
| --- | --- |
| Tag | `odisseu-node:a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64` |
| Commit do runtime | `fefab4c2abbecf3aedd84f13df6a355a4b00dd5c` |
| Tree desse commit | `71e1f15c671416ebf7a045de140bb78a6fad6b3c` |
| Tar local | `/tmp/odisseu-node-a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64.tar` (371 MB) |
| SHA-256 do tar | `ff3c6f2ee385783212b3c14f4a7271aea0bd20ec72c0e0d4adca4f9993046cb6` |
| Image ID (`docker image inspect`) | `sha256:ce429153851799cc77f7e40c1f3bc328d963d891a9a1dc38ec9ceb4a3c7fb377` |
| Manifesto da plataforma no build | `sha256:0602766f50103af024a9f8789a3a0bef6f5c6e2240f74e5e888efecbbdd86e78` |
| Config do build | `sha256:7c90627516de67929a90d17eaf8ead434354043bed39a352e7131965967dd82d` |
| Plataforma | `linux/amd64` |
| Tamanho reportado | 389 MB |
| Base Git | `03a3dc7ec2acb6d75e6e95390fabe69bd21f0a92` |
| Worktree | `/private/tmp/zapbot-a6-ai-drain-combined` |
| Branch | `rc/a6-ai-drain-sandbox` |
| Node | `22.23.4` (`node:22.23.4-bookworm-slim@sha256:aaa4b5baf1ecff3b283486bb1f90405cdda225a3e6f79660d2c559fa510a7292`) |
| Chrome | `157.0.8103.38-1` |
| libbrotli1 | `1.0.9-2+deb12u1` |
| Usuário da imagem | `1000:1000` |
| Perfil seccomp | `docker/chromium-seccomp.json`, SHA-256 `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10` |
| `a6-maintenance.cjs` | SHA-256 `04b5b61d082a5dc8669c3633f0c8e611fa7e2740c7dab714f21964e90e7e4d80` |
| `a6-shutdown.cjs` | SHA-256 `16dae2a86eb98437316bdc67406c230095e395fb9cfaf048ad79ec6c2383ad68` |

O Dockerfile não copia `docs/`. O Image ID é o de `docker buildx build --platform linux/amd64 --load`. O commit `fefab4c` (tree `71e1f15c671416ebf7a045de140bb78a6fad6b3c`) contém o runtime copiado para a imagem. A tag canônica e o tar foram registrados depois, com `docker tag` no mesmo Image ID, sem rebuild.

## Diferença contra a imagem ativa `1d00dd01`

Produção consultada só para leitura. Container de produção não foi recriado.

| | Ativa `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390` | Combinada `sha256:ce429153851799cc77f7e40c1f3bc328d963d891a9a1dc38ec9ceb4a3c7fb377` |
| --- | --- | --- |
| Tag observada | `a6-drain-breakglass-node:20261106-local` | `odisseu-node:a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64` |
| Label `io.zapbot.source-base` | `03a3dc7ec2acb6d75e6e95390fabe69bd21f0a92` | ausente; a base Git é a mesma, sem esse label |
| Usuário efetivo | vazio na imagem; smoke `uid=0` | `1000:1000` |
| Node | `v22.23.3` | `v22.23.4` |
| Navegador | Chromium Debian `155.0.8059.79` em `/usr/bin/chromium` | Google Chrome `157.0.8103.38-1`, symlink `/usr/bin/chromium` |
| `target/main.js` | contém `--no-sandbox` e `--disable-setuid-sandbox` | não contém nenhuma das duas flags |
| Drain | os dois arquivos, hashes acima | os mesmos bytes |
| `ai-client.cjs` | presente | presente, com `generateDetailed` |
| `ai-content-generator.cjs` / `ai-content-history-file.cjs` | ausentes | presentes |
| Capabilities no smoke | não reexecutado como mudança | `CapEff=0`, `NoNewPrivs=1`, `Seccomp=2` |

## O que entrou no código

- Drain de produção copiado byte a byte para `scripts/lib/a6-maintenance.cjs` e `scripts/lib/a6-shutdown.cjs`.
- Ligação já presente em `03a3dc7` foi mantida e completada com os hunks de encerramento em `core.cljs`, `parar!` em `lembretes.cljs` e `close!` em `platform_jvm.cljs`. `pokemon_http.cljs` já referenciava o gate nessa base e não foi reescrito.
- `!piada [assunto]` e `!curiosidade [assunto]` passam por `zapbot.conteudo-dinamico`, com histórico em `/app/data/ai-content-history.json`. Gemini/Groq e o status de boot existente foram preservados; entrou `gerar-texto-detalhado`.
- Puppeteer ficou só com `--disable-quic` e `--disable-features=Quic`, mais as flags opcionais de GPU/baixo recurso. O teste `core_test.cljs` acompanha essa lista.
- Compose de exemplo com `user: "1000:1000"`, `cap_drop: [ALL]`, `no-new-privileges` e o seccomp: `docker-compose.yml`, o serviço `odisseu-node` de `docker-compose.odisseu-core.yml` e `docker-compose.release-a6-cutover.example.yml`. `docker-compose.production.yml` não foi editado.

## Testes

Executados neste worktree, Node local `v22.23.3` (a imagem carrega `v22.23.4`).

- `npm run test:ai`: 44/44.
- `node --test test/a6-maintenance.test.cjs test/a6-shutdown.test.cjs test/a6-ai-drain-sandbox-invariants.test.cjs`: 16/16. O invariante falha se os arquivos de drain sumirem ou mudarem de hash, se `--no-sandbox` ou `--disable-setuid-sandbox` voltarem em `src/zapbot/core.cljs` ou no Dockerfile, ou se o Dockerfile perder `USER 1000:1000`.
- `npm test`: suíte Node verde (44 de IA e 80 de recursos, incluindo o invariante). ClojureScript: 236 testes, 1407 asserções, 1 falha e 3 erros, todos em `test/zapbot/pokemon_http_test.cljs` (`comandos-de-missoes-preservam-o-filtro-de-apresentacao` e `perfil-professor-titulo-amizade-rota-evento-whatsapp`). `src/zapbot/pokemon_http.cljs` e esse teste estão idênticos a `03a3dc7`. Não foram corrigidos aqui.
- `npm run build`: concluído, 106 arquivos, 51 compilados, 869 warnings de inferência/depreciação. O `target/main.js` local não contém as flags de sandbox desligado e exige `a6-maintenance.cjs` e `a6-shutdown.cjs`.
- `npm run test:http`: 287/287.

## Drain e sandbox

Smoke descartável da imagem combinada, sem volume de sessão e sem rede de produção:

- Processo `node` com `--user 1000:1000`, `--cap-drop ALL`, `no-new-privileges` e `seccomp=./docker/chromium-seccomp.json`.
- Hashes de drain iguais aos da imagem ativa. `target/main.js` sem as flags proibidas. `process.version` `v22.23.4`.
- `/proc/self/status`: `NoNewPrivs: 1`, `Seccomp: 2`, `CapEff: 0000000000000000`.
- `google-chrome-stable --version` → `Google Chrome 157.0.8103.38`. `dpkg-query` confirma `google-chrome-stable 157.0.8103.38-1` e `libbrotli1:amd64 1.0.9-2+deb12u1`.
- O build da imagem executa um `node -e` que aborta se os dois módulos de drain faltarem ou se o bundle contiver as flags de sandbox desligado.
- Chrome headless saiu 0 sob o mesmo perfil, mas `chrome://sandbox` não renderizou o relatório: o DOM devolvido foi o da New Tab. Esta sessão, em Docker Desktop sobre macOS emulando AMD64, **não** repete a prova autoritativa “You are adequately sandboxed”. Essa prova continua sendo a do ensaio nativo AMD64 da imagem só de sandbox, com o mesmo perfil seccomp, e precisa ser refeita neste Image ID antes de qualquer GO.

## Trivy 0.76.2

Banco local atualizado em 2026-11-07. Contagem de ocorrências pacote/CVE, não de CVE únicos.

| Imagem | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN | IDs únicos |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Combinada | 4 | 95 | 227 | 217 | 4 | 339 |
| Ativa `1d00dd01` | 12 | 1031 | 441 | 348 | 81 | 1022 |

Os quatro CRITICAL da combinada: `CVE-2026-58016` (`libglib2.0-0`, fix adiado), `CVE-2025-7458` (`libsqlite3-0`, afetado), `CVE-2026-6653` (`libxml2`, fix adiado), `CVE-2023-45853` (`zlib1g`, will_not_fix). A queda grande contra a imagem ativa acompanha a troca do Chromium Debian 155 pelo Chrome 157; a combinada também introduz IDs que a ativa não tinha. Pacotes npm já conhecidos (`adm-zip` 0.6.0, `extract-zip` 2.0.1, `sharp` 0.35.3, `js-yaml` 4.3.0, `basic-ftp` 5.3.1) continuam no grafo. O scan não zera o risco e não autoriza deploy.

`npm audit` no `npm ci` deste worktree: 24 vulnerabilidades (6 baixas, 4 moderadas, 14 altas). Nenhuma dependência foi atualizada.

## ACL — plano, não aplicado

A imagem passa a gravar sessão, marker de drain e histórico de IA como UID/GID 1000. Os volumes atuais da sessão WhatsApp e de `/app/data` não foram inspecionados nem alterados nesta tarefa. Antes de uma janela futura, no host, sem executar agora:

1. `namei -l` / `stat` de `.wwebjs_auth` (ou `.wwebjs_auth_core`) e do diretório montado em `/app/data`.
2. Conceder acesso ao UID 1000 sem tomar posse do diretório: `setfacl -m u:1000:rwx` no diretório, `setfacl -R -m u:1000:rwX` no conteúdo e `setfacl -d -m u:1000:rwx` para arquivos novos.
3. Não usar `chown -R` na sessão real nesta etapa. Se o filesystem não tiver ACL, a alternativa é um volume novo criado já como `1000:1000` e uma cópia verificada, em janela própria.
4. Confirmar que o marker `ODISSEU_NODE_MAINTENANCE_FILE` e `ai-content-history.json` nascem mode `0600` e continuam legíveis só por esse UID.

Nada disso foi aplicado.

## Plano de deploy só do Node — não executar

1. Manter `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` e o workflow de CI/CD como está. Não recriar a JVM, não abrir ginásios, não mexer em raids, eventos, saves ou Datomic.
2. Na VM AMD64, carregar exatamente `sha256:ce429153851799cc77f7e40c1f3bc328d963d891a9a1dc38ec9ceb4a3c7fb377` e conferir `docker image inspect`.
3. Aplicar o plano de ACL acima e montar o perfil `docker/chromium-seccomp.json` no host. O `docker-compose.production.yml` deste commit ainda não tem `user`, `cap_drop` nem `seccomp`; a janela precisa usar a estrofe do exemplo (`user: "1000:1000"`, `cap_drop: [ALL]`, `no-new-privileges:true`, `seccomp=./docker/chromium-seccomp.json`).
4. Entrar em drain pelo gate já implantado, com o token operacional que já existe. Não gerar token novo. `stop_grace_period` segue 60s; a espera de quiescência no código é 30s e o deadline interno de 330s não encerra o processo sozinho. Pré-drenar antes do `stop`.
5. Trocar somente o container Node. Verificar `/health`, UID 1000, `CapEff=0` e um `chrome://sandbox` nativo neste Image ID.
6. Exercitar `!piada` e `!curiosidade` num chat autorizado. O histórico é de uma instância só.

## Rollback

Voltar o container Node para `a6-drain-breakglass-node:20261106-local` / `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`. Não apagar `.wwebjs_auth` nem `/app/data`. A imagem anterior ignora `ai-content-history.json`. Ela volta a ser root e a passar `--no-sandbox`; o rollback restaura o comportamento atual, não o sandbox. JVM, Datomic e flags de ginásio/raid ficam fora do rollback.

## GO / NO-GO

**SUPERADO.** Os quatro gates foram homologados; a decisão vigente está no topo: READY FOR DEPLOY condicionada à autorização da janela.

---

# Histórico — conteúdo dinâmico e sandbox anteriores

O texto abaixo registra a linhagem anterior. Onde ele apontar uma imagem candidata ou um GO técnico, vale o NO-GO desta seção de cima.

# Conteúdo dinâmico de piadas e curiosidades

## Implementação

`!piada [assunto]` e `!curiosidade [assunto]` agora usam uma geração assíncrona comum, com Gemini principal, Groq opcional, prompts limitados, validação de saída e fallback local identificado. A fila compartilhada serializa pedidos neste processo. O histórico separa os dois comandos e registra categoria, subtema, resumo sanitizado, fingerprint e instante, sem guardar a resposta integral.

O histórico é salvo em `/app/data/ai-content-history.json`, no mount persistente Node já presente nos Compose A6, staging e produção. O arquivo tem versão, retenção configurável, modo 0600, temporário exclusivo, `fsync` e rename atômico. Leituras e gravações são serializadas dentro do processo. A implantação suporta uma instância Node; múltiplas réplicas compartilhando o mesmo arquivo não estão suportadas.

## Falhas e recuperação

Se o arquivo não puder ser lido ou gravado, a resposta usa o acervo local e avisa que o histórico durável está indisponível. O cursor e o fingerprint são mantidos em memória para reduzir repetição durante a falha; não se declara deduplicação persistente. Um JSON inválido falha fechado e não é sobrescrito automaticamente. Uma gravação interrompida antes do rename preserva o arquivo anterior; temporários órfãos são removidos na próxima leitura.

O modo `PLATFORM_ADAPTER=datomic` não abre Cassandra nem outro cliente Datomic. O arquivo fica no volume Node `/app/data`, fora do volume Datomic e dos saves Pokémon.

## Verificações

- `npm run test:ai`: 44/44 aprovados.
- `npm run build`: aprovado no clone isolado; 106 arquivos compilados, com 716 warnings de inferência/depreciação.
- `npm test` no clone isolado com apenas os hunks de IA: 232 testes, 1.391 asserções, 0 falhas e 0 erros.
- `npm test` no workspace original: 233 testes, 1.397 asserções, 1 falha e 5 erros. O teste Pokémon limpo em `HEAD` passou 231 testes/1.389 asserções. No workspace, os erros estão em `pokemon_http_test.cljs` (3 erros: a resposta do teste de missões não é string) e `pokemon/ajuda_test.cljs` (2 erros e 1 falha: aliases de novidades retornam `nil`). Os arquivos/fontes Pokémon envolvidos já estavam alterados localmente antes desta tarefa; o clone isolado passa sem esses hunks. Nenhum teste Pokémon foi alterado nesta implementação.
- Ensaio Docker: um container gravou no volume nomeado temporário e um segundo container leu após recriação. O smoke também passou com a imagem candidata. Os volumes temporários foram removidos.
- `git diff --check` nos arquivos escopados: aprovado.

O `npm ci` da construção Docker reportou 24 avisos de vulnerabilidade no estágio de build; após `npm prune --omit=dev`, o estágio final reportou 16 (2 moderadas, 14 altas). Nenhuma dependência foi atualizada neste escopo. Fazer a triagem desses avisos antes do GO operacional de implantação.

## Artefato candidato local

- Base: `a6a983a0af416ba9e077b9b4a05e1432f8906cbd`.
- Commit isolado: `a40f8aa9eb767287fd00a4d9316d6147433bbcb9`.
- Content-tree: `ac5af1cf3772c1de0da6aa5679d9df823c7c05c5`.
- Imagem Node candidata: `odisseu-node:ai-dynamic-a40f8aa-amd64`.
- Image ID: `sha256:ac0a4d696b89fedb4bc411e64e681d0d3ed0495013e4518ed445cd9d95aabbc5` (`linux/amd64`).
- Construída no clone isolado; contém apenas as alterações da funcionalidade e documentação/configuração de volume. Nenhum tarball foi exportado, portanto não há checksum de tarball.

## Estado

**HISTÓRICO, SUPERADO.** A decisão vigente está no topo. A imagem só de IA não deve ser implantada. A persistência em `/app/data` foi testada depois de restart/recriação de container, a suíte e o build do clone isolado passaram e existe imagem AMD64 candidata rastreável. As seis falhas do workspace sujo não se reproduzem no clone isolado nem no `HEAD` limpo. O candidato pressupõe uma única instância Node. Antes do GO operacional, revisar os avisos de `npm audit`, o commit isolado e confirmar o mount `/app/data` no serviço de destino. Produção não foi consultada nem alterada; ginásios, recompensas, raids, WhatsApp, manutenção e CI/CD ficaram intocados por esta tarefa.

## Plano de implantação e rollback

1. Confirmar no serviço alvo que `/app/data` está montado em volume persistente e gravável pelo Node; preservar o volume existente.
2. Fazer o rollout Node-only com a imagem candidata usando o graceful drain já implantado. Não recriar a JVM nem executar migrations.
3. Verificar readiness do Node. A primeira resposta que puder ser persistida cria o arquivo no mount; não há histórico legado para migrar.
4. Emitir solicitações de teste autorizadas e confirmar persistência/fallback por logs sanitizados, sem enviar assuntos privados.
5. Em rollback, restaurar apenas a imagem/configuração anterior e manter `/app/data`; não apagar nem recriar o volume. A versão anterior ignora o arquivo e uma implantação futura volta a lê-lo.

Não foi feito deploy nem alteração de flags operacionais.

## Auditoria de segurança da imagem candidata — 2026-11-07

### Imagem e comparação read-only

- Imagem originalmente avaliada: `odisseu-node:ai-dynamic-a40f8aa-amd64`, Image ID `sha256:ac0a4d696b89fedb4bc411e64e681d0d3ed0495013e4518ed445cd9d95aabbc5`.
- Produção consultada somente para leitura em `odisseu-core` (AMD64): `odisseu-node:a6-drain-breakglass-20261106-1d00dd01`, Image ID `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`; container saudável na observação.
- As duas imagens tinham as mesmas versões dos pacotes que aparecem no alerta: WhatsApp 1.34.6, Puppeteer/Puppeteer Core 24.38.0, `@puppeteer/browsers` 2.13.0, `chromium-bidi` 14.0.0, `extract-zip` 2.0.1, `basic-ftp` 5.3.1, `get-uri` 6.0.5, `proxy-agent` 6.5.0, `pac-proxy-agent` 7.2.0, `zod` 3.25.76, `adm-zip` 0.6.0, `sharp` 0.35.3 e `js-yaml` 4.3.0. Portanto a imagem original não introduzia esses alertas em relação à imagem ativa observada.
- No container de produção, `PLATFORM_ADAPTER=datomic`, `PUPPETEER_SKIP_DOWNLOAD=true`, `PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium` e não havia proxy configurado. Nenhuma variável secreta foi consultada ou exibida.

O número `14 high` do npm representa pacotes afetados no grafo de dependências, não 14 CVEs independentes. Parte da contagem é propagação do mesmo advisory por dependências ascendentes, incluindo `puppeteer`, `puppeteer-core`, `whatsapp-web.js`, `@puppeteer/browsers` e agentes de proxy. O relatório inicial também mostrava dois pacotes moderados (`cassandra-driver` e `ip-address`).

Os 14 registros altos por pacote e seus advisories-raiz eram:

| Pacote e versão inicial | Advisory raiz que gera o registro | Estado na imagem revisada |
| --- | --- | --- |
| `@puppeteer/browsers` 2.13.0 | `extract-zip` (GHSA-jmr9-qjv8-65gv, GHSA-7pqw-9j4j-h8q3) e cadeia de proxy | Permanece no lockfile; caminho de instalação não usado. |
| `adm-zip` 0.6.0 | GHSA-7q85-xj36-vmfc, GHSA-rcw4-f5rp-g42v, GHSA-j5f4-cc29-5x44, GHSA-8238-w5pm-2374 | Corrigido em 0.6.1. |
| `basic-ftp` 5.3.1 | GHSA-c475-qrg2-pj4r | Permanece no lockfile; alcançável apenas na cadeia FTP de proxy/download não usada. |
| `chromium-bidi` 14.0.0 | Zod GHSA-mrhx-c7xc-m3w5 | Permanece; aplicação usa CDP, não BiDi. |
| `extract-zip` 2.0.1 | GHSA-jmr9-qjv8-65gv, GHSA-7pqw-9j4j-h8q3 | Permanece; sem release corrigida no advisory, mas instalação de browser não é chamada. |
| `get-uri` 6.0.5 | GHSA-c475-qrg2-pj4r via `basic-ftp` | Permanece; proxy/download não usado. |
| `js-yaml` 4.3.0 | GHSA-5p4m-2wfm-xmqj, GHSA-2883-xcg3-v3hh | Corrigido em 4.3.2. |
| `pac-proxy-agent` 7.2.0 | GHSA-c475-qrg2-pj4r via `get-uri`/`basic-ftp` | Permanece; proxy não configurado. |
| `proxy-agent` 6.5.0 | GHSA-c475-qrg2-pj4r via `pac-proxy-agent` | Permanece; só integra utilitários de download/proxy. |
| `puppeteer` 24.38.0 | Propaga `extract-zip`, proxy/FTP e Zod BiDi | Permanece; sem download e usa Chromium do sistema/CDP. |
| `puppeteer-core` 24.38.0 | Propaga `extract-zip`, proxy/FTP e Zod BiDi | Permanece; usa Chromium do sistema/CDP. |
| `sharp` 0.35.3 | GHSA-rgj7-g3m4-5g8c, GHSA-wq5f-xc86-pv6w | Corrigido em 0.35.5. |
| `whatsapp-web.js` 1.34.6 | Propaga a cadeia vulnerável do Puppeteer | Permanece na mesma versão da produção; sem vulnerabilidade própria identificada nesse conjunto. |
| `zod` 3.25.76 | GHSA-mrhx-c7xc-m3w5 | Permanece porque BiDi declara Zod 3; a linha 4 não foi forçada. |

Os dois registros moderados eram `adm-zip` 0.6.0 (GHSA-vwc7-r8mq-g2x9, GHSA-p634-w6r4-rjp2 e GHSA-c6fg-446q-cg94) e `ip-address` 10.3.1 (GHSA-rpw4-54j3-4h4q, GHSA-2vr4-cq9g-pvrc, GHSA-j6r3-76f7-8jcv, GHSA-h3mg-xc3c-68pw e GHSA-rxp7-6hxx-x8q5). Todos desapareceram do audit após as versões 0.6.1 e 10.7.6, respectivamente.

### Correções compatíveis feitas no clone isolado

O lockfile foi atualizado sem mudar as faixas de API diretas nem a versão de `whatsapp-web.js`:

| Pacote | Produção e imagem inicial | Imagem revisada | Resultado |
| --- | --- | --- | --- |
| `adm-zip` | 0.6.0 | 0.6.1 | Corrige escrita por symlink, permissões SUID/SGID, limites de descompressão, entradas ZIP duplicadas e crash na descompressão assíncrona. Veio pelo caminho DataStax Cloud do driver Cassandra; a aplicação está em `PLATFORM_ADAPTER=datomic`. |
| `ip-address` | 10.3.1 | 10.7.6 | Remove os cinco advisories moderados encontrados na versão anterior. É usado pela cadeia de proxy do downloader, não por código da aplicação. |
| `js-yaml` | 4.3.0 | 4.3.2 | Corrige os dois advisories de consumo quadrático/ilimitado de CPU em entradas YAML. Não há chamada direta no código do bot. |
| `sharp` | 0.35.3 | 0.35.5 | Corrige os advisories de `libheif` e `librsvg`. Como o bot usa `sharp` para renderizar imagens, este era o achado mais relevante alcançável; foi corrigido e a suíte completa/build passaram. |

Os advisories correspondentes são [adm-zip GHSA-j5f4-cc29-5x44](https://github.com/advisories/GHSA-j5f4-cc29-5x44), [js-yaml GHSA-2883-xcg3-v3hh](https://github.com/advisories/GHSA-2883-xcg3-v3hh) e [sharp GHSA-wq5f-xc86-pv6w](https://github.com/advisories/GHSA-wq5f-xc86-pv6w). A release `adm-zip` 0.6.1 lista as respectivas correções; `sharp` 0.35.5 é o primeiro release que cobre o advisory de `librsvg`.

### Achados altos remanescentes e alcance

| Advisory raiz | Pacote/versão revisada | Dependência e cenário necessário | Classificação no runtime atual |
| --- | --- | --- | --- |
| [GHSA-jmr9-qjv8-65gv](https://github.com/advisories/GHSA-jmr9-qjv8-65gv), [GHSA-7pqw-9j4j-h8q3](https://github.com/advisories/GHSA-7pqw-9j4j-h8q3) | `extract-zip` 2.0.1 | Extração de arquivo de navegador com entrada symlink maliciosa; sem versão corrigida identificada no audit. Chega via `@puppeteer/browsers`. | Não alcançável pelo fluxo implantado: download do Puppeteer está desabilitado, Chromium vem do pacote do sistema, e a aplicação não chama a API de instalação/extração. A documentação oficial descreve essa biblioteca como instalador/descarregador de browsers. |
| [GHSA-c475-qrg2-pj4r](https://github.com/advisories/GHSA-c475-qrg2-pj4r) | `basic-ftp` 5.3.1 | DoS de CPU ao analisar listagem Unix FTP controlada por servidor; o fix indicado é `basic-ftp` 6.2.1, fora da faixa usada por `get-uri` e sem atualização compatível validada. | Não alcançável pelo fluxo atual: o caminho é `get-uri` → `proxy-agent` → utilitários de download do browser; nenhum proxy está configurado e o bot não chama essa cadeia. |
| [GHSA-mrhx-c7xc-m3w5](https://github.com/advisories/GHSA-mrhx-c7xc-m3w5) | `zod` 3.25.76 | Esgotamento de memória ao validar coleções enormes; sem correção na linha Zod 3 que `chromium-bidi` declara suportar. | Não alcançável no fluxo atual: `zod` é usado pelo parser WebDriver BiDi. O bot lança Chromium com Puppeteer; não define protocolo BiDi. A documentação oficial confirma CDP como protocolo padrão para Chrome. |

Os avisos de `@puppeteer/browsers`, `puppeteer`, `puppeteer-core`, `whatsapp-web.js`, `chromium-bidi`, `get-uri`, `proxy-agent` e `pac-proxy-agent` são propagações desses três advisories no grafo, não falhas independentes descobertas nesses pacotes. Alterar Puppeteer/WhatsApp, trocar o protocolo ou remover suporte de proxy/instalação exigiria uma atualização de maior risco e não foi feito.

### Candidato revisado e validação

- Commit de dependências: `84609cf258f11db751067afc5839db50c756ba5b`.
- Content-tree: `fff6f5d0d80a7ad442705bd81202c02a6eb521a2`.
- Tag local AMD64: `odisseu-node:ai-dynamic-security-84609cf-amd64`.
- Image ID local: `sha256:9744eeca39d1b89d1fee9393c94c1a1ea3c0950c5aede19aaed3e96666b9b061`.
- Pacotes dentro da imagem conferidos depois do build. `npm audit --omit=dev`: zero críticos, zero moderados e 11 registros altos no grafo; esses 11 correspondem a quatro advisories raiz em três pacotes sem caminho compatível descritos acima e suas dependências ascendentes. No estágio de build, o npm também lista dependências de desenvolvimento, que não entram na imagem final.
- Esta auditoria cobre o grafo npm incluído no runtime e as configurações efetivas relevantes. Não foi executado scanner de CVEs do sistema operacional/Chromium da imagem; se os “14 altos” vierem de um relatório de scanner OCI que inclui pacotes Debian, esse relatório ainda precisa de triagem separada.
- `npm test`: 232 testes, 1.391 asserções, 0 falhas e 0 erros. `npm run build`: aprovado, 106 arquivos, 51 compilados, 716 avisos preexistentes de inferência/depreciação. `npm run test:ai`: 44/44. O smoke de volume Docker descartável passou gravando em um container e lendo após criar outro; o volume de teste foi removido.
- O build Docker AMD64 passou. Image ID e labels registram commit e content-tree acima. O candidato antigo `ac0a4d…` foi preservado localmente e não foi sobrescrito. Nenhum tarball foi exportado, portanto não há checksum de tarball.

### Decisão

**CONDITIONAL — a imagem original não deve ser promovida porque continha `sharp` vulnerável em um caminho usado para imagens; o candidato revisado corrige esse pacote e não tem caminho de exploração demonstrado para os avisos restantes sob a configuração observada.** A aceitação explícita do risco residual dos quatro advisories raiz não alcançáveis ainda é necessária. Os avisos restantes requerem caminhos de instalação de browser, proxy FTP ou WebDriver BiDi que não são utilizados pela configuração A6 verificada. Se qualquer operação futura habilitar download de browser em runtime, proxy FTP ou BiDi, esta avaliação deixa de valer e deve ser refeita. A análise de CVEs da camada OS/Chromium também é pendente se estiver incluída na contagem externa de 14. A comparação com produção foi somente de leitura; nenhuma configuração, imagem, container ou serviço foi alterado, e não houve deploy.

### Complemento de fechamento operacional — 2026-11-07

- Proveniência confirmada novamente: commit `84609cf258f11db751067afc5839db50c756ba5b`, árvore `fff6f5d0d80a7ad442705bd81202c02a6eb521a2`, Image ID `sha256:9744eeca39d1b89d1fee9393c94c1a1ea3c0950c5aede19aaed3e96666b9b061`. Labels da imagem correspondem ao commit/árvore; o SHA-256 do `package-lock.json` embarcado coincide com o lockfile do commit; o `target/main.js` contém os comandos e a imagem inclui o store persistente. `.env`, `.git`, dados e diretórios de sessão estão excluídos do contexto Docker. Não há variáveis com nomes de segredo na configuração de ambiente da imagem.
- O diff da funcionalidade contra a base contém apenas Compose de Node, documentação, dependências, cliente/gerador/store de conteúdo, integração do router e testes; a correção de segurança posterior toca somente `package-lock.json`. Nenhum código de saves, economia, ginásios, raids ou eventos foi alterado por esses commits.
- Consulta GitHub: o commit não foi encontrado (API 422) e a busca de branch de homologação retornou vazia. O `origin` do clone aponta para o workspace local, não para GitHub. O `gh` local está sem autenticação válida; portanto `ODISSEU_PRODUCTION_DEPLOY_HOLD` e o estado atual do workflow não foram verificados nesta execução. Para revisão remota, o commit precisa ser publicado em uma branch de homologação autorizada; não foi publicado.
- Scanner OCI: Docker Scout v1.26 está instalado, mas o scan foi bloqueado pela revisão automática porque enviaria SBOM/metadados da imagem privada a serviço externo. Não havia Trivy, Grype, Syft ou `osv-scanner` local/offline instalado. Não foi executado outro caminho de egress. Assim, CVEs de Debian/Chromium/Node sistema continuam sem triagem nesta entrega; o audit npm anterior não substitui essa análise.
- Testes já concluídos após a correção do lockfile permanecem válidos: 232 testes/1.391 asserções, build aprovado, 44/44 testes IA e persistência entre containers. Nenhum código ou dependência mudou depois desses testes; não foram repetidos.

**Decisão operacional: NO-GO até concluir scanner OCI offline ou aprovar explicitamente o envio dos metadados da imagem ao Docker Scout.** Nenhuma vulnerabilidade nova de sistema foi confirmada, mas não é possível satisfazer o gate de risco crítico/alto novo sem esse scan. O hold do GitHub também está UNVERIFIED nesta execução por falta de autenticação/leitura de configurações; isso impede declarar pronto um plano operacional atualizado. Não houve deploy, backup, alteração de flags ou mudança em serviços.

### Complemento final de scanner OCI e verificação GitHub — 2026-11-07

Esta seção substitui a pendência de scanner registrada acima. O scanner local foi instalado por Homebrew e executado sem Docker Scout/SaaS, sem envio da imagem/SBOM/metadados. A base pública Trivy foi baixada de `mirror.gcr.io/aquasec/trivy-db:2` e usada localmente.

#### Proveniência e método

- Scanner: Trivy `0.76.2`; base `UpdatedAt=2026-11-07T12:52:01Z`, baixada às `2026-11-07T14:46:20Z`; scans às `14:46–14:47Z`.
- Comando: `trivy image --skip-db-update --skip-java-db-update --scanners vuln --list-all-pkgs --format json`, cache em `/private/tmp/trivy-a6-cache`.
- Candidata: Image ID completo `sha256:9744eeca39d1b89d1fee9393c94c1a1ea3c0950c5aede19aaed3e96666b9b061`, `linux/amd64`; labels confirmam commit `84609cf258f11db751067afc5839db50c756ba5b` e árvore `fff6f5d0d80a7ad442705bd81202c02a6eb521a2`.
- Comparação read-only com a imagem ativa localmente disponível, `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`, também `linux/amd64`.
- Relatórios JSON completos ficaram em `/private/tmp/trivy-a6-candidate.json` e `/private/tmp/trivy-a6-production.json`; nenhum arquivo ou imagem foi enviado para fora.

#### Resultado comparativo

| Camada | Imagem ativa | Candidata | Diferença atribuível à candidata |
| --- | ---: | ---: | --- |
| Debian 12.15, pacotes inventariados | 265 | 265 | Inventário idêntico; SHA-256 do inventário `9d9fa442fa466412d8146680679c0493fc16fd5817f97a1377f3607607a0f385`. |
| Debian, registros Trivy | 12 críticos, 1.010 altos, 421 médios, 347 baixos, 81 sem severidade | Idêntico | Zero achados novos; 985 advisory IDs distintos, com registros repetidos por pacote/binário e potencial falso positivo que requer triagem. A contagem bruta não foi usada isoladamente como aprovação ou reprovação. |
| npm, registros Trivy | 42: 21 altos, 20 médios, 1 baixo | 26: 13 altos, 12 médios, 1 baixo | Zero achados novos; 16 registros da imagem ativa desapareceram (8 altos, 8 médios), principalmente após corrigir `adm-zip`, `js-yaml` e `sharp`. |

`sharp` está em `0.35.5` e `@img/sharp-libvips-linux-x64` em `1.3.4`; Trivy não reportou CVE alto/crítico para essas versões. `libwebp7`, `libjpeg62-turbo` e `libpng16-16` também não tiveram achados altos/críticos neste scan. Persistem advisories npm altos herdados, já detalhados na seção anterior (`basic-ftp`, `extract-zip` e dependências de tooling); eles não foram introduzidos por esta imagem. Não foram aceitos como impossíveis de explorar apenas por ausência em testes.

#### Bloqueios concretos da imagem

Os achados abaixo existem tanto na produção quanto na candidata, portanto não são regressões desta mudança, mas continuam no artefato que seria implantado:

| Componente observado | Achado e alcance avaliado | Correção publicada / decisão |
| --- | --- | --- |
| Debian Chromium `155.0.8059.79-1~deb12u1` | Trivy marca `CVE-2026-118333` e `CVE-2026-118337` como críticos. São falhas acionáveis por HTML elaborado remotamente (vídeo/origin policy e Forms). Chromium é executado pelo bot para WhatsApp Web; a entrada remota torna o caminho plausível, então testes funcionais não demonstram inalcançabilidade. | Debian Bookworm security lista Chromium `156.0.8078.79-1~deb12u1` como corrigido. **Bloqueante até atualizar e revalidar a imagem** ou obter aceitação explícita do risco pelo responsável. [Debian DLA-4882-1](https://security-tracker.debian.org/tracker/DLA-4882-1), [Google Chrome release](https://chromereleases.googleblog.com/2026/10/stable-channel-update-for-desktop_0562499947.html). |
| Node `22.23.3` embutido | O Node.js oficial descreve `CVE-2026-111497`: decoder Brotli com escrita fora dos limites, podendo causar crash, disclosure ou execução remota se a aplicação descomprimir stream Brotli controlado. A versão `22.23.4` contém a correção. O binário Node não liga dinamicamente ao `libbrotli` do sistema; o risco do runtime embutido é separado. O scan de pacotes não substitui a verificação da versão Node. | Atualizar o Node para `22.23.4` ou posterior corrigido e validar o runtime. **Bloqueante** até correção ou aceitação explícita. [Aviso oficial Node.js](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases). |
| `libbrotli1` Debian `1.0.9-2+b6` | Trivy marca `CVE-2026-111497` como crítico. A inspeção local read-only confirmou que Chromium liga a `libbrotlidec.so.1`; portanto a biblioteca do sistema é usada no caminho do navegador e streams compactados chegam de conteúdo remoto. | Debian Bookworm security corrigiu em `1.0.9-2+deb12u1`. **Bloqueante** até atualização do pacote/runtime e nova verificação. [Debian DLA-4855-1](https://security-tracker.debian.org/tracker/DLA-4855-1). |
| GLib `2.74.6-2+deb12u9` | Trivy classifica `CVE-2026-58016` como crítico, mas o rastreador Debian descreve um DoS por XML de introspecção D-Bus, marca o problema como menor/postergado em Bookworm e registra ausência de correção upstream. Não foi tratado como RCE nem como explorável pela simples presença do pacote. | Sem correção Bookworm indicada no tracker. Requer triagem/aceitação de risco separada; não altera os três bloqueios acima. [Debian CVE-2026-58016](https://security-tracker.debian.org/tracker/CVE-2026-58016). |
| `zlib1g` `1:1.2.13.dfsg-1`, CVE-2023-45853 | Trivy sinaliza crítico, mas o Debian Security Tracker explica que Bookworm não compila nem distribui o componente MiniZip afetado; issue é ignorada para esse pacote binário. | Classificado como falso positivo/não afetado neste pacote Bookworm, não como bloqueio. [Debian CVE-2023-45853](https://security-tracker.debian.org/tracker/CVE-2023-45853). |

Os findings de Chromium são duplicados em `chromium`/`chromium-common`; contagens por pacote não representam CVEs independentes. A varredura também identificou outras famílias OS críticas/altas preexistentes. O inventário integral está nos JSON locais acima e requer revisão por advisory antes de qualquer nova imagem; não se inferiu segurança absoluta pelo resultado de testes.

#### GitHub e contenção de deploy

- A API GitHub read-only confirmou `master` em `efed6e622a59d8fe5f13a0e9f99f1a20177f3158`. O workflow `.github/workflows/deploy.yml` exige `vars.ODISSEU_PRODUCTION_DEPLOY_HOLD == 'false'` para deploy/rollback e restringe o job ao `master`; o push também exige `AUTO_DEPLOY_ENABLED == 'true'`. A ausência da variável bloqueia conforme o código.
- A pasta de workflows contém somente `deploy.yml` e `pokemon-storage-ci.yml`; o segundo roda testes/build e não faz deploy.
- Consultas atuais por runs `in_progress`, `queued`, `waiting` e `pending` retornaram zero.
- A integração GitHub disponível não permite ler repository variables nem o estado administrativo do workflow; `gh auth status` confirma que a sessão local está inválida. Assim, `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` e `CI/CD=disabled_manually` permanecem **não verificados tecnicamente nesta execução**; a confirmação administrativa anterior é declarada, mas não foi tratada como nova evidência técnica. A leitura pública do workflow não permite confirmar que esteja desabilitado.
- O commit local da candidata continua não encontrado no GitHub; isso não prova falha do hold. Não publiquei commit ou imagem.

Para verificação pelo operador, sem compartilhar PAT ou segredo com o assistente: autenticar `gh` localmente pelo fluxo interativo aprovado (`gh auth login --hostname github.com`) usando conta com leitura de Actions e variables; então executar `gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo caioclavico/zapbot`, `gh workflow list --all --repo caioclavico/zapbot` e listar runs em estados ativos. Confirmar o valor `true`, `deploy.yml` em `disabled_manually` e nenhum run ativo. Não alterar o valor nem habilitar workflow.

#### Decisão

**NO-GO OPERACIONAL.** A imagem candidata não introduz vulnerabilidades em relação à imagem ativa e melhora o grafo npm, mas ainda contém vulnerabilidades críticas/altas preexistentes e plausivelmente alcançáveis em Chromium e Brotli, com versões corrigidas disponíveis. É necessário gerar e homologar imagem atualizada (sem modificar o código de IA ou os domínios do jogo) ou obter aceitação formal explícita do risco pelo responsável; nenhuma aceitação foi feita nesta auditoria. Separadamente, hold e estado disabled do workflow ainda precisam de confirmação técnica read-only. O estado do commit ausente do GitHub segue pendente para rastreabilidade/revisão remota, sem publicação nesta tarefa.

Não foram repetidos testes funcionais/build porque não houve mudança de código ou dependências nesta execução. Não houve deploy, alteração de workflow/variáveis, saves, Datomic, grupos, ginásios, recompensas, raids, eventos ou tokens.

## Fechamento do runtime e imagem final — 2026-11-07

Esta seção substitui a decisão sobre Chromium/Node/Brotli da auditoria acima. A avaliação é local; produção, GitHub settings e serviços live não foram alterados.

### Correção e proveniência

- Clone isolado: `/private/tmp/zapbot-ai-content-rc`; branch `a6-save-slots`.
- Commit final: `8397ab795be0ec261240c7c7d3e869d3f5eb9f38`.
- Content-tree: `76eba3535f93d51ed421b4e7142c71ecb7f81c3b`.
- Imagem AMD64: `odisseu-node:ai-dynamic-final-8397ab7-amd64`.
- Image ID / digest local: `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`.
- SHA-256 do `package-lock.json`: `e0ee540ce5ea70fcb8ba10d876e296240e3afc937e59a1bacb740afac4601128`.
- SHA-256 do bundle `target/main.js` dentro da imagem final: `4f00618d550fe66d6b429b83acb19258df9aeb73584833c1909de7d0ea6ccdea`; confirmado igual ao artefato extraído do mesmo estágio `build` do Dockerfile.
- O build não exportou tarball; não há checksum de tarball.
- Alteração final de empacotamento: copiar `assets/` completo. O runtime anterior copiava só `abujamra.png`, embora os comandos Pokémon carreguem também imagens do Centro Pokémon, Loja, Enfermeira Joy, Professor Carvalho e Ash. A imagem final contém os sete PNGs. Não foram adicionados secrets a labels, ENV ou layers.

Versões confirmadas na imagem: Node `22.23.4` (`linux/amd64`), Google Chrome Stable `157.0.8103.38-1`, `libbrotli1` `1.0.9-2+deb12u1`, `whatsapp-web.js` `1.34.6`, Puppeteer `24.38.0`, Sharp `0.35.5` / libvips `8.18.7`, `adm-zip` `0.6.1`, `ip-address` `10.7.6`, `js-yaml` `4.3.2`, `@img/sharp-libvips-linux-x64` `1.3.4`. As quatro dependências npm corrigidas permanecem nas versões acima; `basic-ftp` `5.3.1` e `extract-zip` `2.0.1` continuam presentes sem fix compatível identificado.

Node 22.23.4 corrige o decoder Brotli vulnerável identificado no aviso oficial Node.js; Debian marca `1.0.9-2+deb12u1` como corrigido para o Brotli do sistema. Chrome 157.0.8103.38 é o Stable Linux publicado em 3 de novembro de 2026. [Aviso Node.js](https://nodejs.org/en/blog/vulnerability/october-2026-security-releases), [correção Debian Brotli](https://security-tracker.debian.org/tracker/DLA-4855-1), [Chrome Stable 157](https://chromereleases.googleblog.com/2026/11/stable-channel-update-for-desktop.html).

### Testes desta imagem

- `npm run build` no builder Node 22.23.4: aprovado; patch de mídia do WhatsApp verificado; bundle ClojureScript compilado (106 arquivos, 51 compilados; 716 warnings de inferência/depreciação já conhecidos).
- `npm test`: **232 testes, 1.391 asserções, zero falhas e zero erros**. Os testes de recursos Node passaram 64/64; o bundle de teste compilou 158 arquivos. A execução do teste usou rede desativada.
- `npm run test:http`: **281/282**. Uma falha conhecida e não relacionada: `playable-save-activation.test.cjs`, “real eligibility gate blocks each impediment and isolates the peer”, `Missing expected rejection`. O patch desta fase é apenas Dockerfile/assets; nenhum código dessa funcionalidade ou teste foi modificado. A falha permanece registrada, não foi ocultada nem teve expectativa alterada.
- Harness Docker local: Chromium real em fixture encerrou corretamente com SIGTERM/SIGINT; deploy simulado, falha de health, rollback automático/manual e persistência passaram.
- Volume Docker temporário: um container gravou o histórico e um segundo container, criado separadamente, leu o mesmo registro. O volume exclusivo de teste foi removido.
- Smoke do runtime final: Node/Chrome corretos, sete PNGs presentes, npm/npx ausentes do runtime. A inspeção dinâmica do Chrome mostrou GLib e zlib entre as ligações observadas; não mostrou libcurl nem libldap nos executáveis `chrome`, `chrome_crashpad_handler` e bibliotecas `.so` inspecionadas. Código `src`/`scripts/lib` não invoca `wget`, `spawn`, `execFile` ou `child_process`.

### Trivy local e comparação

Scanner Trivy `0.76.2`, varredura local do Image ID exato com scanner `vuln`; sem serviço SaaS e sem upload da imagem/SBOM. A base local válida foi atualizada em `2026-11-07T12:52:01Z` e o scan ocorreu em `2026-11-07T15:56Z`. JSON integral: `/private/tmp/trivy-ai-runtime-final-8397ab7.json`.

| Imagem | CRITICAL | HIGH | MEDIUM | LOW | UNKNOWN |
| --- | ---: | ---: | ---: | ---: | ---: |
| Produção observada (`1d00dd01…`) | 12 | 1.031 | 441 | 348 | 81 |
| Candidata anterior (`9744eeca…`) | 12 | 1.023 | 433 | 348 | 81 |
| Final (`607fcd26…`) | 4 | 87 | 219 | 217 | 4 |

São registros de advisories por pacote, com duplicação entre dependências; as contagens não equivalem a CVEs únicos. No final, os quatro registros críticos e 84 dos 87 High são do sistema operacional; os três High restantes são npm. A redução da contagem não foi usada isoladamente para aprovação.

Os alvos anteriores `CVE-2026-118333` e `CVE-2026-118337` não aparecem na varredura final. `CVE-2026-111497` não aparece no scan como vulnerabilidade da imagem e as versões Node e Brotli foram verificadas diretamente contra as versões corrigidas oficiais. Nenhum finding de Chrome apareceu no resultado final do Trivy.

Oito IDs High não estavam na imagem de produção comparada e aparecem no fechamento Chrome/dependências do sistema: `libcurl4` (`CVE-2026-12064`, `CVE-2026-6276`, `CVE-2026-8286`, `CVE-2026-8458`, `CVE-2026-8927`), `libldap-2.5-0` (`CVE-2023-2953`) e `wget` (`CVE-2026-58471`, `CVE-2026-58472`). O Trivy não informa versão corrigida para esses registros. A inspeção de ligações e chamadas da aplicação indica alcance reduzido nos caminhos normais documentados, mas não prova impossibilidade de exploração; os pacotes estão presentes na imagem e seguem como risco residual para revisão/aceitação do responsável.

Os três registros Critical preexistentes são GLib `CVE-2026-58016`, SQLite `CVE-2025-7458` e libxml2 `CVE-2026-6653`; permanecem também no scan de produção. O tracker Debian descreve GLib como problema de introspecção D-Bus sem correção Bookworm indicada; SQLite exige caminho SQL arbitrário segundo a análise já registrada; libxml2 corresponde a parsing XML vulnerável, sem fix Bookworm reportado no scan. O ambiente Compose não monta socket D-Bus, mas GLib é carregada pelo Chrome; por isso não classifico esse advisory como impossível de alcançar. O quarto Critical, zlib `CVE-2023-45853`, é falso positivo para o pacote Debian Bookworm conforme o tracker, pois o componente MiniZip afetado não é compilado/distribuído nesse binário. Permanecem ainda três findings npm High (`basic-ftp` e dois `extract-zip`), presentes também na imagem anterior; download/extração de browser pelo Puppeteer estão desativados e a aplicação usa Chrome do sistema, mas a presença dos pacotes não equivale a prova absoluta de inalcançabilidade.

O Chromium segue com os argumentos preexistentes `--no-sandbox` e `--disable-setuid-sandbox`; esta tarefa não os introduziu nem os alterou. O sandbox desativado aumenta o impacto potencial de falhas no navegador e é risco operacional residual que deve ser considerado explicitamente.

### GitHub e decisão

Não foi possível revalidar tecnicamente os controles administrativos do GitHub nesta execução. `gh auth status` mostra credencial local inválida; o fluxo web anterior não conectou por indisponibilidade de rede. A integração disponível não expõe repository variables nem estado administrativo do workflow. Consultas anteriores de runs retornaram zero em `in_progress`, `queued`, `waiting` e `pending`, mas isso não comprova o estado atual. Portanto `ODISSEU_PRODUCTION_DEPLOY_HOLD=true`, `CI/CD=disabled_manually` e ausência atual de execução concorrente ficam **UNVERIFIED**. A confirmação administrativa anterior é registrada somente como declaração.

**NO-GO OPERACIONAL.** O runtime corrigiu os CVEs de Chrome/Node/Brotli alvo, o build e `npm test` passam, e o pacote inclui os assets Pokémon. Antes de autorizar o Node-only cutover, o responsável ainda precisa: (1) autenticar `gh` em ambiente de operador autorizado e confirmar hold `true`, workflow de deploy `disabled_manually` e zero runs ativos; (2) revisar/aceitar formalmente os advisories residuais acima, em especial o GLib Critical carregado pelo Chrome, os oito High adicionais e o risco conhecido de Chromium sem sandbox; (3) resolver ou aceitar a falha HTTP já conhecida se ela for parte dos gates exigidos. Instruções administrativas, sem compartilhar tokens: `gh auth login --hostname github.com --git-protocol https --web`, `gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo caioclavico/zapbot`, `gh workflow list --all --repo caioclavico/zapbot` e inspeção de runs ativos. Não alterar o hold nem habilitar workflows como parte dessa verificação.

### Cutover Node-only proposto (não executado)

1. Confirmar os gates GitHub e a janela exclusiva; registrar imagem ativa, flags e estado de health.
2. Confirmar o mount persistente `/app/data` e fazer cópia protegida do `ai-content-history.json` existente, se houver, sem tocar na sessão WhatsApp.
3. Acionar graceful drain Node, aguardar confirmação e recriar somente Node com `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`.
4. Preservar mounts `/app/data` e `.wwebjs_auth`; não recriar JVM/Datomic, não executar migration.
5. Confirmar health Node, WhatsApp READY, persistência do histórico e comandos IA em uma janela autorizada; verificar flags do jogo sem alterá-las.
6. Se houver falha, rollback somente da imagem/configuração Node, mantendo volume e dados atuais; nunca restaurar histórico antigo sobre gravações novas.

Nenhum deploy, publicação, export de imagem, alteração de produção, flags, saves, Datomic, sessão, raids, eventos, tokens, CI/CD ou deploy hold foi realizado.

### Homologação final do sandbox Chromium — 2026-11-07

Esta avaliação prevalece sobre descrições anteriores de sandbox nesta documentação. Imagem examinada: `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6` (`linux/amd64`, Chrome 157.0.8103.38). Não alterei o runtime, Dockerfile, dependências nem imagem.

#### Resultado do sandbox em container descartável

- A imagem inicia como root (`Config.User` vazio; `id` no container retornou UID 0). O teste de browser executou explicitamente como UID 1000, sem sessão WhatsApp real, sem Docker socket e sem rede.
- Controles do container: `--cap-drop=ALL`, `no-new-privileges=true`, filesystem read-only e apenas `/tmp`, home e volumes nomeados descartáveis graváveis. Não foram usados `--privileged`, `SYS_ADMIN`, `seccomp=unconfined` ou capacidades adicionadas.
- O kernel da VM de teste suporta user namespaces (`user.max_user_namespaces=31879`; `unshare -Ur id` passou). O seccomp padrão do Docker bloqueou as syscalls necessárias. Um perfil experimental derivado do default Moby/Docker 29.8.2, adicionando permissões para as famílias `clone`, `unshare` e `setns`, permitiu a camada de namespace do Chromium. A variante ampla não restringia adequadamente flags desses syscalls e foi somente diagnóstica; não está incluída nem conectada ao Compose. Uma variante por argumentos, com `chroot`, não passou o startup completo e foi removida. Não há perfil customizado final para distribuir; seria necessário reduzir/justificar cada permissão e provar novamente as camadas em `chrome://sandbox`.
- Puppeteer iniciou Chrome sem `--no-sandbox`; CDP respondeu, uma página local `data:` carregou, os processos não tinham capabilities, browser encerrou limpo e o processo aceitou SIGTERM com saída 0. O renderer ficou em namespace de PID/rede separado. O volume descartável do perfil e o sentinel de `/app/data` foram lidos com sucesso por um segundo container independente.
- A checagem autoritativa do Chrome em `chrome://sandbox` indicou: namespaces PID/rede **Yes**, Seccomp-BPF **No**, TSYNC **No**, Yama **No**, e a mensagem **“You are NOT adequately sandboxed.”** `/proc` mostrou o filtro seccomp externo do container, mas não um filtro BPF de renderer adicional do Chromium. Portanto, a prova de CDP/Puppeteer não equivale à prova do sandbox completo: somente a camada de namespace foi demonstrada. Chromium documenta Seccomp-BPF como camada de redução de superfície do kernel; a imagem não a ativou neste ensaio. [Documentação de sandbox Linux do Chromium](https://chromium.googlesource.com/chromium/src/%2B/2ff9468fb9b76df5480218e448a8c92ec427496c/docs/linux/sandboxing.md).

**Decisão do gate Chromium: FAIL.** Não existe perfil seccomp customizado homologado para distribuição. A ausência de `--no-sandbox` por si só não satisfaz o requisito. Não modificar produção nem usar este resultado como autorização para deploy.

#### Dependências e advisories residuais

Scan local do ID exato com Trivy `0.76.2`; DB local atualizado em `2026-11-07T12:52:01Z` e baixado de mirror público às `2026-11-07T17:01:13Z`. O scanner analisou o filesystem local; nenhum serviço recebeu a imagem ou SBOM. Resultado: Debian 12.15 — 4 Critical, 84 High, 219 Medium, 217 Low, 4 Unknown; npm — 3 High. Comparação local com a imagem Node anterior usou a mesma base do Trivy.

O diff do scan contém nove registros High adicionais: sete correspondem a CVEs reais em pacotes instalados (quatro em `libcurl4`, um em `libldap-2.5-0` e dois em `wget`); dois são associações não aplicáveis ao conteúdo instalado: `CVE-2026-12064` é defeito da CLI `curl`, que não está instalada, e `CVE-2026-9538` diz respeito a `Archive::Tar`, cujo módulo `Archive/Tar.pm` não existe na imagem. A inspeção confirmou que Chrome não liga diretamente a libcurl/libldap nos binários examinados e que a aplicação não invoca `wget`/`curl` nem subprocessos nos caminhos analisados. Porém, libcurl, libldap e Wget constam da imagem; “não chamado nos caminhos examinados” reduz alcançabilidade, mas não prova inalcançabilidade. O Trivy não apontou versão corrigida para esses pacotes Debian Bookworm. Os três findings críticos reais restantes (GLib, SQLite e libxml2) são preexistentes em relação à imagem anterior; zlib é não afetado pelo binário Bookworm conforme o tracker Debian. Os 3 High npm (`basic-ftp` e `extract-zip`) também são herdados. Os achados do scanner e advisories foram revistos nos registros locais e fontes dos fornecedores; exemplo: os advisories upstream de libcurl especificam pré-condições de reutilização de handle/conexão e têm severidades próprias menores do que o rótulo High do scanner. [curl 12064](https://curl.se/docs/CVE-2026-12064.html), [curl 6276](https://curl.se/docs/CVE-2026-6276.html), [curl 8286](https://curl.se/docs/CVE-2026-8286.html), [curl 8458](https://curl.se/docs/CVE-2026-8458.html), [curl 8927](https://curl.se/docs/CVE-2026-8927.html), [Debian wget 58471](https://security-tracker.debian.org/tracker/CVE-2026-58471), [Debian OpenLDAP 2953](https://security-tracker.debian.org/tracker/CVE-2023-2953).

Não removi pacotes: `wget` e uma alternativa de `libcurl4` são dependências declaradas do pacote Google Chrome instalado, `libldap` é dependência de libcurl, e a simulação de purge removeria também `google-chrome-stable`. Removê-los sem reconstrução e revalidar o Chrome seria inseguro. Não executei `apt upgrade` indiscriminado nem suprimi advisories. Nenhum aceite administrativo de risco foi presumido.

#### Regressões e testes finais

| Verificação | Resultado |
| --- | --- |
| Sandbox Chromium 157 / namespaces / CDP / Puppeteer / perfil descartável | Browser inicializou e encerrou; persistência entre dois containers confirmada. **Sandbox completo reprovado** por Seccomp-BPF desligado no renderer. |
| Harness de encerramento WhatsApp simulado | `SIGTERM`, exit code 0, browser encerrado e sessão de teste preservada. Não conectou ao WhatsApp real. |
| `npm test` | **232 testes, 1.391 asserções, zero falhas e zero erros.** |
| `npm run test:http` após corrigir a fixture de captura | **282/282 PASS**. A correção dá dano positivo à fixture que espera captura pendente; não altera regra de produção. |
| `npm run build` | Aprovado; 106 arquivos, 26 compilados; warnings de inferência/depreciação, sem erro. |
| Trivy da imagem | Completo com DB local válido; resumo acima. JSON: `/private/tmp/trivy-ai-dynamic-607fcd-sandbox-review.json`. |

O primeiro `npm test` sem autorização de loopback falhou por `EPERM` ao abrir servidor local; repetido no ambiente autorizado, passou. O `test:http` final também foi executado com permissão para loopback. O perfil persistente e o sentinel existiam apenas nos volumes descartáveis nomeados `codex-chromium-sandbox-profile-20261107` e `codex-chromium-sandbox-data-20261107`; não houve volume de produção. Os dois volumes de teste foram removidos após a verificação.

#### GitHub e decisão final

Não tentei contornar a autenticação GitHub inválida. Hold, workflow `disabled_manually` e ausência de runs ativos/na fila permanecem **UNVERIFIED nesta sessão**; os checks devem ser feitos imediatamente antes de qualquer deploy pelo operador autorizado:

```sh
gh auth status
gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo caioclavico/zapbot
gh workflow list --all --repo caioclavico/zapbot
gh run list --repo caioclavico/zapbot --status queued --limit 100
gh run list --repo caioclavico/zapbot --status in_progress --limit 100
gh run list --repo caioclavico/zapbot --status waiting --limit 100
gh run list --repo caioclavico/zapbot --status pending --limit 100
```

**NO-GO OPERACIONAL.** Bloqueadores concretos: (1) renderer Chromium continua sem Seccomp-BPF, e o próprio `chrome://sandbox` reprova o isolamento; (2) sete advisories High reais em dependências novas/instaladas não foram corrigidos nem tiveram risco formalmente aceito; (3) os controles atuais do GitHub não foram verificados. O sandbox deve ser corrigido com perfil mínimo comprovado e novamente avaliado no Chrome, ou a imagem não atende o requisito desta tarefa. Os advisories precisam de correção compatível ou aceitação explícita do responsável; pacote presente sem caminho observado não prova risco zero. Não alterei Dockerfile, dependências, runtime, perfil, candidato ou imagem; o Image ID permanece `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`. Nenhum deploy, publicação, alteração de produção ou dados do jogo foi realizado.

### Fechamento de segurança solicitado — 2026-11-07

#### Oito findings High novos

Scan analisado: Trivy 0.76.2, base local `2026-11-07T12:52:01Z`, JSON `/private/tmp/trivy-ai-runtime-final-8397ab7.json`, imagem `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`. Os oito findings são novos em relação à imagem anterior porque a troca do Chromium Debian pelo Google Chrome Stable acrescentou a dependência de `libcurl4`, `libldap-2.5-0` e `wget`. Isso é uma diferença real de pacotes, não só de heurística/versão do scanner. Para nenhum deles o JSON Trivy fornece `FixedVersion`.

| CVE / pacote instalado | Evidência e severidade efetiva | Mitigação / alcance observado | Decisão técnica | Aceitação administrativa |
| --- | --- | --- | --- | --- |
| `CVE-2026-12064` — `libcurl4 7.88.1-10+deb12u15` | Trivy High. Advisory curl: falha **somente no executável curl**, não em libcurl; fix upstream curl 8.21.0. [Advisory](https://curl.se/docs/CVE-2026-12064.html) | Finding associado à biblioteca, mas a falha é na CLI ao combinar `--proto-default` e SFTP/SCP. O app não invoca `curl` nem subprocessos. | Falso positivo para `libcurl4`; não é bloqueador isolado. | Não requerida para esse CVE, salvo confirmação posterior de que a CLI está incluída e invocável.
| `CVE-2026-6276` — `libcurl4 7.88.1-10+deb12u15` | Trivy High; curl upstream Low, fix 8.20.0; Debian Bookworm lista vulnerável/sem DSA. [curl](https://curl.se/docs/CVE-2026-6276.html), [Debian](https://security-tracker.debian.org/tracker/CVE-2026-6276) | Exige consumidor libcurl reutilizando easy handle após `Host:` customizado. App não tem consumidor libcurl; Chrome não aparece ligado à biblioteca na inspeção documentada. | Vulnerabilidade real no pacote; baixa alcançabilidade nos fluxos examinados, não risco zero. | **Sim** se a imagem for mantida sem correção.
| `CVE-2026-8286` — `libcurl4 7.88.1-10+deb12u15` | Trivy High; curl upstream Low; fix upstream 8.21.0. [Advisory](https://curl.se/docs/CVE-2026-8286.html) | Exige reutilização de conexão durante STARTTLS com configuração TLS incompatível. App não chama libcurl e Chrome não aparece ligado a ela. | Código afetado não alcançado no fluxo examinado; pacote vulnerável segundo Trivy, sem `FixedVersion` indicado. | **Sim** se mantido sem correção compatível.
| `CVE-2026-8458` — `libcurl4 7.88.1-10+deb12u15` | Trivy High; curl upstream Low; fix upstream 8.21.0. [Advisory](https://curl.se/docs/CVE-2026-8458.html) | Exige autenticação Negotiate e reutilização entre serviços. Nenhum fluxo do app usa libcurl/Negotiate. | Sem caminho da aplicação identificado; pacote presente e advisory real. | **Sim** se mantido sem correção compatível.
| `CVE-2026-8927` — `libcurl4 7.88.1-10+deb12u15` | Trivy High; curl upstream Medium; fix upstream 8.21.0. [Advisory](https://curl.se/docs/CVE-2026-8927.html) | Exige handle reutilizado, proxies por ambiente e autenticação Digest. Nenhum desses usos foi encontrado; Chrome não aparece ligado à libcurl. | Baixa alcançabilidade observada; vulnerabilidade real da biblioteca, sem patch Debian Bookworm confirmado nesta revisão. | **Sim** se mantido sem correção compatível.
| `CVE-2023-2953` — `libldap-2.5-0 2.5.13+dfsg-5` | Trivy High; null dereference em `ber_memalloc_x`; fix upstream 2.5.14/unstable Debian 2.5.16; Bookworm vulnerável sem DSA, classificado minor. [Debian](https://security-tracker.debian.org/tracker/CVE-2023-2953) | Requer uso da lib LDAP; nenhum consumidor identificado e `libldap` não aparece ligado aos binários Chrome inspecionados. | Código instalado sem caminho normal encontrado; risco reduzido, não eliminado. | **Sim** se mantido sem correção compatível.
| `CVE-2026-58471` — `wget 1.21.3-1+deb12u1` | Trivy High; heap overflow por nome de arquivo fornecido pelo servidor; fix upstream commit `c2640fe`, sem versão Debian corrigida; Bookworm postpones como minor. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-58471) | Requer Wget processando resposta maliciosa com nome que acione conversão de charset. App não invoca Wget nem subprocessos nos caminhos examinados. | CVE real no executável instalado; não chamado no fluxo normal documentado. | **Sim** se mantido sem correção compatível.
| `CVE-2026-58472` — `wget 1.21.3-1+deb12u1` | Trivy High; heap overflow ao converter atributo HTML; fix upstream commit `dd692d9`, sem versão Debian corrigida; Bookworm postpones como minor. [Debian](https://security-tracker.debian.org/tracker/CVE-2026-58472) | Requer Wget processando HTML remoto elaborado. App não invoca Wget nem subprocessos nos caminhos examinados. | CVE real no executável instalado; não chamado no fluxo normal documentado. | **Sim** se mantido sem correção compatível.

Os advisories curl ilustram por que a severidade `High` do scanner não equivale automaticamente à severidade efetiva do upstream: os quatro defeitos de conexão/cookie têm classe Low no curl, e o defeito de proxy tem classe Medium. Ainda assim, os três pacotes são novos e estão presentes na imagem. Não fiz atualização isolada de libcurl, LDAP ou Wget: isso alteraria dependências do pacote Chrome/Bookworm e precisaria de uma nova imagem, teste de compatibilidade do Chrome e novo scan. Não suprimi findings nem aceitei risco em nome do responsável.

#### Sandbox Chromium

O processo usa os argumentos `--no-sandbox` e `--disable-setuid-sandbox` em `src/zapbot/core.cljs`; não foram adicionados nesta tarefa. A imagem candidata não define `Config.User` e inicia como root; o helper `chrome-sandbox` é root-owned com setuid. Os smokes isolados forçaram UID 1000. Com seccomp padrão, o sandbox setuid falhou com `Operation not permitted`, e user namespaces falharam porque o perfil padrão bloqueia as syscalls necessárias. A documentação Chromium separa user namespaces como camada 1 e Seccomp-BPF como camada 2, e recomenda verificar as camadas efetivas em `chrome://sandbox`; o guia Playwright também recomenda processo não-root e seccomp ajustado para sandbox de Chromium. [Chromium Linux sandbox](https://chromium.googlesource.com/chromium/src/%2B/2ff9468fb9b76df5480218e448a8c92ec427496c/docs/linux/sandboxing.md), [Playwright Docker](https://playwright.dev/docs/docker), [Docker seccomp](https://docs.docker.com/engine/security/seccomp).

Não testei nem adotei `seccomp=unconfined`, `SYS_ADMIN` ou capacidades adicionais. Embora os smokes tenham usado UID 1000 e todas as capabilities removidas, isso não equivale ao sandbox Chromium; a configuração padrão da imagem ainda inicia como root. Permissões do volume autenticado real não foram inspecionadas nesta tarefa. O Chromium recebe páginas do WhatsApp Web e pode processar conteúdo remoto. Portanto, o sandbox incompleto é risco material de defesa em profundidade e aumenta o impacto potencial de falhas do navegador; não considero a imagem segura apenas porque o Chrome está atualizado.

#### Teste HTTP 281/282

A reprodução solicitada foi feita sem modificar o checkout: commit base limpo `a6a983a0af416ba9e077b9b4a05e1432f8906cbd` e candidata antes da correção de fixture, `8397ab795be0ec261240c7c7d3e869d3f5eb9f38`. O arquivo do teste tem o mesmo blob nos dois commits e os arquivos `save-activity-eligibility.cjs` e o teste também não diferem entre a base e a candidata. A falha isolada é idêntica: `real eligibility gate blocks each impediment and isolates the peer` → `Missing expected rejection`, no caso `activity_raid_capture_pending`.

A causa é uma fixture inválida, não uma mudança de regra nem efeito de isolamento: ela representava raide em `vitoria` e janela aberta, mas deixava `participantes.p.dano` ausente. O contrato de raides só cria captura pendente se o jogador tiver causado dano positivo; o domínio e outras fixtures de elegibilidade já codificavam esse requisito. Para corrigir a cobertura sem relaxar a regra, atualizei a fixture para `dano:1` e acrescentei a asserção negativa de que dano zero não cria obrigação de captura. Nenhum código de produção ou save foi alterado. `npm run test:http` após a correção: **282/282 PASS**. A primeira tentativa sob o sandbox da sessão não conseguia bind em loopback (`EPERM`); a execução final com permissão local de loopback passou. O teste permanece preexistente na base; a imagem/runtime não causou a falha.

#### GitHub e decisão

Verificação atual do GitHub não foi concluída. `gh auth status` confirma que a conta local configurada está com token inválido; chamadas read-only a `gh variable get`, `gh workflow list` e `gh run list --status in_progress` falharam por indisponibilidade de conexão com `api.github.com`. Zero runs vistos anteriormente não é evidência atual. Assim, hold, workflow desabilitado e ausência de runs ativos permanecem **UNVERIFIED**; não usei credencial alternativa nem alterei o repositório.

Para o operador verificar, autenticado em seu próprio ambiente e sem compartilhar tokens:

```sh
gh auth status
gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo caioclavico/zapbot
gh workflow list --all --repo caioclavico/zapbot
gh run list --repo caioclavico/zapbot --status queued --limit 100
gh run list --repo caioclavico/zapbot --status in_progress --limit 100
gh run list --repo caioclavico/zapbot --status waiting --limit 100
gh run list --repo caioclavico/zapbot --status pending --limit 100
```

**NO-GO OPERACIONAL.** O teste HTTP foi explicado como erro preexistente da fixture e agora passa após ajuste fundamentado; não há regressão de código Pokémon. O GO continua bloqueado por (1) risco Chromium sem sandbox ainda não mitigado nem aceito pelo responsável, (2) advisories novos High do pacote instalado que exigem revisão/aceitação ou correção segura, e (3) hold/workflow/runs do GitHub não verificados nesta sessão. Não reconstruí a imagem, pois nenhuma alteração de runtime foi aplicada; os únicos arquivos alterados são duas fixtures/testes e esta documentação. Nenhum deploy, alteração de produção, Datomic, save, flag, sessão WhatsApp, raids, eventos, credencial ou workflow ocorreu.

### Decisão consolidada desta execução — 2026-11-07

Esta é a conclusão vigente para a solicitação de homologar o sandbox e fechar a avaliação da imagem `607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`; em caso de divergência, prevalece sobre conclusões anteriores deste histórico.

- **Sandbox:** NO-GO. Chrome iniciou como UID 1000 apenas no container de teste, com CDP/Puppeteer e volume descartável funcionais, mas `chrome://sandbox` confirmou Seccomp-BPF desativado no renderer e declarou “NOT adequately sandboxed”. Não foi homologado um perfil seccomp mínimo.
- **Pacotes:** sete CVEs High reais novos permanecem em `libcurl4`, `libldap` e `wget`; dois registros High adicionais não se aplicam ao conteúdo (falha da CLI curl sem `curl` instalado e módulo `Archive::Tar` ausente). Reachability reduzida não foi tratada como impossibilidade. Purge simples remove também o Chrome; nenhum pacote foi removido ou atualizado.
- **Testes:** `npm test` 232/1.391 sem falhas; `npm run test:http` 282/282; `npm run build` aprovado; smoke Puppeteer/WhatsApp simulado e persistência entre containers passaram, sem sessão ou rede de produção.
- **GitHub:** hold, estado `disabled_manually` e ausência atual de runs continuam UNVERIFIED; o operador deve executar o preflight descrito acima com sua autenticação autorizada.
- **Artefato:** nenhum código de runtime ou dependência mudou; não houve rebuild. O digest da candidata permanece `sha256:607fcd2653d1e38aa78b3c2d389c0e86425b0aa281839e4f1f50179a2229e4b6`.

**Resultado: NO-GO.** Não implantar até que (1) o sandbox de Chromium esteja efetivamente ativo e comprovado em `chrome://sandbox`, (2) os sete advisories tenham correção compatível ou aceitação explícita do responsável, e (3) o operador confirme os gates atuais do GitHub. Nada foi alterado em produção; os volumes e containers descartáveis desta prova foram removidos.

### Atualização de sandbox — 2026-11-07

A exigência (1) foi resolvida tecnicamente em Linux AMD64 nativo. O runtime removeu `--no-sandbox` e `--disable-setuid-sandbox`, a imagem agora usa UID/GID 1000 e o perfil seccomp Docker/Moby recebe somente uma regra filtrada para `clone(CLONE_NEWPID)`. `chrome://sandbox`, `/proc`, Puppeteer/CDP, volumes descartáveis e shutdown simulado confirmaram namespace e Seccomp-BPF completos. Evidências detalhadas estão em [chromium-seccomp-diagnosis.md](chromium-seccomp-diagnosis.md).

Nova candidata para homologação: `odisseu-node:chromium-seccomp-20261107`, Image ID `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`, `linux/amd64`. O SHA-256 do tar usado apenas para transporte de teste foi `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`. Trivy 0.76.2 comparou 531 findings com a candidata anterior: nenhum advisory novo/removido; portanto os sete advisories reais continuam pendentes da decisão já registrada. A sessão persistente real não foi inspecionada nem alterada; uma implantação futura deve validar a propriedade UID 1000 do bind mount sem correção automática.

`npm test` passou com 232 testes/1.399 asserções, `npm run test:http` com 282/282 e build completo aprovado. Os arquivos de Compose e o adaptador de deploy agora selecionam usuário não-root, removem capabilities e aplicam o perfil; conflito com usuário/seccomp/capabilities antigos bloqueia o plano. O arquivo do perfil e a imagem foram exercitados somente em containers temporários na VM; nenhum container A6 ativo foi recriado, reiniciado ou conectado à sessão real.

**Sandbox: GO técnico, ON. Promoção para produção: ainda não aprovada nesta fase.** Permanecem os advisories sem aceitação do responsável e a verificação administrativa do GitHub/hold. Não houve deploy nem alteração de produção.

A prova AMD64 foi encerrada e os recursos temporários foram removidos do host: quatro volumes descartáveis, duas imagens de probe e arquivos temporários. A verificação final mostrou apenas os containers A6 existentes, saudáveis. O tarball candidato foi preservado localmente em `/private/tmp/odisseu-node-chromium-seccomp-20261107.tar` (SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`); nenhum container foi atualizado.

Rastreabilidade do sandbox: commit de código `c854dfbd3b909aefe9e7238c672ec4264dccdb3d`, tree `583295acc1a3b995c19fbe5d3a0e06a4cfd35b59`; imagem AMD64 `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`; tarball SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`. O commit documental que registrou a limpeza é `f9ee80e162bc16a00645ad21f7db38ac66f95f33` (tree `af3e723de536c483fc1ea1487764d556e246b0c6`); a rastreabilidade foi complementada depois. O commit de código permanece local, sem publicação ou deploy.

Identidade do tar foi reconciliada no Engine AMD64: o tar com SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0` foi carregado e `docker image inspect` reportou `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`. O arquivo salvo contém config blob `c5d9f49e1a3a20be02cb7fd6c84711904184103d3d24d9175ee84d8090856cde`; uma exportação round-trip reteve o mesmo tar hash/blob. O harness foi executado no Image ID reportado pelo Engine após o load.

Após o reteste do tar exato, removi do host de ensaio a imagem carregada, seus volumes temporários e arquivos transferidos. A verificação final listou apenas Node/JVM A6, ambos healthy; o tar candidato permanece local para revisão.

### GO final condicionado à aceitação explícita — 2026-11-07

Esta atualização registra o GO administrativo recebido para preparar/executar um deploy Node-only, condicionado aos gates. O administrador declarou ter confirmado diretamente no GitHub `ODISSEU_PRODUCTION_DEPLOY_HOLD=true`, `CI/CD=disabled_manually`, o workflow `Pokémon storage progression CI` ativo somente para testes e zero runs ativos/na fila. Registro como confirmação administrativa fornecida nesta conversa; não consultei independentemente a API do GitHub nesta etapa. Hold deve permanecer `true` e CI/CD desabilitado.

O artefato revisado é Node AMD64 Image ID `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`, commit `c854dfbd3b909aefe9e7238c672ec4264dccdb3d`, tree `583295acc1a3b995c19fbe5d3a0e06a4cfd35b59`. Tarball `/private/tmp/odisseu-node-chromium-seccomp-20261107.tar`, SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`. O tar foi carregado no Docker Engine AMD64; o Engine retornou o Image ID indicado e o harness foi executado nesse ID. Evidências de namespace, Seccomp-BPF, usuário, capabilities, CDP, fechamento limpo e persistência estão em [chromium-seccomp-diagnosis.md](chromium-seccomp-diagnosis.md). Configuração: Chrome `157.0.8103.38-1`, Node `22.23.4`, UID/GID 1000, `cap_drop=ALL`, `no-new-privileges`, perfil seccomp SHA-256 `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10`. O sandbox não torna as bibliotecas abaixo corrigidas; ele reduz a exposição do processo Chromium a escapes.

#### Matriz dos sete advisories adicionais

| CVE / pacote e versão | Severidade do fornecedor / Trivy | Condição de exploração descrita | Exposição e mitigação observada | Correção e risco residual |
|---|---|---|---|---|
| `CVE-2026-6276` — `libcurl4 7.88.1-10+deb12u15` | curl Low / Trivy High | Reuso de easy handle após `Host:` customizado pode expor cookies. | Código Node examinado não usa libcurl; Chrome inspecionado não liga à libcurl. Não foi encontrado consumidor no fluxo normal. Não é falso positivo: a biblioteca está instalada. | Fix upstream 8.20.0; sem versão Bookworm corrigida no scan. Risco residual baixo no fluxo atual, não zero se surgir consumidor. |
| `CVE-2026-8286` — `libcurl4 7.88.1-10+deb12u15` | curl Low / Trivy High | Reuso de conexão STARTTLS com configuração TLS incompatível. | Sem chamada libcurl ou vínculo do Chrome identificados. | Fix upstream 8.21.0; sem pacote Bookworm corrigido identificado. Risco residual baixo no fluxo atual. |
| `CVE-2026-8458` — `libcurl4 7.88.1-10+deb12u15` | curl Low / Trivy High | Reuso lógico de conexão sob autenticação Negotiate. | Aplicação não usa libcurl/Negotiate nos caminhos examinados; biblioteca permanece instalada. | Fix upstream 8.21.0; sem pacote Bookworm corrigido identificado. Risco residual baixo no fluxo atual. |
| `CVE-2026-8927` — `libcurl4 7.88.1-10+deb12u15` | curl Medium / Trivy High | Proxy e autenticação Digest em handle reutilizado podem expor estado de autenticação. | Nenhum uso de libcurl, proxy Digest ou vínculo do Chrome observado; não prova inalcançabilidade futura. | Fix upstream 8.21.0; sem pacote Bookworm corrigido identificado. Risco residual baixo a moderado se um consumidor for introduzido. |
| `CVE-2023-2953` — `libldap-2.5-0 2.5.13+dfsg-5` | Debian tracker Minor / Trivy High | Null dereference em `ber_memalloc_x` ao usar a biblioteca LDAP. | Nenhum consumidor LDAP nem vínculo aos binários Chrome examinados. O pacote está presente. | Fix upstream 2.5.14; sem correção Bookworm indicada. Risco residual baixo no fluxo atual, não zero se houver consumidor. |
| `CVE-2026-58471` — `wget 1.21.3-1+deb12u1` | Debian tracker Minor/postponed / Trivy High | Heap overflow ao processar nome de arquivo fornecido por servidor com conversão de charset. | `wget` está instalado, mas a aplicação não o invoca nem cria subprocessos nos fluxos examinados. UID não-root, zero capabilities e NNP limitam impacto; sandbox Chromium não protege uma execução direta de Wget. | Há commit upstream `c2640fe`, mas sem versão Debian corrigida identificada. Risco residual baixo no fluxo atual e maior se algum código passar a invocar Wget. |
| `CVE-2026-58472` — `wget 1.21.3-1+deb12u1` | Debian tracker Minor/postponed / Trivy High | Heap overflow ao converter atributo HTML elaborado processado por Wget. | Mesmo alcance: sem invocação de Wget ou subprocessos no app; o binário permanece executável na imagem. Sandbox do Chrome não corrige o Wget. | Há commit upstream `dd692d9`, sem versão Debian corrigida identificada. Risco residual baixo no fluxo atual, não zero se o caminho de execução mudar. |

A mitigação comum é ausência de caminho de chamada identificado nos fluxos atuais, execução Node como não-root sem capabilities, e, para conteúdo remoto recebido pelo Chromium, sandbox AMD64 comprovado. Isso reduz a exposição, mas não neutraliza uma chamada futura aos pacotes vulneráveis. Não apliquei patches manuais: não há pacotes Bookworm corrigidos indicados e trocar essas dependências requer nova imagem, teste de compatibilidade Chrome e nova varredura.

O scan completo contém 531 registros e sua comparação com a candidata imediatamente anterior não mostrou delta; isso não representa aceitação automática dos registros. A matriz acima cobre somente os sete advisories adicionais reais associados a `libcurl4`, `libldap` e `wget`. Findings herdados, críticos/altos de outras famílias ou advisories npm não são aceitos por esta proposta; continuam sujeitos às classificações e decisões específicas já registradas nas seções anteriores.

#### Proposta de decisão administrativa

A avaliação técnica não encontrou vetor direto desses sete advisories nos caminhos normais atualmente examinados; nenhum dos sete foi corrigido na imagem, e o risco residual não é zero. Proponho aceitação **somente** para as sete combinações CVE/pacote/versão da tabela, no Image ID, commit/tree e configuração de sandbox especificados acima; válida até 2026-12-07 ou até a substituição da imagem/configuração, o que ocorrer primeiro. A aceitação não cobre outros findings do Trivy, alterações futuras de dependências, ativação de subprocessos/downloaders, mudanças no sandbox ou qualquer outra imagem.

**Decisão atual: NO-GO para cutover enquanto essa aceitação formal não for dada.** A autorização de deploy condicionada não equivale à aceitação do risco. Nenhum drain ou preflight de produção foi iniciado nesta etapa; depois de uma resposta explícita, ainda será necessário executar o preflight atual de produção e abortar diante de qualquer divergência. Não houve deploy, restart, migration, mudança de flags, saves, Datomic, sessão WhatsApp, CI/CD ou deploy hold.

### Preflight de deploy após aceitação limitada — 2026-11-07

O administrador aceitou formalmente apenas os sete advisory listados acima, para o Image ID/configuração especificados e até 2026-12-07 ou substituição. Isso satisfaz somente o gate de aceitação desses sete riscos; não autoriza aceitar os demais findings nem dispensa o preflight operacional.

#### Evidências read-only da VM

- Acesso pela configuração GCloud autorizada ao host `odisseu-core`, projeto informado, zona `us-central1-a`; `uname -m=x86_64` e Docker `x86_64`.
- Node atual: container `odisseu-a6-node`, imagem rollback disponível e ativa `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`, HEALTHY; JVM `odisseu-a6-jvm`, imagem `sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c`, HEALTHY. Nenhum dos dois teve restart/recreate; restart count observado zero.
- Health passivo do Node: `status=ok`, `whatsapp=READY`, `chromium=true`. Do container Node à rota JVM `/health`: HTTP 200.
- Flags atuais: `ALLOW_ALL_GROUPS=true`; desafios `true`; recompensas por permanência `true`; `POKEMON_RAID_AUTO_TICK=true`; `ODISSEU_BRIDGE_RAID_AUTO_TICK=true`; `RELEASE_ANNOUNCER_ENABLED=false`. Esses valores não foram alterados.
- Perfil WhatsApp está em bind mount dedicado do cutover; `/app/data` é o volume nomeado `odisseu-a6-cutover-data`. O arquivo de histórico não existe no caminho padrão `/app/data/ai-content-history.json` observado; `AI_CONTENT_HISTORY_PATH` não está definido. Espaço disponível: 11 GiB. `who` não mostrou sessões interativas, mas isso não comprova ausência de operadores remotos/API.
- O processo ativo usa root. O candidato usa `1000:1000`. Teste somente de acesso, executado como UID/GID 1000 sem escrever: `EACCES` no perfil e em `/app/data`. Metadados: perfil `1001:1002`, modo `700`; diretório de dados `0:0`, modo `755`. O Docker socket e os serviços não foram alterados. Não apliquei `chown` nem ACL: é preciso autorização e um plano que preserve metadados/conteúdo dos mounts.
- A resposta atual de `/health` contém somente `status`, `whatsapp`, `chromium`; não expõe admissão, operações em voo ou `quiescent`. O canal JVM Unix `maintenance.sock` existe com modo `0600`, mas não foi invocado. Não foi possível comprovar que o Node fechou admissão e drenou handlers/persistências; portanto nenhum drain foi iniciado.

#### GitHub, atestação e bloqueio

O administrador declarou no GO hold `true`, `CI/CD=disabled_manually`, workflow Pokémon ativo apenas para testes e zero runs ativos/na fila. Registrei como atestação administrativa. A verificação read-only independente não funcionou nesta sessão: `gh auth status` encontrou token local inválido e a API retornou erro de conexão. Não alterei GitHub, hold ou workflows.

**Resultado: NO-GO; nenhum cutover iniciado.** A aceitação dos sete CVEs foi registrada, mas o candidato UID 1000 não consegue acessar os dois mounts persistentes e não há evidência observável de drain/quiescência do Node atual. Ainda faltou confirmação atual de janela exclusiva por um único operador; `who` vazio não prova exclusividade. A imagem atual permaneceu ativa, e JVM, Datomic, saves, perfil, volumes, flags e deploy hold não foram modificados.

Para retomar com segurança, são necessários: (1) mecanismo autorizado para conceder ao runtime não-root acesso preservando conteúdo e sessão, seguido de novo teste de acesso sem escrita; (2) uma forma comprovável de fechar admissão e observar zero operações aceitas/persistências pendentes antes da parada — o health atual não fornece isso; (3) confirmação explícita de janela exclusiva. Depois desses gates, repetir a consulta atual do GitHub pelo operador autenticado e validar o caminho do perfil e histórico antes de considerar o cutover.

### Preflight de permissões e observabilidade Node — 2026-11-07

Esta atualização substitui o diagnóstico anterior de permissões e drain. O trabalho foi somente de leitura na aplicação ativa e usou a candidata apenas em containers/volumes descartáveis AMD64. Não houve `enter`, `drain`, sinal, restart/recreate, ACL, `chown`, gravação ou alteração de arquivo nos mounts ativos.

#### Estado observado na VM

- Node `odisseu-a6-node`: imagem ativa `sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390`, usuário Docker sem override (UID 0), HEALTHY, WhatsApp READY, restart count 0. JVM `odisseu-a6-jvm`: `sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c`, HEALTHY, restart count 0. `/health` JVM respondeu HTTP 200.
- Mount de sessão: bind `/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core` em `/app/.wwebjs_auth_a6_cutover`. Raiz `1001:1002`, modo `0700`; inventário agregado sem nomes/conteúdo mostrou, abaixo da raiz, 143 diretórios `0:0` modo `0700`, 12.354 arquivos `0:0` modo `0600`, 7 arquivos `0:0` modo `0644` e 3 symlinks. Nenhum arquivo da sessão foi lido/copied.
- `/app/data` é o volume `odisseu-a6-cutover-data`, `0:0`, modo `0755`. `/app/data/a6-maintenance.json` é `0:0`, modo `0600`, marker `open`; o histórico `/app/data/ai-content-history.json` ainda não existe. `AI_CONTENT_HISTORY_PATH` não está definido, então vale o caminho default acima.
- Flags observadas e inalteradas: ginásios `true`, recompensas por permanência `true`, ambos os ticks de raid `true`, `RELEASE_ANNOUNCER_ENABLED=false`. O CI/CD e hold não foram alterados.

#### Comparação de identidade

| Alternativa | Ensaio descartável com ownership equivalente | Avaliação |
| --- | --- | --- |
| A — executar `1001:1002` | Chromium e histórico passaram com `HOME=/tmp`; ACL específica no volume de dados e nos objetos root-owned da árvore fictícia. | A identidade coincide com o dono da raiz da sessão, mas não com os arquivos internos. Exige override de `user` e HOME; a aplicação completa sob esse HOME não foi iniciada. |
| B — executar `1000:1000` | Chromium, histórico e leitura do marker root-owned passaram com a identidade nativa da imagem e HOME padrão. ACLs específicas no perfil fictício e no volume de dados. | **Preferida**, pois mantém o `USER 1000:1000` da imagem/candidato e a configuração Compose revisada, sem `chown` nem override de identidade. Exige ACL restrita ao volume de sessão e ao volume de dados. |

O smoke final reproduziu a árvore observada: raiz da sessão `1001:1002`/`0700`, descendentes root-owned, diretórios `0700`, arquivos `0600`, e marker de dados `root:root`/`0600`. Em ambas as alternativas, o histórico usou `scripts/lib/ai-content-history-file.cjs`: leitura do marker, criação, gravação em arquivo temporário, `fsync`, rename atômico, leitura, deduplicação e persistência após recriar o container passaram; arquivo final `0600`. Chromium/Puppeteer usou os caminhos de mount A6, perfil descartável persistente e rede desativada. Para B, o harness também passou sem sobrescrever HOME.

Em ambos os UIDs, `chrome://sandbox` declarou namespaces PID/rede e Seccomp-BPF `Yes` e “You are adequately sandboxed”; CDP funcionou e o browser fechou com exit code 0. `/proc` mostrou UID esperado, `NoNewPrivs=1`, `Seccomp=2`, `CapEff=0`; browser tinha 1 filtro e renderer 2, distinguindo o filtro do container do BPF adicional do Chromium. Foram usados `cap-drop=ALL`, `no-new-privileges`, perfil seccomp versionado (`sha256:4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10`), sem `--no-sandbox`, `SYS_ADMIN`, `--privileged`, socket Docker, Datomic ou sessão WhatsApp.

**Permissões escolhidas para uma janela futura, ainda não aplicadas:** conceder ACL somente ao UID 1000 dentro de `/app/.wwebjs_auth_a6_cutover/**` e no volume `/app/data`. Manter owners, GIDs e demais ACLs; diretórios da sessão precisam de `rwx`, arquivos existentes de sessão de `rw-`; no volume de dados, diretório `rwx` e ACL `rw-` específica para o marker existente `a6-maintenance.json` e para `ai-content-history.json` somente se ele existir. Outros diretórios, volumes Datomic e demais arquivos não entram no escopo. Novos arquivos criados pelo Node UID 1000 serão de sua propriedade e manterão o modo de arquivo `0600`; não há necessidade demonstrada de ACL default global. O host não tem os binários `getfacl`/`setfacl`, embora `libacl.so.1` esteja presente; antes da janela, preparar/revisar um helper `libacl` que salve o estado ACL num artefato protegido e restaure exatamente esse estado. Não usar `chown -R`, `chmod` amplo ou tornar volumes graváveis por grupo/outros. Rollback: parar o candidato, restaurar do snapshot ACL apenas os objetos desses dois mounts e iniciar a imagem/configuração anterior; manter os arquivos e volumes atuais.

#### Gate Node ativo e sua observabilidade

- A imagem ativa contém `a6-maintenance.cjs` (SHA-256 `04b5b61d082a5dc8669c3633f0c8e611fa7e2740c7dab714f21964e90e7e4d80`) e `a6-shutdown.cjs`. O marker persistente está configurado e atualmente `open`; o token estava configurado, mas seu valor nunca foi exibido. `/health` retorna somente `status`, `whatsapp`, `chromium`; GETs de `/maintenance`, `/maintenance/status`, `/internal/maintenance`, `/internal/maintenance/status`, `/drain`, `/readyz` e `/metrics` retornaram 404.
- O bundle ativo envolve mensagens recebidas em `node_maintenance.begin()` e executa `end()` em `finally`, inclusive nas rejeições/exceções; durante gate fechado, comando com prefixo recebe resposta amigável de manutenção. Pollers de lembretes e Pokemon HTTP também usam begin/end. SIGTERM aciona o controlador de shutdown: fecha admissão/atualiza saúde, grava `draining`, suspende fontes de admissão/schedulers, chama `enter(30s)`, e somente com `quiescent=true` e `durable=true` encerra recursos, bridge JVM, health server e recuperação WhatsApp. Falhas são fail-closed. O controlador tem deadline de 330 s e logs estruturados para drain incompleto, marker não durável, limpeza falha e timeout; sucesso não tem evento de log próprio.
- **O contador `activeOperations` não é consultável do processo em execução:** `snapshot()` chamado num `docker exec node -e` cria outra instância com contador próprio e não prova o contador live. Também não há endpoint de status. A evidência autoritativa após um SIGTERM futuro será a transição persistida do marker a `phase=quiescent`/`quiescentAt` (o código só a grava quando o contador live chega a zero), junto com saída limpa do processo (`ExitCode=0`) e ausência de eventos de erro. A fase `draining`, timeout, saída não zero ou marker ilegível significa abortar; não usar `/health`, silêncio de log ou contador de processo auxiliar como substituto.
- Em volume descartável, o coordenador real executou `open → draining → quiescent`, bloqueou novo begin, aguardou uma operação admitida terminar, coalesceu SIGTERM repetido e saiu `0` após fechar as fontes, recursos, bridge, servidor e recovery. Uma segunda execução/container leu o marker `quiescent`; `resume()` persistiu `open`, e uma terceira execução confirmou admissão novamente. Esse ensaio usou o código da imagem ativa, não acionou o gate da VM.
- O marker e o contador Node não cobrem operações JVM ou writers externos. A prova global ainda exige o break-glass da JVM e contenção dos writers externos. Não considerar este teste Node como prova de quiescência do Datomic.

#### Incompatibilidade da candidata e sequência de verificação futura

A candidata `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865` foi carregada apenas para ensaios e removida da VM ao final; o tar local permanece preservado. A imagem ativa possui o módulo/hash acima, mas a candidata **não contém** `/app/scripts/lib/a6-maintenance.cjs`, não contém a referência no bundle, nem o mecanismo de shutdown correspondente. Portanto substituir o Node ativo por essa candidata perderia o graceful drain. Esse é um bloqueio concreto; permissões foram aprovadas em fixtures, mas a combinação completa ainda não é segura.

Antes de uma janela futura:

1. Produzir uma imagem combinada que preserve o módulo e o wiring de drain da imagem ativa e acrescente apenas a correção de sandbox/IA; recompilar, testar, escanear e registrar novo digest. Não implantar o Image ID `2bb…` isolado.
2. Repetir imediatamente pelo operador autorizado hold=true, CI/CD `disabled_manually`, zero runs ativos/fila e demais caminhos de deploy. Nesta execução, o administrador já tinha atestado esses estados, mas a integração GitHub local continuou sem autenticação; nenhuma variável/workflow foi alterado.
3. Obter nova confirmação do administrador de que será o único operador de SSH/sudo/Docker/deploy durante a janela. A declaração anterior de exclusividade é uma atestação humana, não uma verificação por `who`.
4. Confirmar a configuração efetiva do novo serviço como UID/GID `1000:1000`; o container atual não tem override de usuário e roda como root, então não inferir a configuração live apenas pelo Compose do clone. Validar imagem e mounts efetivos; guardar snapshot ACL protegido; somente então aplicar as ACLs específicas acima e testar sem tocar no perfil real fora do procedimento autorizado.
5. Confirmar marker `open` por leitura sanitizada. Para iniciar drain futuro, enviar SIGTERM pelo supervisor/Docker com espera sem timeout que force-kill; não usar `docker stop` com timeout curto. Acompanhar o marker até `quiescent` e conferir o container antigo `exited/ExitCode=0` antes de qualquer substituição. Se permanecer `draining` ou houver falha, manter o gate fechado, não enviar SIGKILL e abortar.
6. Depois do cutover, antes de iniciar mensagens, executar em container one-shot da imagem combinada `require("/app/scripts/lib/a6-maintenance.cjs").resume()` no volume `/app/data`; conferir marker `open` durável e então iniciar o Node. A aplicação não expõe endpoint de resume/status; não inventar rota HTTP ou usar o token antigo.

Comandos de conferência para a janela autorizada (não executados nesta etapa):

```bash
# GitHub: operador autenticado imediatamente antes do cutover
gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo caioclavico/zapbot
gh workflow list --all --repo caioclavico/zapbot
gh run list --repo caioclavico/zapbot --status in_progress --limit 100
gh run list --repo caioclavico/zapbot --status queued --limit 100

# Estado persistente antes do sinal (somente campos não sensíveis)
sudo docker exec odisseu-a6-node node -e 'const x=JSON.parse(require("fs").readFileSync("/app/data/a6-maintenance.json","utf8")); console.log(JSON.stringify({phase:x.phase,startedAt:x.startedAt||null,quiescentAt:x.quiescentAt||null,resumedAt:x.resumedAt||null}))'

# Iniciar apenas o handler SIGTERM, sem timer do Docker que force-kille o processo
sudo docker kill --signal=SIGTERM odisseu-a6-node

# Depois do encerramento: consultar marker e exit code (DATA vem do volume ativo)
DATA=$(sudo docker volume inspect -f '{{.Mountpoint}}' odisseu-a6-cutover-data)
sudo python3 -c 'import json,sys; x=json.load(open(sys.argv[1])); print({k:x.get(k) for k in ("phase","startedAt","quiescentAt","resumedAt")})' "$DATA/a6-maintenance.json"
sudo docker inspect --format '{{.State.Status}}/{{.State.ExitCode}}' odisseu-a6-node
```

O envio de SIGTERM só inicia o ciclo; não prova drain. Prosseguir apenas se o marker persistido for `quiescent`, `quiescentAt` estiver presente e o container antigo tiver terminado com código 0. Se ainda estiver `draining`, não enviar SIGKILL nem substituir o container.

#### Resultado

**Permissões/sandbox: PASS em fixtures descartáveis para A e B; B preferida. Observabilidade e sequência do drain: PASS no código da imagem Node ativa, sem executar nada live. Cutover futuro: NO-GO.** O bloqueio é a imagem candidata não preservar o drain da imagem ativa. É necessária uma imagem combinada rastreável, mais o preflight/ACL operacional autorizado da janela. No fim dos ensaios, os 13 volumes descartáveis, todos os containers temporários, a imagem candidata carregada e os arquivos temporários remotos foram removidos por nome exato. Node e JVM ativos continuaram saudáveis, WhatsApp permaneceu READY, flags ficaram inalteradas; nenhum ACL, save, Datomic, perfil, release, CI/CD ou deploy hold foi modificado.
