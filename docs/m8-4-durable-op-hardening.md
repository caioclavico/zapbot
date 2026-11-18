# M8.4 — Endurecimento operacional do journal de recuperação

**Checkpoint base:** `2bc263f` (M8.3)  
**Escopo:** protocolo prepare→confirm, fsync, fail-closed, crash boundaries sintéticas.  
**Fora de escopo:** produção, WhatsApp, Kafka/Cassandra extras, cutover, wiring nos stores.

> **`cutover-ready? = false`**  
> **`rollback-recon-available? = false`** (protocolo lab ≠ journal operacional + stores wired)  
> **`wired-in-stores? = false`**  
> **Exactly-once / RPO zero: não declarados**

---

## 1. Protocolo de durabilidade

### Máquina de estados

| Estado | Significado |
|--------|-------------|
| `:new` | Op recebida, ainda sem evidência |
| `:prepared` | Intent no journal (antes do Datomic) |
| `:datomic-committed` | Transação + receipt no SoT |
| `:journal-durable` | Confirmação no journal com fsync |
| `:http-acked` | Resposta de sucesso ao Node |
| `:uncertain` | Datomic OK; journal confirm falhou |
| `:aborted` | Falha antes/durante Datomic |

Transições (sem 2PC entre journal e Datomic):

1. `write-intent!` → `:prepared`
2. `datomic-fn` (+ receipt) → commit
3. `append-phase! :journal-durable` + fsync
4. Só então `http-success? = true` (política `:strict`)

Se (3) falhar sob `:strict`: marca `:uncertain`, `fail-closed!` (manutenção), **não** confirma HTTP.

### Identidade e payload

- `operation-id` estável
- `payload-hash` (SHA-256 do `pr-str` do payload)
- Reuso do mesmo id com payload diferente → `journal_operation_id_payload_mismatch`
- Scope (`chat` / `player` / `save`) + `fence` + `schema` versionado (`m84-v1`)
- Ordem de replay: `:seq` monotônico nos arquivos `NNNNNNNN.edn`

### Limite honesto

Journal e Datomic são dois armazenamentos **sem** transação distribuída. O protocolo reduz a janela de “cliente acha que deu certo sem journal”, mas **não** elimina a possibilidade de Datomic commitado + journal incompleto (estado `:uncertain` + manutenção).

---

## 2. Cobertura real das operações

| Camada | Status |
|--------|--------|
| Harness sintético (`durable-op` / `durable-crash`) | Cobre kinds de lab (`:coins-delta`, etc.) |
| Stores Pokémon / Platform | **Não wired** |
| Bridge JVM → Node | **Não wired** |
| Mutações reais (captura, HP, rank, quiz, …) | Inventariadas, **fora do protocolo** |

Inventário necessário (ainda não protegido):

- **Pokémon:** captura, HP, XP, moedas, evolução, batalhas, saves, Joy, loja  
- **Platform:** rank, bloqueios, admins, enquetes, Quiz, lembretes, bugs, participantes  

Não declarar cobertura completa.

---

## 3. Falhas — fechadas vs abertas

| Fronteira | Lab M8.4 | Aberto em produção |
|-----------|----------|--------------------|
| Antes do prepare | Sem mutação / sem HTTP ok | — |
| Durante escrita prepare | Sem Datomic | Crash mid-fsync real depende do FS |
| Prepare sem Datomic | Órfão reconciliável; sem HTTP ok | Reconciliação automática nos stores |
| Durante Datomic | `:aborted`; sem HTTP ok | — |
| Datomic OK, journal confirm falha | `:uncertain` + manutenção; **sem HTTP success** | Ops precisa do volume journal |
| Ambos OK, crash antes HTTP | Retry → duplicate | Bridge precisa do mesmo `operation-id` |
| Após HTTP | Caminho feliz | — |
| Replay | Sem double-apply via receipt | Só se stores usarem receipts |
| Compactação | Crash não corrompe leituras restantes | Política de retenção operacional |
| Corrupção parcial | Leitura falha → manutenção | Monitoramento + alertas |
| Perda simultânea Datomic+journal | Continua fatal (M8.3) | Domínio de falha distinto obrigatório |

Gap M8.3 (`m83-lag-coins` com HTTP já ok): **fechado no protocolo `:strict`** — o cliente não recebe sucesso se o journal falhar. Ainda aberto: stores reais não usam esse caminho.

---

## 4. Testes de crash e recuperação

Runner: `./scripts/test-m8-4-durable-op.sh` → alias `:test-m8-4-durable-op`.

12 cenários em `odisseu.cutover.durable-crash` / artefato `target/m8-4/report.edn`.

---

## 5. Garantias demonstradas e limitações

**Demonstrado (sintético):**

- Sem confirmação HTTP se journal pós-Datomic falhar (`:strict`)
- Idempotência de retry após crash pós-journal / pré-HTTP
- Rejeição de `operation-id` com payload divergente
- Fail-closed em corrupção / journal pós-commit
- fsync via `FileChannel.force` no append

**Não demonstrado / não alegado:**

- RPO zero
- Exactly-once entre journal e Datomic
- Integração nos stores ou bridge
- Sobrevivência a perda do disco único (dois dirs no mesmo disco ≠ domínio distinto)
- `rollback-recon-available?`

---

## 6. Infraestrutura para journal independente

| Requisito | Nota |
|-----------|------|
| Path fora do volume Datomic | Preferir disco/FS/região distintos |
| Permissões | Escrita só do processo Odisseu; backup read-only |
| Retenção | Compactar para `archive/` após backup verificado; não apagar antes |
| Hash / corrupção | Falha de leitura EDN → manutenção |
| Disco | Alertar espaço e erros de escrita; fail-closed |
| Restore | Backup quiescido + replay de `:journal-durable` em ordem `:seq` |
| Journal indisponível | Política `:strict` → manutenção; sem novas confirmações de sucesso |
| Dois dirs no mesmo disco | **Não** protege contra perda desse disco |

---

## 7. Estado dos gates

```clojure
(evaluate-m84-gates
  {:protocol-crash-proven? true   ;; após drill verde
   :stores-wired? false
   :production-journal-deployed? false
   :independent-failure-domain? false
   :whatsapp-staging-validated? false})
;; => rollback-recon-available? false
;;    cutover-ready? false
```

---

## 8. Próximo passo mínimo

**M8.5 (sugerido):** wiring mínimo de `durable-op` / journal em **uma** mutação real de store (ex.: `coins-delta` no store Pokémon) + smoke de integração — ainda sintético, sem produção — mantendo gates bloqueados até domínio de falha independente e política operacional.

---

## Artefatos

| Path | Papel |
|------|-------|
| `odisseu.cutover.op-journal` | Fases, fsync, mismatch, compact, fail-closed |
| `odisseu.cutover.durable-op` | Coordenador + cobertura inventário |
| `odisseu.cutover.durable-crash` | 12 fronteiras de crash |
| `odisseu.smoke.m8-4-durable-op` | Runner de testes |
| `scripts/test-m8-4-durable-op.sh` | Entrypoint |
| `target/m8-4/report.edn` | Relatório da execução |
