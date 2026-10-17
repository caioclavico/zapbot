# M3.5 — Ginásios (JVM/Datomic)

## Escopo

| API | Fence | Notas |
|---|---|---|
| `gym/resolve-challenge!` | sim | Vitória/derrota + ocupação + recibo |
| `gym/set-gym-team!` | sim | Escalação `[i j k]` |
| `gym/heal-motivation!` | sim | Líder do **mesmo save** |
| `gym/get-occupation` | leitura | Estado compartilhado do grupo |

Raids **fora**. UI WhatsApp permanece no Node.

## Regras portadas

- Catálogo de 5 ginásios + desbloqueio sequencial
- Motivação 100→mín 20 (−5/h, −12/defesa); fator stats `0.5+0.5*(mot/100)`
- XP participante: 3/7/4/2 + nocautes
- Vitória 1ª: 100 moedas, pedra 100%, PE 5 + vitória treinador
- Revanche: 25 moedas, pedra 25%, PE 2 + vitória
- Derrota: +1 PE; desgaste se ocupado
- Permanência: >6h → 50 moedas; XP `clamp(floor(ms/30min),1..24)` + amizade
- Ocupação grava `save-id` explícito; devolução só nesse save
- Switch A↔B bloqueado com `gym-defense-count > 0`

## Como rodar

```bash
./scripts/test-m3-gym-datomic.sh
git diff --check
```

## Divergências / riscos MVP

- Combate 3×3 turno-a-turno ainda orquestrado no Node (resolver usa resultado agregado)
- Ranking/histórico de estatísticas não persistidos nesta etapa
- NPC team fetch (PokeAPI) permanece no Node; domínio escala stats se dados forem passados
- Sem retirada voluntária (igual legado)
