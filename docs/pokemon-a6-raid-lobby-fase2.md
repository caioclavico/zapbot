# A6 — Fase 2: Lobby cooperativo de raides

## Auditoria (base `1273fa4` / `a6-save-slots`)

| Camada | Local |
| --- | --- |
| Domínio combate/inscrição | `modules/pokemon/domain/pokemon/domain/raid.clj` |
| Casos de uso | `pokemon.application.raid` + `bridge_tx` / `bridge_reads` |
| Persistência | `raid_store.clj` + ports em `store.clj` |
| Schema | `migrations/pokemon/007-raids.edn` (+ `029-raid-lobby.edn` nesta fase) |
| WhatsApp | `src/zapbot/pokemon_http.cljs` (`abrir`/`cancelar` estavam bloqueados) |
| HTTP | `/v1/raids/*` (`open` era harness-only) |

PR [#34](https://github.com/caioclavico/zapbot/pull/34) trouxe só domínio CLJS puro (`criar`/`inscrever`/`encerrar-inscricoes`). Esta fase **porta as regras** para o JVM A6 (keywords + `err/error`), reutilizando motor M3.6/M4.9 de combate/recompensas sem duplicá-los.

## Incompatibilidades resolvidas

| Antes (A6) | Fase 2 |
| --- | --- |
| Lobby 45 min / max 6 | **2 min / max 5** (persistidos em opens novos) |
| `abrir`/`cancelar` bloqueados no WA | Liberados |
| `/v1/raids/open` só `allowTestRng` | Open por `ginasio` em gameplay |
| Sem cancelar no domínio | `cancelar` + fase `cancelada` |
| Open sem CAS de ponteiro | CAS / assert 1 raide ativa por chat |
| Expiração só por `ativa?` | Eager expire em read/mutate (sem timer em memória) |

## Comandos

- `!pk raid abrir` — lista ginásios A6 com seleção numérica
- `!pk raid abrir <n|id>` — cria lobby (liga/chefe derivados do ginásio)
- `!pk raid entrar` / `sair` / `status` / `iniciar` / `cancelar`

Criador **não** entra automaticamente; precisa de `entrar`. Lobby: 2 min, máx. 5, mín. 2.

## Fora de escopo

Raides espontâneas/globais; mudanças em ledger/XP/recompensas de combate; deploy/migrate/backfill em produção.

## Riscos

- Templates de chefe por ginásio são determinísticos e limitados ao catálogo A6 de ases.
- Raides abertas antes do 029 sem `min`/`max` usam defaults do domínio na leitura.
- Eager expire no instante do prazo cancela/inicia no próximo comando ou status.
- Abertura concorrente exige ponteiro `chat-active-raid` pré-existente + CAS `nil→raid-id` (upsert puro do unique identity **não** serializa duas opens).

## Fase 2.1 — Validação final (2026-10-31)

**Branch:** `feature/a6-raid-lobby-fase2` (worktree `zapbot-raid-lobby`).  
**Worktree `a6-save-slots`:** preservado (sem alterações desta fase).  
**Não feito:** deploy, migrate, backfill, merge, prod.

### Suítes

| Suite | Resultado |
| --- | --- |
| `./scripts/test-m4-raid.sh` (M4.9 + M3.6 + M4.8 + smoke HTTP + Node) | OK |
| `./scripts/test-raid-lobby-fase2.sh` (23 JVM + 4 Node) | OK |
| `npm run build` (shadow-cljs release app → `target/main.js`) | OK |

### Cenários críticos (cobertos em `raid_lobby_fase2_test` / present)

| Cenário | Resultado |
| --- | --- |
| Dois jogadores abrem raide no mesmo chat | 1 vence, outro `:raid_chat_busy` (CAS ponteiro) |
| Mesmo `operation-id` duas vezes (join) | 2ª resposta `duplicate=true`, sem 2ª inscrição |
| Reinício durante inscrição / combate | Ponteiro + fase recuperados via Datomic |
| Comandos após expire sem quorum | `:raid_not_found` |
| Criador sai do lobby | Ordem sem criador; start pelo criador `:not_in_raid`; outro `:raid_not_creator` |
| Início antecipado com 2 | Combate + `rewards-paid=false` |
| Grupos independentes | Duas raides ativas em chats distintos |
| 2ª open com chat busy | Ponteiro da vencedora intacto |

### Integração A6

- Combate/recompensas: motor M3.6/M4.9 (`build-attack-tx` / `rewards-paid?` só na vitória).
- Schema `029-raid-lobby.edn`: só attrs aditivos (`criada-em`, `min-jogadores`, `max-jogadores`); 007 intacto.
- Lobby não paga recompensa (`rewards-paid` permanece false em start).
- Recibos + CAS: open/join/attack/capture idempotentes; ponteiro CAS na open.
- WhatsApp present: chefe, ginásio, inscritos N/5, participantes e prazo (`⏱️`) em opened/status.

### Problemas encontrados e correções

1. **Corrida na open:** upsert de `:chat-active-raid/raid-id` via unique identity sobrescrevia sem conflito. Corrigido com `ensure-chat-raid-pointer!` + CAS `nil→id`; cancel/expire passam a **retract do attr** (mantêm a entidade).
2. **M4.9 `join-auto-e-liga`:** assumia 2ª open no mesmo chat; adaptado a cancelar antes (regra 1 ativa/chat).
3. **Node `formatGymBlocked(abrir/cancelar)`:** asserção obsoleta; alinhada à liberação Fase 2.

### Riscos residuais

- Sem migrate/deploy: produção ainda sem 029 e sem este código.
- Eager expire só no próximo comando/status (sem timer push).
- Multi-process Odisseu: CAS cobre; ensure do ponteiro é best-effort com ignore de unique conflict.
- Orfãos: raid entity da open perdedora **não** persiste (tx CAS falha por completo); verificado no diagnóstico de corrida.
