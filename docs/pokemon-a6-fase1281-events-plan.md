# Fase 12.8.1 — Plano: Eventos temáticos operacionais

**Status:** planejamento · **bloqueado até GO pós-12.8.0**  
**Pré-requisito:** cutover 12.8.0 estável no piloto  
**Flag:** `POKEMON_THEMATIC_EVENTS_ENABLED` (default `false` em 12.8.0)

---

## Objetivo

Ativar o catálogo já presente em `pokemon.domain.events` com:

1. Agendamento e administração (janelas `inicio`/`fim`, allowlist por chat).
2. Missões exclusivas resgatáveis com ledger + inventário reais.
3. Doces de famílias evolutivas (mesmo `professor-cards-edn`).
4. Modificadores temporários de caça via `active-events` → `resolver-tipos-encontro` (4 args).
5. Mensagens de abertura/encerramento.
6. Idempotência, expiração e recuperação após restart.

---

## Escopo técnico

| Peça | Estado em 12.8.0 | Trabalho 12.8.1 |
| --- | --- | --- |
| Catálogo 7 eventos | Presente, `inicio`/`fim` nil → `inactive` | Janelas + admin HTTP/ops |
| `active-events` | Sempre `[]` se flag off | Flag on + filter janela/chat |
| `montar-plano` | Aridade 3 (sem bias evento) | Passar `active-events` do chat |
| Claim missões evento | Não operacional para catálogo temático | Allocate itens + `grant-candy-map` + ledger |
| UX WhatsApp | Filtro `!pk missoes evento` (legado Novo Recomeço) | Listar/ativar eventos temáticos |

---

## Critérios de aceite

- Sem janela válida → nenhum bias de caça / nenhuma missão resgatável.
- Doces só de famílias reais; nunca doces genéricos de tipo.
- Concessão idempotente; inventário cheio → pending.
- Restart não duplica recompensas nem reabre evento encerrado.
- Testes: ativação/expiração, bias estatístico, claim Doces, regressão clima+caça 12.8.0.

## Rollout

1. Gates locais + Datomic descartável.  
2. RC JVM/Node.  
3. Piloto only com flag `true` + 1 evento de teste com janela curta.  
4. GO global só após smoke.

**Não ativar em 12.8.0.**
