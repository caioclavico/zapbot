# Deploy do Odisseu no Google Cloud

Se a parada de `fead640` terminar com `Chromium closure was not confirmed`,
consultar o [diagnóstico e primeira transição controlada](odisseu-google-shutdown-incident.md)
antes de repetir o Actions. A opção de transição é root/operador, não é ativada
automaticamente pelo workflow.

Destino exclusivo do Odisseu: **35.238.24.225**, SSH **caiohclavico**, aplicação
**/home/caiohclavico/zapbot**, container **zapbot**. A antiga Oracle não é acessada
por deploy, rollback ou `check`. Pokémon conserva seu destino e seu helper Docker;
Cassandra não participa da troca. Nenhum comando deste guia foi executado na VM
durante a implementação.

## Preparação administrativa, antes da publicação

1. Manter a automação pausada enquanto os helpers e secrets são preparados;
   alterações de variables exigem autorização do operador. Não publicar esta
   revisão antes de concluir esta preparação. O gate `AUTO_DEPLOY_ENABLED`,
   Environment `production`, concorrência e testes continuam no workflow.
2. Confirmar por console/sessão administrativa Google a identidade SSH do host.
   `ODISSEU_KNOWN_HOSTS` precisa conter a entrada OpenSSH para `35.238.24.225`,
   com chave e fingerprint reais comparados à chave pública do host no console.
   Porta diferente de 22 exige entrada `[35.238.24.225]:PORTA` e ajuste da variable
   `ODISSEU_SSH_PORT`. Não reutilizar a entrada Oracle.
3. Usar uma chave **exclusiva Actions**, Ed25519, sem passphrase. O secret
   `ODISSEU_SSH_KEY` contém o arquivo privado completo desta chave; a pública vai
   ao instalador. Não usar a chave administrativa para automação. Os secrets de
   Pokémon permanecem iguais. `GITHUB_TOKEN` continua automático para GHCR.
4. Conferir os requisitos sem imprimir Env ou perfis:

```sh
cd /home/caiohclavico/zapbot
docker compose version
docker version --format '{{.Server.Version}}'
docker inspect zapbot --format 'id={{.Id}} running={{.State.Running}} restarting={{.State.Restarting}} restart={{.HostConfig.RestartPolicy.Name}}'
docker exec zapbot node scripts/healthcheck.js
stat -c '%U:%G %a %n' .env .wwebjs_auth data docker-compose.production.yml
df -h . /var/lib
du -sh .wwebjs_auth
```

O `.env` deve ser regular, não vazio, sem permissão para outros/gravação de grupo
(0600 recomendado); sessão e data devem ser diretórios reais e binds já montados
em `/app/.wwebjs_auth` e `/app/data`. `unless-stopped` é aceito; `always` é bloqueado
por poder reativar um container retido após restart do Docker. Recursos e redes
existentes são preservados, sem mudanças em Docker/Linux. Verifique espaço para
**duas cópias lógicas do perfil + 64 MiB**, imagens e evidências já existentes.
O helper repete a checagem de espaço após shutdown antes do snapshot.

5. Revisar o `docker-compose.production.yml` existente: apenas serviço `bot`,
   `container_name: zapbot`, imagem `${ZAPBOT_IMAGE}`, `.env` local, os dois binds
   já existentes, sem dependências, volumes novos ou hooks. O workflow **não
   sobrescreve este arquivo nem `.env`**. O helper valida o Compose antes da
   parada e confere configuração real após criação, antes de iniciar Chromium.
   O comando declarado deve coincidir com o container atual; um CMD legado com
   limpeza embutida exige revisão administrativa, não é reaplicado silenciosamente.
   Se a configuração divergir, aborta; não remova verificações para liberar deploy.
6. Transferir **somente helpers revisados e chave pública** por acesso
   administrativo autorizado. O pacote do [guia geral](deploy-github-actions.md)
   inclui `deploy_compose.py`. Instalar manualmente na Google:

```sh
sudo bash /caminho/do/pacote/scripts/install-deploy.sh \
  odisseu caioclavico /caminho/da/chave-actions.pub --confirm-reviewed-persistence-v1
```

O instalador não inicia containers. Reutiliza `caiohclavico` sem modificar UID,
home, senha ou grupos; conserva outras chaves no `authorized_keys`, adicionando
uma única entrada `restrict,command="...deploy-ssh-command.py odisseu"`. Se a
mesma chave tiver outra entrada/permissão, aborta. `.ssh` e `authorized_keys`
tornam-se root-owned; a chave Actions permite apenas `check`, `deploy SHA` e
`rollback`, com sudo limitado ao helper Odisseu. As capacidades administrativas
anteriores do usuário continuam disponíveis por suas chaves administrativas.
Se OS Login gerenciar SSH, confirme com o operador como aplicar o comando forçado;
não substitua a chave restrita por uma chave sem restrições.

Para GHCR privado, configurar login **na VM** em
`/etc/zapbot-deploy/odisseu/docker/config.json` root/0600, usando token real com
`read:packages` via `--password-stdin`, conforme guia geral. Não precisa de novo
secret GitHub para pull nem de enviar credenciais de Cassandra/WhatsApp.

7. Cadastrar os dois secrets do Odisseu no Environment `production`, conferindo
   secrets homônimos: os do Environment têm precedência. A operação manual
   `check`, serviço `odisseu`, branch `master`, verifica autenticação, identidade
   de host e comando restrito sem executar deploy. Ela não valida prontidão do
   Compose/helpers; isto depende da preparação administrativa acima.
8. Após revisão/autorização de publicação, manter `AUTO_DEPLOY_ENABLED=true` para
   o fluxo automático solicitado. Configurar esta variable não é feito pelo
   código. Aprovação de `production` continua conforme regras reais do GitHub.

## Fluxo automático

Push/merge em `master` → seleção de mudanças/testes obrigatórios → imagem GHCR
`ghcr.io/caioclavico/zapbot:SHA_COMPLETO`, Linux AMD64, revisão validada e tag
imutável → SSH verificado na Google → helper com lock/journal → pull antes da
parada → validação do Compose → shutdown limpo do anterior → exclusividade do
perfil nos containers **e nos processos do host** → snapshot frio com checksum →
remoção apenas dos três locks órfãos → retenção do container anterior →
`compose create --no-build --pull never bot` → validação de ID/revisão/Env/binds/
recursos → `compose start bot` → healthcheck e estabilidade → estado confirmado.

Cada transação recebe projeto Compose próprio, mas usa redes existentes como
externas (ou `network_mode` atual), evitando que Compose encontre e remova o
container anterior renomeado. Um plano JSON resolvido root/0600 fica no diretório
privado de estado; pode conter Env e nunca deve ser publicado ou impresso.
Não há `compose down`, limpeza de volumes, reaplicação de `.env` nem novos serviços.
`172.17.0.1:9042` e `http://10.128.0.2:8080` continuam vindos do ambiente existente.

O healthcheck executado é `docker exec zapbot node scripts/healthcheck.js` através
da API Docker Exec. Só retorna zero com HTTP 200, `status=ok`, `whatsapp=READY` e
`chromium=true`; o helper exige ainda Cassandra carregado, janela estável de 30 s
e nenhum restart. O prazo continua 600 s. `PERFORMANCE_METRICS=false` é o padrão
da imagem; overrides operacionais existentes não são trocados automaticamente.

## Rollback e recuperação

Falha de criação/startup/healthcheck: parar candidato, comprovar saída limpa e
ausência de escritores, revalidar/restaurar snapshot frio se candidato iniciou,
preservar perfil que falhou como evidência, remover somente locks órfãos,
renomear/iniciar o **ID anterior retido**, verificar saúde e restaurar referências.
O job original permanece com erro mesmo após rollback saudável. A restauração
usa o Docker por ID, podendo recuperar um container anterior que não era Compose.

Rollback manual via workflow: `operation=rollback`, `service=odisseu`, `master`.
Reutiliza o container/imagem anterior retido com as mesmas proteções. Nunca envia
rollback à Oracle. Rollback não desfaz operações já concluídas pelo domínio.

Se shutdown, restauração ou saúde do anterior falhar, bloquear novas trocas e
reter journal, snapshots, planos e containers. Não editar IDs para forçar sucesso,
não apagar perfil/locks manualmente com Chromium ativo e não solicitar QR como
fallback automático. Após revisão administrativa dos IDs e autorização:

```sh
sudo python3 /usr/local/lib/zapbot-deploy/deploy-service.py \
  odisseu recover /home/caiohclavico/zapbot
```

`recover` é idempotente para journal v2 e só arquiva após identidade/saúde
confirmadas. Journal legado/ambíguo requer revisão e mantém evidências.

Verificação depois do deploy autorizado:

```sh
docker exec zapbot node scripts/healthcheck.js
docker inspect zapbot --format 'id={{.Id}} image={{.Image}} revision={{index .Config.Labels "org.opencontainers.image.revision"}} restarts={{.RestartCount}}'
```

Esperado: `{"status":"ok","whatsapp":"READY","chromium":true}`, revisão do SHA
aprovado e zero restarts. Testes locais usam servidor fictício; não comprovam
autenticação WhatsApp ou permissões/connectividade da VM Google real.
