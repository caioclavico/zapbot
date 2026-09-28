# Estabilidade do WhatsApp em VM de 1 GB

## Análise da estrutura existente

O projeto compila ClojureScript para um único processo Node (`target/main.js`).
`core/on-message` encaminha mensagens para histórico, router e módulos de comandos.
Os agendadores de lembretes e raides são ligados por `on-ready`. Cassandra é
acessado pela camada `armazenamento`, antes da inicialização do WhatsApp.
Não havia servidor HTTP, supervisor de reconexão nem outro ponto de criação de
Client/Puppeteer no código da aplicação. Os scripts de migração são entradas
separadas e não fazem parte do startup do bot.

| Área | Local e comportamento anterior |
|---|---|
| Client e LocalAuth | `src/zapbot/core.cljs`, função `main`, uma construção por chamada |
| Puppeteer | Opções no mesmo `main`, executável de `config.cljs`, quatro flags explícitas |
| initialize | Depois de `armazenamento/iniciar!`, erro apenas registrado |
| Eventos | `qr`, `ready`, `auth_failure`, `disconnected` em core; faltavam `authenticated` e `loading_screen` |
| Watchdog | `avisar-se-travar!`: 60s, cancelado em QR/ready, texto sugeria travamento |
| Docker | Dockerfile, Compose local e Compose de produção, `unless-stopped` já presente |
| Sessão | LocalAuth sem clientId: `/app/.wwebjs_auth/session`; bind mount do host |
| Problema na sessão | CMD executava `find ... Singleton* -delete` em cada startup |
| Reconexão | Aplicação não tinha reconexão automática nem guarda contra chamar main duas vezes |
| Tradução | Toda tentativa chamava Google, inclusive imediatamente após 429; Gemini já era fallback |

A dependência instalada usa `protocolTimeout` padrão de 180.000ms. A opção regula
cada chamada CDP, não o tempo total até READY. A versão de whatsapp-web.js fixada
no lockfile tem também esperas próprias e navegações durante a injeção.
Aumentar o timeout **não corrige necessariamente** `Execution context was destroyed`:
este erro também ocorre quando uma navegação invalida o contexto JavaScript.
Não foi alterada a dependência nem implementado ciclo de reinicialização para mascará-lo.

## Alterações e arquivos

- `src/zapbot/core.cljs`: integra diagnóstico, guarda uma tentativa de main por
  processo, protocolo de 300s e fechamento do Client em SIGTERM/SIGINT.
- `src/zapbot/whatsapp_saude.cljs`: estado por eventos, três avisos em 60/120/180s,
  logs com timestamp ISO, progresso por faixas de 25%, GET `/health`.
- `src/zapbot/traducao.cljs`: após 429, Google entra em cooldown de 60s;
  `Retry-After` em segundos/data é respeitado dentro de 60s–15min. Durante a pausa,
  usa Gemini diretamente. Não altera o texto original usado quando ambos falham.
  Requisições já em voo não são canceladas; não há cache de traduções novo.
- `Dockerfile`: remove limpeza da sessão, inicia Node diretamente, copia a sonda
  e inclui HEALTHCHECK a cada 60s, timeout 10s, carência 180s, três falhas.
- `docker-compose.yml` e `docker-compose.production.yml`: só o serviço bot recebe
  60s para parada e comando Node explícito, inclusive ao usar imagens antigas.
  Volumes, `LocalAuth`, caminho/clientId e serviço Cassandra permanecem iguais.
- `scripts/healthcheck.js`: consulta local usando `node:http`, timeout de 5s,
  imprime JSON e retorna 0 para saudável/1 para degradado ou inacessível.
- `scripts/deploy-vm.sh`: usa saúde atual, não apenas um log READY antigo;
  atraso após 300s falha o workflow sem rollback/restart. Guarda `previous-image`.
  Falha do processo/banco continua acionando o rollback já existente.
- `test/zapbot/core_test.cljs`, `test/zapbot/whatsapp_saude_test.cljs`,
  `test/zapbot/traducao_test.cljs`, `scripts/test-deploy.py`: testes com clientes,
  respostas HTTP e Docker simulados, sem login ou acesso à sessão.
- `README.md` e este documento: operação e limitações.

As alterações de GitHub Actions, versão 0.19.0 e evento Novo Recomeço já estavam
no workspace antes desta revisão. Não foram desfeitas nem ampliadas. Esta revisão
não modifica módulos de jogos, comandos, schema ou configuração Cassandra.

## Saúde e limites

Não foi adicionado Express ou dependência. O servidor embutido de Node escuta
somente `127.0.0.1:3001`, dentro do container, sem porta publicada.

```bash
sudo docker exec zapbot node scripts/healthcheck.js
sudo docker inspect --format '{{.State.Status}} / {{.State.Health.Status}}' zapbot
sudo docker logs --since 10m --timestamps zapbot
sudo docker stats --no-stream zapbot
```

Exemplos:

```json
{"status":"ok","whatsapp":"READY","chromium":true}
{"status":"degraded","whatsapp":"STARTING","chromium":false}
```

Estados: STARTING, QR, AUTHENTICATED, READY, DISCONNECTED, AUTH_FAILURE e ERROR.
HTTP 200 exige READY, Puppeteer conectado e página aberta; demais casos HTTP 503.
`whatsapp` representa o último estado recebido; se Chromium cair depois de READY,
`chromium:false` torna a resposta degradada mesmo antes de outro evento do WhatsApp.
Node travado/encerrado faz a consulta expirar/falhar. Docker UP sozinho não é saúde.

A consulta não envia mensagens nem executa JavaScript remoto. Assim, não comprova
latência de entrega ou responsividade de um renderer conectado mas congelado.
O watchdog só registra três diagnósticos; em QR informa espera de leitura.
READY, erro ou desconexão cancelam os avisos pendentes. Não há autoheal.
`unless-stopped` continua recuperando saída do processo; estado unhealthy sozinho
não dispara restart. A guarda impede duas inicializações pela aplicação no mesmo
processo; não impede alguém de iniciar manualmente outro container com o mesmo perfil.

## Sessão e parada

A aplicação não chama logout nem apaga arquivos. `Client.destroy()` na versão
instalada fecha o browser e chama `authStrategy.destroy()`; LocalAuth herda esse
método vazio. Isso difere de `LocalAuth.logout()`, que remove o perfil e não é usado
por nossa rotina de parada. Uma revogação real pelo WhatsApp continua sujeita ao
comportamento da biblioteca; não é possível garantir autenticação contra revogação.

SIGTERM/SIGINT fecha o navegador, com limite de 45s para a parada explícita;
Compose concede 60s. Não existe timeout de startup que mate o cliente.
Parada forçada, host sem recursos ou a primeira troca de uma imagem antiga sem
shutdown gracioso podem deixar locks. Nesse caso, diagnostique o erro e os processos;
o startup não tentará remover SingletonLock nem recriar a sessão.
`.dockerignore` mantém credenciais fora da imagem. Nunca usar `down -v`, limpar
`.wwebjs_auth` ou executar dois bots com o mesmo bind mount para este procedimento.

## Recursos: o que mudou e o que foi preservado

Não é esperada redução expressiva de RAM do Chromium. O servidor local adiciona
pequeno custo não medido; cada sonda executa um Node curto por minuto. Cooldown
reduz chamadas inúteis ao Google durante limitação, sem suprimir Gemini.
Timeout de protocolo passa de 180 para 300s, finito, tolerando a lentidão observada;
ele não acelera processamento e mantém operações pendentes por mais tempo.

As quatro flags da aplicação foram mantidas. `--enable-gpu-rasterization` não
aparece na configuração do projeto; sua origem no executável/pacote da imagem
precisa ser verificada no runtime. Não foi adicionado `--disable-gpu`: ausência de
GPU física não torna SwiftShader/processo GPU automaticamente dispensável.
Não foram removidos renderers, serviços network/storage, flags padrão, caches,
swap ou processos por RSS. Também não houve limite de heap novo nem ajuste de
mem_limit existente (1280m não cria RAM física na VM de 954MiB).

## Deploy seguro

As mudanças são locais até revisão/commit/push. Não foi feito deploy ou login real.
O workflow existente constrói a imagem no runner e envia à VM no push da master.
Como o workspace contém mudanças anteriores de jogos, revise-as separadamente
antes de publicar; este documento não implica que foram adicionadas nesta tarefa.

Depois que o commit desejado estiver na master e secrets/variables do README
estiverem configurados, o gatilho é:

```bash
git push origin master
```

Para repetir um deploy cuja imagem e arquivos **já foram enviados pelo Actions**,
na VM, substitua o SHA de 40 caracteres:

```bash
APP_DIR=/home/ubuntu/zapbot
REVISAO=COLE_O_SHA_DE_40_CARACTERES
bash "$APP_DIR/releases/$REVISAO/deploy-vm.sh" "$APP_DIR" "$REVISAO"
```

Não execute o Compose local de desenvolvimento na VM: ele inclui Cassandra.
O deploy de produção troca somente bot, preserva binds e não remove imagens antigas.
Se levar mais de 300s, o workflow fica vermelho, mas a instância é mantida;
consulte a saúde novamente, sem repetir o deploy só por causa da espera.

Para reiniciar a versão instalada, quando realmente necessário:

```bash
sudo docker restart --time 60 zapbot
sudo docker exec zapbot node scripts/healthcheck.js
```

A primeira consulta pode estar degradada enquanto inicializa. Espere e consulte
novamente; isso não exige gerar outro QR. Se a conta tiver sido revogada, o estado
QR é diagnóstico real e exige atendimento humano, sem limpeza de arquivos.

## Rollback

O deploy grava a imagem anterior em `/home/ubuntu/zapbot/previous-image` antes da
troca. Use o Compose da **nova release**, que substitui o CMD de imagens antigas
para impedir sua antiga limpeza automática de locks. Não altere os binds.

Na VM, usando o SHA da release que acabou de ser instalada:

```bash
APP_DIR=/home/ubuntu/zapbot
REVISAO=COLE_O_SHA_DA_RELEASE_NOVA
IMAGEM_ANTERIOR=$(cat "$APP_DIR/previous-image")
sudo docker image inspect "$IMAGEM_ANTERIOR" >/dev/null
sudo env ZAPBOT_APP_DIR="$APP_DIR" ZAPBOT_IMAGE="$IMAGEM_ANTERIOR" \
  docker compose --project-name zapbot \
  -f "$APP_DIR/releases/$REVISAO/docker-compose.production.yml" \
  up -d --no-build --pull never --no-deps bot
sudo docker logs --since 5m --timestamps zapbot
```

Imagem antiga pode não ter `/health` nem shutdown gracioso: nesse caso confira os
logs e o funcionamento, sem assumir que o novo diagnóstico estará disponível.
Rollback de imagem não restaura snapshots nem apaga credenciais. Não há migração
de sessão ou Cassandra nesta revisão.

## Validação executada

- `npm test`: 147 testes, 1.014 assertions, zero falhas/erros.
- `npm run build`: compilação concluída; 162 warnings preexistentes de inferência
  de tipos/depreciações, sem novos warnings nas áreas modificadas.
- `python3 scripts/test-deploy.py`: cinco testes passaram (Docker simulado).
- `bash -n scripts/deploy-vm.sh`, `node --check scripts/healthcheck.js` e
  `git diff --check`: passaram.
- YAML de ambos os Composes e workflow carregado com `js-yaml`.
- Conferência do serviço Cassandra byte a byte e hashes dos módulos de jogos:
  nenhuma mudança nesta revisão. Binds da sessão preservados.
- Não há lint configurado em package.json/CLAUDE.md.
- Docker indisponível localmente: não executados `docker build`,
  `docker compose config`, container real ou verificação de processos na VM.
  Validação de YAML não substitui a validação semântica do Compose.
- A guarda de inicialização foi testada com Client simulado: duas chamadas a
  main e nova chamada após rejeição produzem só um Client/initialize.
- Nenhum deploy, restart de produção, login/logout ou manipulação de credenciais
  foi executado nesta revisão. Consumo adicional de RAM não foi medido.

## Fontes

- [Puppeteer: protocolTimeout](https://pptr.dev/api/puppeteer.connectoptions)
- [Docker: restart policies](https://docs.docker.com/engine/containers/start-containers-automatically/)
- Implementação instalada e fixada em package-lock: whatsapp-web.js
  `src/Client.js`, `src/authStrategies/LocalAuth.js` e `BaseAuthStrategy.js`.

## Hostname persistente para o perfil existente

Quando um perfil aponta para um hostname de container antigo já removido, após
confirmar que não há outro navegador usando o perfil, mantenha esse hostname em
`/home/ubuntu/zapbot/docker-compose.hostname.yml`:

```yaml
services:
  bot:
    hostname: cf399453163f
```

Esse é o hostname identificado nesta instalação; outras instalações devem usar
o hostname correspondente ao próprio perfil. Não é o hostname da VM Oracle.
O deploy inclui esse override se presente, tanto na atualização quanto no rollback.
Em operações manuais de Compose, acrescente
`-f "$APP_DIR/docker-compose.hostname.yml"` após o Compose da release.
O arquivo fica fora das releases e não contém credenciais. Nenhum arquivo do
perfil é removido pelo script; a verificação do lock pertence ao Chromium.
