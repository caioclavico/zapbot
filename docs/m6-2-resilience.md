# M6.2 — Resiliência operacional e rollback seguro

**Status:** validado em staging sintético (JVM/Datomic). Sem cutover.  
**Branch:** `refactor/modular-monolith-datomic`  
**Checkpoint base:** `e9ef9c2` (M6.1)

---

## Parte A — Revisão do staging

| Item | Estado |
|---|---|
| Compose Node+JVM+volume Datomic | `docker-compose.staging.yml` |
| Bridge só em `127.0.0.1:18090` no host | Confirmado |
| `APP_ENV=staging` + `POKEMON_ADAPTER=jvm` | Habilita JVM; produção força `legacy` |
| `POKEMON_SERVICE_URL=""` | Um writer Pokémon |
| FileLock `.odisseu-writer.lock` | Um processo JVM por storage |
| Storages M5.3B/M5.5 | Bloqueados no entrypoint |
| Healthcheck JVM + `depends_on` | Ordem Node após JVM healthy |
| `stop_grace_period` + shutdown hook | Encerramento controlado |
| Limites | JVM 512m/1 CPU; Node 2560m/2 CPU (+ swap 4g) |

Cassandra permanece **opcional** (`profile with-cassandra`) só para módulos Node não-Pokémon.

---

## Comandos

```bash
./scripts/staging-audit.sh
./scripts/staging-resilience.sh          # suite M6.2
./scripts/staging-measure.sh             # RSS/CPU snapshot

# Backup/restore (writer parado)
./scripts/staging-down.sh
./scripts/staging-backup.sh .datomic-local/m61-staging .datomic-local/m62-bak
./scripts/staging-restore.sh .datomic-local/m62-bak .datomic-local/m62-restore
```

---

## Parte B — Testes de falha (sintéticos)

Cobertos por `odisseu.smoke.m6-2-resilience` / `./scripts/staging-resilience.sh`:

| Cenário | Resultado esperado |
|---|---|
| Reinício JVM + reopen store | Saves A/B e moedas intactos |
| “Node reinicia” (cliente HTTP novo) | JVM permanece healthy |
| Tx confirmada + bridge morta + retry `operationId` | Sem duplicação |
| Reenvio idempotente | `duplicate=true` |
| Bridge indisponível | Cliente recebe falha de conexão |
| Encerramento + restart gracioso | Health 200 |
| Backup frio → restore dir descartável | Moedas iguais |
| Storage path inválido | Falha sem corromper primary |
| Cutover A→progresso→rollback snapshot B | Progresso pós-cutover **perdido** (demonstrado) |
| Carga 8 jogadores × 4 ops | Sem erros; moedas corretas |

**Não coberto sem sessão WhatsApp de teste:** reinício Node real com Chromium READY, falha de renderização WhatsApp.

---

## Parte C — Memória / desempenho

Medir com `./scripts/staging-measure.sh` com bridge no ar. Smoke M6.2 reporta RSS in-process e latências de health/activate/coins + batch concorrente.

**Bloqueio aberto:** Chromium READY não medido sem sessão WhatsApp de staging autorizada (nunca usar sessão de produção).

---

## Parte D — Runbook de cutover / rollback

### Princípios

1. **Um writer** de gameplay Pokémon (JVM **ou** legado — nunca os dois).
2. **Backup frio** do storage Datomic (writer parado) **antes** de trocar o writer.
3. Rollback para Cassandra **após** escritas Datomic **não é seguro** — perde progresso pós-cutover em silêncio (demonstrado em M6.2).
4. Rollback aprovado só se: (a) restaurar snapshot Datomic que **inclui** o progresso a preservar, **ou** (b) não houve escritas pós-cutover, **ou** (c) export explícito de deltas antes de reverter.

### Procedimento de cutover (ensaio — bancos descartáveis)

1. Congelar writer legado (ou desligar `pokemon-service` no ambiente de ensaio).
2. Backup frio Datomic + (se aplicável) snapshot Cassandra pré-cutover.
3. Apontar Node ensaio: `APP_ENV=staging` `POKEMON_ADAPTER=jvm` `POKEMON_SERVICE_URL=""`.
4. Smoke: health, saves A/B, caça, Joy, loja; monitorar RSS/latência.
5. Go só se critérios abaixo passarem.

### Procedimento de rollback (ensaio)

| Situação | Ação segura |
|---|---|
| Falha **antes** de qualquer escrita Datomic pós-cutover | Religa legado; Datomic vira arquivo read-only |
| Escritas Datomic já ocorreram | **Não** voltar ao Cassandra como SoT. Restaurar Datomic a partir do último backup frio **ou** manter JVM e corrigir forward |
| Precisa preservar progresso pós-cutover e sair do JVM | Exportar/migrar deltas Datomic→legado **antes** de reativar writer Cassandra (trabalho futuro; fora de M6.2) |

### Critérios go/no-go

**Go (ensaio staging):**

- [ ] Health JVM + Node (ou Node simulado) estáveis 15+ min
- [ ] Idempotência e fence OK sob reinício
- [ ] Backup/restore ensaiado em dirs descartáveis
- [ ] Um writer confirmado (lock + `POKEMON_SERVICE_URL` vazio)
- [ ] Bridge só loopback/rede privada
- [ ] Módulos Cassandra não-Pokémon inventariados (não bloqueiam cutover Pokémon)

**No-go produção (ainda):**

- [ ] Chromium READY + JVM na mesma VM sem OOM comprovado
- [ ] Lacunas MVP (PvP, missões, matching Adventure — M4.12)
- [ ] Runbook de preservação de progresso pós-cutover se rollback Cassandra for exigido
- [ ] Aprovação explícita de cutover (fora deste milestone)

### Monitoramento sugerido

- `/health` JVM a cada 15s (já no Compose)
- RSS JVM/Node/Chromium (`ODISSEU_RESOURCE_METRICS` no Node)
- Taxa de `409` fence / `operation_pending`
- Espaço em disco do volume Datomic

### Recuperação rápida

1. Bridge down → reiniciar só `odisseu-jvm` (volume intacto).
2. Volume corrompido suspeito → **não** escrever; restaurar último backup frio em volume novo.
3. Dual-writer detectado (lock ou URL legado setada) → parar imediatamente o segundo writer.

---

## Parte E — Outros módulos (inventário, sem migração)

| Módulo | Persistência | Staging JVM impacto |
|---|---|---|
| Velha / Naval / Adedonha | Memória (+ rank Cassandra) | Funciona sem Cassandra no jogo; placar precisa Cassandra |
| Quiz | Memória + `quiz-historico` Cassandra | Histórico exige Cassandra |
| Lembretes | Cassandra | Exige Cassandra (ou degradar) |
| Rank | Cassandra | Exige Cassandra |
| IA (`!resuma`, `!pergunta`) | APIs + histórico memória / `participantes` | Sem Cassandra para o núcleo; `participantes` opcional |
| Enquetes / bloqueios / admins / bugs | Cassandra | Exigem Cassandra |
| Pokémon (adaptador JVM) | Datomic | **Não** usa Cassandra |

**Para monólito sem Cassandra:** migrar chaves genéricas de `zapbot.armazenamento` (trabalho futuro) — **fora de M6.2**.

---

## Bloqueios restantes de produção

1. Chromium READY não medido neste ambiente.
2. Rollback Cassandra pós-escritas Datomic = perda silenciosa → **não aprovado**.
3. Gaps M4.12 (PvP, missões, Adventure sync).
4. Sem alteração de flags/VMs de produção neste milestone.

## Próximas mudanças mínimas

1. Sessão WhatsApp de **staging** isolada → medir Chromium + reinício Node real.
2. Ensaio compose `up/down` com volume nomeado + backup via volume.
3. Documento operacional de export de deltas se rollback legado for requisito de negócio.
4. Só então considerar go/no-go de cutover (marco futuro).
