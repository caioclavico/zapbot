# Preparação — `!pk sair` / battle-leave (sem GO de deploy)

**Quando:** 2026-10-29  
**Status:** patch local + testes verdes · **produção NÃO alterada**  
**Host prod (RO):** `odisseu-core` · imagens `a64-party-fix-20261029T1825-amd64`

---

## 1. Causa raiz (comprovada)

| Camada | Achado |
|--------|--------|
| Node | `!pk sair` → `POST /v1/battles/leave` via `leaveBattle` · opId `battle-leave` |
| Payload | `{ chatId, playerId, saveId, fenceGeneration, operationId }` |
| JVM | Rota **registrada** em `bridge.clj`, mas gated |
| Flag live | `POKEMON_BATTLE_LEAVE_ENABLED=false` |
| Log Node | `NOT_FOUND 404 Rota inexistente.` · etapa `pokemon_jvm_battle_leave` |
| Probe RO | `POST /v1/battles/leave` → **404** `not_found` (idêntico a rota realmente ausente) |
| Caps health | **sem** `battle-leave` (flag off) |
| Domínio | `leave-battle!` + `battle-leave-store` **já implementados** na imagem atual |

**Conclusão:** não é “batalha inexistente” nem contrato Node/JVM divergente. A JVM **mascara** o endpoint como 404 até a flag ser `true`.

---

## 2. Rota real esperada

```
POST /v1/battles/leave
Authorization: Bearer <POKEMON_JVM_TOKEN>
Content-Type: application/json

{
  "chatId": "<chat>",
  "playerId": "<player>",
  "saveId": "<active-save>",
  "fenceGeneration": <long>,
  "operationId": "<stable battle-leave id>"
}
```

Respostas (com flag on):

| Caso | HTTP | Corpo |
|------|------|-------|
| Saída ok | 200 | `{ status: "left", modality, … }` |
| Sem batalha | 200 | `{ status: "no_battle" }` |
| Já encerrada | 200 | `{ status: "already_finished" }` |
| Idempotente | 200 | `{ duplicate: true, status: "left" }` |
| Flag off (patch) | 503 | `{ code: "feature_disabled" }` — **não** mais 404 |
| Rota realmente ausente | 404 | `{ code: "not_found" }` |

**Não** usar `/v1/battles/expire-idle` com `force=true` (não exposto via HTTP).

---

## 3. Diff preparado (local)

| Arquivo | Mudança |
|---------|---------|
| `docker-compose.release-battle-idle.yml` | `POKEMON_BATTLE_LEAVE_ENABLED: "true"` |
| `modules/.../http/bridge.clj` | flag off → **503 `feature_disabled`**; health cap `battle-leave` quando on |
| `scripts/lib/pokemon-jvm-present.cjs` | mensagens `FEATURE_DISABLED` / `NOT_FOUND` |
| `battle_leave_test.clj` | +aguardando-captura · +recovery-v1 slot · leave com `save-id` |
| `test/pokemon-jvm-present.test.cjs` | `formatLeaveResult` + erros |

Node handler (`pokemon_http.cljs` / client) **já correto** — sem mudança obrigatória.

---

## 4. Testes locais

| Suite | Resultado |
|-------|-----------|
| `pokemon.tests.battle-leave-test` | **11 / 77 OK** |
| present Node | **16 / 16 OK** |

Cobertura: selvagem, aguardando captura, ginásio, raid individual, PvP, sem batalha, idempotência (2×), concorrência ataque/idle 300s, restart JVM, preservação slot `a` vs `recovery-v1`.

---

## 5. Flag vs rebuild

| Opção | O que faz | Rebuild? |
|-------|-----------|----------|
| **A — mínimo** | Só `POKEMON_BATTLE_LEAVE_ENABLED=true` + recreate JVM | **Não** (código leave já na imagem `a64-party-fix`) |
| **B — patch completo** | A + bridge (503/capability) + present | **Sim** JVM (+ Node se quiser present) |

Recomendação: **B** no próximo GO (mensagem clara se a flag voltar a off; cap no health).

---

## 6. Plano de implantação (aguardando GO)

1. Confirmar testes locais ainda verdes.
2. Cold backup leve opcional (leave não migra schema; volumes `a56-*` intactos).
3. Rsync `bridge.clj` (+ present se B) · build tag `a64-battle-leave-…-amd64`.
4. Atualizar compose: imagem + `POKEMON_BATTLE_LEAVE_ENABLED=true`.
5. `up -d odisseu-jvm` (e Node se B) · **sem** `-v` · **sem** migrations/import.
6. Gate: `POST /v1/battles/leave` sem batalha → 200 `no_battle` (não 404).
7. Health: capability `battle-leave` presente.
8. Smoke: `!pk sair` sem batalha → mensagem amigável; com batalha ativa → abandono.
9. Cooldowns inalterados (1800/1800/300); 13/158 intactos.

### Rollback

```bash
# Voltar flag false (e imagem anterior se rebuild)
POKEMON_BATTLE_LEAVE_ENABLED=false
# ou compose bak pré-leave
```

Não fazer rollback destrutivo automático.

---

## 7. Restrições respeitadas nesta etapa

- Sem deploy / migrations / Cassandra / mutação de saves/economia/grupos/cooldowns em produção.
