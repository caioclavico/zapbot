# M7.0 — Inventário de contratos de persistência (não-Pokémon)

Confirmado no código Node (`zapbot.armazenamento` → Cassandra `estado_particionado`).  
**Pokémon JVM/Datomic permanece isolado** e fora deste inventário operacional.

## Camada comum

| Item | Valor |
|---|---|
| API | `registrar!` / `obter` / `salvar!` / `salvar-confirmado!` |
| Tabela | `{keyspace}.estado_particionado (modulo, particao, valor)` |
| Diff | Partições = chaves de 1º nível do mapa; gravação serializada por módulo |
| TTL Cassandra | **nenhum** explícito |
| Retry | 3 tentativas, backoff 150ms×n |
| Sem Cassandra | `salvar!` no-op (cache only); `salvar-confirmado!` rejeita |

---

## Domínios

### 1. Bloqueios — chave `bloqueio`

| | |
|---|---|
| NS | `zapbot.bloqueio` |
| Shape | `chat-id → {"bot?" bool, "comandos" [str]}` (sets→vec no save) |
| Ops | bloquear/desbloquear comando(s)/jogos; bot on/off |
| Concorrência | fila por módulo em `armazenamento` |
| Externos | checagem admin WhatsApp (`getChat`); fallback `admins-conhecidos` |
| Timers | nenhum |
| Harness M7.0 | **implementado** (Datomic Local) |

### 2. Admins — chave `admins-conhecidos`

| | |
|---|---|
| NS | `zapbot.admins` |
| Shape | `chat-id → [player-id]` (set→vec) |
| Ops | `lembrar-admin!` / `esquecer-admin!` / evento promote/demote |
| Externos | `group_admin_changed` |
| Timers | nenhum |
| Harness M7.0 | **implementado** |

### 3. Enquetes — chave `enquetes`

| | |
|---|---|
| NS | `zapbot.enquetes` |
| Shape | `chat → {pergunta, opcoes[], votos{eleitor→n}, prefix}` |
| Ops | criar / votar / fechar (uma ativa por chat) |
| Timers | nenhum |
| M7.x | fixture inventário; store TBD |

### 4. Rank — chave `rank`

| | |
|---|---|
| NS | `zapbot.rank` |
| Shape | `chat → player → {nome, pontos, jogos{}, efeitos-pokemon-http{}}` |
| Ops | `pontuar!` / `penalizar!` / `aplicar-efeito!` (HTTP Pokémon, dedupe por id) |
| Concorrência | fila por `chatId` em efeitos HTTP; `salvar-confirmado!` |
| Timers | nenhum |
| Risco | dedupe de efeitos HTTP deve sobreviver migração |

### 5. Quiz — chave `quiz-historico`

| | |
|---|---|
| NS | `zapbot.quiz` |
| Shape | `chat → [texto-pergunta]` (últimas 12) |
| Ops | append ao gerar pergunta |
| Memória | rodada ativa em atom (não Cassandra) |
| Timers | nenhum |

### 6. Lembretes — chave `lembretes`

| | |
|---|---|
| NS | `zapbot.lembretes` |
| Shape | `id → {id, chat, texto, criador, em}` (`em` = epoch ms) |
| Ops | criar / listar / cancelar |
| Timer | `setInterval` 1s → `sendMessage` |
| Semântica deadline | `em <= now` dispara; marca `enviando` p/ não duplicar in-process |
| Staging | só chats `chat-autorizado?` |
| Risco migração | twin writers de timer; precisa single poller |

### 7. Bugs — chave `bugs`

| | |
|---|---|
| NS | `zapbot.bugs` |
| Shape | `id-str → {id, versao, chat, reportado-por, autor-mensagem, mensagem, … status}` |
| Ops | registrar / listar / resolver |
| Externos | lê histórico/citação WhatsApp |

### 8. Participantes — chave `participantes`

| | |
|---|---|
| NS | `zapbot.historico` |
| Shape | `chat → {player-id → nome}` |
| Ops | update passivo em `registrar!` |
| Memória | mensagens só em RAM (não persistir conteúdo) |

---

## Incompatibilidades Cassandra ↔ Datomic (antecipadas)

| Tema | Nota |
|---|---|
| JSON livre vs schema tipado | Normalizers por domínio obrigatórios |
| Sets↔vecs | Já tratados nos NS CLJS |
| Partição 1º nível | Rank/efeitos aninhados → attrs ou EDN documentado |
| Sem TTL nativo igual | Lembretes usam `em` na app, não TTL C* |
| Dual-write | Proibido; cutover por domínio |
| Pokémon | Storage/system distintos; harness platform não toca |

## Ordem M7.1+

1. **Bloqueio + Admins** (harness pronto) → primeiro cutover ensaiado  
2. Enquetes  
3. Rank (cuidado efeitos HTTP)  
4. Quiz-histórico  
5. Lembretes (timers)  
6. Bugs / participantes  
