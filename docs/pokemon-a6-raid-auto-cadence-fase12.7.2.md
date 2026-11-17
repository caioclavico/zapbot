# A6 — Fase 12.7.2: raids fáceis mais frequentes

**Status:** IMPLEMENTADO + readiness final · validado em descartável · **sem GO / sem deploy / sem migrate**  
**Base:** 12.7.1 (lembretes ovo + lobby dinâmico + `iniciar`)  
**Piloto alvo (após GO):** `120363427684548487@g.us`  
**Cap piloto:** **12 raids/dia** · `special-reserve=2` · metas de categoria ≠ obrigação  
**Preserva:** recovery-v1 · fences · unknown-pending · leave ON · schema 030/031 · raids manuais · catálogo 571 elegíveis · economia 12.6.2  

Ver também: [`pokemon-a6-raid-ready-for-go-fase12.7.2.md`](./pokemon-a6-raid-ready-for-go-fase12.7.2.md)

---

## 0. Auditoria do scheduler (antes de mudar)

| Área | Achado (12.7.1 live) |
| --- | --- |
| Tick | `expire lobby → reminders → spawn due` |
| Agenda | 1 `nextAt` + `intervalMs` por chat; spawn-id = `raid-auto:{chat}:{due}` |
| Cadência | Intervalo único (~6h no piloto); categoria sorteada por pesos 40/30/20/10 |
| Conflito | `ocupa-slot` em inscrição/combate → `:skip-busy` avança `nextAt` 1× (sem fila) |
| Lembretes | T-30/15/5 para **todo** nextAt com interval ≥ offset |
| Manual | Intocada; nunca cancelada para abrir auto |
| Persistência | Schedule Datomic 031; anti-repetição via histórico de `chefe-edn` |

Problema: com 6h e pesos mistos, raro/épico são raros demais; lembretes em todo evento spamariam o WA se só baixássemos o intervalo.

---

## 1. Política de cadência

### Intervalo-base (slot exclusivo)

`default-interval-ms = 1h` — um evento por grupo por vez.  
Piloto atual (6h) **não muda sozinho**; GO faz `PUT intervalMs=3600000`.

### Cooldown por categoria (configurável)

| Categoria | Default | Papel |
| --- | ---: | --- |
| raro | 1h | filler diurno |
| epico | 3h | filler diurno |
| lendario | 12h | evento especial |
| mitico | 24h | evento especial |

Overrides sem migration: receipt `raid-auto-cadence:{chat}` ou `PUT categoryIntervals`.

### Seleção determinística (`pick-categoria`)

- **Dia BRT [08, 22):** épico se due → raro → especial. Especial só se overdue ≥ 1,5×.
- **Noite:** mítico/lendário se due → épico → raro.
- Catálogo dinâmico intacto: `category-weights = {cat: 1.0}` no slot escolhido.

### Intent do próximo slot

Receipt `raid-auto-intent:{chat}` = `{next-at, categoria, reminders?}`.  
Restart idempotente: spawn-id + intent; não recria due já confirmado.

### Skip-busy

Adia `nextAt` em 1 intervalo. Se o intent era **especial**, **preserva a categoria** (não troca lendário por raro). Sem fila ilimitada.

---

## 2. Proposta de horários reais (exemplo BRT, estado frio)

Assumindo GO com `intervalMs=1h` e cooldowns default, dia útil típico:

| Hora | Categoria provável | Motivo |
| --- | --- | --- |
| 08:00 | épica | dia + ambas due |
| 09:00 | rara | épico em CD |
| 10:00 | rara | — |
| 11:00 | épica | CD 3h |
| 12–19 | rara / épica a cada ~3h | — |
| 20–23 | lendária (se due) | noite |
| ~mesmo horário +1d | mítica | CD 24h |

Horários exatos dependem do `nextAt` atual e do histórico; a tabela é a **forma** da distribuição, não um cron fixo.  
**Não remarcar** especial já planejado sem autorização (skip-busy só adia).

---

## 3. Anti-conflito

1. Slot único (`chat-active-raid`) — auto nunca cancela manual/auto viva.  
2. Expire roda **antes** do spawn no tick.  
3. Skip-busy avança 1 passo (sem backlog).  
4. Intent especial preservado no adiamento.

---

## 4. Estimativas / dia (piloto · limite 12)

Simulação 7d (domínio): **exatos 12/dia** · mix típico ~6 raro · ~4 épico · ~1 lendário · ~1 mítico.

| Métrica | Com cap 12 |
| --- | ---: |
| Raids/dia | **12** |
| Msgs WA/dia | **~30** (fáceis×2 + especiais×5) |
| Lembretes ovo/dia | ~6 (só especiais) |

Sem cap (teórico 1h 24/7): ~18–22 raids · ~45–55 msgs — **não usado no piloto**.

---

## 5. Lembretes (compat 12.7.1)

| Condição | Lembretes ovo? |
| --- | --- |
| `next-categoria` lendario/mitico | sim |
| `next-categoria` raro/epico | **não** |
| Legado: sem categoria + `intervalMs ≥ 3h` | sim (piloto 6h atual) |

PNG/caption/ids estáveis inalterados.

---

## 6. Testes

```bash
clojure -M:test-raid-auto-cadence-fase1272
# inclui regressão 12.7.1 (reminder + lobby/iniciar)
clojure -M:test-raid-auto-fase3
```

Cobertura 12.7.2: pick dia/noite, reminders gate, skip preserva especial, intent+restart, cadence config.

---

## 7. Rollout (após GO explícito)

1. Deploy JVM/Node com 12.7.2 (piloto allowlist intacta).  
2. `PUT /v1/raids/auto/schedule` no piloto:
   - `intervalMs: 3600000`
   - **não** alterar `nextAt` se já houver especial planejado (só se autorizado).  
3. Opcional: `categoryIntervals` custom.  
4. Observar 24h: logs `raid_auto_spawn` / `skip` / `reminder`; volume WA.  
5. Kill switch: `enabled:false` ou tick global off.

### Rollback

1. `PUT intervalMs: 21600000` (volta ~6h).  
2. Ou `enabled:false`.  
3. Reverter artefato se necessário. Intent/cadence receipts são harmeless leftover.

**Sem migration. Sem tocar outros grupos.**

---

## 8. Checklist de requisitos

| # | Requisito | Status |
| --- | --- | --- |
| 1 | Auditar scheduler | ✓ §0 |
| 2 | Frequências configuráveis | ✓ cadence receipt + PUT |
| 3 | Priorizar raro/épico de dia | ✓ |
| 4 | Lendário/mítico especiais | ✓ + lembretes |
| 5 | Catálogo 571 dinâmico | ✓ |
| 6 | Dificuldade por attrs | ✓ intacto |
| 7 | Lobby 5/10/20/30 | ✓ |
| 8 | `!pk raide iniciar` | ✓ |
| 9 | Sem simultâneas | ✓ |
| 10 | Não cancelar para abrir | ✓ |
| 11 | Sem anúncios excessivos | ✓ lembretes só especial |
| 12 | Lembretes ovo especiais | ✓ |
| 13 | Idempotência restart | ✓ spawn-id + intent |
| 14 | Economia 12.6.2 | ✓ |
| 15 | Raids manuais | ✓ |
