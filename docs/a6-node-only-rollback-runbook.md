# Runbook — rollback Node-only (A6)

## Regra dura

A imagem live antiga **não** entende o marker persistente do Announcer do PR #68/#70 da mesma forma que a imagem nova.

Só iniciar a imagem antiga se:

```text
RELEASE_ANNOUNCER_ENABLED=false
```

estiver **comprovadamente** no ambiente do Compose/overlay **antes** do `docker compose up` / recreate.

Se a flag não puder ser garantida: **NÃO iniciar** a imagem antiga. Manter Node parado, preservar volumes (`/app/data`, LocalAuth), escalar recuperação.

## Checklist

1. Registrar Image ID atual e o digest de rollback (`a87abb51…` / pin live `8ef41c8`).
2. Editar overlay/env: `RELEASE_ANNOUNCER_ENABLED=false`.
3. `docker compose config` (ou equivalente) e grepar a flag — evidência anexada.
4. Recriar somente Node com a imagem antiga.
5. Confirmar: zero anúncios, LocalAuth presente, locks órfãos não apagados, volume de controle íntegro, comandos básicos respondendo.
6. Não reconciliar às cegas `transport_uncertain`.
7. Não tocar JVM/schema/saves/recibos.
