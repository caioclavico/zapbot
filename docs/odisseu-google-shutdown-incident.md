# Odisseu Google: conflito de shutdown em 7d4c801

## Evidências

O [run 37823411736](https://github.com/caioclavico/zapbot/actions/runs/37823411736)
tem falhas distintas. A tentativa 4 terminou com `Readiness timeout` antes de
pull/parada. A consulta do operador confirmou naquele momento
`{"status":"degraded","whatsapp":"ERROR","chromium":false}`. O motivo deste
ERROR anterior não foi recuperado dos logs; não comprova perda de autenticação.

A tentativa posterior (job `113491911856`, 08/10/2026 19:09 UTC) passou na saúde
inicial, puxou a imagem `7d4c801` e recusou o shutdown do original. O operador
forneceu o trecho:

```text
19:09:40.697Z [WhatsApp] Encerrando navegador; preservando sessão.
19:09:40.762Z [WhatsApp] Erro ao encerrar: Chromium closure was not confirmed
```

O helper abortou **antes** de snapshot, locks, rename ou criação do candidato.
O rollback iniciou/verificou a imagem original `fead640`, com o mesmo container.
O problema desta tentativa está no shutdown da imagem antiga, não no pull,
Compose ou autenticação SSH. Não foi lido nem alterado nenhum perfil real.

## Causa reproduzida e correção

`encerrar-com-sessao!` já controla SIGTERM/SIGINT, chama `recovery.close()` e exige
fechamento limpo do Chromium. `opcoes-puppeteer` deixava ativos os handlers de
sinal padrão do Puppeteer. No Puppeteer 24.38.0 instalado, o handler de SIGTERM
chama o fechamento do processo, que pode usar SIGKILL, concorrendo com
`browser.close()` da aplicação. SIGINT também tem tratamento automático de kill
e saída 130. Os [LaunchOptions oficiais](https://pptr.dev/api/puppeteer.launchoptions)
permitem desligar os handlers; o diagnóstico foi feito com o código instalado.

A reprodução local com Chromium real, página vazia e perfil temporário retornou:

- Defaults: `exit_code=null`, `signal=SIGKILL`, `Chromium closure was not confirmed`.
- `handleSIGTERM=false`, `handleSIGINT=false`: `exit_code=0`, `signal=null`.

Essas duas opções passam a false em `opcoes-puppeteer`. Os handlers próprios,
guard de shutdown de 45s, flags de browser, GPU opcional, timeout do deploy,
verificação de exit code/OOM, healthcheck, sessão e rollback permanecem.
O helper registra exit code/OOM quando bloqueia o shutdown. Python usa `-u` para
entregar os logs ao Actions enquanto a operação acontece, sem esperar pelo exit.
`PERFORMANCE_METRICS=false` permanece como padrão; logs essenciais continuam.

## Pré-requisitos e bootstrap efetivo

Fluxo: workflow → `ci-ssh-deploy.sh` (`deploy SHA`) → forced command → sudo
`zapbot-deploy-odisseu` → `deploy-entry.py` → `deploy-vm.sh` → `deploy-service.py`
→ pull/Compose/stop/snapshot/create/start/healthcheck. SSH `check` não passa por
sudo nem consulta o Docker. A tentativa mais recente passou por esses controles
até o stop, portanto não há evidência para modificar SSH novamente.

| Requisito na Google | Preparado por `install-deploy.sh`? |
|---|---|
| Biblioteca `/usr/local/lib/zapbot-deploy/*`, entrada sudo, chave pública restrita | Sim |
| `/etc/zapbot-deploy/odisseu/config.json` e `persistence-version`, root/0600 | Sim |
| `/var/lib/zapbot-deploy/odisseu`, root/0700, base para estado/journal/snapshot | Sim; referências são registradas durante deploy |
| Login GHCR privado em `/etc/zapbot-deploy/odisseu/docker/config.json` | Não; só cria o diretório |
| Python, Docker Engine API v1.45, Compose disponível ao root com PATH `/usr/bin:/bin` | Devem existir previamente |
| Container atual e `.env`, `.wwebjs_auth`, `data`, binds e Compose de produção | Devem existir previamente; nunca são sobrescritos pelo instalador |
| Imagem compilada com a correção dos sinais | Não; precisa de build/publicação de um novo SHA |

O Compose em `fead640` e `7d4c801` é idêntico. Restaurar apenas `scripts/` não
atualiza `target/main.js` dentro da imagem nem os arquivos já instalados em
`/usr/local/lib`; executar o instalador copia os helpers, mas não recria imagem.
O helper fixa `/home/caiohclavico/zapbot`, não usa paths Oracle nem depende do
HOME do login para GHCR. O Env efetivo preserva Cassandra/Pokémon já configurados.
`state.json` é comparado ao ImageID real; referências antigas divergentes e
journals pendentes são bloqueados, não apagados nem corrigidos arbitrariamente.

## Primeira atualização, somente após autorização

Publicar só a imagem corrigida não resolve a primeira parada: o container antigo
ainda instala os handlers concorrentes. Foi preparado um caminho **operador,
opt-in**, `--legacy-graceful-stop`, exclusivo do CLI root para `odisseu deploy`.
O forced command/entrada sudo não aceitam esta opção e o workflow não a solicita.

Dentro da mesma transação/lock, após saúde/pull/validação/journal, ele localiza um
único Chromium do perfil padrão e o endpoint CDP loopback já existente. Confere
por CDP que o endpoint pertence ao mesmo user-data-dir, envia fechamento normal
de browser, espera desaparecer o processo do perfil e só então envia SIGTERM ao
Node. Não usa kill, logout, removeListener, edição da imagem em execução ou
remoção de sessão/locks. Sem endpoint válido, fechamento completo ou exit zero,
aborta e conserva as proteções/evidências. O snapshot só começa após exit zero,
sem OOM, exclusividade e ausência de processos, como no fluxo normal.

Uma vez revisados/publicados os novos arquivos e a **imagem do SHA corrigido**,
na sessão administrativa Google, atualizar só os helpers afetados. Não reinstalar
chaves, sudoers ou configurações que já funcionam. Os comandos abaixo são
procedimento futuro: **não foram executados na VM**.

```sh
cd /home/caiohclavico/zapbot
git fetch origin master
# Use aqui o SHA completo da correção aprovada/publicada, não 7d4c801.
CORRECTED_SHA='SUBSTITUA_PELO_SHA_COMPLETO_APROVADO'
git restore --source="$CORRECTED_SHA" --worktree -- \
  scripts/deploy-service.py scripts/deploy-vm.sh scripts/odisseu-legacy-stop.cjs
sudo install -o root -g root -m 755 \
  scripts/deploy-service.py scripts/deploy-vm.sh scripts/odisseu-legacy-stop.cjs \
  /usr/local/lib/zapbot-deploy/
docker exec zapbot node scripts/healthcheck.js
sudo python3 -u /usr/local/lib/zapbot-deploy/deploy-service.py \
  odisseu deploy "ghcr.io/caioclavico/zapbot:$CORRECTED_SHA" \
  /home/caiohclavico/zapbot --legacy-graceful-stop
docker exec zapbot node scripts/healthcheck.js
docker inspect zapbot --format 'revision={{index .Config.Labels "org.opencontainers.image.revision"}} restarts={{.RestartCount}}'
```

Substituir o placeholder antes de executar. Aguardar o job anterior terminar;
o mesmo lock impede competição com Actions. Se houver journal pendente, revisar
e usar a recuperação existente com autorização, preservando evidências. Sem
saúde inicial, o procedimento também aborta: não forçar a passagem do gate.
Este comando realiza um deploy real e exige autorização específica do operador.

Depois da primeira troca saudável para a imagem corrigida, deploys automáticos
voltam a usar SIGTERM normal e os mesmos secrets/forced command/Compose.
Não é indicado repetir o deploy de `7d4c801`, nem ignorar seu exit não limpo.

## Rollback e limites

Rollback reutiliza ID/imagem/configuração do container retido. Não recompila a
partir do checkout. Se o candidato iniciou, usa o snapshot frio validado e retém
o perfil que falhou como evidência; `.env`, data e Cassandra não são restaurados
nem apagados. Um rollback para `fead640` pode voltar a apresentar o conflito no
próximo shutdown; o opt-in continua necessário para sair dessa imagem legada.

Testes usam perfis temporários/página vazia e servidores fictícios, sem acesso a
WhatsApp, Cassandra ou jogadores. Eles comprovam a disputa de sinais e as
proteções do fluxo, mas não garantem autenticação real. Endpoint CDP inacessível,
perfil personalizado, OOM, falha de persistência ou journal inconsistente precisam
de diagnóstico específico; não há fallback de kill, reset de sessão ou QR.
Uma interrupção entre o fechamento CDP e o SIGTERM pode deixar o Node antigo
ativo com navegador fechado. O journal é preservado, mas a recuperação pode
exigir revisão/parada graciosa autorizada desse ID antes de recuperar a saúde;
não apagar o journal nem repetir o deploy com outro processo usando o perfil.
