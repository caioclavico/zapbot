# Odisseu A6 — deploy produção · trocas Pokémon

**Classificação:** SUCCESS técnico  
**Validação funcional jogador↔jogador:** pendente (sem mensagens reais nesta janela)  
**UTC início preflight:** 2026-11-04T14:25:03Z  
**UTC cold backup:** 2026-11-04T14:27:26Z  
**UTC JVM RC healthy:** ~2026-11-04T14:29:30Z  
**UTC Node RC READY:** ~2026-11-04T14:31:20Z  
**UTC pós-check:** 2026-11-04T14:32:52Z  
**Host:** `odisseu-core` (`x86_64`)

---

## 1. Artefatos implantados

| Papel | Tag | Image ID completo |
| --- | --- | --- |
| JVM RC | `odisseu-jvm:a6-trades-20261104T132934Z-f4e0972046d3-amd64` | `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` |
| Node RC | `odisseu-node:a6-trades-20261104T132934Z-f4e0972046d3-amd64` | `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` |
| Código | `f4e0972046d360f29081c36184beaa23cd590e09` | tree `a76b14aeb865e677ac4cf867ee6419acee287402` |
| Migration | `pokemon/033-trade-public-id` | arquivo `033-trade-public-id.edn` |

### Rollback disponível (não usado)

| | Image ID |
| --- | --- |
| Node | `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` |
| JVM | `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` |

Meta local: `/home/caiohclavico/odisseu-recovery/a6-trades-deploy-20261104T142800Z/`

---

## 2. Preflight

| Gate | Resultado |
| --- | --- |
| Hostname/arch | `odisseu-core` / `x86_64` |
| Image IDs RC no Docker | match exato |
| Node/WA antes | healthy · READY · `508021e8…` |
| JVM antes | healthy · up · raid OFF · `e18c20cb…` |
| Schema | máx **032** · `has.033=false` |
| Trades | 3×`invited` · **0 unexpired** |
| Disco | ~16 GiB livres |
| Backups pré-existentes | session-backup + periodics 12832 |
| Announcer | ausente |
| Flags | `ALLOW_ALL_GROUPS=true` · raid ticks `false` |

---

## 3. Backup cold

| Campo | Valor |
| --- | --- |
| Modo | **cold** (`A6_PERIODIC_BACKUP=1`) |
| Dir | `/home/caiohclavico/odisseu-recovery/a6-periodic-backup-trades-pre-20261104T142726Z` |
| Manifest-of-manifest | `8a206c25fe323b4f873e136aa6712a236390f934b07c99bcf3c82c9141716dac` |
| Session WA | preservada (`…20261104T004608Z.tar.gz` sha `b2b8125e…`) |
| Compose before | `meta/compose-before.yml` |

JVM restaurou healthy após o cold stop/start **antes** do cutover RC.

---

## 4. Operações executadas

### Etapa A — JVM

1. Patch compose: JVM → tag RC (Node ainda `a6-critical-5088b5b`).  
2. `docker compose up -d --no-deps --force-recreate odisseu-a6-jvm`.  
3. Health `up` · Image ID RC · `raidAutoTickEnabled=false`.  
4. Node antigo permaneceu READY (janela JVM-new + Node-old).

### Etapa B — Migration 033

Aplicada automaticamente no boot via `schema/install!` (idempotente) contida na imagem RC.

Verificação RO (cópia do volume):

- `migrations.max=pokemon/033-trade-public-id`  
- attrs `:trade/public-id`, `:trade/public-key`, `:trade-seq/*` = true  
- trades: ainda `{"invited" 3}` · `trade.with-public-id=0` (sem backfill)  
- **sem** attrs econômicos novos

### Etapa C — Node

1. Patch compose: Node → tag RC.  
2. `up -d --no-deps --force-recreate odisseu-a6-node`.  
3. Mounts idênticos (session + data).  
4. healthy · WhatsApp **READY** · Image ID RC.  
5. Flags `ALLOW_ALL_GROUPS=true` · raid ticks `false` nos dois containers.

**Não** removidos locks/cookies Chromium. **Não** mensagens WA de teste.

---

## 5. Estado depois

| Item | Valor |
| --- | --- |
| Node | healthy · READY · `62414ffe…ea429d4f6` |
| JVM | healthy · up · raid OFF · `d07a379e…4408570` |
| Schema | **033** aplicada |
| Propostas | 3 `invited` expiradas · 0 com public-id · nenhuma concluída indevidamente |
| Volumes | `odisseu-a6-cutover-datomic` intacto |
| Disco | ~16 GiB livres (67%) |

### Smokes não destrutivos

- `formatTradeCreated` no container Node: inclui `#84`, **sem** vazamento `@g.us` / `false_` / operationId (`PRESENT_UX_OK`).  
- Schema/trades RO confirmados.  
- Troca real entre jogadores: **não executada** (restrito).

---

## 6. Rollback

Não necessário. Imagens anteriores permanecem no Docker. Schema 033 é aditivo — rollback de app usaria imagens `508021e8…` / `e18c20cb…` sem un-migration automática.

---

## 7. Riscos residuais

| Risco | Nota |
| --- | --- |
| Sem smoke de troca real | pendente janela autorizada com 2 jogadores |
| 3 invited legadas | só id canônico; resolvedor RC as aceita |
| Contador `#N` | inicia no primeiro create pós-033 |

---

## 8. Decisão

**SUCCESS técnico**

Infraestrutura e schema de trocas em produção conforme RC `f4e0972`.  
**Não** declarar sucesso funcional completo até validação autorizada de uma troca real.
