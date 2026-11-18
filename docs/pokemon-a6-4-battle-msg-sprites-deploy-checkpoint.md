# Checkpoint — mensagem de dano master + sprites no switch do ginásio

**Quando:** 2026-10-31 ~15:41 UTC  
**Host:** odisseu-core · `35.192.113.146`

## Escopo

| Incluído | Excluído |
|----------|----------|
| Texto `causou N de dano` (+ efetivo/crítico/multi-hit/rival) | Economy |
| Sprites nas reservas do ginásio (sem caixa preta no próximo) | Migrations |

## Imagens

| | Tag |
|--|-----|
| JVM / Node | `a64-battle-msg-sprites-20261031T154142Z-amd64` |
| Rollback | `a64-insignias-master-20261031T152533Z-amd64` |

## Validação WA

- `!pk atacar N` deve mostrar `*Golpe* de *X* causou Y de dano em *Z*!`
- No ginásio, ao derrubar o 1º e entrar o 2º, sprite do próximo (não caixa preta)
