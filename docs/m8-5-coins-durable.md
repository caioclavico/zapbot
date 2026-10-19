# M8.5 — Integração real do durable-op com coins-delta

**Checkpoint base:** `8999505` (M8.4)  
**Escopo:** wiring de `coins-delta` no store Datomic Local descartável + reconciliação + bridge hook.  
**Fora de escopo:** demais mutações, produção, WhatsApp, domínio de falha independente operacional.

> **`cutover-ready? = false`**  
> **`rollback-recon-available? = false`**  
> **`global-protection-complete? = false`**  
> **Exactly-once / RPO zero: não declarados**

---

## 1. Caminho real integrado

```
Node → POST /v1/rewards/coins (bridge)
     → credit-coins! hook (opcional: coins-durable)
     → prepare (journal :prepared + fsync)
     → bridge-tx/credit-coins!
          → reward/apply-gameplay-reward!
          → store apply-gameplay-reward!* (fence CAS + coins CAS + operation-receipt)
     → journal :journal-durable + fsync
     → HTTP 200 só se strict satisfeito
```

Sem journal configurado, a bridge usa `tx/credit-coins!` direto (compat).

Identidade: `chat-id`, `player-id`, `save-id`, `fence-generation`, `operation-id`.  
Payload canônico: `reward/reward-payload` → `{:coins-delta N}` (hash alinhado ao receipt).

---

## 2. Estados e transições observados

| Transição | Lab |
|-----------|-----|
| prepare → datomic → journal-durable → ok | caminho feliz |
| retry mesmo op-id | `:duplicate`, moedas estáveis |
| mesmo op-id / payload diferente | rejeitado (journal ou receipt) |
| fence obsoleto | `:stale_writer_fence` após prepare; abort |
| Datomic OK, journal falha | `:durability_compromised` (503) + manutenção; moedas no SoT |
| reconcile pós-falha | receipt → `:journal-durable` **sem** reaplicar moedas |
| pós-journal, pré-HTTP | retry → duplicate |
| manutenção ativa | novas escritas `:durability_maintenance` |

---

## 3. Testes e resultados

`./scripts/test-m8-5-coins-durable.sh` — 15 cenários, 15 asserções smoke, 0 falhas.

Cobre: normal, retry, mismatch, fence, concorrência, crashes prepare/datomic/journal/HTTP, reopen JVM, volume loss+replay, corrupção, manutenção, saves A/B, bridge HTTP.

---

## 4. Evidência de não duplicação

Retry / reopen / replay pós-restore: receipts Datomic impedem segundo crédito. Contagens de moedas estáveis nos cenários `:retry-same-op`, `:jvm-reopen`, `:volume-loss-replay` (segundo replay all-duplicate).

---

## 5. Reconciliação após falha pós-commit

`coins/reconcile-uncertain!` consulta `ports/get-receipt`, valida player/save/payload-hash; se match → append `:journal-durable` com `coins-reapplied? = false`. Sem receipt → não confirma. Evidência insuficiente → mantém manutenção.

---

## 6. Reconstrução após perda do volume

Backup pré-op → op pós-backup journalizada → wipe → restore backup → replay só entradas `:journal-durable` + `datomic-confirmed?` via `apply-gameplay-reward-on-active!`. Moedas restauradas ao valor live; sem depender de memória do processo original.

---

## 7. Operações ainda descobertas

Captura, HP, XP, evolução, batalhas, save-switch, Joy, loja; platform (rank, bloqueios, admins, enquetes, quiz, lembretes, bugs, participantes).

---

## 8. Gates (separados)

| Gate | Estado |
|------|--------|
| durable-op sintético (M8.4) | ok (pré-requisito) |
| coins-delta store real | ok |
| recuperação coins-delta | ok |
| bridge HTTP integrada (hook) | ok |
| demais mutações | **não** |
| journal domínio de falha independente | **não** |
| rollback-recon-available? | **false** |
| cutover-ready? | **false** |

---

## 9. Próxima menor integração

Wiring de **uma** mutação platform frequente (ex. `score-point` / rank) no mesmo protocolo, ainda sintético — ou configurar journal em volume distinto no lab de staging (sem cutover).

---

## Artefatos

| Path | Papel |
|------|-------|
| `odisseu.cutover.coins-durable` | Wrapper + reconcile + replay |
| `odisseu.cutover.coins-durable-drill` | 15 ensaios |
| `pokemon.infrastructure.http.bridge` | Hook `:credit-coins!` + 503 durability |
| `scripts/test-m8-5-coins-durable.sh` | Runner |
| `target/m8-5/report.edn` | Relatório |
