# M9.5.2 — POKEMON_JVM_URL na rede Docker (`odisseu-jvm`)

## Problema

O Node em odisseu-core recebia `POKEMON_JVM_URL=http://odisseu-jvm:18090` (Compose correto), mas `assertLoopback()` rejeitava qualquer host ≠ loopback → `CONFIG`.

## Correção

| Cliente | Flag (default `false`) | Extra permitido |
|---------|------------------------|-----------------|
| Pokémon | `POKEMON_JVM_ALLOW_DOCKER_HOST` | só `http://odisseu-jvm:18090` |
| Platform | `PLATFORM_JVM_ALLOW_PRIVATE_HOST` | mesma allowlist (antes aceitava qualquer host privado) |

- Flags só são honradas com `APP_ENV=staging` (produção nunca).
- Bearer continua obrigatório.
- Loopback permanece o default sem flag.

Compose odisseu-core / staging (serviço Node) define as flags `"true"`.

## Recriar só o Node

```bash
docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  --profile with-node up -d --build --no-deps odisseu-node
```

Não use `down -v` nem limpe `.wwebjs_auth_core`.

## Testes

```bash
node --test test/pokemon-jvm-client.test.cjs test/platform-jvm-client.test.cjs
```
