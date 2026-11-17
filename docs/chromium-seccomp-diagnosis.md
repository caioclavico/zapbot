# Diagnóstico final do Seccomp-BPF do Chromium

Data: 2026-11-07. Escopo: containers descartáveis com a imagem Chrome 157; sem produção, sessão real, rede ou Docker socket.

## Causa comprovada e limite do diagnóstico

O código atual do Odisseu acrescenta `--no-sandbox` e `--disable-setuid-sandbox` na inicialização Puppeteer (`src/zapbot/core.cljs:456`). O Puppeteer não acrescenta `--no-sandbox` por padrão. A documentação do Chromium afirma que `--no-sandbox` desativa todo o sandbox. No teste com essas flags, `chrome://sandbox` confirmou `Layer 1: None`, namespaces `No` e Seccomp-BPF `No`. **Esta é a causa comprovada para o caminho de inicialização atual do Odisseu.**

Também testei sem `--no-sandbox` para explicar o estado parcial observado anteriormente. Nesse caminho, Chromium ativou namespaces PID e rede, mas `chrome://sandbox` continuou mostrando Seccomp-BPF `No` e “You are NOT adequately sandboxed”. Portanto, remover a flag é necessário, mas esta execução não provou que seja suficiente para ativar BPF.

## Experimentos

Todos usaram Chrome oficial `157.0.8103.38`, Puppeteer instalado na imagem, `--platform linux/amd64`, UID 1000, `--cap-drop=ALL`, `--network none`, root filesystem read-only e somente `/tmp` gravável. Nenhum teste usou capability adicional ou enfraqueceu políticas do host.

| Hipótese/variação | Resultado observado |
| --- | --- |
| A. Seccomp padrão Docker; sandbox habilitado | Setuid sandbox falhou ao criar namespaces com `EPERM`. Com `--disable-setuid-sandbox`, Chrome falhou com `No usable sandbox`; o perfil padrão não permite o caminho de user namespace neste ambiente. |
| B. Perfil baseado no default Moby 29.8.2 + regra Playwright para `clone`, `setns`, `unshare` | Puppeteer/CDP e Chrome funcionaram; namespace sandbox ativo. Renderer: `Seccomp=2`, `Seccomp_filters=1`. O filtro único é o herdado do container; `chrome://sandbox` confirmou que o filtro BPF do Chromium não foi instalado. |
| C. `no-new-privileges` ligado/desligado, com o perfil B | Resultado BPF idêntico. Ligado: browser e renderer `NoNewPrivs=1`; desligado: browser `0`, renderer `1`. Nos dois, renderer ficou com um filtro. NNP não explica a ausência. |
| `--disable-setuid-sandbox`, sem `--no-sandbox`, com perfil B | Namespace ativo; Seccomp-BPF continuou desligado. A flag de setuid não explica a ausência de BPF. |
| `--no-sandbox` e `--disable-setuid-sandbox` (flags efetivas do Odisseu) | Nenhuma camada Chromium ativa; confirma o efeito da flag principal. |

Metadados coletados dos processos browser e renderer: PID, UID, `NoNewPrivs`, `Seccomp`, `Seccomp_filters`, `CapEff` e apenas flags de sandbox/headless. Ambos estavam sem capabilities (`CapEff=0`). No caso parcial, browser e renderer tinham `Seccomp=2` e contagem 1; a página `chrome://sandbox` confirmou `Seccomp-BPF No`. Assim, a distinção não depende somente de inferir contagem em `/proc`.

O kernel expôs ações seccomp (`kill_process`, `errno`, `allow`, entre outras), `user.max_user_namespaces=31879`, e `unshare -Ur id` passou. O perfil experimental permitiu as syscalls de namespace; `prctl` e `seccomp` também constam como permitidos no perfil usado. Mesmo assim, Chromium não instalou seu filtro. Não apliquei política no host nem usei `SYS_ADMIN`, `--privileged` ou `seccomp=unconfined`.

## Limitação de plataforma

O daemon Docker local reportou arquitetura **ARM64**, enquanto a imagem testada é **AMD64**. O container mostrou `uname -m=x86_64` e Node `process.arch=x64`, portanto o teste executou a imagem por compatibilidade/emulação, não sobre um host Linux AMD64 nativo. A build oficial 157 inicia e CDP/Puppeteer funcionam nesse modo, mas não foi possível determinar se a ausência de BPF no caminho parcial é causada pela build Chrome, pela tradução AMD64/ARM64, ou por interação residual da política externa. Não se deve atribuir essa segunda falha a uma dessas causas sem o ensaio nativo.

As flags efetivas do Puppeteer diagnosticado eram `--headless=new` (mais flags de rotina); não havia `--disable-seccomp-filter-sandbox`. A execução com flags reais do Odisseu adicionou explicitamente `--no-sandbox`. O `--disable-setuid-sandbox` sozinho não impediu a camada de namespaces.

## Recomendação e decisão

1. Remover `--no-sandbox` do caminho da aplicação e executar Chromium como usuário não-root; manter capabilities zeradas e NNP ativo.
2. Começar pelo perfil Docker default acrescido somente das permissões de user namespace documentadas pelo Playwright (`clone`, `setns`, `unshare`). Não conceder `SYS_ADMIN`.
3. Repetir o ensaio em Linux **AMD64 nativo** com a mesma imagem Chrome 157/Puppeteer e perfil. Aprovar somente quando `chrome://sandbox` marcar namespace e Seccomp-BPF ativos no renderer, Puppeteer/CDP e shutdown passarem, e `/proc` confirmar o filtro adicional do renderer em relação ao filtro externo.
4. Se BPF continuar ausente no host nativo, usar logs/diagnóstico de Chromium e rastreamento de syscalls nesse host para localizar o retorno de `prctl(PR_SET_SECCOMP)`/`seccomp`; revisar a regra específica que o nega. Não ampliar o perfil por tentativa e erro.

**NO-GO técnico.** A flag `--no-sandbox` no código do Odisseu é uma causa direta comprovada e precisa sair. A causa do BPF ainda ausente no smoke sem essa flag permanece indeterminada porque o runner disponível era ARM64 com imagem AMD64. Não reconstruí imagem nem alterei código. A avaliação dos advisories residuais permanece separada e inalterada em `docs/ai-dynamic-content.md`; GitHub/hold também não foi consultado ou alterado.

Fontes: [Chromium Linux sandbox](https://chromium.googlesource.com/chromium/src/%2B/0b6120702e8f69ce43b34b02903a1d32a0be07d3/docs/linux/sandboxing.md) descreve `--no-sandbox`, as duas camadas e a inspeção via `chrome://sandbox`; [Playwright Docker](https://playwright.dev/docs/docker) documenta o perfil padrão mais syscalls de user namespace e recomenda processo não-root para conteúdo não confiável.

### Atualização final — homologação Linux AMD64 nativo (2026-11-07)

Esta seção substitui o NO-GO de sandbox acima, que foi baseado em execução ARM64 com emulação AMD64. A prova agora foi feita em `odisseu-core`, `x86_64`, Docker Engine 29.9.1. Os únicos containers de aplicação listados antes/depois permaneceram `odisseu-a6-node` e `odisseu-a6-jvm`, sem recreate/restart. Os probes foram containers com nome exclusivo, `--network none`, sem portas, root filesystem read-only, limites de 768 MiB/1 CPU/256 PIDs, volumes temporários e nenhum mount de sessão real ou Datomic.

#### Correções

- `src/zapbot/core.cljs`: removidos `--no-sandbox` e `--disable-setuid-sandbox`. Não há fallback que desative sandbox após falha. Permanecem flags QUIC, baixa memória e GPU configuradas pela aplicação.
- `Dockerfile`: a imagem passa a executar como UID/GID `1000:1000`, cria os diretórios de perfil/dados com owner `node` e deixa o entrypoint/Tini rodar como usuário não-root.
- Compose: Node recebe `cap_drop: [ALL]`, `no-new-privileges:true` e `docker/chromium-seccomp.json`. O adaptador de deploy preserva o usuário explícito do template quando a imagem anterior não especifica usuário, combina drops de capability e falha fechado se encontrar usuário, seccomp ou capabilities adicionais conflitantes.
- O perfil seccomp mantém a ação default `SCMP_ACT_ERRNO` do perfil Docker/Moby e acrescenta uma regra apenas: `clone` quando `arg0` contém `CLONE_NEWPID` (`0x20000000`, `SCMP_CMP_MASKED_EQ`). Não permite `clone`, `setns` ou `unshare` sem restrição e não adiciona `SYS_ADMIN`.

Perfil em `docker/chromium-seccomp.json`, SHA-256 `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10`. Referência de desenho: [guia Docker do Playwright](https://playwright.dev/docs/docker) descreve um perfil baseado no default Docker com permissões de namespaces; a regra deste perfil foi reduzida e validada isoladamente. Base e rationale estão em [docker/chromium-seccomp.md](../docker/chromium-seccomp.md).

#### Evidência do sandbox

Imagem testada no host nativo: `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`, `linux/amd64`, usuário `1000:1000`, Node `22.23.4`, Chrome `157.0.8103.38-1`.

O harness `scripts/test-chromium-sandbox.cjs` iniciou Chrome/Puppeteer com os flags efetivos QUIC, low-resource e GPU, sem flags que desliguem sandbox. CDP carregou uma página e enumerou renderers. `chrome://sandbox` mostrou `Layer 1 Sandbox: Namespace`, namespaces PID e rede `Yes`, `Seccomp-BPF sandbox: Yes` e “You are adequately sandboxed”.

Metadados lidos de `/proc/<pid>/status`:

| Processo | UID/GID | `NoNewPrivs` | `Seccomp` | `Seccomp_filters` | `CapEff` |
| --- | --- | --- | --- | --- | --- |
| Browser | 1000/1000 | 1 | 2 | 1 (filtro externo Docker) | `0000000000000000` |
| Renderer | 1000/1000 | 1 | 2 | 2 (externo + Chromium) | `0000000000000000` |

IDs de namespace de usuário, PID e rede do renderer diferiram dos do browser. Assim, `Seccomp: 2` sozinho não foi usado como prova: a confirmação foi a camada `Seccomp-BPF Yes` de `chrome://sandbox` e o filtro adicional no renderer. CDP respondeu, o browser fechou com exit code zero e o teste persistiu um perfil/sentinel nos volumes descartáveis montados em `/app/.wwebjs_auth` e `/app/data`; um container novo os leu em modo read-only.

Também passaram no mesmo perfil os harnesses WhatsApp simulados: managed SIGTERM, managed SIGINT e legacy SIGTERM, todos `exit_code=0`, `browser_signal=null`, `session_preserved=true`. O teste de mídia continua coberto pelo `npm run verify:media-patch` executado tanto na suíte quanto na build. Não conectamos WhatsApp nem a rede externa.

#### Regressões, imagem e scan

- `npm test`: **232 testes, 1.399 asserções, 0 falhas/erros**. A primeira tentativa sob o sandbox local teve quatro erros de `listen EPERM` em 127.0.0.1; a repetição autorizada para loopback passou.
- `npm run test:http`: **282/282 PASS**.
- `npm run build`: aprovado; Shadow CLJS manteve 716 warnings de inferência/depreciação preexistentes.
- Build multi-stage `linux/amd64`: concluído; `Config.User=1000:1000`, Chromium 157 e Brotli `1.0.9-2+deb12u1`. Bundle `target/main.js` sem flags de sandbox desativado.
- Trivy 0.76.2, DB local válida de 2026-11-07, scan local completo (SO e Node): 531 findings; comparação canônica com o JSON da imagem `607fcd...` deu 0 novos e 0 removidos. A exposição relativa dos sete advisories já classificados não mudou; nenhum foi aceito ou suprimido. JSON completo: `/private/tmp/trivy-ai-chromium-seccomp-20261107.json`.
- Tar de teste foi transferido por SSH/SCP com SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`; imagem carregada na VM apenas no cache para os probes. **Nenhum deploy.**

**Resultado técnico do sandbox: PASS / ON.** O sandbox Chromium 157 foi comprovado em AMD64 nativo e a nova imagem está pronta para homologação. A decisão operacional para produção continua separada: os advisories residuais continuam sem aceitação administrativa, o usuário dono do volume WhatsApp real não foi inspecionado nem alterado, e o hold/GitHub não foi consultado nesta execução. Uma futura janela deve conferir owner UID 1000 dos mounts e repetir o preflight de segurança; abortar sem `chown` automático se houver incompatibilidade.

### Limpeza do host de teste

Após a prova, confirmei que nenhum container referenciava os quatro volumes descartáveis; removi esses volumes, as duas imagens de probe e os arquivos temporários nomeados. A verificação final listou somente `odisseu-a6-node` e `odisseu-a6-jvm`, ambos healthy, sem restart/recreate. A imagem candidata permanece no tarball local `/private/tmp/odisseu-node-chromium-seccomp-20261107.tar`, SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`; a cópia carregada no host de teste foi removida.

### Rastreabilidade do artefato

- Commit temático de código/configuração/testes: `c854dfbd3b909aefe9e7238c672ec4264dccdb3d`.
- Git tree desse commit: `583295acc1a3b995c19fbe5d3a0e06a4cfd35b59`.
- Imagem construída e exercitada a partir dos mesmos arquivos de runtime: `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865` (`linux/amd64`, `USER 1000:1000`). O commit contém a mesma versão dos arquivos que entram no build; a documentação de fechamento posterior não altera a imagem.
- Tarball local verificado: `/private/tmp/odisseu-node-chromium-seccomp-20261107.tar`, SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0`.
- Commit documental de limpeza e rastreabilidade: `f9ee80e162bc16a00645ad21f7db38ac66f95f33`, tree `af3e723de536c483fc1ea1487764d556e246b0c6`.

O commit de código não foi publicado. Os dois arquivos de fixture Pokémon já modificados no clone permaneceram fora dos commits.

A checagem independente do arquivo `docker save` encontrou o config blob em `blobs/sha256/c5d9f49e1a3a20be02cb7fd6c84711904184103d3d24d9175ee84d8090856cde`; após `docker load` no Engine 29.9.1, `docker image inspect` reportou Image ID `sha256:2bbca08946d957190ef710ecda8d0889ecfc7af8e492933f75170264e1d16865`. Repeti `docker save` da imagem carregada: o tar round-trip preservou SHA-256 `6c26e4dc9950b7e2075aea66ea245aee24b8977af95740cb45e7c29e7862b0d0` e o mesmo config blob. O harness foi então executado no Image ID efetivamente reportado pelo Engine após carregar esse tar. O manifesto distingue, portanto, o SHA do tar e o Image ID do Engine; não trata o hash do caminho `Config` como Image ID observado.

Depois do reteste com o tar exato, removi seus dois volumes de perfil/dados, a imagem carregada no cache do host e os arquivos transferidos de teste. `docker ps` voltou a listar somente Node/JVM A6, ambos healthy; nenhum serviço foi reiniciado ou recriado. O tar de revisão continua apenas no workspace local.
