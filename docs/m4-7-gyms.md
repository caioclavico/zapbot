# M4.7 — Ginásios no adaptador JVM

## Mapa de comandos (legado)

| Comando / alias | Ação |
| --- | --- |
| `ginasio` / `gin` / `ginasios` | Menu / detalhe do ginásio |
| `gin desafiar` / `des` / `dsf` | Inicia combate 3×3 |
| `gin time` / `tm` `[auto\|i,j,k]` | Escalação de 3 Pokémon |
| `gin pocao` / `pot` `<id> <1-3>` | +40 motivação (líder) |
| `gin fruta` / `fru` … | +20 / dourada → 100 |
| `gin ranking` / `ran` | Stats (Cassandra legado) |
| `gin historico` / `hist` | Últimas batalhas (Cassandra) |
| Raid sob `gin entrar/atk/…` | **Fora** deste marco |

## M3.5 / M3.6 já no JVM

- Catálogo, desbloqueio, motivação, recompensas, ocupação CAS, fence A/B
- `set-gym-team!`, `heal-motivation!`, `resolve-challenge!` (resultado **agregado**)
- Combate 3×3 turno-a-turno **não** está no motor M3.4 (1v1 selvagem)

## Decisão deste marco

| Fluxo | Status |
| --- | --- |
| Consulta menu / detalhe / ocupação / insígnias | **Habilitado** |
| Ver / salvar time de ginásio | **Habilitado** |
| Poção / fruta (motivação) | **Habilitado** (pontos derivados do item no servidor) |
| `desafiar` + turnos 3×3 | Ver **M4.8** (`docs/m4-8-gym-battle.md`) |
| Ranking / histórico | **Bloqueado** — não persistidos no Datomic M3.5 |
| Raids | **Fora** |

`resolve-challenge` com `:venceu?` do cliente permanece fora do WhatsApp; no M4.8 o resultado sai do motor no TX do turno final.

## Contratos HTTP

| Método | Rota |
| --- | --- |
| GET | `/v1/gyms` — catálogo + unlock + ocupações |
| GET | `/v1/gyms/:id` — detalhe + time defensor (motivação) |
| GET | `/v1/gyms/team` — escalação atual resolvida |
| POST | `/v1/gyms/team` — `set-gym-team` (fence + operationId) |
| POST | `/v1/gyms/heal` — poção/fruta (fence + operationId) |

## Testes

```bash
./scripts/test-m4-gym.sh
```
