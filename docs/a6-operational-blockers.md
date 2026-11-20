# Odisseu A6: bloqueios operacionais

Base: `273c571ae39e480c7baaf19f0e3ae86e9e8a78e2`. Trabalho em
`fix/a6-operational-blockers`. Nenhuma janela de produção foi aberta.

## Evidência somente de leitura, 19/11/2026

Coleta executada na VM por SSH autenticado como `caioclavico`, com
host key verificada. O endereço foi resolvido pelo GCE, sem usar IP como
identidade. A primeira chave foi recusada; uma das chaves locais de operação
permitiu a coleta. Falha de SSH não foi substituída por observação local.

O metadata server oficial respondeu às quatro consultas com cabeçalho de
requisição **e resposta** `Metadata-Flavor: Google`:

| Campo | Valor observado |
|---|---|
| instance/name | odisseu-core |
| instance/zone | us-central1-a (sufixo do resource path oficial) |
| project/project-id | project-e8cd7809-1145-4be9-b8d |
| instance/id | 8264769050613851592 |

`observe_gce()` agora consulta os endpoints oficiais com curl, sem proxy,
com timeouts, valida o flavor de resposta e o contrato. Não aceita identidade
manual nem o comando inexistente `metadata`.

O `docker inspect` do Node confirmou:

- tipo `bind`, RW, propagação `rprivate`;
- origem `/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core`;
- destino `/app/.wwebjs_auth_a6_cutover`;
- diretório existente, proprietário `1001:1002`, modo observado `0770`.

O stat exigiu sudo; a tentativa sem privilégio foi recusada. O contrato já
contém a origem correta e foi preservado. Não foi lido conteúdo da sessão,
nem movido, copiado, removido ou recriado qualquer arquivo.

O Node possui seccomp customizado inline, diferente de `unconfined`:

- SHA-256 dos bytes inline: `577d8efad2157f8f53db613e379a4bd165b3e64cdb2a1947d92d185773b68cf2`;
- SHA-256 do JSON canônico: `135e5823e89d78d1164f2d708306687fc7a3864399e57bc67771b404591dc8ad`;
- o JSON canônico corresponde a `docker/chromium-seccomp.json`;
- SHA-256 do arquivo conhecido: `4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10`.

O hold foi **somente lido** pelo GitHub CLI local e retornou `true`.
Nenhum token foi instalado na VM.

## Execução escolhida: na VM

O controlador local/SSH foi avaliado: `GithubHold` pode usar gh local e o runner
pode enviar comandos via SSH. Entretanto, o backup, a leitura do manifesto,
a restauração isolada, a validação dos caminhos e o diário usam filesystem
no processo Python. Trocar somente o runner misturaria evidência da VM com
arquivos locais. Separar essas operações exige um protocolo transacional
remoto, fora desta correção pequena.

A execução deve ocorrer na VM, com gh instalado e credencial **temporária**,
restrita ao repositório e à leitura/escrita de repository variables, concedida
apenas para a janela e sua recuperação. Validar permissões efetivas com uma
variável de laboratório antes de autorizar produção. Não copiar o token
OAuth amplo do computador para a VM, nem persistir `gh auth login` nela.
A credencial deve entrar via mecanismo temporário em tmpfs/ambiente do
processo, nunca por argumento, log ou checkpoint, e ser revogada ao encerrar.
A recuperação do hold precisa permanecer possível após a morte do processo.
O provisionamento dessa credencial não foi realizado nesta tarefa.

## Ambiente e seccomp

`Materializer` consulta inspect diretamente, fora do runner auditado, conserva
a lista completa de variáveis e recusa duplicatas, chaves inválidas, valores
obrigatórios vazios e CR/LF/NUL que env-file não representa fielmente.

Exige tmpfs verificado por `findmnt`; não há fallback para `/tmp`. Usa
`/dev/shm/odisseu-a6-runtime-UID`, modo `0700`, lock exclusivo, `umask 077`,
criação exclusiva de env-file `0600` e rejeição de symlinks. O argv só contém
o caminho. Após o retorno de Docker, o arquivo é eliminado antes da próxima
operação. Um guardião separado remove o arquivo quando o pipe do pai fecha,
inclusive após SIGKILL. A próxima preparação limpa resíduos reconhecidos;
resíduos desconhecidos e falhas de exclusão bloqueiam a execução.

O seccomp é comparado ao inspect atual. Perfil customizado exige caminho
absoluto conhecido, arquivo não gravável por grupo/outros, hash SHA-256 e
igualdade do JSON efetivo. `seccomp=unconfined` é recusado. Quando o container
usa o padrão, o daemon deve comprovar `profile=builtin`; não se aplica fallback
silencioso. Configuração futura na VM:

- `A6_NODE_SECCOMP_PATH`: arquivo provisionado e verificado pelo administrador;
- `A6_NODE_SECCOMP_SHA256`: hash do arquivo conhecido acima;
- equivalentes `A6_JVM_SECCOMP_*` se o inspect atual da JVM exigir perfil customizado.

Preflight verifica ambiente e seccomp antes de liberar o hold. A materialização
repete a leitura antes do run, preservando também o seccomp da JVM.

## Helper administrativo de backup

`a6_root_backup.py` oferece somente `--backup WINDOW_ID`. Não recebe origem,
comando de shell, destino livre, remoção ou restauração do Datomic.
A origem é a constante exata autorizada; o destino é
`/var/backups/odisseu-a6/WINDOW_ID/data`, em base root:root `0700` pré-instalada.
O preflight recusa falta de root e destino/base incompatível **antes do hold**.
O runner existente chama o helper dentro da intenção durável de backup;
o restante do runner, journal e recuperação permanece existente.

O helper recoleta metadata, inspect de todos os containers e marcadores.
Exige JVM parada, Node em manutenção sem operações, quiescência persistida,
mount exato, ausência de holders ativos e de handles/mappings abertos no
host. Recusa symlinks e arquivos especiais. Usa os gates existentes de
espaço, exclusividade, manifesto SHA-256 e preservação da origem. Reconfere
a observação após copiar e restaura somente em `restore-isolated`, novo e
separado. O runner reaproveita essa restauração verificada.

Este caminho requer processo privilegiado na VM e código/dependências
provisionados como root, não graváveis pelo operador. Não conceder sudo a um
script em checkout gravável. Esta tarefa não instalou código, sudoers ou base
de backup na VM. Administradores do host/Docker permanecem fora da garantia:
a janela exclusiva proíbe iniciar writers concorrentes durante a cópia.

## Transferência AMD64: plano, não executado na VM

Inspect **local** confirmou os dois Image IDs aprovados, `linux/amd64` e a
revisão OCI exata `faedebd5036124eb4f946147c5012c48122acc69`:

| Serviço | Image ID |
|---|---|
| JVM | sha256:4be43c6b5e26c37691ce002ec6458b60b8186f1409b6e1ec519e82fc5c667891 |
| Node | sha256:3dc29e8f07f1a615e6aff529f6effd0b2257bc123fd3cc58e2671d7adeb5af5e |

Plano para uma etapa autorizada posterior:

1. Conferir novamente ID, OS, arquitetura e revisão de cada imagem local.
2. `docker save` usando os dois **Image IDs**, para arquivo novo fora dos volumes.
3. Calcular SHA-256 do arquivo fechado; transferir por SSH autenticado com
   host key verificada, sem transmitir credenciais GitHub.
4. Na VM, comparar SHA-256 do arquivo recebido **antes** de `docker load`.
5. Registrar IDs antigos por inspect na própria VM, sem confiar nos locais.
6. Executar somente `docker load`, sem run, tag sobre container ou remoção.
7. Inspecionar os IDs aprovados após load: exigir igualdade exata dos IDs,
   `linux/amd64` e revisão OCI exata. Conferir que todos os IDs antigos ainda
   existem e que os containers mantêm seus Image IDs originais.
8. Recalcular o hash do arquivo recebido e registrar apenas metadados públicos.

Esse plano não autoriza sua execução agora. Nenhuma imagem foi transferida
para produção nesta tarefa.

## Validação e prontidão

`bash scripts/test-a6-ops-docker.sh` cria daemon Docker-in-Docker novo sem rede
externa e volume root descartável. O argv integral do Node é montado pelo
`recreate_argv`, materializado em tmpfs e consumido por Docker real; ambiente,
seccomp e mounts são comparados antes/depois. Não conecta WhatsApp.

O ensaio root usa volume Docker novo com arquivo root `0600`, executa cópia,
manifesto e restore isolado. A coleta GCE/estado é mockada nesse ensaio;
a identidade real acima foi obtida separadamente na VM. Os testes de janela
existentes exercitam diário, hold, manutenção/drain/resume e interrupções em
laboratório. Isso não equivale a uma janela completa com todos os serviços
reais e credenciais temporárias provisionadas.

Estado conservador: **EXECUTION_BLOCKED / DEPLOY_BLOCKED** até provisionar e
validar gh temporário, código privilegiado/base de backup e perfil seccomp
na VM, concluir os três checks do PR e o ensaio integrado completo. Os
ensaios parciais não são evidência suficiente para declarar EXECUTION_READY.
Nenhuma manutenção, restart, alteração de hold, cópia do Datomic real,
merge, deploy ou ativação de CI/CD legado foi executada.

## Procedimentos operacionais preparados

O provisionamento é uma operação administrativa separada. O operador cria um
tar do commit revisado, confere seu SHA-256 fora da VM e o disponibiliza ao
administrador em caminho root-owned. O comando é:

```bash
sudo A6_ADMIN_BUNDLE=/root/a6-admin-<sha>.tar \
  A6_ADMIN_BUNDLE_SHA256=<sha256-do-tar-conferido> \
  A6_PROVISIONER_SHA256=<sha256-do-a6-provision-admin.sh-conferido> \
  A6_ADMIN_VALIDATOR_SHA256=<sha256-do-a6_validate_admin_bundle.py-conferido> \
  A6_NODE_SECCOMP_SHA256=4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10 \
  bash /caminho-verificado/a6-provision-admin.sh
```

Ele instala somente o helper e seus módulos em `/usr/local/lib/odisseu-a6`, o
entry point root-owned `/usr/local/libexec/odisseu-a6/a6-root-backup`, a base
de backup `root:root 0700`, perfil seccomp previamente verificado e a regra
sudoers restrita ao helper. Ele não entra em manutenção, não escreve o hold,
não carrega imagens nem substitui containers. A credencial temporária do `gh`
continua fora deste provisionamento: deve ser injetada em tmpfs/ambiente do
processo na sessão da janela e revogada depois; nunca é salva pelo script.

A execução da janela continua separada, interativa e exige todos os gates:

```bash
A6_BACKUP_DESTINATION=/var/backups/odisseu-a6/<window-id>/data \
A6_HOLD_STORE=github bash scripts/a6-operational-window.sh --window
```

O comando B só aceita esse destino fixo. O runner opera como `caioclavico` e
usa `sudo -n` somente para o entry point root-owned; nunca eleva o checkout.
O ensaio AMD64 reproduz o usuário, a regra sudoers e a recusa explícita de
um entry point localizado no checkout. A recuperação do hold usa o fluxo
existente e não precisa do helper de backup.

## Contrato do backup privilegiado

O helper instalado expõe somente objetos JSON em `stdout`:

| Operação | Uso | Evidência devolvida |
|---|---|---|
| `--preflight WINDOW_ID` | antes de soltar o hold | helper root-owned e janela válida |
| `--ensure WINDOW_ID` | cria ou reutiliza backup | manifesto, origem, restore, volume e contagem de arquivos |
| `--status WINDOW_ID` | retomada após substituir JVM | revalida atestado, manifesto, backup e restore sem consultar o Datomic ativo |

`a6_admin_backup_client.py` rejeita qualquer JSON que não contenha a janela,
o volume `odisseu-a6-cutover-datomic`, manifesto válido, origem preservada,
restore isolado e contagem positiva. O atestado root-only registra janela,
volume, hash do manifesto, número de arquivos e que a origem/restauração
foram verificadas durante a cópia. O runner não abre o volume live, a base
de backup, manifesto ou restore. Também não mede espaço: o helper mede e
valida o espaço como root antes da cópia. Na retomada após uma interrupção
com JVM já substituída, o runner confirma Image ID, checkpoint e JVM anterior
parada, chama `--status` e não tenta ler o backup. O status nunca compara o
Datomic ativo ao backup nem autoriza uma restauração: progresso legítimo após
a troca da JVM permanece aceito.

O ensaio AMD64 valida `--preflight` através do sudoers real e valida o
backup sintético, manifesto e `--status` pelo entry point root-owned, sem
expor dados. Ele também confirma que o usuário operacional não lê o diretório
`root:root 0700` nem eleva o checkout. A infraestrutura de produção continua
inalterada; portanto o estado permanece **EXECUTION_BLOCKED / DEPLOY_BLOCKED**.
