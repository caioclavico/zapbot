# Fase 12.8.2 — Cadência uniforme de raids automáticas — READY FOR GO

**Status:** código + testes locais prontos. **Sem deploy / sem mutação de produção.**  
**Versão-alvo:** 12.8.2 (sobre 12.8.1 com Festival ativo no piloto).  
**Ativação:** somente após GO explícito, via `PUT /v1/raids/auto/schedule` com `distribution=uniform`.

---

## 1. Diagnóstico da lógica antiga (12.7.2 / 12.8.1)

| Item | Comportamento |
|------|----------------|
| Avanço | `nextAt = due + intervalMs` (1h) |
| Cap | `dailyLimit=12` conta spawns reais; ao atingir → `skip-daily-cap` → 08:00 BRT do dia seguinte |
| Janela | Viés dia/noite `[08,22)` só na escolha de categoria — **não** impedia spawn fora dela |
| Efeito observado (piloto 02/11) | 12 raids 02h–13h BRT → silêncio o resto do dia |
| `lastSpawnId` | Atualizado também em skips (parecia raid aberta) |
| Lembretes | Podiam anunciar ovo para slot que depois seria skip-cap |

Scheduler e Festival estavam corretos e **independentes**; o problema era a política de distribuição.

---

## 2. Nova política (`distribution=uniform`)

1. **Timezone explícito** na agenda (`timezone`, default `America/Sao_Paulo`) — nunca TZ do servidor.
2. **Janela** `[windowStart, windowEnd)` — default produto **08–23**.
3. **Slots diários** (determinísticos):

```
spanMs = (windowEnd - windowStart) * 1h
slot[i] = windowStart(day) + i * spanMs / dailyLimit
  i = 0 .. dailyLimit-1
```

4. **Sem catch-up:** slot atrasado > 10 min → `skip-missed` → próximo slot futuro.
5. **Sem compressão:** avanço = próximo slot, não `due+1h` em rajada.
6. **Fora da janela:** `skip-outside-window` (não consome orçamento de spawns).
7. **Cap:** após `dailyLimit` spawns no dia civil → próximo dia no 1º slot.
8. **Default de código:** `distribution=interval` (legado) — produção **não muda** até PUT controlado.

---

## 3. Tabela dos 12 horários (08–23 / limit=12)

| # | Horário BRT |
|---|-------------|
| 1 | 08:00 |
| 2 | 09:15 |
| 3 | 10:30 |
| 4 | 11:45 |
| 5 | 13:00 |
| 6 | 14:15 |
| 7 | 15:30 |
| 8 | 16:45 |
| 9 | 18:00 |
| 10 | 19:15 |
| 11 | 20:30 |
| 12 | 21:45 |

Derivados da fórmula (não hardcoded). Com outros `dailyLimit` / janela, a grade recalcula.

---

## 4. Política das 2 reservas especiais

- `specialReserve=2` = **teto** de especiais (lendário/mítico) **por dia civil**.
- Cada especial **ocupa um dos 12 slots** (não cria 13ª raid).
- Preferência por especial:
  - nos **últimos N índices** do dia (`N=specialReserve`), se elegível; ou
  - overdue / anti-starvation (como antes); ou
  - fora da janela no modo `interval` legado.
- Se especial inelegível / reserva esgotada → **fallback para comum** (`raro`/`épico`) com `fallback-reason` registrado.
- Reserva **não utilizada não bloqueia** comuns.

Exemplos:
- 10 comuns + 2 especiais due nos slots 11–12 → especiais.
- Já houve 2 especiais → demais slots só comum, mesmo com lendário due.
- Lendário planejado em cooldown → fallback `category-cooldown` → raro/épico.

---

## 5. Seleção de raridade

1. `pick-categoria` / `resolve-categoria` consideram cooldown por categoria + reserva.
2. Preferência dia: épico → raro; especiais só sob regras da §4.
3. Se categoria forçada/planejada inelegível → fallback comum **sem** inflar especiais.
4. Pesos 40/30/20/10 do catálogo dinâmico permanecem na seleção de **espécie** via `force-category-weights` da categoria resolvida.
5. Anti-repetição de espécies e ginásios inalterados.

---

## 6. `lastSpawnId` e receipts

| Ação | Receipt kind | `lastSpawnId` | `lastRaidId` |
|------|--------------|---------------|--------------|
| spawn | `raid-auto-spawn` | atualiza | atualiza |
| skip-busy | `raid-auto-skip` | **preserva** | preserva |
| skip-daily-cap | `raid-auto-skip-cap` | **preserva** | preserva |
| skip-outside-window | `raid-auto-skip-outside-window` | **preserva** | preserva |
| skip-missed | `raid-auto-skip-missed` | **preserva** | preserva |

Histórico legado em produção **não é reescrito**.

---

## 7. Lembretes fantasmas

Antes de T−30/15/5:

- categoria especial (não basta `nextReminders=true` em raro);
- `enabled`;
- `spawns-today < dailyLimit`;
- em `uniform`: due dentro da janela **e** due é um slot válido.

Lembretes continuam só para lendário/mítico; sem spoiler de chefe.

---

## 8. Simulação 7 dias (RNG fixo, relógio controlado)

Parâmetros: `uniform`, 08–23, limit=12, reserve=2, start=2026-11-03 08:00 BRT.

| Métrica | Valor |
|---------|-------|
| total spawns | 84 |
| por dia | 12 × 7 |
| max/dia | 12 |
| por hora BRT | 7 em cada slot hour (8,9,10,11,13,…,21) |
| categorias | raro 42, épico 28, lendário 7, mítico 7 |
| skips | nenhum no cenário ideal (lobby livre) |

---

## 9. Testes e regressões

```bash
clojure -M:test-raid-auto-cadence-fase1282
```

**Resultado local:** `{:ok true, :test 60, :pass 335, :fail 0, :error 0}`

Inclui:
- 12.8.2 (slots, janela, missed, cap, reserva, lembretes, lastSpawnId, sim 7d)
- regressão 12.7.2 / 12.7.1 / 12.7.3 lobby+intent

Não executado neste pacote (sem GO / fora do escopo imediato): captura 12.6 live, economia A6 completa, Node/WA E2E, Festival live.

---

## 10. Arquivos modificados

| Arquivo | Mudança |
|---------|---------|
| `modules/pokemon/domain/.../raid_auto.clj` | slots, janela, plan-spawn, reminders, sim, diagnostics |
| `modules/pokemon/infrastructure/.../raid_store.clj` | cadence fields; lastSpawnId só em spawn; novos skip kinds |
| `modules/pokemon/infrastructure/.../store.clj` | load/enrich cadence; reminders com day-stats; logs |
| `modules/pokemon/application/.../raid_auto.clj` | upsert window/distribution; `schedule-view` |
| `modules/pokemon/infrastructure/.../http/bridge.clj` | GET diagnostics; PUT novos campos |
| `modules/pokemon/tests/.../raid_auto_cadence_fase1282_test.clj` | novos testes |
| `apps/odisseu/src/odisseu/smoke/raid_auto_cadence_fase1282.clj` | runner |
| `deps.edn` | alias `:test-raid-auto-cadence-fase1282` |
| `docs/pokemon-a6-fase1282-ready-for-go.md` | este doc |

---

## 11. Migrations

**Nenhuma.** Cadência extra vai no receipt `raid-auto-cadence:*` (já existente). Schema Datomic de schedule inalterado.

---

## 12. Compatibilidade com scheduler `rev=18` (piloto)

- Agenda atual permanece em `distribution=interval` até PUT.
- Festival / thematic tick **não** lê esses campos.
- Após ativação uniform: próximo `nextAt` deve ser alinhado a um slot (recomendado: 1º slot futuro ≥ agora).
- `safeCadence=true` continua impedindo antecipação de `nextAt` e drop de especial planejado.

---

## 13. Plano de ativação controlada (após GO)

1. Backup Datomic + export GET schedule piloto.
2. Deploy imagem 12.8.2 **sem** alterar schedule (default interval → comportamento idêntico).
3. Gates: health, Festival ainda ativo, raid-auto tick ON, schedule rev estável.
4. PUT piloto:

```json
{
  "chatId": "120363427684548487@g.us",
  "enabled": true,
  "distribution": "uniform",
  "windowStart": 8,
  "windowEnd": 23,
  "timezone": "America/Sao_Paulo",
  "dailyLimit": 12,
  "specialReserve": 2,
  "safeCadence": true,
  "nextAt": <próximo slot futuro BRT>
}
```

5. Validar GET `diagnostics` (windowOpen, slotsToday, slotsRemaining).
6. Observar 2–3 slots sem forçar spawn manual.
7. Demais grupos: permanecer `enabled=false` / sem cadence uniform.

---

## 14. Rollback

1. PUT `distribution=interval` (+ windowEnd 22 se desejado) com `safeCadence=true`.
2. Ou redeploy imagem 12.8.1 (código legado).
3. Receipts de skip novos são ignoráveis pelo código antigo (kinds desconhecidos ≠ spawn).

---

## 15. Riscos residuais

| Risco | Mitigação |
|-------|-----------|
| Retarget operacional para horário fora da grade | `skip-outside-window` / realinhar `nextAt` |
| Lobby mítico 30m vs passo 75m | `advance-next-at` empurra para slot após fim do lobby |
| Contagem `spawns-today` vs oportunidades skip | Cap usa spawns **reais**; skips não inflam o dia — slots avançam mesmo assim |
| Lembrete já entregue + cancel posterior | suppress stale já existente; sem novo anúncio |
| Ativação acidental | default `interval`; exige PUT explícito |

---

## 16. READY FOR GO — checklist

- [x] Política uniforme implementada atrás de flag
- [x] Janela 08–23 / TZ explícito
- [x] `lastSpawnId` só em spawn real
- [x] Lembretes anti-fantasma
- [x] Simulação 7 dias OK
- [x] Suite 12.8.2 + regressão 12.7.x verde (335 pass)
- [x] Sem migration
- [x] Sem alteração em produção nesta entrega
- [ ] **GO do responsável** para build/deploy
- [ ] **GO** para PUT `distribution=uniform` no piloto
- [ ] Validação pós-ativação (2–3 slots)

**Aguardando GO explícito para qualquer deploy ou retarget.**
