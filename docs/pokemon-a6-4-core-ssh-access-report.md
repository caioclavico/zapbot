# A6.4 — Relatório de acesso SSH Core + ensaio migration 025

Branch: `a6-save-slots` (preserva `32fcaf4`, `20786d2`, …).
Atualizado: 2026-10-27. **Sem import V1/V2, sem deploy, sem escrita Cassandra,
sem migration no volume de produção.**

## 1. SSH

Após inclusão da chave pública em `~/.ssh/authorized_keys` do usuário
`caiohclavico`:

```text
ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146
→ AUTH_OK (user=caiohclavico host=odisseu-core)
```

## 2. Volume Datomic real (A5.6 live)

| Item | Evidência |
|------|-----------|
| Imagem JVM | `odisseu-jvm:2cab639-amd64` |
| Compose | `docker-compose.odisseu-core.yml` + `docker-compose.release-2cab639.yml` |
| Node overlay | `docker-compose.allow-all-groups.yml` (imagem `allow-all-groups-47ff8a1`) |
| Storage env | `ODISSEU_BRIDGE_SYSTEM=odisseu-m90-core` `ODISSEU_BRIDGE_DB=core` `ODISSEU_BRIDGE_STORAGE=/data/datomic-local` |
| **Volume montado** | **`odisseu-core-a56-datomic` → `/data/datomic-local`** |
| Journal / maint | `odisseu-core-a56-journal`, `odisseu-core-a56-maint` |
| Volumes legados (não montados no JVM atual) | `odisseu-core-datomic`, `odisseu-core-journal`, `odisseu-core-maint` |

## 3. Backup frio

Procedimento (JVM only; Node permaneceu up; **sem** `down -v`):

1. `docker compose … stop odisseu-jvm`
2. Health `:18090` ausente → OK
3. Cópia RO via Alpine: `odisseu-core-a56-{datomic,journal,maint}` →
   `~/odisseu-recovery/a64-preflight/a56-cold-20261027T213056Z/`
4. `docker compose … up -d odisseu-jvm` (mesmos overlays; sem `--build`)
5. Health OK (~31 tentativas / ~1 min)

Espelho local (Mac, privado, fora do Git):

`~/odisseu-recovery/a64-preflight/a56-cold-20261027T213056Z/`

Produção após restart: JVM+Node healthy; mounts A5.6 inalterados.

## 4. Migration 025 — só cópia descartável

Cópia: `~/odisseu-recovery/a64-preflight/a56-025-rehearsal`  
(original cold backup **não** recebeu 025).

| Check | Antes | Depois |
|-------|-------|--------|
| `pokemon/025-save-slot-metadata` | ausente | **aplicada** |
| Attrs `:save/display-name` … `:save/last-rename-name` | ausentes | presentes |
| Migrations (core) | 24 | 25 |
| Players (`:player/key`) | 0 | 0 |
| Saves (`:save/key`) | 0 | 0 |
| Pokémon | 0 | 0 |
| Contagens preservadas | — | **sim** |

Conclusão do ensaio: migration **aditiva e idempotente** neste dataset;
produção **não** foi migrada.

## 5. Integridade / inventário do Core A5.6 (achado crítico)

No volume live `odisseu-core-a56-datomic` (backup frio):

| DB | Conteúdo |
|----|----------|
| `core` | Schema Pokémon completo (001–024); **0 treinadores, 0 saves, 0 Pokémon** (~31 txs / ~40 KB) |
| `platform` | Dados operacionais: ~11689 txs; **151** `:platform.participant/key`; **2** `:platform.rank.player/key` |

Ou seja: o inventário “seis saves / treinadores de jogo” **não está** no `core`
A5.6 atual. O snapshot antigo `a455` (outro backup, 3 players / 6 saves) **não**
equivale a este volume live.

Implicação para A6.4: a recuperação V1/V2 a partir do Cassandra continua
necessária para restaurar progresso histórico de RPG; o Datomic Core A5.6
hoje carrega sobretudo platform + schema vazio de Pokémon.

## 6. Riscos

1. Assumir que `odisseu-core-datomic` (sem `a56`) é o live — **falso**.
2. Rodar `odisseu-core-up.sh` sem o overlay `release-2cab639` pode remontar
   volumes errados.
3. Copiar Datomic com JVM up — evitado; backup foi frio.
4. Volume `core` vazio: validação 025 não exercitou preservação de saves
   populados neste ensaio; apenas schema vazio + platform intacta na cópia.

## 7. Restrições respeitadas

- Sem deploy / troca de imagem.
- Sem import V1/V2.
- Sem escrita Cassandra.
- Sem `schema/install!` no volume de produção.
- Sem alteração de chaves SSH ou metadata da VM.

## 8. Próximos passos

1. Confirmar com operadores se a ausência de saves no `core` A5.6 é esperada
   (cutover parcial / uso só platform) ou se há outro storage.
2. Exportar dumps Cassandra autorizados + aliases de identidade.
3. Carregar catálogo offline na JVM (release A6) **após** plano de deploy
   que preserve `odisseu-core-a56-*`.
4. Import admin somente após confirmação do jogador — nunca no volume live
   sem backup frio recente.
