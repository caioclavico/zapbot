# Checkpoint — GO ginásio `datomic_value_too_large` / slim state+team (Odisseu Core)

**Quando:** 2026-10-29 ~22:23–22:27 UTC  
**Autorização:** GO (chat) — referência `docs/pokemon-a6-4-gym-value-too-large-audit.md`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| JVM: `slim-battle-state` (ativos+reservas) + `encode-gym-team-edn` | Node rebuild |
| Rebuild/redeploy **somente JVM** | Migrations / import / Cassandra |
| Erro explícito `datomic_value_too_large` preservado | Batalhas/desafios artificiais no live |
| Volumes `a56-*` | Rollback destrutivo automático |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `test-m4-gym-battle` | **OK** · 12 testes / **72** asserts (+ M4.7/M3.5 + smoke HTTP) |
| `hunt-m45` unit | **17 / 17** (0 fail) |
| `encounter-m44` | **10 / 10** |
| Size gates (fixture V1 gordo) | gym-state **3379** · team-edn **1411** · wild **1621** · lim **4000** |
| Histórico canônico | intacto no mapa de coleção (não no snapshot) |
| Cold | `~/odisseu-recovery/a64-gym-slim-cold-20261029T222310Z/` |
| Restore | **19=19** · mom `96115182bc182d69e7d7f5f1ff22642f863c70e44e11ec5f1b2d7b5b19caf0d7` |

Imagem **antes:** `odisseu-jvm:a64-capture-ux-20261029T2124-amd64`  
`sha256:af4519b3a0d5b8633d21db8813239d81e7ca0aab9af83e17e36c93c1a07ee829`

Arquivos rsync: `battle.clj` · `gym_store.clj`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-gym-slim-20261029T2223-amd64` | `sha256:42f990b2c43aac3c22f8c36a119a27fea62611273c47a591cddacd3fa0d5153c` |
| Node (inalterado) | `odisseu-node:a64-capture-ux-20261029T2124-amd64` | `sha256:259c45e66a423ab5707390483d07e9d01a9dba5f78e56c4e369774c5dafbc246` |

Compose bak: `docker-compose.release-battle-idle.yml.bak-pre-gym-slim-a64-gym-slim-20261029T2223-amd64`

Env: `HUNT=1800` `JOY=1800` `IDLE=300` `LEAVE=true` `BRIDGE_RESET=false` `ALLOW_ALL_GROUPS=true`  
Volumes: `odisseu-core-a56-{datomic,journal,maint,data}`

Código no container: `slim-battle-state` · `encode-gym-team-edn` presentes.

---

## Pós-deploy (sem combate artificial)

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** (sessão Node preservada desde 21:27Z) |
| Caps | `gym-challenge` · `encounter-*` |
| Pedra | listado · `desbloqueado: true` · ocupação `null` |
| User | `recovery-v1` · fence **2** · **119** · `activeBattleId=null` |
| Integridade | 12/12 · 39 · 119 · **158** |
| Mutação | **none** |

Arquivo: `~/odisseu-recovery/a64-gym-slim-post-integrity.json`

Validação de desafio/vitória real: **aguardando jogadores** (autorização proibiu batalha artificial no live).

---

## Riscos residuais

1. Primeiro desafio Pedra no live ainda não exercitado pós-deploy — confirmação humana.  
2. Outros ginásios compartilham o mesmo encode; risco residual baixo se algum DTO extra crescer além dos drops atuais.  
3. `datomic_value_too_large` permanece se um snapshot futuro ultrapassar 4000 após slim.

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-gym-slim-a64-gym-slim-20261029T2223-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm
```

Não executar rollback destrutivo automaticamente.
