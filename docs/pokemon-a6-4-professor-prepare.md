# Preparação — `!pk professor` (enviar/confirmar/cancelar) · sem GO

**Quando:** 2026-10-29  
**Status:** patch local + testes verdes · **GO deploy concluído** → ver `docs/pokemon-a6-4-professor-deploy-checkpoint.md`  
**Restrição:** nenhum Pokémon de `recovery-v1` foi transferido/removido no deploy (confirmação real aguarda teste WhatsApp).

---

## 1. Causa raiz

| Camada | Achado |
|--------|--------|
| Node | `!pk professor enviar <n>` **está registrado** e chama `POST /v1/professor/send` |
| JVM | Endpoints **existem** (`professor-send/confirm/cancel/use/cards`) e estão no health |
| Datomic | `professor-send!` **cria pendente** em `:save/professor-pending-edn` (5 min) |
| Bug UX | Resposta JVM aninhava campos em `summary`; `formatProfessor` lia `d.token` no topo → mensagem **"operação concluída"** sem código |
| Índice | `!pk tm` nº **1-based** → `collectionIndex = n-1` (correto após patch explícito `enviar`) |
| Save | Usa `activeSaveId` (`recovery-v1` quando ativo) — **não** inventa outro Pokémon |

**Conclusão:** a transferência pendente **já era persistida**; o jogador não via o código de 3 dígitos nem o texto de confirmação (comportamento master).

Probe RO (sem mutação): `/v1/professor/cards` → `{}`; coleção 119 com `collectionIndex` 0…; caps `professor-*` presentes.

---

## 2. Comparação master × A6

| Aspecto | Master | A6 (após patch) |
|---------|--------|-----------------|
| Enviar | Pendente + token 100–999, 5 min | Igual (token estável via hash do operationId) |
| Confirmar | Remove 1 Pokémon, +1 cartão família, devolve item, ajusta times | Igual + CAS/fence/recibo |
| Cancelar | Limpa pendente | Igual |
| Família | PokeAPI evolution chain | Catálogo local (`profile/family`; pidgey compartilhado; demais = species) |
| Bloqueios | Batalha/caça/raid/favorito/evolução pendente | Batalha ativa, favorito, enfermaria, busy, fence |
| Apresentação | Texto completo master | Restaurado em `formatProfessor*` |
| Persistência | Cassandra JSON | Datomic TX atômica |

---

## 3. Endpoints

```
GET  /v1/professor/cards?chatId&playerId&saveId
POST /v1/professor/send     { chatId, playerId, saveId, fenceGeneration, operationId, collectionIndex, now? }
POST /v1/professor/confirm  { …, token, now? }
POST /v1/professor/cancel   { … }
POST /v1/professor/use      { …, collectionIndex }
```

Sem `force=true`. Identidade `chat|player` + fence; troca de save → `stale_writer_fence`.

---

## 4. Diff proposto (local)

| Arquivo | Mudança |
|---------|---------|
| `profile_store.clj` | DTO plano (`token` no topo); enriquecer pendente; enfermaria; ajuste time-ginasio/liga; validação save |
| `bridge.clj` | HTTP status para `pokemon_in_treatment` / `pokemon_max_level` |
| `pokemon-jvm-present.cjs` | `formatProfessor` / Cards / Help + erros PROFESSOR_* |
| `pokemon_http.cljs` | `enviar` explícito; ajuda; índice 1-based; prefix |
| `professor_a64_test.clj` | Suite nova (envio→confirm→cancel→expira→fav→save→idempotência→busy→overcap→restart) |
| `pokemon-jvm-present.test.cjs` | Mensagens master |

---

## 5. Testes locais

| Suite | Resultado |
|-------|-----------|
| `professor-a64-test` + `profile-a551-test` | **15 / 54 OK** |
| present Node | **17 / 17 OK** |

Cobertura: envio sem remover, confirmação+cartão+item+times, cancelar, expiração 5 min, índice inválido, favorito, troca de save, retry idempotente, busy, coleção>cap, restart JVM, DTO plano.

---

## 6. Plano de deploy (aguardando GO)

1. Reconfirmar testes.
2. Cold backup `a56-*` (como nos GO anteriores).
3. Rsync/build JVM (`profile_store` + `bridge`) + Node (`present` + build CLJS/`pokemon_http`).
4. Tag `a64-professor-…-amd64`; compose **sem** `-v`; **sem** migrations.
5. Gate RO: `GET /v1/professor/cards` ok; **não** enviar Pokémon reais do recovery sem autorização.
6. Smoke opcional em save descartável / slot `a` vazio — **não** em 119 do recovery-v1 sem GO explícito de teste.
7. Health: caps `professor-*`; Hunt/Joy 1800; idle 300; `ALLOW_ALL_GROUPS`.

### Rollback

Imagens anteriores + compose bak. Sem rollback destrutivo automático.

---

## 7. O que **não** foi feito

- Deploy / migrations / Cassandra / mutação em produção  
- Transferência real de qualquer um dos 119 Pokémon  
- Expansão completa do catálogo de famílias (PokeAPI) — documentado como gap residual vs master
