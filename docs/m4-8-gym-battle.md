# M4.8 — Motor de combate 3×3 (ginásios)

## Regras legado (mapa)

| Regra | Legado | M3.4 1×1 | M4.8 |
| --- | --- | --- | --- |
| Dano/tipos/status/precisão | core `resolver-ataque` | `damage`/`status` | Reuso |
| 3 Pokémon / reservas | `reservas` + auto-troca no KO | — | Novo |
| Intimidação na entrada | sim (início) | create-wild | create-gym |
| Fuga do adversário | não (líder) | 8% comum | Desligada em `:gym` |
| Turno do líder | auto após jogador | wild-response | Mesmo padrão sem flee |
| Troca manual | não no ginásio | — | Não |
| Poção mid-fight | sim | — | **Adiada** (só fora da batalha) |
| Resultado | `finalizar-ginasio` | — | `resolve` **interno** no TX do turno final |
| Cliente manda `venceu?` | nunca no WhatsApp | — | Rota resolve **não** na bridge WhatsApp |

## Divergências aceitas neste marco

1. Poção/fruta **durante** o combate: ainda só fora da batalha (`gin pocao`).
2. Overlay visual 3×3 / motivação no SVG: texto + arena 1v1 atual (M4.6); cartão ginásio completo depois.
3. Empate (KO mútuo sem reservas): encerra sem ocupação/recompensa diária (igual legado).
4. Ranking / histórico: ainda não no Datomic (bloqueados no adaptador).

## Comandos habilitados

| Comando | Status |
| --- | --- |
| `!pk gin` / `!pk gin <id>` | Menu / detalhe |
| `!pk gin time` / `time auto` / `1,2,3` | Escalação |
| `!pk gin pocao\|fruta <id> <1-3>` | Motivação (líder) |
| `!pk gin desafiar <id>` | **Habilitado** — start no JVM |
| `!pk atacar` / `defender` | Turno (caça **ou** ginásio) |
| `resolve` via cliente | **Não existe** na bridge WhatsApp |
| Ranking / histórico / raid | Bloqueados |

## Contratos HTTP

| Método | Rota |
| --- | --- |
| POST | `/v1/gyms/challenge` — start 3×3 (fence + operationId; NPC via `candidates`) |
| POST | `/v1/battles/turn` — turno; se ginásio termina, resolve recompensas na mesma TX |

## Reuso do motor M3.4

- `apply-player-turn`, `wild-response` (sem flee), dano, status, precisão, itens (faixa-foco), RNG
- Persistência de batalha + CAS `turn-index` + fence A/B + recibo `operation-id`
- Bloqueio de `ativar` save com batalha pendente

## Testes

```bash
./scripts/test-m4-gym-battle.sh
```
