# M8.2 — Recuperação pós-cutover e proteção contra perda de progresso

**Checkpoint base:** `350d233` (M8.1)  
**Escopo:** ensaio sintético Datomic descartável + runbook.  
**Fora de escopo:** produção, cutover, WhatsApp, Cassandra remoto, dual-write.

> **Não declarar recuperação sem perda.** O ensaio demonstra RPO > 0 quando TXs confirmadas ocorrem após o último backup sem log externo independente.  
> **`rollback-recon-available? = false`**. **`cutover-ready? = false`**.

---

## 1. Estratégia

| Princípio | Decisão |
|---|---|
| SoT após liberação | **Datomic** |
| Rollback automático → Cassandra | **Proibido** |
| Writers | Um por storage (Pokémon / Platform) |
| Pollers | Só após validar lembretes; off em manutenção |
| Divergência | Gameplay em manutenção; escrita bloqueada |
| Perda zero | **Não alegada** sem prova |

Namespace: `odisseu.cutover.recovery` (modelo/gates) + `odisseu.cutover.recovery-drill` (ensaio).

```bash
./scripts/test-m8-2-recovery.sh
```

---

## 2. Modelo de falhas (resumo)

| # | Cenário | Recuperável com receipts/backup? |
|---|---|---|
| 1 | JVM antes do commit | Sim — retry mesmo `operation-id` |
| 2 | Commit OK, HTTP perdido | Sim — retry → `:duplicate` |
| 3 | Node após confirmação | Sim — reopen + receipts |
| 4 | JVM destruída, volume OK | Sim — reopen |
| 5 | Volume indisponível | Outage até volume/restore; manutenção |
| 6 | Backup < últimas TXs | **RPO gap** — TXs pós-backup irrecuperáveis sem log externo |
| 7 | Corrupção/perda volume | Quarantine + restore destino **novo** |
| 8 | Falha no restore | Não sobrescrever único remanescente; retry em dest novo |
| 9 | Só Pokémon ou Platform | Isolar storage; manutenção acoplada se divergência |

Detalhe em `recovery/failure-catalog`.

---

## 3. Testes de falha e restauração

Ensaio `recovery-drill/run-drill!`:

1. Seed sintético Pokémon + Platform (8 domínios exercitados: moedas, rank, bloqueio, admins, enquete/voto, lembrete, switch A/B).
2. Ops **pré-backup** (confirmadas).
3. Backup ocioso (`pokemon.migration.backup/snapshot-storage!`) — **não** copiar volume sob escrita ativa em produção sem quiesce.
4. Ops **pós-backup** (confirmadas).
5. Wipe dos volumes (simula perda).
6. Restore a partir do backup.
7. Comparação: pré sobrevive; pós **perdida**.
8. Reprocessamento dos op-ids pré → `:duplicate`, sem double-apply.
9. Simulação HTTP perdido → retry duplicate.
10. Manutenção; saída sem resolver divergência → **bloqueada**.
11. Restore em destino **novo**; falha parcial de um storage; reopen JVM.

---

## 4. RPO / RTO medidos

Gravados em `target/m8-2/report.edn` (`:rpo`, `:rto`):

- **RPO** = `last-confirmed-tx-at − backup-at` (+ contagem de op-ids perdidos).
- **RTO** = tempo até restore em destino novo + reopen no ensaio.

Ensaio local recente (ordem de grandeza): **RPO ≈ 35 ms** (3 op-ids pós-backup perdidos), **RTO ≈ 4–5 ms**. Em produção o RPO será a política de backup (minutos/horas), não este micro-gap de laboratório.

`zero-loss?` só seria true se gap=0 e zero op-ids perdidos — **não** é o caso deste ensaio.

---

## 5. Operações recuperadas vs não recuperadas

| Classe | Exemplos no ensaio | Destino após restore |
|---|---|---|
| Recuperadas | `m82-pre-coins`, `m82-pre-score`, `m82-pre-block`, `m82-pre-vote`, seed admin/lembrete | Presentes + retry idempotente |
| Não recuperadas | `m82-post-coins`, `m82-post-score`, `m82-post-block` | Ausentes; **sem** reconstrução possível só com o backup |

**Não** reprocessar cegamente filas Node de op-ids pós-backup após restore antigo — sem recibo no SoT restaurado, um retry **aplicaria de novo** efeitos que o usuário já “viu” confirmados, ou pior: recriaria estado inconsistente com a narrativa. Manutenção primeiro.

---

## 6. Runbook operacional

### A) Antes da liberação de jogadores (abort window)

1. Congelar writers legados (`freeze-legacy-writers!` / flag real sob autorização).
2. Abort se gates falharem — legado continua SoT.
3. Não ativar writer Datomic único.

### B) Após liberação (Datomic = SoT)

#### Interromper novos comandos
1. Entrar em manutenção (`enter-maintenance!` / flag operacional Node+JVM).
2. `write-enabled? = false`; pollers off.
3. Comunicar indisponibilidade e possível perda de progresso recente.

#### Identificar última TX confirmada
1. Consultar receipts (`operation-receipt/*`, `platform.op/*`) no volume atual.
2. Cruzar com logs Node (op-ids) se existirem — **não** são SoT.

#### Evidências / volume danificado
1. Parar writers (single-writer).
2. Quarantine: renomear/copiar volume danificado para fora do path ativo.
3. **Não** apagar até restore validado em destino novo.

#### Escolher backup
1. Preferir último backup com checksum/manifesto ok e quiesce documentado.
2. Aceitar RPO = gap até a última TX confirmada conhecida.

#### Restaurar
1. Destino **novo** (nunca o único remanescente).
2. `restore-storage!` → reopen JVM → validar Pokémon e Platform.
3. Comparar receipts esperados vs presentes; listar op-ids perdidos.

#### Liberar escrita ou permanecer em manutenção
- Liberar **só** se divergência resolvida (operador + checklist).
- Pollers: gate separado após validar lembretes pendentes.
- Casos ambíguos: procedimento manual (humano); sem auto-rollback Cassandra.

#### Comunicação
Informar: janela de possível perda = RPO do backup escolhido; progresso após esse ponto pode não ter sido recuperado.

---

## 7. Gates

| Gate | Ensaio M8.2 |
|---|---|
| Backup consistente validado | aprovado (quiesce no drill) |
| Restauração comprovada | aprovado |
| Single-writer | aprovado (procedimento) |
| Reprocessamento seguro | aprovado (duplicates) |
| Falha de storage tratada | aprovado |
| Sem divergência silenciosa | aprovado (manutenção) |
| RPO/RTO medidos | aprovado |
| Manutenção funcional | aprovado |
| **rollback-recon-available** | **bloqueado** — perda confirmada sem reconstrução |

---

## 8. Critérios mínimos para produção

1. Política de backup com quiesce + retenção + teste periódico de restore.
2. RPO/RTO **acordados** com stakeholders (não “zero”).
3. Manutenção operacional no Node/JVM (bloquear cmds + pollers).
4. Journal externo independente **ou** aceite explícito de RPO (sem marcar recon=ok).
5. Staging WhatsApp autenticado (gate M8.0 ainda aberto).
6. Single-writer por storage; sem dual-write.
7. Runbook ensaiado; WIP ginásio fora deste marco.

---

## Auditoria

- WIP `store.clj` / `gym_m48_test.clj` permanece separado.
- Sem dados reais; storages `.datomic-local/m82-*`.
- Sem commit/push automático neste marco.
