# M8.6 — Integração durable-op ao Rank (score-point)

**Checkpoint base:** `9aea1ec` (M8.5)  
**Escopo:** `score-point!` no Platform Datomic + journal + política `:required-strict`.  
**Fora de escopo:** penalize/effect, demais platform, produção, cutover.

> **`cutover-ready? = false`**  
> **`rollback-recon-available? = false`**  
> **`global-protection-complete? = false`**

---

## 1. Integração RankStore / bridge

```
Node → POST /v1/platform/rank/score
     → handlers (hook :score-point!)
     → rank-durable/score-point!
          prepare → ports/score-point! (platform.op + pontos) → journal-durable
     → HTTP 200 só se strict ok
```

- Receipts: `:platform.op/*` domain `"rank"`, hash `score|chat|player|game|name` (M7.4).
- Hook opcional em `bridge/start!` `:score-point!`; sem hook = legado.
- Journals Pokémon e Platform **separados** (mesma string de op-id não colide).

---

## 2. Confirmação e reconciliação

Política (`durability-policy`):

| Modo | Sem journal | Com journal |
|------|-------------|-------------|
| `:optional` (default) | pass-through legado | prepare→commit→journal→HTTP |
| `:required-strict` | **bloqueia** (`:durability_journal_required`) | fail-closed + manutenção |

`reconcile-uncertain!`: consulta `get-op-receipt`; se domain/hash batem → `:journal-durable` **sem** reaplicar pontos.

---

## 3. Testes

`./scripts/test-m8-6-rank-durable.sh` — 17 cenários (normal, retry, 2 eventos, mismatch, concorrência, chats/jogos, crashes, journal indisponível, HTTP lost, reconcile, volume+replay, reopen, replay repetido, required ausente, isolamento Pokémon/Platform, bridge HTTP).

---

## 4. Garantias demonstradas

- Sem HTTP success se journal falhar após commit (`:strict`).
- Retry / reopen / replay → duplicate via `platform.op`.
- Required-strict sem journal → nenhuma mutação.
- IDs iguais em DBs/journals distintos → sem colisão.

**Não alegado:** RPO zero, exactly-once cross-store, proteção global.

---

## 5. Regressões

M7.4, M8.3–M8.5, M3.2 reward — a executar no runner de entrega.

---

## 6. Cobertura efetiva

| Domínio | Status |
|---------|--------|
| coins-delta | protegido (M8.5) |
| rank score-point | protegido (M8.6) |
| rank penalize/effect | **não** |
| bloqueio, admins, enquetes, quiz, lembretes, bugs, participantes | **não** |
| captura, HP, XP, batalha, Joy, loja, … | **não** |

---

## 7. Infra pendente

Journal em domínio de falha distinto do volume Platform; deploy operacional; `:required-strict` só em staging/lab até infra pronta.

---

## 8. Próxima família

`block-commands!` / `remember-admin!` (bloqueio+admins) — alto uso operacional, mesmo padrão de `platform.op`.

---

## Artefatos

| Path | Papel |
|------|-------|
| `odisseu.cutover.durability-policy` | optional / required-strict |
| `odisseu.cutover.rank-durable` | wrapper + reconcile + replay |
| `odisseu.cutover.rank-durable-drill` | 17 ensaios |
| `scripts/test-m8-6-rank-durable.sh` | runner |
