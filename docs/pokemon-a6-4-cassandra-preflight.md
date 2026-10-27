# A6.4 — Preflight Cassandra (somente leitura)

Executado a partir do Mac via túnel SSH → Oracle (`10.0.0.234:9042`).
**Nenhuma escrita** no Cassandra. IDs de jogadores não são publicados aqui —
apenas contagens, sufixos de grupo e hashes SHA-256 truncados.

## Conectividade

| Item | Valor |
|------|--------|
| Host Oracle | `ubuntu@144.22.248.79` |
| Contêiner | `zapbot-cassandra` (healthy) |
| Cassandra | 5.0.9 / DC `datacenter1` |
| Túnel local | `127.0.0.1:19045` → `10.0.0.234:9042` |
| Keyspace | `zapbot` |
| Auth no native protocol | conexões locais sem credencial (SELECT only) |

Artefatos privados (mode `600`, fora do Git):

`~/odisseu-recovery/a64-preflight/`

## Partições V1 (`legacy_estado_particionado`, modulo=`treinador`)

Confirmadas **9** partições:

- 2 grupos `@g.us`
- 6 partições `@lid` (ou equivalente)
- 1 marcador `__migrado__` (não é save)

Grupos com treinadores (sufixo):

- `…363231939572938@g.us`
- `…363427684548487@g.us`

## Jogadores V1 nos dois grupos

| Grupo (sufixo) | Players | `@lid` | `@c.us` |
|----------------|---------|--------|---------|
| `…572938@g.us` | 1 | 1 | 0 |
| `…548487@g.us` | 22 | 18 | 4 |
| **Total** | **23** | 19 | 4 |

Um mesmo hash de player aparece nos dois grupos (conta compartilhada entre chats).

## Presença V1/V2 (`--probe-player`)

Probe SELECT-only sem exportar atributos/Pokémon/moedas:

| Métrica | Contagem |
|---------|----------|
| Players sondados | 23 |
| Com V1 | 23 |
| Com V2 | 21 |
| Com ambos | 21 |
| Sem nenhum | 0 |
| Só V1 (sem V2) | 2 |

Todos os 23 têm pelo menos V1 recuperável. Dois têm apenas V1.

## Comandos usados (repetíveis)

```bash
ssh -f -N -T -i ~/.ssh/oracle_cassandra -o IdentitiesOnly=yes \
  -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:19045:10.0.0.234:9042 \
  ubuntu@144.22.248.79

export CASSANDRA_CONTACT_POINTS='127.0.0.1:19045'
export CASSANDRA_LOCAL_DC='datacenter1'
export CASSANDRA_KEYSPACE='zapbot'

node pokemon-service/scripts/export-historical-saves.cjs --list-players \
  '120363231939572938@g.us' '120363427684548487@g.us'

# Probe individual (sem payload de conta):
# RECOVERY_CHAT_ID=... RECOVERY_PLAYER_ID=... \
# node pokemon-service/scripts/export-historical-saves.cjs --probe-player
```

## Migration 025 em cópia descartável

Fonte: backup a frio local `~/odisseu-recovery/a455-20261022T172313Z`
(`system=odisseu-m90-core`, host `odisseu-core`, git `6f62a67` — **não** é o volume
live `2cab639`, mas é um snapshot Core real).

Rehearsal em `~/odisseu-recovery/a64-preflight/datomic-025-rehearsal` (cópia; original intacto):

| Check | Resultado |
|-------|-----------|
| Antes: attrs 025 | ausentes |
| Antes: players / saves | 3 / 6 |
| Após `schema/install!` | `pokemon/025-save-slot-metadata` aplicada |
| Attrs `:save/display-name` … `:save/last-rename-name` | presentes |
| Contagens players / saves | 3 / 6 (preservadas) |
| Save amostral pré-existente | intacto (sem origin/import) |

**Ainda falta:** repetir o mesmo ensaio contra cópia do volume live
`odisseu-core-a56-datomic` na VM Core. SSH com `~/.ssh/odisseu_core` retornou
`Permission denied (publickey)` em 2026-10-27; docs de deploy também citam
`google_pokemon` — precisa reautorizar a chave no host antes do ensaio live.

## Próximos passos (ainda sem import/deploy)

1. Restaurar acesso SSH ao Core e validar 025 na cópia do volume A5.6 live.
2. Exportar dumps autorizados por jogador (EDN privado) após confirmação de identidade atual ↔ histórica.
3. Declarar aliases `@lid`/`@c.us` no dump quando o WhatsApp atual divergir do histórico.
4. Só então carregar catálogo na JVM e executar import admin por solicitação confirmada.
