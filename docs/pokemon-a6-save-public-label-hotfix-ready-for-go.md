# A6 — READY FOR GO · Hotfix saves (criação + nome público)

**Decisão:** READY FOR GO · **sem deploy / sem migration / sem backfill / sem alteração em produção**  
**Escopo:** `!pk novo save`, renomeação e listagem por identificador público  
**Base:** produção 12.8.2 (imagens/`distribution=uniform` / Festival **fora** deste hotfix)

---

## 1. Causas raiz

### `!pk novo save`

O Node gerava `saveId` a partir do `requestId`:

`slot-<requestId-sanitizado>`

Problemas:

1. `requestId` com maiúsculas → falha em `valid-slot-id?` (`^[a-z0-9][a-z0-9_-]{0,63}$`).
2. IDs opacos (`slot-…`) em vez da sequência estável `a`/`b`/`c`…
3. Sem nome público opcional na criação.

### Renomeação

O backend **já persistia** `:save/display-name` (ID interno/`slot-id` imutável).

A UI e a resolução de comandos continuavam centradas no **slot-id**:

- `formatSavesList` destacava `*saveId*` e só anexava o display-name.
- `!pk save` / `!pk renomear` / `!pk ativar` só casavam número ou `saveId`, **não** o nome público.

Resultado: rename “funcionava” no Datomic, mas o jogador seguia vendo/selecionando o identificador antigo.

---

## 2. ID interno vs identificador público

| Conceito | Campo | Mutável? | Uso |
| --- | --- | --- | --- |
| ID interno | `:save/slot-id` (`a`, `b`, `c`, `recovery-v1`, …) | **Não** | Chaves Datomic, fences, writers, raids |
| Nome público | `:save/display-name` | **Sim** | Listagens, seleção e mensagens WhatsApp |

`public-save-label` = `display-name` se presente, senão `slot-id`.

---

## 3. Correção implementada

**JVM / domínio**

- `next-available-slot-id` (a–z, depois `s1`…`s99`).
- `POST /v1/saves/create` aceita `saveId` omitido (alocação server-side).
- Unicidade de nome público por jogador (colide com display-name **ou** slot-id de outro save).
- Nomes reservados (`save`, `novo`, `ativar`, …).
- `activate` e `save-profile` / `getActiveSave` passam a devolver `display-name`.
- Mensagens amigáveis para `INVALID_SLOT_ID` / `SAVE_NAME_TAKEN` / limite.

**Node**

- `!pk novo save [nome]` → lista saves → aloca próximo slot estável (minúsculo) → create.
- Seleção por **nome | número | id interno** (`resolveSaveSelection`).
- `formatSavesList` / ativo / activate priorizam o nome público.
- Não altera o save ativo na criação.

**Sem migration.** Atributo `:save/display-name` já existe (025).

---

## 4. Comandos finais

| Comando | Efeito |
| --- | --- |
| `!pk saves` | Lista nomes públicos (✅ ativo / 📦 recuperado) |
| `!pk save` | Save ativo |
| `!pk save <nome\|n\|id>` | Ativa pelo nome público, índice ou slot |
| `!pk ativar <nome\|n\|id>` | Idem |
| `!pk novo save` | Cria próximo slot; **não** troca o ativo |
| `!pk novo save aventura` | Cria já com nome público |
| `!pk renomear save <alvo> <novo-nome>` | Altera só o display-name |

Compatível: `!pk save a`, `!pk ativar b`, `recovery-v1`.

---

## 5. Exemplos WhatsApp

```
📁 *SEUS SAVES*
✅ `aventura` — ativo _(id a)_
• `b`
📦 `legado` — recuperado _(id recovery-v1)_

Selecionar: !pk save <nome|número|id>
Criar: !pk novo save [nome]
Renomear: !pk renomear save <nome|número|id> <novo-nome>
```

```
✅ Save criado: *aventura*.
Use `!pk save aventura` para selecionar.
(O save ativo não foi alterado.)
```

```
✅ Save renomeado para *aventura*.
Use `!pk save aventura` / `!pk saves`.
```

---

## 6. Testes executados (local, Datomic descartável)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-m2-trainer` | **PASS** — 13 tests / 84 assertions (inclui alocação, unicidade, recovery rename, persistência/fence) |
| `node --test test/pokemon-jvm-present.test.cjs` | **PASS** — 20 tests (label/resolve/next slot/listagem) |

Não houve acesso a produção, Cassandra real, nem alteração de saves reais.

---

## 7. Impacto nos saves existentes

- Slot-ids e fences **intactos**.
- `recovery-v1` permanece o id interno; pode ganhar nome público sem mudar origem.
- Saves sem `display-name` continuam exibindo o slot (`a`, `b`, …).
- Economia, Doces, party, inventory, raids, Festival: **não tocados** por este hotfix.

---

## 8. Migration?

**Não.** Nenhuma migration/backfill necessária.

---

## 9. Rollback

1. Redeploy imagens Node+JVM da revisão anterior ao hotfix.
2. Dados: `display-name` eventualmente gravados **permanecem** (inofensivos para o código antigo, que só os ignora na UI).
3. Não reverter imagens por problema só de texto sem evidência de corrupção de save.

---

## 10. Proteções preservadas (não regressar no deploy)

- Saves `recovery-v1` / IDs internos / fences  
- Economia `unknown-pending` + ledger  
- Doces / mochila diária / migration 032  
- Raid-auto `distribution=uniform` + Festival dos Dragões  
- Gemini/Groq · allowlist piloto  

**Deploy deste hotfix exige GO explícito separado.**
