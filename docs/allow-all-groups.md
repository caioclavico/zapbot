# Respostas em todos os grupos

`ALLOW_ALL_GROUPS=false` é o padrão. Em staging/development, o bot continua
restrito ao `DEV_GROUP_ID` e mantém os gates de confirmação existentes.

Com `ALLOW_ALL_GROUPS=true`, qualquer grupo (`@g.us`) pode enviar comandos,
mesmo com `APP_ENV=staging`. `DEV_GROUP_ID` e `STAGING_GROUP_CONFIRMED` deixam
de ser necessários para iniciar nesse modo. Conversas privadas não recebem
essa liberação adicional. Produção mantém seu comportamento anterior.

`STAGING_BOOTSTRAP_GROUP=1` tem prioridade: continua sem processar mensagens,
replies ou notificações. A allowlist `STAGING_ALLOWED_USERS`, `ADMIN_NUMBERS`,
os administradores do grupo e os bloqueios de comandos permanecem aplicáveis.
A opção não altera adaptadores, bancos nem autenticação.

## Aplicação manual na VM odisseu-core

Depois de revisar e incorporar o código, edite o arquivo de ambiente já usado.
No Compose deste projeto, ele é `.env.odisseu-core`, não o `.env` genérico:

```dotenv
ALLOW_ALL_GROUPS=true
```

Mantenha `APP_ENV=staging` e os valores atuais dos adaptadores, tokens,
`WWEBJS_DATA_PATH` e `WWEBJS_CLIENT_ID`. Fora do Compose, use o `.env` que já
é carregado pelo processo. Para operação normal, o bootstrap precisa estar
desativado; enquanto estiver ativo, nenhum grupo será processado.

Com a JVM já saudável, execute manualmente na raiz do projeto:

```bash
sudo docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core --profile with-node up -d --build --no-deps odisseu-node
sudo docker logs --tail 80 odisseu-core-node
```

O comando recompila e recria apenas o Node, recarregando o ambiente. Um simples
`docker restart` não atualiza o ambiente do container. O Compose mantém o bind
`./.wwebjs_auth_core:/app/.wwebjs_auth_core` e o client ID `core`, reutilizando
a sessão existente. Não apague a pasta de autenticação, não faça logout e não
remova volumes. Não é necessário novo QR para uma sessão ainda válida.

Verifique um comando comum em dois grupos e a recusa de um comando administrativo
para um membro comum. Se houver `STAGING_ALLOWED_USERS`, use um usuário permitido.

Para voltar à restrição, defina `ALLOW_ALL_GROUPS=false`, mantenha um
`DEV_GROUP_ID` válido e `STAGING_GROUP_CONFIRMED=1`, e recrie somente o Node
com o mesmo comando. Nenhum deploy é executado por esta alteração.

## Filtros revisados

`core.cljs` centraliza a entrada por `permitido-pelo-ambiente?` e filtra eventos
de administradores com `chat-autorizado?`. Lembretes e eventos do adaptador
Pokémon também reutilizam `chat-autorizado?`; não possuem outro filtro por
`DEV_GROUP_ID`. Os guards de bootstrap que impedem registrar handlers,
iniciar lembretes/Pokémon e hidratar estado continuam intactos.
