# A6 — Fase 12.7.3: planejamento antecipado de intent (ovos)

**Status:** IMPLEMENTADO + testes verdes em descartável · **sem GO / sem deploy / sem migrate**  
**Base:** 12.7.2 (cadência 1h · cap 12 · specialReserve 2 · ovos só especiais)  
**Piloto alvo (após GO):** `120363427684548487@g.us`  
**Preserva:** recovery-v1 · fences · unknown-pending · leave ON · schema 030/031 · catálogo 571 · economia 12.6.2 · allowlist piloto  

Produção permanece em **12.7.2** até autorização explícita.

---

## 0. Causa raiz (auditoria 12.7.2)

Na 12.7.2 o receipt `raid-auto-intent:{chat}` só era gravado **no spawn** (para o *próximo* slot).

Consequências:

1. Primeiro evento após `enable` / antecipação de `nextAt` → `nextCategoria=null`.
2. Com `intervalMs=1h` (< legado 3h), `reminders-allowed?` = false.
3. T−30/15/5 **nunca** enfileiravam antes do 1º spawn — mesmo se a categoria *viesse a ser* lendária/mítica.
4. Antecipação manual do piloto (01h00 CG) herdou exatamente esse buraco.

Não era falha de outbox/WA: o gate de domínio omitia de propósito por falta de intent.

---

## 1. Comportamento 12.7.3

| Regra | Detalhe |
| --- | --- |
| Quando planeja | Tick: **antes** dos lembretes, se `enabled` e `nextAt` futuro dentro do lead (`intent-planning-lead-ms = 40min`) e sem `next-categoria` durável |
| O que grava | Receipt `raid-auto-intent:{chat}` = `{next-at, categoria, reminders?}` — **sem spawn** |
| Especiais | `reminders?=true` → T−30/15/5 (PNG ovo, sem spoiler) |
| Raro/épico | `reminders?=false` → sem ovos |
| Spawn | Respeita `forced-categoria` do intent; depois planeja o **próximo** slot |
| Restart | Intent casado com `nextAt` → não re-sorteia |
| Reagendamento | `nextAt` novo **sem** `nextCategoria` explícita → invalida categoria do slot antigo; tick replaneja |
| Especial reservado | PUT/`safe-cadence` com especial → não sobrescreve |
| Janela tardia | Ativação dentro de T−30: planeja; só dispara offsets ainda válidos (grace 2min) |
| Cap / reserva | `dailyLimit=12` · `specialReserve=2` intactos |
| Catálogo | `category-weights` 100% na cat escolhida → 571 elegíveis dinâmicos |

Ordem do tick: `expire lobby → suppress stale → ensure intent → reminders → spawn`.

---

## 2. Testes

```bash
clojure -M:test-raid-auto-intent-fase1273
```

Cobre: enable · alteração de `nextAt` · lead >30min · dentro de T−30 · 3 ovos especial · fácil sem ovo · restart · reagendamento · disable · idempotência · spawn usa categoria planejada · reserva especial · **regressão 12.7.2 + 12.7.1**.

Última corrida local: **47 tests · 262 pass · 0 fail**.

---

## 3. Riscos

| Risco | Mitigação |
| --- | --- |
| Categoria travada cedo demais vs budget do dia | Lead 40min (perto do due); `spawns-today`/`last-by` no momento do plan |
| Reagendar carregar especial antigo | Clear de `next-categoria` quando `nextAt` muda sem reserva explícita |
| Duplicata de ovo | Outbox id estável `raid-reminder:{chat}:{due}:{offset}` |
| Concorrência plan × PUT × spawn | Intent por chat (unique); spawn CAS em `revision`; enrich só aplica intent com `next-at` igual |
| Produção 12.7.2 | Sem deploy até GO |

---

## 4. Plano de implantação (após GO)

1. Gates locais: commit + `clojure -M:test-raid-auto-intent-fase1273` + content-hash.  
2. Sync + build RC JVM/Node no Core (mesmo fluxo 12.7.2).  
3. Backup warm/cold + snapshot schedule/intent/cadence do piloto.  
4. Cutover imagens · WA READY · **sem** force tick / spawn manual.  
5. Observar logs `raid_auto_intent_planned` / `raid_auto_reminder` no piloto.  
6. Confirmar: intent casa com `nextAt`; especiais geram ovos; fáceis não; demais grupos `enabled=false`.  

Rollback: reverter artefato para 12.7.2. Intent/cadence receipts são leftover inofensivo.

---

## 5. Arquivos

- `modules/pokemon/domain/pokemon/domain/raid_auto.clj` — `needs-intent-planning?` / `plan-slot-intent` / lead 40min  
- `modules/pokemon/infrastructure/.../store.clj` — `ensure-intent-for-chat!` no tick  
- `modules/pokemon/application/.../raid_auto.clj` — invalida intent em mudança de `nextAt`  
- `modules/pokemon/tests/.../raid_auto_intent_fase1273_test.clj`  
- `apps/odisseu/src/odisseu/smoke/raid_auto_intent_fase1273.clj`  
- `deps.edn` — alias `:test-raid-auto-intent-fase1273`  
