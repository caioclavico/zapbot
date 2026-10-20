# M9.6 — Onboarding completo do Pokémon

**Branch:** `refactor/modular-monolith-datomic`  
**Escopo:** menu `!pk`, `!pk iniciar` (kit + starters), `!pk inicial <n|nome>`  
**Ambiente alvo:** `odisseu-core` (atualização local pronta; **sem commit/push/deploy neste marco**)

---

## 1. Regras originais (legado)

| Tema | Fonte | Regra |
|------|--------|--------|
| Kit inicial | `loja/resgatar-bolas!` com `kit?` | **10× `pokebola`**, flag `kit-inicial-resgatado` na conta |
| Starters | `core/iniciais` + `escolher-inicial` | Bulbasaur / Charmander / Squirtle via `!pk inicial <1-3>` |
| Disponibilidade | `treinador/inicial-disponivel?` | Sem flag **e** sem histórico (equipe/PC/enfermaria/pokédex/…) |
| Stats | captura/suavizar legado | Stats base Gen1 suavizados; **sem IVs**; nível 1; amizade 70 |
| Menu vazio | `core` sem args | Entrar/iniciar batalha **ou** orientação — no JVM vira menu de onboarding/jogo |

**Diferença modelada A/B:** no legado o kit é por conta (sem A/B). No Datomic o inventário é por save; a flag `kit-inicial-resgatado` é gravada em **A e B** na mesma TX para impedir kit extra ao trocar save. O starter continua **por save** (playthrough independente), bloqueado se `collection-count > 0` (migrados).

---

## 2. Kit inicial (quantidades)

- Item: `pokebola`
- Quantidade: **10**
- Idempotência: recibo `operation-receipt` + flag em ambos os slots + CAS de `save/revision`

---

## 3. Contratos de comando / mensagem

| Comando | Comportamento |
|---------|----------------|
| `!pk` / `!pokemon` | Menu: boas-vindas se `not_started`; retoma starters se `awaiting_starter`; menu de cmds JVM se `completed` |
| `!pk iniciar` | `POST /v1/onboarding/start` — ensure + kit (se disponível) + texto dos 3 iniciais |
| `!pk inicial` | Lista starters (ou “já escolhido”) |
| `!pk inicial 1\|2\|3\|nome` | `POST /v1/onboarding/starter` |

Mensagens: sem “JVM”, “Cassandra”, `POKEMON_ADAPTER`, “não integrado”.

---

## 4. Estado de onboarding

```
not_started → awaiting_starter → completed
```

Derivado de:

- `kit-inicial-resgatado` (qualquer save A/B)
- `inicial-escolhido` (save ativo)
- `collection-count` (save ativo) — se `> 0`, trata como `completed` (migrado)

Schema: `migrations/pokemon/013-onboarding.edn` (`:save/kit-inicial-resgatado`, `:save/inicial-escolhido`).

---

## 5. Transações e idempotência

| Operação | Receipt kind | Proteções |
|----------|--------------|-----------|
| Kit | `onboard-kit` | Flag A+B, revision CAS, receipt + payload-hash, inflight `with-operation!` |
| Starter | `onboard-starter` | Flag `inicial-escolhido`, ativo-index 0, coleção+pokédex na mesma TX; se já indisponível → duplicate sem novo Pokémon; kit pendente pode ir na mesma TX |

**Garantias demonstradas nos testes:** mesma `operationId` → duplicate; concorrência de 3 escolhas → 1 Pokémon; reopen store → estado intacto; troca A→B → sem segundo kit.

**Não declarado:** exactly-once entre Node e JVM após timeout de rede sem reconciliação pelo cliente (`!pk recibo` / retry com mesmo `operationId`).

---

## 6. Arquivos alterados

**JVM / Datomic**

- `migrations/pokemon/013-onboarding.edn`
- `modules/pokemon/domain/pokemon/domain/onboarding.clj`
- `modules/pokemon/application/pokemon/application/onboarding.clj`
- `modules/pokemon/application/pokemon/application/ports.clj`
- `modules/pokemon/application/pokemon/application/bridge_tx.clj`
- `modules/pokemon/infrastructure/.../onboarding_store.clj`
- `modules/pokemon/infrastructure/.../store.clj` (ports + `->save` flags; WIP ginásio preservado)
- `modules/pokemon/infrastructure/.../schema.clj`
- `modules/pokemon/infrastructure/.../http/bridge.clj`
- `modules/pokemon/tests/.../onboarding_m96_test.clj`
- `apps/odisseu/src/odisseu/smoke/m9_6_onboarding.clj`
- `deps.edn` (`:test-m9-6-onboarding`)
- `scripts/test-m9-6-onboarding.sh`

**Node**

- `scripts/lib/pokemon-jvm-client.cjs`
- `scripts/lib/pokemon-jvm-present.cjs`
- `src/zapbot/pokemon_http.cljs`
- `test/pokemon-jvm-present.test.cjs`

**Não tocados (restrição):** `odisseu-zapbot`, `odisseu-pokemon`, Cassandra Oracle, volumes Datomic de produção, sessão WhatsApp, `cutover-ready?` / `rollback-recon-available?`.

---

## 7. Testes executados

```bash
./scripts/test-m9-6-onboarding.sh
```

Resultado local:

- Unit Clojure `pokemon.tests.onboarding-m96-test`: **8 tests / 55 assertions — OK**
- Smoke HTTP bridge onboarding: **OK**
- Node `pokemon-jvm-present.test.cjs`: **6 tests — OK**

Cobertura: menu/aliases (Node), kit 10, repeat start, race starters, save A/B, reopen, migrado com coleção, Bearer routes.

---

## 8. Exemplos de mensagens (WhatsApp)

**Novo (`!pk`):**
```
🌟 Pokémon RPG
Bem-vindo, treinador! Sua jornada ainda não começou.

Para criar seu perfil e receber o kit inicial:
👉 !pk iniciar
```

**Após `!pk iniciar`:**
```
✅ Treinador criado! Bem-vindo à jornada.
🎁 Você recebeu *10 Pokébolas* (kit inicial).

🌟 Escolha seu Pokémon inicial:
1. 🌱 Bulbasaur (grass/poison)
…
Use !pk inicial <número> ou !pk inicial <nome>.
```

**Após `!pk inicial 2`:**
```
🔥 Você escolheu *Charmander* como seu inicial!
A aventura começou. Próximos passos:
• !pk treinador · !pk meus · !pk cacar · !pk mochila
```

---

## 9. Riscos / ainda não portado

- PvP, doação, evolução por pedra, missões/presentes, títulos, pokedex filtrada avançada, times prontos, etc. (mensagem amigável via `formatUnavailable`).
- Cartão visual dos 3 starters: ver **M9.6.1** (`docs/m9-6-1-starters-card.md`) — uma imagem com Bulbasaur/Charmander/Squirtle.
- Starter em save B vazio ainda é permitido (playthrough B); **kit** não.

---

## 10. Procedimento seguro em `odisseu-core`

1. Build/imagem JVM + bot com este branch (lab ou PR), **sem** apagar volumes Datomic.
2. Subir JVM; confirmar `GET /health` com capabilities `onboarding-*`.
3. Confirmar `POKEMON_ADAPTER=jvm`, `PLATFORM_ADAPTER=datomic`, URL `http://odisseu-jvm:18090` + `POKEMON_JVM_ALLOW_DOCKER_HOST=true` se Compose.
4. Smoke interno: `!pk` → `!pk iniciar` → `!pk inicial 1` em chat de teste.
5. Reiniciar só o container JVM e repetir `!pk treinador` / `!pk mochila` (10 bolas + 1 Pokémon).
6. **Não** cutover; manter `cutover-ready? = false` e `rollback-recon-available? = false`.

---

*Marco implementado e testado localmente. Parar antes de commit, push ou deploy remoto.*
