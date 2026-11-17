# Auditoria — `datomic_value_too_large` no Ginásio Pedra (Odisseu A6)

**Quando:** 2026-10-29 / atualizado 2026-10-30  
**Status:** slim1 insuficiente (6556) → **slim2 (gzip) deployado** → `docs/pokemon-a6-4-gym-slim2-deploy-checkpoint.md`  
**Conta:** `recovery-v1` · fence 2 · coleção atual 302 (pós remaining)

---

## 1. Diagnóstico produção (RO)

| Campo | Valor |
|-------|--------|
| Comando | desafio Ginásio Pedra |
| Node | `pokemon_jvm_gym_challenge` → falha |
| HTTP | `POST /v1/gyms/challenge` |
| Erro | `DATOMIC_VALUE_TOO_LARGE` 500 |
| Payload | `{"attr":"stateEdn","bytes":14503,"limit":4000}` |
| Atributo | **`:battle/state-edn`** (confirmado; não hipótese) |
| Limite | 4000 chars (Datomic Local / guard JVM) |
| TX | **abortada** — `activeBattleId=null`, encounter `nil` |
| Efeitos parciais | nenhum (HP/cooldown/ocupação/receipt de challenge) |
| Receipt | não persistido (encode falha antes do `d/transact`) |

Logs (~21:52–21:54Z): duas tentativas com **14503** bytes no stateEdn.

---

## 2. Caça × ginásio

| | Caça (`start-hunt!`) | Ginásio (antes do patch) |
|--|---------------------|---------------------------|
| Encode | `encode-state-edn` | idem |
| Slim | só `:pokemons :x/:o` | **igual** — insuficiente |
| Time | 1×1 | **3×3** em `:reservas` com `historico-golpes` V1 |
| Resultado | OK após slim M4.5 | **14503 > 4000** no challenge start |

Causa raiz: o slim da caça **não** percorria `:reservas`. No Pedra, 2 ativos + 4 reservas carregavam histórico/golpes/descrições → ~14 KiB.

Segunda superfície (vitória): `:gym-occupation/team-edn` gravava o time completo da coleção (com histórico) — estoura no resolve pós-KO.

---

## 3. Correção (mínima)

Arquivo principal: `modules/pokemon/domain/pokemon/domain/battle.clj`

1. `slim-battle-state` — slim ativos **e** reservas (estrutura `{:collection-index :pokemon}` ou NPC cru).
2. Golpes: só campos de combate (`golpe-normalizado` / select-keys); drop `descricao`/`efeitos`.
3. Ginásio: sem sprites no EDN (ativos e reservas); caça mantém imagem* nos ativos.
4. `encode-gym-team-edn` — mesma política para ocupação.
5. `gym_store.clj` — `team-edn` via `encode-gym-team-edn`.
6. `datomic_value_too_large` permanece explícito se ainda exceder.

**Não** altera registros canônicos `:pokemon/historico-golpes-edn`, Cassandra, economia, cooldowns, fence/CAS.

---

## 4. Testes

| Suite | Resultado |
|-------|-----------|
| `test-m4-gym-battle` (M4.8 + regressões + smoke) | **OK** · 12 testes / 72 asserts (incl. Pedra gordo) |
| Novo `gym-state-edn-slim-reservas-evita-item-too-large` | raw ≫ 4000 → encode ≤ 4000; start recovery-v1 fence≥2; turnos+vitória; team-edn slim; abort sem parcial |
| `test-m4-hunt` | regressão slim caça (rodar no CI local) |

Instrumentação: asserts de `count` em state-edn / team-edn.

---

## 5. Plano de deploy (aguardando GO)

1. Reconfirmar `clojure -M:test-m4-gym-battle` e `clojure -M:test-m4-hunt`.  
2. Cold backup `a56-*` + restore-check.  
3. Rebuild **somente JVM** (Node inalterado se present não muda).  
4. Tag `a64-gym-state-edn-…`; compose sem `-v`; sem migrations.  
5. Smoke RO: health; 119/158; `activeBattleId` null.  
6. Smoke funcional: desafio Pedra em save descartável **ou** GO explícito do usuário na conta real.  
7. Rollback: imagem JVM anterior + compose bak.

**Não** executar deploy nem desafio artificial em produção sem autorização.
