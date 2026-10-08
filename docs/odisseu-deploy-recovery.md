# Recuperação segura do Odisseu após o deploy de e5a7e8c

## Fatos, evidências e hipóteses

O operador informou que `e5a7e8ce0e46c3a5c0a7a5d665d718888fd3cb93` falhou em
`Client.inject()` com `Runtime.callFunctionOn: Execution context was destroyed`,
que o rollback precisou de autenticação adicional e que `37ffb6ab` está saudável
com WhatsApp READY. A transação anterior foi arquivada como
`transaction.recovered.json`. Não acessamos a VM nem lemos esse arquivo.

O diff entre as duas versões não muda `package.json`, `package-lock.json` nem
`src/zapbot/core.cljs`. O Dockerfile acrescenta `PERFORMANCE_METRICS=false`.
Essa flag controla instrumentação e não interfere diretamente em `inject()`.
Não há evidência que aponte para a flag como causa.

A dependência fixada no projeto faz avaliações JavaScript durante `inject()`;
a primeira avaliação rejeitada encerra `initialize()`. O listener assíncrono de
navegação também chama `inject()` e não trata sua rejeição. Uma navegação durante
a avaliação é a causa provável do erro apresentado. O Puppeteer documenta que
handles são descartados quando seu contexto é destruído ou o frame navega:
[API oficial](https://pptr.dev/api). Isso não comprova corrupção da sessão.

Riscos comprovados no código anterior:

- A aplicação registra ERROR sem recuperar uma falha transitória de injeção.
- `Client.initialize()` registra o browser/página depois de preparar a página;
  uma falha anterior pode impedir que `destroy()` alcance os recursos criados.
- O listener de navegação chama `LocalAuth.logout()` em `post_logout=1` ou
  `lastLoggedOut`. Essa implementação remove recursivamente o diretório da sessão.
- O rollback reutiliza o perfil que o candidato pode ter atualizado, sem snapshot
  frio anterior e sem restauração desse baseline.
- Uma transação pendente bloqueia outro deploy, mas sua recuperação não tinha
  validação executável de IDs, imagens, fase, snapshot e saúde.

A perda de autenticação após o rollback pode ter relação com alterações no
perfil, logout remoto, atualização do Chromium ou estado remoto de autenticação.
Nenhuma dessas causas foi confirmada. `node:22-bookworm-slim` e o pacote Chromium
instalado pelo apt não estão fixados por digest/versão; builds diferentes podem
conter browsers diferentes mesmo com o mesmo lockfile. Comparar as versões reais,
exit codes, OOM e logs das duas imagens exige diagnóstico autorizado na VM.

## Correções

- Um Client/browser por processo. Apenas `inject()` é repetido, no mesmo browser,
  com no máximo três tentativas e esperas de 2 e 5 segundos. Chamadas simultâneas
  compartilham a operação. Erros de aplicação, auth, timeout e target fechado
  não geram tentativas de reinicialização.
- Prazo operacional de 120s por tentativa de injeção e 480s até READY. Ao expirar,
  o cliente fecha os recursos conhecidos e falha. O timeout de deploy permanece
  600s; protocolo Puppeteer e flags Chromium permanecem iguais.
- O patch npm existente também registra browser/página assim que criados. Marcadores
  ausentes ou incompatíveis bloqueiam build/verificação. A versão da dependência
  não foi trocada.
- READY recebido durante uma injeção é liberado ao fluxo de inicialização após
  sua conclusão. Navegação, crash, recuperação, logout e cleanup incompleto
  impedem saúde positiva. Eventos tardios não recuperam um cliente já em falha.
- Navegação de subframe não reinjeta o frame principal. Rejeições do listener
  assíncrono são tratadas. Logout de navegação e QR solicitado para um perfil
  existente encerram a tentativa e preservam os arquivos. Uma instalação
  realmente nova ainda pode realizar seu primeiro pareamento.
- SIGTERM cancela backoff e aguarda o único inicializador. O fechamento verifica
  desconexão e saída normal do processo Chromium. Erros, sinal de kill ou fechamento
  não comprovado continuam sendo falhas; o guard de 45s do Odisseu permanece.
- Healthcheck tem prazo total de 5s e corpo limitado a 4 KiB. Imprime somente
  status, estado WhatsApp e booleano Chromium, sem corpos ou exceções arbitrárias.

## Snapshot e rollback

O helper root-owned toma o snapshot depois de parar o container anterior e
comprovar `Running=false`, sem pausa/restart/PID ativo, exit code zero e ausência
de outro writer. Não copia Chromium ativo. Verifica espaço para snapshot e
restauração, conteúdo por SHA-256, permissões e UID/GID. Symlinks são preservados
sem seguir seus alvos; hard links internos são preservados. Links externos e
arquivos especiais impedem o snapshot. O registro fica em diretório privado,
com manifest 0600 e raiz do snapshot 0700.

Depois de falha, o candidato precisa estar comprovadamente parado, com shutdown
limpo. A restauração exige o original parado e nenhum outro writer. Prepara e
verifica uma cópia do snapshot; renomeia o perfil do candidato para evidência
privada e promove a cópia. Não apaga o perfil modificado, snapshots ou cópias
interrompidas. O container anterior mantém seu ID, configuração, mounts e hostname.
O teste Docker local confirmou a remontagem do diretório restaurado.

`.env`, `/app/data`, Cassandra e dados dos jogadores não fazem parte da cópia ou
restauração. Writes de jogo feitos antes de um rollback não são desfeitos.

## Transações interrompidas

O journal v2 registra IDs completos dos dois containers, imagens imutáveis,
revisão, fase, início do candidato, snapshot e referências anteriores. A recuperação
valida o inventário Docker, nomes, labels, mounts, recursos e estado dos processos.
Um resultado incerto de create/rename não autoriza selecionar um container
somente pelo nome `zapbot`. Containers pausados/reiniciando e binds sobrepostos,
inclusive diretórios ancestrais, são considerados na exclusividade.

Após parada, restauração e saúde estável, o journal é arquivado sob nome único.
Depois de um commit interrompido, só arquiva se o container registrado e a
referência salva coincidem e a saúde é confirmada. Arquivos antigos como
`transaction.recovered.json` não são substituídos. A retomada após renomeação do
perfil ou arquivo de archive já escrito é idempotente. Um deploy comum continua
recusando transações pendentes; recuperação nunca é acionada implicitamente.

## Atualização e recuperação futuras, somente após autorização

1. Revisar esta alteração e confirmar a referência atual, IDs, saúde e existência
   dos arquivos de controle. Preservar a transação arquivada do incidente.
2. Instalar os helpers revisados como root, incluindo `deploy_auth_profile.py`,
   antes de usar o novo fluxo. A imagem sozinha não atualiza o helper da VM.
   No Google, a conta é `caiohclavico`; preserve home/UID, chaves administrativas e sessão. Veja o [procedimento Google](odisseu-google-deploy.md).
   O instalador completo não é necessário para substituir somente código revisado.
3. Publicar um **novo SHA** aprovado. A imagem imutável de `e5a7e8c` não deve ser
   sobrescrita. Manter `PERFORMANCE_METRICS=false`; não habilitar auto deploy para
   contornar a revisão. Overrides existentes são preservados pelo helper.
4. Executar o deploy isolado do Odisseu pelo fluxo aprovado. O Compose de produção é executado pelo helper; não usar Compose ou
   `docker start` manualmente em paralelo. Todos os escritores gerenciados passam pelo lock e
   verificação de mounts; operadores externos precisam respeitar essa exclusividade.
5. Validar o novo container por saúde estável, revisão e erros. Se falhar, o helper
   executa rollback e mantém logs, containers e perfis como evidência.

Para uma transação **v2** pendente, após aprovação e revisão dos IDs, a conta
administrativa pode executar:

```sh
sudo python3 /usr/local/lib/zapbot-deploy/deploy-service.py odisseu recover /home/caiohclavico/zapbot
```

O comando adquire o lock. Não execute dentro de outro `flock` já adquirido.
`recover` não é permitido pela chave SSH de deploy nem pelo workflow automático.
Journals legados, IDs ausentes/divergentes, snapshots inválidos, writers adicionais
ou shutdown incompleto exigem revisão manual; não há instrução de apagar a sessão,
ignorar erro, editar o journal para forçar sucesso ou solicitar QR como fallback.
Sem journal pendente, não inicia/paralisa containers nem declara saúde verificada.

## Verificação após o deploy aprovado

Estes comandos são para o operador executar depois da atualização; não foram
executados na VM durante esta implementação:

```sh
docker inspect zapbot --format 'id={{.Id}} status={{.State.Status}} health={{.State.Health.Status}} revision={{index .Config.Labels "org.opencontainers.image.revision"}}'
docker exec zapbot node scripts/healthcheck.js
docker exec zapbot node -e 'console.log(JSON.stringify({PERFORMANCE_METRICS:process.env.PERFORMANCE_METRICS||"false"}))'
docker ps -aq | xargs -r docker inspect --format '{{.Id}} {{.Name}} running={{.State.Running}} {{json .Mounts}}'
docker logs --since 5m zapbot 2>&1 | grep -E 'whatsapp_inject_retry|whatsapp_recovery_failed|whatsapp_cleanup_failed|\[WhatsApp\]|Conectado ao Cassandra'
```

Conferir uma única instância ativa com o perfil, revisão esperada e healthcheck
com exit code 0. Não imprimir `Config.Env`, manifests, snapshots ou conteúdo da
sessão. Evidências completas devem permanecer privadas.

## Limites e riscos remanescentes

Snapshots preservam o estado local quiescente e não restauram autenticação
revogada no servidor WhatsApp. Um browser antigo pode não aceitar um perfil
atualizado. O helper falha de forma segura e mantém evidências quando não consegue
comprovar recursos fechados, IDs ou saúde. O primeiro upgrade de `37ffb6ab` dispõe
do exit code do Node; essa versão não tinha a nova comprovação do exit code do
Chromium. Nenhuma cópia fria prova retroativamente esse shutdown interno.

O adapter depende da biblioteca fixada. A integração com WhatsApp real, recursos
da VM, CPU steal, swap e versões dos browsers não foi reproduzida em produção.
Snapshot/hash/restauração custam espaço e I/O durante deploy; backups são retidos
sem limpeza automática e precisam de política de retenção revisada. O novo
recurso não reinicia automaticamente um processo apenas porque ficou unhealthy.

## Validação e arquivos

Os resultados finais e a lista de arquivos desta implementação estão registrados
abaixo. Não houve acesso à VM, alteração de secrets, dados, sessões de produção,
push ou deploy de produção. Fixtures locais usam perfis sintéticos, mocks e nomes
de containers únicos, sem WhatsApp ou Cassandra reais.

| Verificação local | Resultado |
|---|---|
| Node.js: startup, healthcheck, patch, recursos e contratos | 51 testes; zero falhas, em cada estado da flag |
| ClojureScript Odisseu, métricas false | 207 testes / 1322 assertions; zero falhas ou erros |
| ClojureScript Odisseu, métricas true | 207 testes / 1326 assertions; zero falhas ou erros |
| Deploy, recuperação e SSH restrito, Python 3.9 e 3.12 | 26 + 16 + 3 testes; todos passaram em ambas as versões |
| Seleção CI, SSH CI, carregamento de imagem e registry | 17 + 10 + 4 + 5 testes; todos passaram |
| Docker local real, Odisseu sintético | Deploy, falha de health, rollback automático/manual, remontagem do perfil e preservação passaram |
| Compilação de testes / release app | Concluídas; 196 / 138 warnings já existentes |
| actionlint, sintaxe shell, patch da dependência e diff | Passaram |

Arquivos modificados, por finalidade:

- Startup e saúde: `src/zapbot/core.cljs`, `src/zapbot/whatsapp_saude.cljs`,
  `scripts/lib/whatsapp-startup.cjs`, `scripts/patch-whatsapp-media.js`,
  `scripts/healthcheck.js`.
- Deploy e instalação: `scripts/deploy-service.py`, `scripts/deploy_auth_profile.py`,
  `scripts/install-deploy.sh`.
- Testes e CI: `test/whatsapp-startup.test.cjs`, `test/whatsapp-resource-patch.test.cjs`,
  `test/healthcheck.test.cjs`, `test/zapbot/whatsapp_saude_test.cljs`,
  `scripts/test-deploy.py`, `scripts/test-deploy-recovery.py`,
  `scripts/test-deploy-docker.py`, `package.json`, `.github/workflows/deploy.yml`.
- Documentação: este arquivo, `docs/deploy-github-actions.md` e
  `docs/estabilidade-whatsapp.md`.

Os cenários incluem erro transitório e não transitório, exaustão, timeout, SIGTERM
durante startup/backoff, browser registrado tardiamente, crash, cleanup incompleto,
logout e QR de perfil existente bloqueados, health inválido e resposta lenta,
interrupção de create/rename/restore/commit, IDs divergentes, dois writers,
snapshot adulterado, falta de espaço, hard links e preservação de `.env`/dados
sintéticos. Nenhum teste exige conexão com WhatsApp ou Cassandra de produção.
