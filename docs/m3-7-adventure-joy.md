# M3.7 — Adventure Sync, ovos e Centro Pokémon

## Checkpoint Git (M3.4–M3.6)

Antes desta etapa, HEAD local = `origin/refactor/modular-monolith-datomic` = **`b7b2d56`**
(`feat(m3.4–m3.6): migrar batalha, ginásios e raids para Datomic`).

**Recomendação:** esse commit já é um checkpoint remoto suficiente. Opcional:
tag anotada `m3.6` em `b7b2d56` antes de integrar M3.7 (não feita automaticamente).

## Separação de estado

| Estado | Escopo | Notas |
|---|---|---|
| Conta Adventure + inventário de ovos | `(chat-id, player-id)` | Global ao jogador; **não** muda com A↔B |
| Carimbo no ovo incubado | `save-id` + `fence-generation` | Congelado na incubação |
| Entrega do nascimento | Save carimbado | Coleção + recibo |
| Enfermaria Joy | Save ativo | Leitos com `pronto-em` durável |

## A → B → A (explícito)

1. **Incubar em A** → ovo grava `save-id=a` e fence atual.
2. **Trocar para B** → `hatch!` com writer B → `:egg_save_mismatch` (ovo A não nasce em B).
3. **Voltar para A** → `hatch!` com writer fresco em A → entrega OK (fence pode ter subido; o carimbo do ovo não precisa bater com a fence atual).
4. **Callback stale** (writer congelado em A, depois switch) → `:stale_writer_fence`.

Joy: leitos ficam no save A; em B a enfermaria está vazia; em A o `collect!` após `pronto-em` devolve o Pokémon.

## Regras portadas

### Ovos (pokemon-service `ovos.cljs` atividade)
- Inventário máx. 9; incubadora permanente default
- Categorias: comum 2h/100 pts, raro 6h/250, épico 12h/500
- Pronto por tempo **ou** pontos; `due-at` durável
- Nascimento nível 1, HP cheio; TX atômica + `operation-id`

### Joy (legado `treinador.cljs`)
- 30 min; só feridos (HP ou status)
- Lazy collect via `pronto-em` (sem timer em memória)
- Sem limite de leitos além da capacidade de coleção

## Como rodar

```bash
./scripts/test-m3-adventure-datomic.sh
git diff --check
```

## Completo vs pendente integração

| Completo no JVM | Ainda Node / fora |
|---|---|
| Grant/incubate/hatch com fence | WhatsApp `!pk ovos`, notificações |
| Joy send/collect no save | Auto-drops de eventos (captura/PvP) |
| Reinício via `due-at`/`pronto-em` | Catálogo Adventure Sync completo / pools |
| | Agendador 60s (recovery é sob demanda no hatch) |
| | `aventuras.cljs` (pedras/surtos) — outro módulo |

Domínio puro (`eggs`, `joy`) sem Datomic/WhatsApp/timers.
