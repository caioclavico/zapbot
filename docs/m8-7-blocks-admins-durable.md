# M8.7 — Durabilidade de bloqueios e administradores

**Checkpoint base:** `667c8bb` (M8.6)  
**Escopo:** camada reutilizável `durable-mutation` + 6 mutações bloqueio/admins.  
**Fora de escopo:** enquetes, quiz, lembretes, bugs, participantes, produção, cutover.

> **`cutover-ready? = false`** · **`rollback-recon-available? = false`**

---

## 1. Integração reutilizável

`odisseu.cutover.durable-mutation/execute!`:

1. Política (`:optional` / `:required-strict`)
2. prepare journal
3. `datomic-fn` (store real + receipt)
4. journal-durable + fsync
5. HTTP success só se strict ok
6. `reconcile-uncertain!` via `platform.op`
7. `replay-entry!` idempotente

Não contém regras de negócio — wrappers em `blocks-admins-durable` chamam `ports/*`.

---

## 2. Mutações protegidas

| Op | Kind |
|----|------|
| set-bot-blocked! | `:set-bot-blocked` |
| block-commands! | `:block-commands` |
| unblock-commands! | `:unblock-commands` |
| remember-admin! | `:remember-admin` |
| forget-admin! | `:forget-admin` |

Bridge: `:platform-hooks` via `make-hooks`.

---

## 3. Replay e ordem oposta

`replay-journal-after!` ordena por `:seq`. Ensaios comprovam:

- block → unblock ⇒ comando livre
- remember → forget ⇒ admin ausente
- bot true → false ⇒ bot livre
- restore+replay não ressuscita estado revogado

---

## 4. Caches Node

`bloqueio.cljs` / `admins.cljs` só aplicam wire após promise HTTP resolvida. Em `:durability_compromised` (503) o cache **não** atualiza (simulado no drill).

---

## 5. Matriz de cobertura

Ver `blocks-admins-durable/coverage-matrix` e `target/m8-7/report.edn`.

Protegido: coins, rank score, blocks/admins.  
Sem journal: restante Pokémon + enquetes/quiz/lembretes/bugs/participantes + rank penalize/effect.

---

## 6. Gates

`evaluate-m87-gates` → `global-protection-complete? false`, cutover/recon false.

---

## 7. Próximo grupo

Enquetes (`create-poll` / `cast-vote` / `close-poll`) — mutações frequentes com receipts `platform.op`.

---

## Artefatos

| Path | Papel |
|------|-------|
| `durable-mutation` | camada reutilizável |
| `blocks-admins-durable` | wrappers + matriz |
| `blocks-admins-drill` | ensaios |
| `scripts/test-m8-7-blocks-admins.sh` | runner |
