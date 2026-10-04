# Deploy do Odisseu e Pokémon com GitHub Actions

## Fluxo e estado inicial

O único workflow é `.github/workflows/deploy.yml`. PRs para `master`
executam CI sem Environment ou secrets de produção. Push na `master` identifica
alterações, testa e publica apenas os serviços afetados. Deploy manual e rollback
usam `workflow_dispatch` na `master`. O deploy automático exige a variable de
repositório `AUTO_DEPLOY_ENABLED=true`; ausente ou `false`, ele fica desabilitado.

`master` permanece como branch principal e de produção. Esta revisão não cria,
renomeia ou migra branches e não modifica o histórico Git. A automação não foi
habilitada nesta etapa. Os comandos de configuração e deploy deste documento
são para execução manual após revisão e autorização explícita.

| Serviço | Imagem GHCR | VM / container | Diretório preservado |
|---|---|---|---|
| Odisseu | `ghcr.io/caioclavico/zapbot:SHA` | `129.148.52.187` / `zapbot` | `/home/ubuntu/zapbot` |
| Pokémon | `ghcr.io/caioclavico/zapbot-pokemon:SHA` | `34.68.189.66` / `zapbot-pokemon` | `/home/caiohclavico/pokemon-service` |
| Cassandra | Não publicada | `144.22.248.79` / `zapbot-cassandra` | Não acessado pela pipeline |

Builds usam Buildx, cache GitHub Actions e `linux/amd64`, fora das VMs. Imagens
recebem SHA completo, sem dependência de `latest`. Nenhum teste de CI conecta a
Cassandra de produção ou envia mensagens WhatsApp.

## Quais serviços são afetados

`scripts/ci-changes.py` compara cada serviço com seu último deploy confirmado
na `master`, incluindo os dois caminhos de renames. Os marcadores por serviço são
artifacts GitHub sem secrets, registrados apenas após saúde confirmada. Isso
preserva alterações acumuladas quando outro push torna uma execução obsoleta,
ou quando só um dos dois deploys passa. São consultados até 300 artifacts, com
retenção de 90 dias; marcador ausente/expirado, histórico incompleto ou API indisponível provoca
validação completa do serviço, sem omitir alterações. Rollback invalida seu
marcador; PRs não podem estabelecer baseline de produção.

Pokémon possui fonte, dependências, assets e testes
próprios em `pokemon-service/`. Odisseu usa `src/`, `test/`, seus assets e arquivos
de build na raiz. Contratos HTTP, scripts de deploy e mudanças desconhecidas
validam ambos. Documentação isolada não provoca deploy. O primeiro push sem
marcadores valida ambos. O workflow também valida os testes da própria
infraestrutura de deploy e usa containers HTTP fictícios com Docker real para
verificar falha de saúde, rollback e preservação de dados.

Não há reinício de um serviço por alteração exclusiva no outro. Quando ambos
precisam de deploy, Odisseu é atualizado primeiro; sua falha impede continuar
para Pokémon. Isso não resolve incompatibilidades entre versões da API: mudanças
de contrato precisam permanecer compatíveis durante a transição.

## Preservação e rollback

A rotina inspeciona o container atual e preserva sua configuração efetiva,
incluindo hostname, Env, montagens, portas e limites de memória/swap. Odisseu
mantém `.wwebjs_auth` e `data`; Pokémon mantém o volume de `/app/data` e porta
8080. Os `.env` não são copiados, impressos ou sobrescritos.

`POKEMON_READ_ONLY` vem do **Env efetivo do container atual**. Isso preserva um
override operacional mesmo quando o valor do `.env` difere. A pipeline não
habilita writes nem muda timers por conta própria. Alterações operacionais de
ambiente continuam sendo feitas pelo operador; editar apenas `.env` não muda
o Env capturado durante este deploy.

As VMs precisam de Docker Engine 26 ou superior (API v1.45), Python 3.9 ou
superior e contas/configuração instaladas conforme este guia.
O pull e as verificações preliminares acontecem antes da parada. A rotina aguarda
encerramento gracioso do container anterior e verifica sua saída antes de iniciar
a nova versão. A tolerância de parada é de pelo menos 60 segundos no Odisseu e
90 no Pokémon. Nunca há duas instâncias ativas compartilhando sessão ou estado.
Não existe limpeza automática de locks, credenciais, volumes ou dados Cassandra.

Odisseu exige Cassandra carregado e o healthcheck existente com WhatsApp `READY`
e Chromium conectado. Pokémon exige HTTP 200 em `/health` e `/ready`, incluindo
hidratação concluída. A rotina verifica estabilidade e ausência de reinícios.
Há até 600 segundos para prontidão, incluindo uma janela estável de 30 segundos.
Falha de startup, saúde ou timeout provoca rollback para o container anterior,
seguido de nova verificação de saúde. Falha do próprio rollback encerra com erro
e exige recuperação manual; ela nunca é reportada como sucesso.

Os Dockerfiles têm labels `io.zapbot.service` e
`io.zapbot.persistence-version=1`. A versão precisa coincidir com a versão
operacional revisada da VM. Imagem legada sem label exige baseline explícito
`/etc/zapbot-deploy/<serviço>/persistence-version`. Uma mudança incompatível deve
incrementar o label; o deploy será bloqueado antes de parar produção. A revisão
de código deve identificar incompatibilidades que uma comparação de labels
não consegue inferir. Nenhuma migração é executada pela pipeline.

## Secrets e variables GitHub

Checklist do Environment **production** para operar ambos os serviços:

| Requisito | Secret obrigatório | Conteúdo esperado |
|---|---|---|
| ☐ | `ODISSEU_SSH_KEY` | Chave privada exclusiva do Actions para Odisseu |
| ☐ | `ODISSEU_KNOWN_HOSTS` | Entrada SSH do Odisseu, fingerprint verificado |
| ☐ | `POKEMON_SSH_KEY` | Chave privada exclusiva do Actions para Pokémon |
| ☐ | `POKEMON_KNOWN_HOSTS` | Entrada SSH do Pokémon, fingerprint verificado |

Checklist das variables de repositório. As opções de conexão podem ficar
ausentes quando os defaults correspondem às contas e hosts instalados:

| Requisito | Variable | Obrigatoriedade | Default / estado antes da autorização |
|---|---|---|---|
| ☐ | `AUTO_DEPLOY_ENABLED` | Exige `true` para ativação automática futura | Ausente ou `false` desabilita deploy automático |
| ☐ | `ODISSEU_HOST` | Opcional | `129.148.52.187` |
| ☐ | `ODISSEU_USER` | Opcional | `zapbot-deploy` |
| ☐ | `ODISSEU_SSH_PORT` | Opcional | `22` |
| ☐ | `POKEMON_HOST` | Opcional | `34.68.189.66` |
| ☐ | `POKEMON_USER` | Opcional | `pokemon-deploy` |
| ☐ | `POKEMON_SSH_PORT` | Opcional | `22` |

Os cadastros atuais de secrets e variables **não foram auditados**: o conector
GitHub disponível não expõe APIs para consultá-los e um inventário autenticado
não esteve disponível. A existência e as regras atuais de `production` também
precisam ser conferidas. Estas tabelas enumeram requisitos; não confirmam que
estão cadastrados e não mostram valores de secrets. O estado real de
`AUTO_DEPLOY_ENABLED` não foi alterado nesta revisão.
Um operador autorizado precisa conferir os nomes em Settings → Environments →
production e Settings → Secrets and variables → Actions antes da ativação.

Com autenticação e permissões adequadas, estas consultas exibem apenas nomes e
metadados, sem imprimir valores dos secrets ou variables:

```sh
gh api repos/caioclavico/zapbot/environments/production \
  --jq '{name, deployment_branch_policy, protection_rules: [(.protection_rules // [])[] | .type]}'
gh api repos/caioclavico/zapbot/environments/production/secrets \
  --jq '.secrets[] | {name, updated_at}'
gh api repos/caioclavico/zapbot/actions/variables \
  --jq '.variables[] | {name, updated_at}'
```

Uma resposta de erro nessas consultas não comprova a ausência do cadastro.

Antes de publicar esta revisão, confirme que `AUTO_DEPLOY_ENABLED` está ausente
ou `false` enquanto a ativação não tiver sido autorizada. A consulta abaixo
mostra somente o estado normalizado da flag, sem imprimir seu valor arbitrário:

```sh
gh api repos/caioclavico/zapbot/actions/variables/AUTO_DEPLOY_ENABLED \
  --jq 'if (.value | ascii_downcase) == "true" then "AUTO_DEPLOY_ENABLED habilitado" else "AUTO_DEPLOY_ENABLED desabilitado" end'
```

Se a consulta falhar, confirme o cadastro na interface com uma conta autorizada
antes do push; não deduza o estado da flag a partir do erro.

`GITHUB_TOKEN` é fornecido automaticamente pelo GitHub. Apenas publicação de
imagens usa `packages: write`; os demais jobs usam permissões mínimas. Nenhum
API_TOKEN, credencial Cassandra ou conteúdo `.env` é necessário no GitHub.
Os antigos `ORACLE_SSH_KEY`/`ORACLE_KNOWN_HOSTS` não são usados por este workflow.

Se uma consulta autenticada com as permissões necessárias confirmar que
`production` ainda não existe, após autorização para configuração use os
comandos abaixo para criá-lo com deploys restritos à `master`. Para um
Environment existente, ajuste a policy em Settings preservando suas regras
de aprovação; confira a configuração antes de alterá-la:

```sh
gh variable set AUTO_DEPLOY_ENABLED --repo caioclavico/zapbot --body false
printf '%s\n' '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
  gh api --method PUT repos/caioclavico/zapbot/environments/production --input -
gh api --method POST repos/caioclavico/zapbot/environments/production/deployment-branch-policies \
  -f name=master -f type=branch
```

Em Settings → Environments → production, configure as regras disponíveis no
plano do repositório, com revisor obrigatório e autoaprovação desabilitada.
`AUTO_DEPLOY_ENABLED=true` libera o agendamento dos jobs; a aprovação do
Environment continua exigida em cada deploy conforme suas regras. Mantenha
`master` protegida com PR/revisão obrigatórios e policy de deploy somente
`master`. Se o plano não oferecer os controles necessários, mantenha a automação
desabilitada até existir um controle aprovado. As regras reais de `production`
não foram modificadas nesta revisão.

Proteja os workflows, Dockerfiles, scripts de deploy e labels de persistência
com revisão de código. PRs de forks não têm acesso a produção; o workflow não
usa `pull_request_target`.

## Chaves exclusivas e identidade dos hosts

No Mac, gere duas chaves novas, fora do repositório:

```sh
ssh-keygen -t ed25519 -N '' -C github-actions-odisseu -f "$HOME/.ssh/zapbot-actions-odisseu"
ssh-keygen -t ed25519 -N '' -C github-actions-pokemon -f "$HOME/.ssh/zapbot-actions-pokemon"
chmod 600 "$HOME/.ssh/zapbot-actions-odisseu" "$HOME/.ssh/zapbot-actions-pokemon"
```

Apenas os arquivos `.pub` serão transferidos para as VMs. A chave privada nova
vai diretamente para o secret GitHub por stdin; não cole seu valor em terminal,
issue, log ou arquivo versionado.

Pelo console confiável de **cada VM**, obtenha o fingerprint da chave de host:

```sh
sudo ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256
```

No Mac, colete as entradas candidatas e compare seus fingerprints com os do
console. `ssh-keyscan` sozinho não valida a identidade:

```sh
ssh-keyscan -T 10 -t ed25519 129.148.52.187 > /tmp/odisseu-known-hosts
ssh-keyscan -T 10 -t ed25519 34.68.189.66 > /tmp/pokemon-known-hosts
ssh-keygen -lf /tmp/odisseu-known-hosts -E sha256
ssh-keygen -lf /tmp/pokemon-known-hosts -E sha256
```

Somente após confirmar a igualdade, registre secrets:

```sh
gh secret set ODISSEU_SSH_KEY --repo caioclavico/zapbot --env production < "$HOME/.ssh/zapbot-actions-odisseu"
gh secret set POKEMON_SSH_KEY --repo caioclavico/zapbot --env production < "$HOME/.ssh/zapbot-actions-pokemon"
gh secret set ODISSEU_KNOWN_HOSTS --repo caioclavico/zapbot --env production < /tmp/odisseu-known-hosts
gh secret set POKEMON_KNOWN_HOSTS --repo caioclavico/zapbot --env production < /tmp/pokemon-known-hosts
```

Para outra porta, use `ssh-keyscan -p PORTA` e a entrada `[host]:PORTA`. O Actions
sempre usa `StrictHostKeyChecking=yes` e `IdentitiesOnly=yes`.

## Instalação manual nas VMs

Revise primeiro os scripts e a compatibilidade de persistência v1 com o
container atual. O instalador é manual: cria a conta exclusiva quando ausente
ou reutiliza a conta existente sem alterar seu UID ou home; instala arquivos
root-owned e não inicia, para ou recria nenhum container.

No Mac, dentro do checkout revisado:

```sh
tar -czf /tmp/zapbot-deploy-bootstrap.tgz \
  scripts/install-deploy.sh scripts/deploy-entry.py scripts/deploy-ssh-command.py \
  scripts/deploy-vm.sh scripts/deploy-service.py
scp -i "$HOME/Downloads/ssh-key-2026-09-27.key" \
  /tmp/zapbot-deploy-bootstrap.tgz "$HOME/.ssh/zapbot-actions-odisseu.pub" ubuntu@129.148.52.187:/tmp/
scp -i "$HOME/.ssh/google_pokemon" \
  /tmp/zapbot-deploy-bootstrap.tgz "$HOME/.ssh/zapbot-actions-pokemon.pub" caiohclavico@34.68.189.66:/tmp/
```

Na VM Odisseu, pela sessão administrativa já validada:

```sh
bootstrap_dir=$(mktemp -d /tmp/zapbot-deploy-bootstrap.XXXXXX)
tar -xzf /tmp/zapbot-deploy-bootstrap.tgz -C "$bootstrap_dir"
sudo bash "$bootstrap_dir/scripts/install-deploy.sh" \
  odisseu caioclavico /tmp/zapbot-actions-odisseu.pub --confirm-reviewed-persistence-v1
```

Na VM Pokémon:

```sh
bootstrap_dir=$(mktemp -d /tmp/zapbot-deploy-bootstrap.XXXXXX)
tar -xzf /tmp/zapbot-deploy-bootstrap.tgz -C "$bootstrap_dir"
sudo bash "$bootstrap_dir/scripts/install-deploy.sh" \
  pokemon caioclavico /tmp/zapbot-actions-pokemon.pub --confirm-reviewed-persistence-v1
```

As contas `zapbot-deploy`/`pokemon-deploy` não recebem grupo Docker nem sudo
genérico. `authorized_keys` usa `restrict` e comando forçado; aceita somente
`deploy SHA` e `rollback`. A entrada privilegiada valida os argumentos e fixa
serviço, registry e diretório. Não aceita SCP, shell, port forwarding, outro
container ou diretório fornecido pelo cliente. Biblioteca, configuração,
baseline e estado ficam sob controle root. Atualizações destes scripts exigem
nova instalação administrativa revisada, com automação pausada.

Teste a restrição sem executar deploy; a resposta esperada é rejeição de comando:

```sh
ssh -i "$HOME/.ssh/zapbot-actions-odisseu" -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/tmp/odisseu-known-hosts \
  zapbot-deploy@129.148.52.187 check
ssh -i "$HOME/.ssh/zapbot-actions-pokemon" -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes -o UserKnownHostsFile=/tmp/pokemon-known-hosts \
  pokemon-deploy@34.68.189.66 check
```

## GHCR privado

A publicação usa o `GITHUB_TOKEN` do próprio repositório. Nas configurações dos
packages GHCR, permita acesso Actions ao repositório `caioclavico/zapbot`.
Para as VMs, crie um PAT **classic** dedicado com apenas `read:packages` e acesso
aos dois packages. Não use as credenciais pessoais de push/admin.

Na VM Odisseu, faça login sem imprimir o token:

```sh
read -rsp 'GHCR token read:packages: ' registry_pull_token; printf '\n'
printf '%s' "$registry_pull_token" |
  sudo docker --config /etc/zapbot-deploy/odisseu/docker login ghcr.io -u caioclavico --password-stdin
unset registry_pull_token
sudo chmod 600 /etc/zapbot-deploy/odisseu/docker/config.json
```

Na VM Pokémon, repita com `--config /etc/zapbot-deploy/pokemon/docker`.
O diretório é root-owned 700 e o arquivo 600. Docker armazena a credencial em
base64 nesse arquivo privado; isso não é criptografia. O engine aceita esse
formato sem credential helper. Packages públicos dispensam login, mas sua
visibilidade deve ser uma decisão explícita.

## Ativação na branch master

Depois da revisão e da autorização para configuração, prepare `production`, as
chaves, GHCR e as contas das VMs. Mantenha a automação desabilitada enquanto
confere os requisitos; a branch de produção continua sendo `master`:

```sh
gh variable set AUTO_DEPLOY_ENABLED --repo caioclavico/zapbot --body false
```

Confira a proteção de `master`, a policy do Environment e os resultados de CI.
Após autorização específica para o primeiro deploy, execute um deploy manual
aprovado de `both` e acompanhe saúde/rollback; isso estabelece os dois marcadores
iniciais de produção.

Somente depois dessas verificações e de **autorização futura explícita para
habilitar deploy automático**, o operador pode executar o comando abaixo. Ele
não foi executado nesta etapa, e a aprovação do Environment permanece aplicável
a cada deploy:

```sh
gh variable set AUTO_DEPLOY_ENABLED --repo caioclavico/zapbot --body true
```

O deploy serializa por serviço no GitHub e por lock na VM. Execuções automáticas
obsoletas na fila não substituem uma versão mais recente da `master`. Jobs de
deploy não são cancelados automaticamente por um push novo.

## Deploy manual, logs e rollback

Deploy manual também altera produção e exige autorização prévia. Após essa
autorização, na página Actions → CI/CD use Run workflow na `master`, escolha a
operação e o serviço e cumpra as aprovações de `production`. Pelo CLI, com os
mesmos inputs do workflow:

```sh
gh workflow run deploy.yml --repo caioclavico/zapbot --ref master -f operation=deploy -f service=odisseu
gh workflow run deploy.yml --repo caioclavico/zapbot --ref master -f operation=deploy -f service=pokemon
gh workflow run deploy.yml --repo caioclavico/zapbot --ref master -f operation=deploy -f service=both
gh run list --repo caioclavico/zapbot --workflow deploy.yml
gh run watch --repo caioclavico/zapbot RUN_ID
gh run view --repo caioclavico/zapbot RUN_ID --log-failed
```

Rollback manual exige autorização, restaura a referência anterior retida e
verifica sua saúde:

```sh
gh workflow run deploy.yml --repo caioclavico/zapbot --ref master -f operation=rollback -f service=odisseu
gh workflow run deploy.yml --repo caioclavico/zapbot --ref master -f operation=rollback -f service=pokemon
```

Rollback não compila e não migra dados. Logs de aplicação podem conter QR ou
conteúdo de mensagens: ficam em arquivo privado da VM e não são publicados no
Actions. Os logs públicos mostram somente etapas, versões e resultados de saúde.
Não remova containers/imagens/volumes de rollback durante uma atualização.

## Emergência e recuperação

Desabilite novos deploys automáticos:

```sh
gh variable set AUTO_DEPLOY_ENABLED --repo caioclavico/zapbot --body false
```

Isso não interrompe uma transação já em andamento. Evite cancelar um job durante
parada/startup/rollback. Use a sessão administrativa para verificar o lock e os
containers, sem iniciar duas instâncias. Se a VM estiver indisponível, restaure
acesso pelo console cloud; não altere Cassandra para corrigir um deploy de app.

Estado e referências ficam em `/var/lib/zapbot-deploy/odisseu` ou
`/var/lib/zapbot-deploy/pokemon`. Consulte somente com privilégio administrativo.
Se não há transação interrompida, o operador pode recuperar uma versão unhealthy
diretamente na VM, mesmo que o GitHub esteja indisponível:

```sh
# Na VM correspondente, executar apenas o comando daquele serviço:
sudo /usr/local/sbin/zapbot-deploy-odisseu rollback
sudo /usr/local/sbin/zapbot-deploy-pokemon rollback
```

Em perda de energia/SIGKILL, `transaction.json` mantém a operação bloqueada até
revisão. Estes comandos mostram somente nomes, IDs e estado, sem Env:

```sh
sudo docker ps -a --format '{{.ID}} {{.Names}} {{.Image}} {{.Status}}'
sudo cat /var/lib/zapbot-deploy/odisseu/transaction.json
# Na VM Pokémon, usar /var/lib/zapbot-deploy/pokemon/transaction.json.
sudo docker inspect --format 'name={{.Name}} running={{.State.Running}} image={{.Image}}' ID_DO_CONTAINER
```

Se rollback automatizado falhar, confirme que a nova instância terminou antes
de restaurar o container anterior. Os nomes e o procedimento de recuperação
dependem da fase registrada. Com o deploy pausado, obtenha um shell administrativo
com o lock do serviço; não prossiga se o lock estiver ocupado:

```sh
sudo flock -n /var/lib/zapbot-deploy/odisseu/deploy.lock bash
# Na outra VM, trocar o diretório para pokemon.
```

Nesse shell, use os IDs verificados da transação. Se houver replacement criado
e `replacement-container` ainda for null, inspecione o nome canônico e o label
`io.zapbot.deployment-id` antes de selecionar qualquer container:

```sh
docker inspect --format '{{.Id}} {{index .Config.Labels "io.zapbot.deployment-id"}}' zapbot
# Para Pokémon, o nome canônico é zapbot-pokemon.
docker stop --timeout 90 ID_DA_NOVA_INSTANCIA
docker inspect --format '{{.State.Running}}' ID_DA_NOVA_INSTANCIA
# Exigir false. Só então liberar o nome e restaurar o original:
docker rename ID_DA_NOVA_INSTANCIA "zapbot-failed-recovery-$(date +%s)"
docker rename ID_ORIGINAL zapbot
docker start ID_ORIGINAL
docker exec ID_ORIGINAL node scripts/healthcheck.js
```

Adapte somente o nome canônico para Pokémon e valide seus dois endpoints
internamente, sem comandos de jogo:

```sh
docker exec ID_ORIGINAL node -e 'Promise.all(["/health","/ready"].map(async p=>{const r=await fetch("http://127.0.0.1:"+(process.env.PORT||8090)+p);if(r.status!==200)throw Error("Unhealthy");})).then(()=>console.log("HTTP 200 em ambos")).catch(()=>process.exit(1))'
```

Pule a renomeação se o original já possui o nome correto; não tente iniciar um
original enquanto outro writer do serviço permanece ativo. Depois de confirmar
saúde estável, arquive `transaction.json` e `state.json` com nomes diferentes
no mesmo diretório privado (sem remover os containers). O próximo deploy
reestabelece a referência funcional a partir do container restaurado. Mantenha
o lock até terminar a recuperação e saia com `exit`. Não faça recuperação de
schema, dados Cassandra ou limpeza de autenticação neste procedimento.

Ainda no shell com lock, após essa verificação:

```sh
deployment_state=/var/lib/zapbot-deploy/odisseu # pokemon na outra VM
recovery_stamp=$(date +%s)
mv "$deployment_state/transaction.json" "$deployment_state/transaction.recovered-$recovery_stamp.json"
if test -f "$deployment_state/state.json"; then
  mv "$deployment_state/state.json" "$deployment_state/state.recovered-$recovery_stamp.json"
fi
umask 077
docker inspect --format '{{.Image}}' ID_ORIGINAL > "$deployment_state/last-good-image"
exit
```

Mantenha backups externos dos volumes e da sessão segundo a política operacional.
A retenção do container anterior permite rollback de código e configuração, mas
não desfaz writes de jogo realizados pela nova versão. Rotacione as chaves de
deploy e o token de pull quando necessário, com `AUTO_DEPLOY_ENABLED=false`.

## Validação local sem produção

Durante a implementação passaram: actionlint, testes existentes e compilação dos
dois serviços, builds Linux AMD64 e testes isolados de preservação e rollback.
Os testes com Docker real usaram aplicações HTTP fictícias sobre as imagens de
runtime; não acessaram WhatsApp ou Cassandra de produção. Também foram
verificadas permissões do instalador e ausência de secrets fictícios nos logs.

O workflow valida a infraestrutura com Python 3.9 e 3.12. Os testes HTTP incluem
respostas maiores que o buffer, conexões encerradas, keep-alive, chunked e 204.

Para repetir os testes auxiliares, com Node 22, Python 3.9+ e Docker local:

```sh
bash -n scripts/deploy-vm.sh scripts/load-image-vm.sh scripts/install-deploy.sh scripts/ci-ssh-deploy.sh
python3 scripts/test-ci-changes.py
python3 scripts/test-ci-ssh.py
node --test scripts/test-ci-registry.cjs
python3 scripts/test-deploy.py
python3 scripts/test-load-image.py
python3 scripts/test-deploy-ssh.py
actionlint .github/workflows/deploy.yml
docker buildx build --platform linux/amd64 --load -t zapbot-odisseu:ci .
docker buildx build --platform linux/amd64 --load -t zapbot-pokemon:ci pokemon-service
local_docker_endpoint=$(docker context inspect --format '{{(index .Endpoints "docker").Host}}')
case "$local_docker_endpoint" in unix://*) ;; *) echo 'Use Docker local com socket Unix.' >&2; exit 1;; esac
python3 scripts/test-deploy-docker.py --socket "${local_docker_endpoint#unix://}" --service odisseu --base-image zapbot-odisseu:ci
python3 scripts/test-deploy-docker.py --socket "${local_docker_endpoint#unix://}" --service pokemon --base-image zapbot-pokemon:ci
```

Os fixtures removem somente os recursos identificados pelo UUID daquele teste.
Use Docker local/descartável; não direcione estes comandos a um daemon remoto.

## Referências oficiais

- [GHCR e autenticação](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Environments e controles de deploy](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/control-deployments)
- [Restrição de chaves SSH e comando forçado](https://man.openbsd.org/sshd.8)
