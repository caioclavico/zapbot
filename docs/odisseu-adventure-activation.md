# Ativação segura do Adventure Sync no Odisseu

A operação restrita `activate-adventure` recria somente o container do Odisseu
com a imagem que já está ativa e altera exclusivamente
`ADVENTURE_SYNC_ENABLED=true` no ambiente efetivo do container. O `.env`, a
imagem, os volumes `.wwebjs_auth` e `data`, recursos Docker e demais variáveis
não são sobrescritos.

## Execução manual

Após instalar a versão correspondente dos helpers na VM, a operação pode ser
solicitada pelo forced command SSH:

```sh
ssh -i "$HOME/.ssh/odisseu_github_deploy" \
  -o IdentitiesOnly=yes caiohclavico@35.238.24.225 activate-adventure
```

O fluxo mantém a trava de escritor único, backup/verificação do perfil,
encerramento gracioso, readiness e rollback. Se a nova inicialização falhar,
o container e o ambiente anteriores são restaurados pelo journal privado.

A operação não usa `docker compose down`, não remove volumes e não imprime
segredos. A flag só entra em vigor após o novo container passar pelo healthcheck.
