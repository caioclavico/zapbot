# Correção — INTERNAL_ERROR em `!pk caçar`

**Branch:** `refactor/modular-monolith-datomic`  
**Sintoma (odisseu-core):** `⚠️ Erro interno na bridge. (INTERNAL_ERROR)`  
**JVM:** `Cannot invoke "java.lang.Number.doubleValue()" because "x" is null`

---

## Causa raiz (confirmada)

Em `store.clj` / `start-hunt!`:

```clojure
(-> selvagem
    hunt/com-raridade
    (hunt/sortear-shiny (:shiny-roll request)))
```

Produção (Node) **não envia** `shinyRoll`. O macro `->` chama a aridade 2:

`(hunt/sortear-shiny selvagem nil)` → `(long nil)` → NPE com mensagem `doubleValue()` (Clojure 1.12 / JVM atual).

Os testes M4.5 sempre passavam `:shiny-roll 1`, por isso o smoke não reproduzia.

`taxa-captura` nula após falha de `pokemon-species` **não** era a causa deste NPE (`classificar-raridade` já tratava `nil`). Ainda assim o Node passou a **omitir** a chave em vez de serializar `null`.

---

## Correção

1. **JVM** — só passa `shiny-roll` quando `some?`; senão chama `(sortear-shiny base)` (RNG local).
2. **Domínio** — `sortear-shiny` trata `nil` como RNG (`(or sorteio (rand-int …))`).
3. **Validação** — candidato exige stats numéricos (hp/ataque/…); `taxa-captura` permanece opcional.
4. **Node** — `finalizeWild` não emite `taxa-captura`/`flags` nulos.

Sem mudança de regras de caça, cooldown, fence, recibos ou Datomic schema.

---

## Arquivos

- `modules/pokemon/domain/pokemon/domain/hunt.clj`
- `modules/pokemon/infrastructure/.../datomic/store.clj` (só ramo shiny; Gym WIP restante intocado)
- `modules/pokemon/tests/pokemon/tests/hunt_m45_test.clj`
- `scripts/lib/pokemon-pokeapi.cjs`
- `test/pokemon-jvm-hunt.test.cjs`
- este relatório

---

## Testes (local)

```bash
./scripts/test-m4-hunt.sh
```

Resultado:

- `pokemon.tests.hunt-m45-test`: **9 tests / 37 asserts — OK** (incl. start sem shiny-roll / sem taxa-captura)
- Encounter M4.4 + smoke HTTP M4.5 — OK
- Bridge M4.1/M4.2 smoke — OK
- Node unit (33) — OK
- Contratos Node↔JVM M4.1/M4.2 — OK (M4.2 após limpar `.datomic-local/m42-contract` residual)

Assertions Node atualizadas para mensagens M9.6 (`não está disponível` / sem Cassandra no texto).

---

## Implantação segura (odisseu-core)

1. Rebuild/restart **só** do container JVM (e bot Node se incluir `pokemon-pokeapi.cjs`).
2. Não limpar volumes Datomic nem sessão WhatsApp.
3. Validar com treinador que já tem Pokémon: `!pk caçar` (sem repetir `operationId` pendente).
4. Se houver caçada travada, usar fluxo já existente de status/recibo — não reenviar mutação com id novo às cegas.
5. `cutover-ready?=false` inalterado.

*Sem commit/push automático neste marco.*
