# M3.2 — Segurança transacional das recompensas

## Caminhos

| API | Canal | Fence | Uso |
|---|---|---|---|
| `pokemon.application.reward/apply-gameplay-reward!` | `gameplay` | CAS ativo+geração na mesma TX | Comandos / callbacks de jogo |
| `pokemon.application.reward/admin-seed-reward!` | `admin` | não | Harness, seed, migração (explícito) |
| `trainer/grant-reward!` | — | — | **Bloqueado** (`:use_explicit_reward_api`) |

Moedas/inventário de `collection` delegam ao caminho gameplay.

## Garantias

1. Identidade: `chat-id`, `player-id`, `save-id`, `fence-generation`, `operation-id`
2. Save ativo + fence na mesma TX que moedas/inventário/progresso
3. Callback A após B ativo → `:stale_writer_fence`
4. Retry mesmo payload → `:duplicate true`
5. Mesmo `operation-id` + payload diferente → `:receipt_payload_mismatch`
6. Isolamento por jogador (recibos e saves distintos)

## Como rodar

```bash
./scripts/test-m3-reward-datomic.sh
git diff --check
```

## Resultados (2026-10-17)

| Suite | Resultado |
|---|---|
| reward-m32 | 8 tests / 23 asserts OK |
| xp + collection + M2 | 15 tests / 101 asserts OK |
| `git diff --check` | limpo |
| RSS | ~232 MB (`-Xmx256m`) |

## Divergências restantes

- Raids/ginásios ainda no legado Node (fora do escopo)
- Seed admin ainda pode escrever em save inativo (proposital; não é comando)
- XP de insígnias/missões continua em cache (M3.1)
