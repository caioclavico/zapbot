# M8.0 — Preparação de exportação e cutover Cassandra → Datomic

**Checkpoint base:** `300f126` (M7.7)  
**Escopo:** pipeline verificável com fixtures sintéticas e artefatos offline autorizados.  
**Fora de escopo:** produção, Oracle, GCP, Cassandra live, WhatsApp, dual-write, deploy, cutover real.

> **Cutover NÃO está pronto.** Staging com WhatsApp autenticado ainda não passou.

---

## 1. Pipeline implementado

| Etapa | Namespace | Artefato |
|---|---|---|
| Inventário | `odisseu.cutover.inventory` | classificação Datomic / legado / decisão |
| Export + manifesto | `odisseu.cutover.export` | `export.edn`, `manifest.edn`, `checksums.edn` |
| Reconciliação | `odisseu.cutover.reconcile` | older (evidência) vs newer (candidato) |
| Import | `odisseu.cutover.import` | batchId, journal, resume, sem poller |
| Rollback / gates | `odisseu.cutover.rollback` | freeze, abort, go/no-go, risco pós-cutover |
| Orquestração | `odisseu.cutover.pipeline` | `run-pipeline!` → `target/m8-0/report.edn` |

```bash
./scripts/test-m8-0-cutover.sh
# ou: clojure -M:test-m8-0-cutover
```

Artefatos privados/descartáveis: `target/m8-0/`, `.datomic-local/m80-*` — **fora do Git**.

---

## 2. Inventário de dados e lacunas

### Datomic-ready (migrar no cutover)

| Domínio | Cassandra | Milestone | Notas |
|---|---|---|---|
| Bloqueio | `bloqueio` | M7.1 | |
| Admins | `admins-conhecidos` | M7.1 | |
| Enquetes | `enquetes` | M7.2 | Uma aberta por chat |
| Quiz-histórico | `quiz-historico` | M7.3 | |
| Rank | `rank` | M7.4 | Não somar snapshots; preservar efeitos HTTP |
| Lembretes | `lembretes` | M7.5 | Import só `pending`; sem poller |
| Bugs | `bugs` | M7.6 | |
| Participantes | `participantes` | M7.6 | `!resuma` RAM — não migrar |
| Pokémon saves A/B | `treinador`/`loja` + slots | M5.0–M5.5 | Proveniência obrigatória |
| Joy / deadlines | enfermaria | M5.5 | Preservar UUID + `pronto-em` |
| Ginásios / raids | `ginasios*`, `raids` | M3/M4 | WIP ginásio (`store.clj`) **separado** do M8 |

### Não migrar / regenerável

| Item | Status |
|---|---|
| `pokedex-cache` | rebuildable |
| `pokemon-batalhas-ativas` / `pokemon-cacadas-ativas` | quiesce obrigatório |
| `pokemon-http-deliveries` | efêmero (M7.7 em memória) |

### Decisão explícita requerida

| Item | Motivo |
|---|---|
| `pokemon-restauracao-journals` / backups | Não copiar como save ativo |
| Snapshot 14/10 (históricos reais) | Evidência — nunca overwrite automático |
| Mensagens `!resuma` | Só RAM |

---

## 3. Resultados de equivalência (sintético)

O smoke `odisseu.smoke.m8-0-cutover` valida:

- Inventário marca gaps (`pokedex-cache`, journals, deliveries).
- Reconciliação: moedas 200→350, coleção/Joy alterados, enquete nova, lembrete novo, rank com evento HTTP adicional (não somado).
- Colisão de proveniência (mesmo `snapshot-id`, conteúdo diferente) → **bloqueio**.
- Platform: import → reimport `:duplicate`; interrupt/resume via journal.
- Pokémon M5 (dois saves): reimport duplicate; backup/restore preserva equivalência.
- Gates: `whatsapp-staging-validated=false` → **no-go** / `cutover-ready?=false`.

---

## 4. Estratégia de snapshot consistente

Cassandra **não** oferece snapshot multi-partição atômico.

Procedimento documentado (cutover real — requer autorização):

1. **Freeze** writers legados (flag/drain); confirmar fila vazia.
2. Aguardar quiescência de batalhas/caçadas/raids ativos.
3. Exportar **todas** as partições dos módulos inventariados numa janela curta.
4. Gerar manifesto + SHA-256 por arquivo + `content-sha256` do pacote.
5. Registrar origem, timestamp, label e meta `:authorized-offline-copy` (nunca `:production`).
6. Relatório de chaves ausentes via `inventory-dump`.
7. Tratar snapshot antigo (ex. 14/10) como **evidência** paralela — não mesclar contadores.

Quiescence notes ficam em `:meta :quiescence` do export.

---

## 5. Procedimento de importação repetível

1. `export/load-dump!` — exige `:synthetic` ou `:authorized-offline-copy`.
2. `reconcile/reconcile-dumps` older→newer — aborta se `block-import?`.
3. Import **somente** do newer (candidato).
4. `import-batch-id` estável; recibos em `import-journal.edn` + recibos Pokémon M5.
5. Platform: `put-*-state!` em bulk; lembretes filtrados a `pending`.
6. Pokémon: `pokemon.migration.import/import-ir!` — **sem** `ensure-trainer` para preencher buracos.
7. Retry do mesmo lote → `:duplicate` se hash igual; mismatch de payload → erro.
8. Resume: journal com `:completed-stages` (`:platform` / `:pokemon`).
9. Sem atomicidade global entre platform e Pokémon — etapas independentes.
10. Sem poller de lembretes, sem gameplay, sem WhatsApp.

---

## 6. Riscos e critérios de cutover

### Go / no-go (código em `rollback/evaluate-gates`)

| Gate | Obrigatório |
|---|---|
| Writers legados congelados | sim |
| Checksums OK | sim |
| Reconcile sem colisão | sim |
| Equivalência por domínio | sim |
| Plano de writer Datomic único | sim |
| Staging WhatsApp autenticado | **sim — ainda falso** |
| Reconciliação pós-escrita para rollback | sim |
| Autorização produção | sim |

### Riscos explícitos

- Restaurar Cassandra **depois** de escritas no Datomic pode **perder progresso**.
- Rollback pós-cutover **não** é seguro sem mecanismo de reconciliação comprovado.
- Abort **antes** de ativar writer Datomic é a janela segura (legado continua SoT).
- WIP ginásio (`modules/pokemon/.../store.clj`, `gym_m48_test.clj`) permanece fora deste marco.

---

## 7. O que exige autorização específica antes de produção

1. Acesso a dump Cassandra / Oracle / GCP.
2. Export com `:authorized-offline-copy` a partir de ambiente real.
3. Freeze de writers em produção.
4. Qualquer alteração de flags/volumes/deploy.
5. Ativação do writer Datomic único.
6. Cutover ou dual-write (dual-write continua proibido neste plano).
7. Uso de sessões WhatsApp / staging autenticado para o gate final.
8. Decisão humana sobre journals de restauração e matching de saves ambíguos.

---

## Fixtures

- `fixtures/m8-0/snapshot-older.edn` — evidência 14/10 sintética  
- `fixtures/m8-0/snapshot-newer.edn` — candidato com deltas  
- Pokémon dois-saves: `pokemon.migration.fixtures/build-cassandra-dump` (reuso M5)

## Preservação WIP ginásio

Não incluir neste commit as correções pendentes de `store.clj` / `gym_m48_test.clj` — track separado.
