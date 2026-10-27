# A6.4 — Plano de implantação (Core) sem destruir volumes

**Não executar automaticamente.** Este documento é o checklist operacional.

## Pré-condições

1. Branch `a6-save-slots` no remoto (HEAD com A6.2–A6.4).
2. Volumes de produção intactos: `odisseu-core-a56-datomic`, `…-journal`, `…-maint`, `…-data`.
3. Sessão WhatsApp em `/home/caiohclavico/odisseu-core/.wwebjs_auth_core`.
4. Cassandra histórico **somente SELECT** (Oracle); sem DDL/DML.
5. Migration 025 validada em cópia descartável do Datomic (não no volume Core).

## Build AMD64

No Mac/CI, a partir do commit A6.4:

```bash
# Tags de exemplo — substituir pelo SHA real
export SHA=$(git rev-parse --short HEAD)
docker buildx build --platform linux/amd64 \
  -f Dockerfile.jvm -t odisseu-jvm:${SHA}-amd64 --load .
docker buildx build --platform linux/amd64 \
  -f Dockerfile -t odisseu-node:${SHA}-amd64 --load .
```

Gerar `docker-compose.release-${SHA}.yml` apontando as mesmas imagens e **os mesmos volume names** da A5.6 (`odisseu-core-a56-*`).

## Env de recovery (JVM)

No `.env.odisseu-core` (não versionar):

```bash
ODISSEU_RECOVERY_DUMP_PATH=/data/recovery/authorized-offline.edn
ODISSEU_RECOVERY_IMPORT_TOKEN=<segredo-forte>
```

Montar o dump como bind read-only no serviço JVM. Mode 600 no host.

## Ordem no Core

```bash
ssh -i ~/.ssh/odisseu_core -o IdentitiesOnly=yes caiohclavico@35.192.113.146
cd /home/caiohclavico/odisseu-core

# 1) Backup lógico do compose atual + env
# 2) Subir imagens novas SEM -v
docker compose --env-file .env.odisseu-core --profile with-node \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-${SHA}.yml \
  up -d

# 3) Health
docker compose ... ps
curl -sS http://127.0.0.1:18090/health   # recoveryCatalogReady
```

**Proibido:** `docker compose down -v`, prune de volumes, reset Datomic, regenerar `.wwebjs_auth_core`.

## Recuperação real (por jogador)

1. Túnel SSH → Cassandra; `--list-players` / `--probe-player` (sem logar IDs em canais públicos).
2. Export SELECT-only → dump com `:authorized-offline-copy true` (+ aliases se necessário).
3. Colocar dump no path da JVM; reiniciar só o contêiner JVM se preciso.
4. Jogador: `!pk recuperar` → `v1|v2|ambos` → `confirmar`.
5. Ops: `POST /v1/recovery/import` com `X-Recovery-Import-Token`.
6. Jogador: `!pk saves` / `!pk save <n>`.

## Rollback

1. Redeploy imagens `2cab639-amd64` com o mesmo compose de volumes.
2. Remover `ODISSEU_RECOVERY_*` se quiser desligar o catálogo.
3. Slots `recovery-*` já importados **não** são apagados automaticamente (preservar progresso).
4. WhatsApp: não tocar no bind de auth.

## Critérios de sucesso

- Health JVM/Node healthy; WhatsApp READY.
- `!pk recuperar` lista prévia sem alterar saves.
- Após import admin: slots `recovery-v*` visíveis; ativo inalterado.
- Cassandra sem escritas; volumes A5.6 intactos.
