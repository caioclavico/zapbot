# Ambiente privado do Odisseu

Durante `deploy <SHA>`, o executor combina o ambiente atual com declarações
válidas do `.env` local. Ausência preserva o valor atual; `KEY=` limpa explicitamente
um valor não protegido. Mesmo SHA com diferenças autorizadas recria somente o bot,
com parada limpa, snapshot da sessão, readiness e rollback existentes.
Rollback usa o container retido e seu ambiente antigo; não relê o `.env`.
O arquivo `.env` nunca é reescrito pelo executor. O Pokémon não utiliza este fluxo.

Protegidos: `CASSANDRA_*`, `POKEMON_*`, `ZAPBOT_*`, `DEPLOY_*`, `DOCKER_*`,
`HOST`, `PORT`, `APP_ENV`, `DEV_GROUP_ID`, `NODE_OPTIONS`, `NODE_ENV`, `PATH`,
`HOME`, `USER`, `PWD`, `API_TOKEN`, `PUPPETEER_EXECUTABLE_PATH`, `CHROME_BIN`,
`ADVENTURE_SYNC_ENABLED` e `POKEMON_READ_ONLY`.
Declarações protegidas diferentes (ou ausentes no ambiente atual) abortam antes
de parar o container. Alterações de infraestrutura exigem revisão específica.

Parser: UTF-8, uma declaração por linha, comentários, `export KEY=value`, valores
literais simples ou entre aspas simples/duplas. Não interpola `$`, não executa
shell, não aceita duplicatas, multiline, escapes de aspas ou backticks.
Erros não incluem valores. Arquivo regular, sem symlink, até 1 MiB, sem permissões
para grupo/outros e proprietário root ou proprietário do diretório da aplicação.
A validação do substituto verifica também os valores autorizados e rejeita
novas variáveis não autorizadas. Compose usa esse ambiente já validado.

## Instalação supervisionada

Execute na VM correta, numa sessão administrativa. A localização da aplicação
não identifica se a VM é Oracle ou Google; confirme o host antes de executar.
Não use o instalador completo para uma atualização destes dois helpers:
o instalador também configura usuário, chave forçada e sudoers. A atualização
abaixo preserva esses arquivos e usa os mesmos caminhos root-owned do instalador.
Copie primeiro os arquivos revisados para um checkout local na VM, sem substituir
`.env`, sessão, Compose ou data. Ainda não há commit publicado desta alteração.

```sh
cd /home/caiohclavico/zapbot
python3 -m py_compile scripts/deploy-service.py scripts/deploy_compose.py
sudo install -o root -g root -m 755 scripts/deploy-service.py /usr/local/lib/zapbot-deploy/deploy-service.py
sudo install -o root -g root -m 755 scripts/deploy_compose.py /usr/local/lib/zapbot-deploy/deploy_compose.py
sudo chmod 600 /home/caiohclavico/zapbot/.env
```

Edite o `.env` em editor privado e declare `GROQ_API_KEY` e `GROQ_MODEL` com os
valores reais. Não use `cat`, `echo` ou comandos que imprimam a chave.
Confirme que declarações protegidas coincidem com o container atual.
Os comandos abaixo consultam só a revisão e solicitam deploy da mesma imagem,
sem novo build. Este último comando altera produção: executar apenas após revisão.

```sh
revision=$(docker inspect zapbot --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
case "$revision" in ''|*[!0-9a-f]*) exit 1;; esac
[ "${#revision}" -eq 40 ] || exit 1
sudo /usr/local/sbin/zapbot-deploy-odisseu deploy "$revision"
docker exec zapbot node scripts/healthcheck.js
```

O healthcheck deve indicar `status=ok`, `whatsapp=READY`, `chromium=true`.
Em falha, o rollback transacional restaura ambiente e sessão anteriores. Para
rollback manual, use `sudo /usr/local/sbin/zapbot-deploy-odisseu rollback`.
Corrija o `.env` antes de repetir deploy, pois rollback não desfaz sua edição.
`update-tmdb` continua compatível; novos deploys não precisam dele.
