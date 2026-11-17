# M3.6 — Concorrência de ginásios + Raids (JVM/Datomic)

## Parte A — Ginásios

### Endurecimento
- Stub `rev=0` permanece o alvo de CAS da primeira ocupação.
- Fail-fast se `expected-revision ≠ slot-revision` → `:gym_cas_conflict` (sem queimar retries de fence).
- `gym-defense-count` via `:db/cas` (não `:db/add`).
- Wear/heal fazem CAS em `:gym-occupation/revision`.
- Testes com `CyclicBarrier`/`CountDownLatch`: corrida no vazio e destronamento concorrente.

### Completo nesta etapa
Exclusividade de ocupação, ausência de recompensa duplicada no perdedor, devolução ao save de origem, bloqueio de switch.

## Parte B — Raids

### Regras portadas (legado `raids.cljs`)
| Item | Valor |
|---|---|
| Inscrições | 45 min, 2–6 jogadores |
| Combate | 30 min; HP chefe = 220×N |
| Dano | `floor(15+0.6*poder*(atk/def))` ∈ [10,160] |
| Revide | `ceil(hp_max/5)` |
| Vitória | 50 moedas + 6 PE + 6 XP (só quem deu dano>0), 1× por raid |
| Captura | 30 min, 3 tentativas; chances por raridade + bola |
| Auto-spawn | **não** portado (removido no legado) |

### Identidade / fence
Participante carimba `save-id` + `fence-generation` na inscrição. Ataque/captura exigem o mesmo carimbo → switch A↔B invalida callback.

### Completo (JVM)
Abertura manual/harness, join/leave, start, attack (CAS `turn-index` + recibo), rewards atômicos na transição vitória, captura pós-raid, reabertura do DB.

### Ainda no Node / fora do escopo
- UX WhatsApp (`!pk raid`, filas, SVG)
- Auto-aparicao / agendas (já removidas no legado)
- Adventure
- Liga/elegibilidade fina de nível do Pokémon (harness usa liga fixa)

O motor de **dano de raid** é o da própria raid (fórmula simples), **não** o motor 1v1 de M3.4. Combate de ginásio 3×3 turno-a-turno continua orquestrado no Node; M3.5/M3.6 só persistem o resultado agregado.

## Como rodar

```bash
./scripts/test-m3-raid-datomic.sh
git diff --check
```
