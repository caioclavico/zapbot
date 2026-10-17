# M3.4 — Motor de batalha selvagem (JVM/Datomic)

## Escopo

| API | Fence | Recibo |
|---|---|---|
| `battle/start-wild-battle!` | CAS ativo+geração | kind `start-battle` |
| `battle/confirm-turn!` | idem + CAS `turn-index` | kind `battle-turn` |

Ginásios e raids **fora** desta etapa. UI WhatsApp permanece no Node.

## Regras portadas (sem alterar balanceamento)

- `fator-dano = 0.22`; `calcular-dano` + variância 0–10
- Tabela de tipos + Levitate; **sem STAB no dano** (igual ao legado)
- Crítico 10% ×1.5; Blaze/Torrent/Overgrow; banda/óculos ×1.15
- Precisão, esquiva ao defender, multi-hit
- Status: paralisia/sono/gelo/confusão; DoT queimadura/veneno; contágio 35%
- Faixa de Foco, Restos, Intimidate (×⅔)
- Loop selvagem: jogador → (fuga | resposta NPC); vitória → `opponent-fainted`

## Garantias

1. Batalha amarrada a `chat-id` / `player-id` / `save-id` / fence
2. Alternância A↔B com batalha ativa → `:battle_pending`
3. Callback com fence antigo → `:stale_writer_fence`
4. Retry mesmo `operation-id` → duplicate; `expected-turn-index` defasado → `:stale_turn_index`
5. Ao encerrar: sync HP/status na coleção; derrota/fuga concede +1 XP via domínio XP

## Como rodar

```bash
./scripts/test-m3-battle-datomic.sh
git diff --check
```

## Resultados (2026-10-17)

| Suite | Resultado |
|---|---|
| battle-m34 | 12 tests / 38 asserts OK |
| M3.3 + M3.2 + M3.1 + M2 | 34 tests / 180 asserts OK |
| `git diff --check` | limpo |
| RSS | ~220→251 MB (`-Xmx256m`) |

## Divergências / riscos MVP

- PvP alternado por velocidade ainda não migrado (só selvagem)
- Golpes de transformação / cópia de moveset não modelados
- Caçada completa (bioma/clima/imagem) continua no Node
- RNG injetável nos testes; produção usará `rand-int` nas chaves ausentes
