# M6.1 — Staging do monólito modular com Datomic

**Status:** preparado (dev/staging local)  
**Branch:** `refactor/modular-monolith-datomic`  
**Escopo:** Node + JVM bridge + Datomic Local isolados. **Sem cutover de produção.**

---

## Arquivos

| Arquivo | Papel |
|---|---|
| `docker-compose.staging.yml` | Compose staging: `odisseu-jvm` + `odisseu-node` (+ Cassandra opcional) |
| `Dockerfile.jvm` | Imagem da bridge Clojure/Datomic |
| `.env.staging.example` | Modelo de env (copiar → `.env.staging`, gitignored) |
| `scripts/staging-up.sh` | Sobe `jvm` \| `compose` \| `node` |
| `scripts/staging-down.sh` | Encerra compose / porta 18090 |
| `scripts/staging-smoke.sh` | Smoke sintético M6.1 |
| `scripts/staging-measure.sh` | Snapshot RSS/CPU |
| `apps/odisseu/src/odisseu/smoke/m6_1_staging.clj` | Suite HTTP sintética |
| `odisseu.bridge.http-server` | Entrypoint JVM com guards de storage/token |

---

## Comandos locais

```bash
cp .env.staging.example .env.staging
# edite ODISSEU_BRIDGE_TOKEN / POKEMON_JVM_TOKEN / DEV_GROUP_ID

# A) Host: só JVM (recomendado para smoke)
./scripts/staging-up.sh jvm          # terminal 1
./scripts/staging-smoke.sh           # terminal 2
./scripts/staging-measure.sh

# B) Compose (bridge publicada só em 127.0.0.1:18090)
./scripts/staging-up.sh compose
./scripts/staging-smoke.sh
./scripts/staging-down.sh

# C) Node + sessão WhatsApp de teste (opcional)
./scripts/staging-up.sh jvm          # terminal 1
npm run build && ./scripts/staging-up.sh node   # terminal 2 — exige QR/sessão
```

Volume Datomic host: `.datomic-local/m61-staging` (Compose: volume `odisseu-staging-datomic`).  
**Nunca** apontar storage para `m53-real-offline` / `m55-real-offline`.

---

## Segurança

- `APP_ENV=staging` + `POKEMON_ADAPTER=jvm` habilitam o adaptador; **produção continua forçando legacy**.
- Staging restringe chats a `DEV_GROUP_ID` (como development).
- Bridge: loopback por padrão; `0.0.0.0` só com `ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true` (rede privada Docker). Compose publica **apenas** `127.0.0.1:18090`.
- Token obrigatório (`ODISSEU_BRIDGE_TOKEN`); sem default inseguro no entrypoint staging.
- `POKEMON_SERVICE_URL` vazio no staging → um único writer Pokémon (JVM).
- Cassandra **não** é dependência do gameplay Pokémon; profile `with-cassandra` é opcional para rank/lembretes/etc.

---

## O que o smoke cobre (sem WhatsApp)

Health, trainer/saves A↔B, moedas+idempotência+fence, Joy send/collect, caça, ginásios/raids (leitura), loja/inventário, isolamento entre jogadores, reopen JVM + persistência.

## O que **não** está comprovado sem sessão WhatsApp

- RSS real de Chromium em estado READY.
- Entrega de mensagens / QR / LocalAuth.
- Falha de renderização de mídia no caminho WhatsApp.
- Reinício coordenado Node↔JVM sob carga de chat real.

Documente medições com `./scripts/staging-measure.sh` quando houver sessão de teste isolada.

---

## Cassandra restante (módulos Node)

Ainda dependem de Cassandra no monólito Node (mesmo em staging com profile):

- `rank`, `bloqueio`, `admins`, `lembretes`, `quiz-historico`, `participantes`/`historico`, `bugs`, enquetes
- Fallback legado Pokémon (`POKEMON_ADAPTER=legacy` / `pokemon-service`) — **desligado** no staging JVM

Gameplay Pokémon no adaptador JVM **não** lê/escreve Cassandra.

---

## Riscos / bloqueios de produção

1. Cutover ainda exige runbook de rollback + decisão PvP/missões (M4.12).
2. Dual-write Cassandra+Datomic prolongado é proibido.
3. Sem medição Chromium READY + JVM na mesma VM de staging autenticada, não declarar capacidade de memória de produção.
4. Matching inventário real / Adventure compartilhados: lacunas M5 conscientes.
5. Não alterar flags/imagens de produção neste milestone.
