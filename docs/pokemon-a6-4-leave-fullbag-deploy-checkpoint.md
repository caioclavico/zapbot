# Checkpoint — bolsa cheia encerra + leave ginásio

**Quando:** 2026-10-30 ~20:06–20:10 UTC  
**Host:** odisseu-core

## Problemas

1. Vitória com bolsa cheia (`somente-batalha`): UI dizia “liberado”, mas a batalha ficava em `aguardando-captura` → precisava `!pk sair`.
2. `!pk sair` no ginásio às vezes respondia “não está participando” (ponteiro ausente / sem `battleId`).

## Correção

| Camada | Mudança |
|--------|---------|
| JVM `store.clj` | Win + `somente-batalha?` → finaliza, limpa ponteiros, flag `selvagem-liberado-bolsa-cheia?` |
| JVM leave | Fallback por `battle/player-key` se `active-battle-id` sumiu |
| Node `sair` | Busca encounter e envia `battleId` |
| present | Mensagem de bolsa cheia também no outcome `win` já finalizado |

## Testes

- hunt **18/124** (novo caso bolsa cheia)
- gym-battle **12/75**

## Deploy

| | Tag | ID |
|--|-----|-----|
| JVM | `a64-leave-fullbag-20261030T200619Z-amd64` | `sha256:95991a36…2f83` |
| Node | idem | `sha256:eeb64c73…3aa5` |

Health: JVM/Node **healthy** · WhatsApp **READY**

## Nota

Durante o diagnóstico, a batalha Pedra aberta do LID `207700…` foi abandonada via leave de API (status `left`). Pode desafiar de novo.
