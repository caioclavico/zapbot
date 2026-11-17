# M6.4 — Proposta: retirar Cassandra dos módulos Node (somente desenho)

**Status:** proposta técnica. **Não implementar nesta etapa.**  
**Escopo:** rank, lembretes, quiz, enquetes, bloqueios, admins (+ bugs/participantes se no mesmo caminho).  
**Fora:** gameplay Pokémon (já em Datomic via JVM).

---

## Princípios

1. Preservar **divisão por domínio** (um namespace / chave lógica ≈ um conjunto de attrs Datomic).
2. Um **writer** por domínio (mesmo padrão fence/operation-id onde houver risco de retry).
3. Migrar **incrementalmente** — cada módulo com feature flag / adaptador `armazenamento`.
4. Sem dual-write prolongado Cassandra×Datomic no mesmo domínio.
5. Produção permanece Cassandra até go/no-go explícito por módulo.

---

## Estado atual (`zapbot.armazenamento`)

Tabela única (legado): `estado` / `estado_particionado` — chave texto → JSON.

| Domínio | Chave Cassandra | Shape (resumo) | Timers / jobs |
|---|---|---|---|
| Rank | `rank` | chat → player → pontos | nenhum (escrita em vitórias) |
| Lembretes | `lembretes` | id → {chat, texto, criador, em} | `setInterval` 1s no Node |
| Quiz | `quiz-historico` | histórico de perguntas | estado de rodada em memória |
| Enquetes | `enquetes` | chat → enquete ativa/votos | nenhum |
| Bloqueios | `bloqueio` | chat → flags comandos/jogos/bot | nenhum |
| Admins | `admins-conhecidos` | chat → set de admins | evento `group_admin_changed` |
| Participantes | `participantes` | atividade p/ sorteio/resumo | histórico memória + persist |
| Bugs | `bugs` | relatórios | nenhum |

Pokémon legado (`treinador`, `loja`, `ginasios`, …) **não** entra nesta proposta (caminho JVM).

---

## Schema Datomic sugerido (por domínio)

### Contratos comuns

```clojure
;; identidade de chat/jogador
:odisseu.chat/id          ; string unique
:odisseu.player/id        ; string (jid/serializado)
;; recibo idempotente (opcional por domínio mutável)
:odisseu.op/id            ; string unique
:odisseu.op/domain        ; keyword
:odisseu.op/payload-hash  ; string
```

### Rank

```clojure
:rank/entry          ; entity
:rank/chat           ; ref → chat
:rank/player         ; string
:rank/points         ; long
:rank/updated-at     ; instant
```

### Lembretes

```clojure
:reminder/id :reminder/chat :reminder/text :reminder/creator
:reminder/due-at :reminder/sent-at
```

Job: manter timer Node **ou** mover para JVM poller; filtrar `chat-autorizado?` em staging (já feito no Node).

### Quiz

```clojure
:quiz.history/chat :quiz.history/question-id :quiz.history/asked-at
```

Rodada ativa permanece em memória até haver necessidade de HA.

### Enquetes

```clojure
:poll/chat :poll/question :poll/options :poll/votes :poll/open?
```

### Bloqueios

```clojure
:block/chat :block/commands :block/games :block/bot?
```

### Admins

```clojure
:admin/chat :admin/player-id :admin/since
```

---

## Contratos de persistência

Abstrair `zapbot.armazenamento` atrás de protocolo:

```clojure
(defprotocol KVStore
  (obter [this chave])
  (salvar! [this chave valor])
  (salvar-confirmado! [this chave valor]))
```

Implementações: `CassandraStore` (atual), `DatomicDomainStore` (por chave → entidades tipadas).  
Migradores one-shot: JSON Cassandra → transação Datomic; dry-run em base descartável.

---

## Ordem incremental recomendada

| Fase | Módulo | Motivo |
|---|---|---|
| 0 | Harness + KV protocol | sem mudança de comportamento |
| 1 | **Bloqueio** + **Admins** | baixo volume; crítica para segurança |
| 2 | **Enquetes** | estado curto; fácil validar |
| 3 | **Rank** | leitura frequente; precisa equivalência |
| 4 | **Quiz-histórico** | append-mostly |
| 5 | **Lembretes** | timers; maior risco operacional |
| 6 | Participantes / bugs | se ainda no Cassandra |

Cada fase: dump descartável → import → smoke Node → feature flag → observação → desligar Cassandra daquele domínio.

---

## Riscos

| Risco | Mitigação |
|---|---|
| Perda silenciosa no cutover de domínio | backup frio + dry-run M5-style |
| Dual-write | proibido; single-writer por domínio |
| Timers de lembretes em dois processos | um Node writer; staging já filtra chat |
| Shape JSON heterogêneo legado | normalizers existentes em cada ns |
| Monólito ainda depende de Chromium | migração de store ≠ cutover Pokémon |

---

## Critério de “Cassandra dispensável”

- Todos os domínios da tabela acima em Datomic (ou deliberadamente memória-only).
- Nenhum `cassandra-driver` no boot path de staging/produção.
- Pokémon já só JVM.

**Estimativa:** várias milestones (M7.x); não acoplar ao chip WhatsApp de staging.
