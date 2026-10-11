# Atualização segura da chave TMDB

O helper de deploy possui a operação restrita `update-tmdb`. Ela lê somente a
linha `TMDB_API_KEY` do `.env` privado do Odisseu, sem imprimir o valor, e
recria o container usando a mesma imagem, rede, volumes, política de reinício
e demais variáveis efetivas. O rollback existente continua sendo aplicado se o
healthcheck falhar.

Após instalar os helpers revisados na VM, execute manualmente:

```sh
ssh -i "$HOME/.ssh/odisseu_github_deploy" \
  -o IdentitiesOnly=yes caiohclavico@35.238.24.225 update-tmdb
```

A operação não usa `docker rm -v`, não altera `.env`, Cassandra ou Pokémon e
não deve ser executada enquanto houver outra operação de deploy em andamento.
