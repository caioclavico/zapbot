# M8.3 — Durabilidade e recuperação após perda do Datomic

**Checkpoint base:** `fbed8ae` (M8.2)  
**Escopo:** auditoria + protótipo sintético backup+journal.  
**Fora de escopo:** produção, WhatsApp, Kafka/Cassandra extras, cutover, RPO zero sem prova.

> **`cutover-ready? = false`**  
> **`rollback-recon-available? = false`** (journal ainda não deployado operacionalmente)  
> **`journal-recon-proven? = true`** no lab quando o journal sobrevive  
> **Exactly-once entre dois storages: não declarado**

---

## 1. Comparação das estratégias

| | A Backup only | B Journal only | **C Backup + journal (escolhida)** |
|---|---|---|---|
| Fecha buraco M8.2 (TXs pós-backup) | Não | Sim* | **Sim*** |
| Complexidade | Baixa | Média | Média |
| Custo | Baixo | Baixo–médio | Baixo–médio |
| Kafka necessário? | Não | Não | **Não** |
| RPO residual | Intervalo de backup | Append falho / perda do journal | Append falho / **perda conjunta** |

\*Somente para ops journalizadas com sucesso após o commit Datomic.

---

## 2. Solução escolhida e justificativa

**C leve:** backup quiescido periódico **mais** journal append-only em path **fora** do `storage-dir` Datomic (arquivos EDN numerados).

Justificativa: menor incremento que reconstrói o gap M8.2 sem novo banco/fila. Kafka/Cassandra não demonstraram necessidade neste escopo.

Protocolo (honesto, sem 2PC):

1. Commit Datomic (+ receipt).
2. Append journal (`operation-id`, domain, kind, scope, payload, hash).
3. Só então a op é “recuperável após perda de volume”.
4. Se (2) falhar → **at-risk** (cliente já pode ter sido confirmado; RPO > 0).
5. HTTP perdido → retry mesmo `operation-id` → `:duplicate`.
6. Replay pós-restore: ordem por `:seq`, escopo chat/player/save, idempotente.

---

## 3. Implementação experimental

| NS | Papel |
|---|---|
| `odisseu.cutover.op-journal` | Append atômico, watermark, intents, wipe |
| `odisseu.cutover.durability` | Auditoria, A/B/C, atomicidade, política, gates |
| `odisseu.cutover.durability-drill` | Ensaios sintéticos |
| `./scripts/test-m8-3-durability.sh` | Runner |

Journal live: `.datomic-local/m83-op-journal/` (descartável).

---

## 4. Testes de perda total do volume

| Cenário | Resultado |
|---|---|
| Happy: bak + journal → wipe volume → restore → replay | Pós-backup **reconstruídas** |
| Datomic OK, journal fail | Op **perdida** após restore (at-risk honesto) |
| Dual loss (volume + journal) | Só baseline do backup; pós-backup **perdidas** |
| HTTP lost + retry | `:duplicate` |
| Intent sem Datomic | Sem confirmação ao cliente |
| Concorrência | `seq` monotônico |
| Scope errado | Replay rejeitado |
| Journal corrompido | Leitura falha → manutenção |

---

## 5. Evidências e limitações

**Recuperados (lab, journal intacto):** `m83-post-coins`, `m83-post-score`, `m83-post-block`.

**Perdidos:**
- `m83-lag-coins` (journal append falhou)
- Pós-backup no dual-loss

**Limitações:**
- Sem fsync explícito de produção (protótipo).
- Ops não determinísticas / WhatsApp: exigem resultado materializado ou `:replayable? false`.
- Não é exactly-once cross-store.
- Cópia de volume ativo continua sem snapshot atômico (quiesce obrigatório).

---

## 6. RPO / RTO observados (lab)

Medidos em `target/m8-3/report.edn`. Ensaio recente: **RPO ≈ 44 ms** (gap backup→última TX, com journal intacto RPO efetivo de *perda* = 0 após replay), **RTO ≈ 158 ms** (restore+reopen). **Não usar como SLA.**

Produção: RPO residual ≈ “ops confirmadas cujo journal falhou” ∪ “desde último backup se journal também morrer”; RTO ≈ restore + replay ensaiado periodicamente.

---

## 7. Complexidade e custo

- Ops: path de journal + rotação + monitoramento de append failure.
- Sem serviços pagos adicionais neste protótipo.
- Custo principal: disciplina operacional (quiesce backup, alertas de journal lag).

---

## 8. Gates ainda bloqueados

| Gate | Estado |
|---|---|
| `journal-recon-proven?` | Lab OK |
| `rollback-recon-available?` | **false** até journal deployado + política aceita |
| `whatsapp-staging-validated?` | Pendente (2º número) |
| `cutover-ready?` | **false** |

Não promover `rollback-recon-available` só porque o lab passou.

---

## 9. Próximo passo mínimo recomendado

1. Manter protótipo C; **não** introduzir Kafka agora.
2. Quando o 2º WhatsApp staging autenticar: validar bridge + manutenção.
3. Se seguir cutover: especificar destino de journal/backup fora do volume live, fsync, alertas de lag, ensaio de dual-loss em ambiente staging.
4. Só então reconsiderar `rollback-recon-available?` com evidência operacional (não só lab).

WIP ginásio permanece separado; sem commit/push automático neste marco.
