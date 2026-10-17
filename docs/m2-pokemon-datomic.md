# M2 — Núcleo transacional Pokémon (Datomic Local)

**Status:** implementado em desenvolvimento (sem migração Cassandra, sem WhatsApp)  
**Branch:** `refactor/modular-monolith-datomic`  
**Pré-requisito:** M1 (`com.datomic/local` 1.0.301)

## Decisões de modelagem

| Conceito | Entidade / attrs | Escopo |
|---|---|---|
| Jogador | `:player/*` (`chat-id`, `player-id`, `key`) | conta global no chat |
| Save ativo | `:player/active-save-id` + `:player/fence-generation` | ponteiro na conta |
| Save A/B | `:save/*` com `:save/key = chat\|player\|slot` | independente |
| Economia / XP | `:save/coins`, `:save/trainer-xp`, `:save/trainer-level`, `:save/revision` | por save |
| Inventário | `:inventory-item/*` | por save |
| Pokémon / party | `:pokemon/*` (`in-party`, `shiny`, xp/nível) | por save |
| Pokédex | `:pokedex-entry/*` | por save |
| Idempotência | `:operation-receipt/id` (unique identity) | global |
| Grupo | `:group/chat-id` | compartilhado; **separado** dos saves |

IDs alinhados ao Cassandra canônico (`chat_id`, `player_id`, `active_save_id`, `fence_generation`, receipts).

## Transações utilizadas

1. **Criar jogador / save** — maps com unique identity (`:player/key`, `:save/key`).
2. **Ativar save A↔B** — uma TX com `[:db/cas … :player/fence-generation old new]` + `[:db/add … :player/active-save-id target]`. **Não** reescreve entidades do outro save. Retry em conflito (até 12).
3. **Recompensa idempotente** — mesma TX: cria `:operation-receipt/id`, CAS em `:save/coins` + `:save/revision`, opcionalmente Pokémon/Pokédex/item. Replay do mesmo `operation-id` → `:duplicate true` sem reaplicar efeito.

## Separação de camadas

- `pokemon.domain.*` — puro (sem Datomic)
- `pokemon.application.*` — casos de uso + `TrainerStore` port
- `pokemon.infrastructure.datomic.*` — schema + adapter
- Schema versionado: `migrations/pokemon/001-trainer-saves.edn`

## Fora de uma transação Datomic (coordenação explícita)

| Conceito | Por quê |
|---|---|
| Entrega WhatsApp / mídia / HTTP | Efeito externo; após commit, fora da TX |
| Timers / Adventure hatch | Relógio e I/O; intent + recibo na TX, disparo depois |
| Dual-write Cassandra | Não existe no M2; cutover futuro é orquestração de deploy |
| Inventário com writers paralelos no **mesmo** item | Qty é re-lida no retry do CAS de `revision`/moedas; M3+ pode CAS dedicado em qty se necessário |
| Raids/ginásios multi-jogador | Precisam TX no `:group/*` + carimbo no save; ainda não modelados além do stub de grupo |

## Como rodar

```bash
./scripts/test-m2-trainer-datomic.sh
```

## Resultados (execução local 2026-10-17)

| Métrica | Valor |
|---|---|
| Testes | **7 tests / 41 assertions — 0 fail / 0 error** |
| Java | OpenJDK 21.0.12.1 |
| Heap used → max | ~42–52 MB / **256 MB** |
| RSS | ~212 → **242 MB** (só JVM) |
| Isolation | Sem Node/Chromium/Cassandra no processo |
