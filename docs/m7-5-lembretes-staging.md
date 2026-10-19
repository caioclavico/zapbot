# M7.5 — Lembretes via Datomic (somente staging)

**Status:** integração funcional em staging. Produção permanece Cassandra.  
**Base:** `46941ff`  
**Sem dual-write. Sem cutover. Sem twin pollers.**

## 1. Contratos reais (legado Node)

| Aspecto | Comportamento legado |
|---|---|
| Chave Cassandra | `lembretes` → `id → {id, chat, texto, criador, em}` |
| Comandos | `!lembrete`, `!lembretes`, `!cancelarlembrete` (sem edição) |
| Deadline | duração relativa → `em = Date.now() + ms` (epoch ms absoluto) |
| Timezone | só na exibição (`toLocaleString`); storage é epoch UTC ms |
| Recorrência | **não suportada** |
| Poller | `setInterval` 1s; set in-memory `enviando`; delete após send OK |
| Falha de envio | remove de `enviando`, mantém no mapa (retry no próximo tick) |
| Reinício | perde `enviando`; reenvia se ainda no Cassandra |

## 2. Schema / endpoints / adaptador

- Schema: `migrations/platform/005-lembretes.edn`
- Domínio: `odisseu.platform.domain.lembretes`
- Porta: `ReminderStore`
- Endpoints autenticados:
  - `GET /v1/platform/reminders`
  - `POST /v1/platform/reminders/create|cancel|claim|complete|fail`
  - `GET /v1/platform/reminders/due`
- Node: `zapbot.lembretes` + `platform-jvm-client.cjs` só com `PLATFORM_ADAPTER=datomic`

## 3. Claim / lease / fence

1. `due` lista `pending|failed` vencidos **ou** `claimed` com lease expirado.
2. `claim` usa `:db/cas` em `status` + `fence` (incrementa fence).
3. Lease default **30s**; crash → reclaim por outro worker com fence novo.
4. `complete` / `fail` exigem `claimant` + `fence` corretos.
5. Importação bulk **não** dispara poller (só grava `pending`).
6. FileLock de storage **não** substitui essa coordenação.

## 4. Política de envio WhatsApp (at-least-once)

```
claim (Datomic) → sendMessage (WA) → complete (Datomic)
                 ↘ fail (Datomic) se erro explícito
```

| Cenário | Resultado |
|---|---|
| Crash após claim, antes do send | lease expira → reclaim → envia 1× |
| Crash após send, antes do complete | lease expira → reclaim → **possível duplicata** |
| Erro WA explícito | `fail` → status `failed` → reclaimable |
| Resultado incerto (timeout após send) | tratado como falha → risco de duplicata |

**Não é exactly-once.** whatsapp-web.js não oferece id de dedupe confiável no send.

## 5. Testes

```bash
./scripts/test-m7-5-lembretes.sh
./scripts/staging-sim.sh   # se aplicável
npx shadow-cljs compile app
```

Cobertura JVM: CRUD, due, cancel, race de 2 pollers, lease, crash pré/pós envio, fail, idempotência, reopen, import, isolamento.

## 6. Isolamento staging / produção

- Produção: `PLATFORM_ADAPTER` default/forçado `cassandra` → poller Cassandra legado.
- Staging: `datomic` → só poller com claim; Cassandra `registrar!`/`salvar!` desligados para lembretes.
- Smokes e testes: sem sessão WA, sem mensagens externas.

## 7. Auditoria Rank async (M7.4)

| Produtor | Aguarda promise? |
|---|---|
| Velha / Naval | sim (`p/then`) |
| Quiz | sim (`p/let`) |
| Adedonha | sim (`p/all` + then na finalização) |
| Pokémon HTTP efeitos | sim |
| Pokémon core PvP vitória | **não bloqueia** o fluxo; agora loga erro (`p/catch`) — pendência consciente |

## 8. Pendências para retirar Cassandra

- Cutover produção + migração real de `lembretes`
- Decisão de outbox/reconciliação se duplicatas WA forem inaceitáveis
- Aguardar `pontuar!` no PvP core (ou aceitar fire-and-forget documentado)
- Remover adaptador Cassandra só após cutover validado

## Próximo

Não declarar cutover. Próximo módulo platform conforme roadmap.
