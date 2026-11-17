# A6 — Fase 11.1: Autorização multi-grupo

**Worktree:** `zapbot-raid-reconcile`  
**Data:** 2026-11-01  
**Modo:** implementação + testes no repo — **sem** deploy, restart, migrate, merge, ampliação real de grupos, alteração Datomic ou sessão WA.

---

## Classificação: **READY** (código) · implantação = GO separado

| Item | Status |
| --- | --- |
| Lib `scripts/lib/group-allowlist.cjs` | implementada |
| Wiring `zapbot.config` (`ALLOWED_GROUP_IDS`) | implementado |
| Compat `DEV_GROUP_ID` + dedupe literal | sim |
| Fail-closed IDs inválidos | sim |
| Separação grupo / usuário (STRICT) | sim · match exact JID |
| Outbox / raid notify / admin event | já usam `chat-autorizado?` (+ user no outbox) |
| Testes Node allowlist | `test/group-allowlist.test.cjs` |
| Deploy / restart / novos grupos | **não** |

---

## 1. Auditoria (entrypoints)

| Caminho | Gate |
| --- | --- |
| Mensagem / comando (`zapbot.core` → `permitido-pelo-ambiente?`) | `chat-autorizado?` **e** `usuario-autorizado?` **antes** do router Pokémon |
| `group_admin_changed` | só `chat-autorizado?` |
| Outbox JVM/HTTP (`pokemon_http` pending events) | `chat-autorizado?` + `usuario-autorizado?` no `chatId`/`playerId` do evento |
| Tick raide auto | só roda se `POKEMON_RAID_AUTO_TICK=true`; **entrega** filtrada pela allowlist |
| Lembretes | `chat-autorizado?` |
| Bridge JVM HTTP | bearer token — **não** replica allowlist WA (bind local) |

Identidades:

- Grupo: WhatsApp `@g.us` (chat da mensagem / `notification.chatId` / outbox `chatId`).
- Usuário: `@lid` / `@c.us` (`author` / `from` / outbox `playerId`).
- Saves Datomic: `(chatId, playerId)` — allowlist **não** altera chaves.

---

## 2. Implementação

### Variáveis

| Env | Papel |
| --- | --- |
| `ALLOW_ALL_GROUPS` | default off; se true, qualquer `@g.us`/`@c.us` (não recomendado) |
| `ALLOWED_GROUP_IDS` | CSV de `@g.us` literais |
| `DEV_GROUP_ID` | unido à lista (compat) |
| `STAGING_STRICT_USER_ALLOWLIST` / `STRICT_USER_ALLOWLIST` | STRICT usuários |
| `STAGING_ALLOWED_USERS` | CSV JIDs literais exact-match |

### Regras

1. Parse CSV → trim → rejeitar qualquer ID que não case `^\d+@g\.us$`.  
2. Erro de config → `chat-autorizado?` **sempre false** em staging-like.  
3. Dedupe preservando a primeira ocorrência literal.  
4. `DEV_GROUP_ID` válido acrescentado se ausente.  
5. STRICT=1 + users vazio → nega todos os usuários.  
6. STRICT=0 + users vazio → qualquer `userId` string (participantes do grupo já autorizado).  
7. Sem strip/`local-part` ambíguo: `207…@lid` ≠ `207…`.

Fonte única: `scripts/lib/group-allowlist.cjs` (requerida por `config.cljs`).

---

## 3. Testes cobertos

Arquivo: `test/group-allowlist.test.cjs`

- Grupo permitido + usuário permitido  
- Grupo permitido + usuário não cadastrado (STRICT)  
- Grupo desconhecido / antigo (`120363431663917673@g.us`)  
- Config vazia / inválida  
- `@lid` e `@c.us`  
- Dois grupos autorizados em paralelo  
- `environmentAllows` (comando / outbox / raid notify)  
- Wiring em `config.cljs` / `pokemon_http` / `core`

Regressões executadas nesta fase:

```text
node --test test/group-allowlist.test.cjs test/a6-staging-isolation.test.cjs
→ 32 pass / 0 fail

node --test test/pokemon-jvm-client.test.cjs  # (+ platform no mesmo run parcial)
→ client JVM URL/outbox: pass

npm run build  # shadow-cljs release :app
→ Build completed (target/main.js inclui require group-allowlist.cjs)
```

---

## 4. Config de produção / piloto (somente confirmado)

Trecho alinhado a `.env.a6-cutover-rc.example` — **não aplicar** sem GO de recreate:

```bash
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
DEV_GROUP_ID=120363427684548487@g.us
STAGING_STRICT_USER_ALLOWLIST=1
STAGING_ALLOWED_USERS=   # preencher JIDs operadores com GO; vazio=quiet WA
POKEMON_RAID_AUTO_TICK=false
ODISSEU_BRIDGE_RAID_AUTO_TICK=false
```

**Não** incluir `120363431663917673@g.us` sem confirmação humana.

---

## 5. Instruções de implantação (fase futura)

1. Merge/RC com esta mudança.  
2. Build imagens Node.  
3. Atualizar `.env` A6 com `ALLOWED_GROUP_IDS` = só piloto (+ operators se STRICT=1).  
4. Recreate **somente** Node (GO explícito) — JVM/Datomic/sessão intactos.  
5. Smoke: comando no piloto; mensagem em grupo não listado ignorada; outbox sem delivery fora da lista.  
6. Ampliar CSV **um ID por GO** + backup A6 (Fase 11).

---

## 6. Riscos

| Risco | Mitigação |
| --- | --- |
| STRICT=1 + users vazio silencia o piloto | Documentado; popular operators antes do recreate UX |
| Bridge sem gate de grupo | Token + bind `127.0.0.1` |
| Require path `../scripts/lib` | Igual ao client JVM; rodar Node a partir do layout deploy atual |
| ALLOW_ALL acidental | Default false; exemplo RC força false |

---

## 7. O que esta fase **não** fez

Deploy · restart · migrate · merge · Datomic write · sessão WA · habilitar grupos extras · ligar raides auto.
