# Odisseu A6 — homologação final · trocas Pokémon (deployment readiness)

**Status:** SUPERSEDED pelo deploy — ver `docs/pokemon-a6-trades-production-deploy.md`  
**Homologação:** 2026-11-04T13:50Z (UTC) · **Deploy produção:** 2026-11-04T14:25Z–14:33Z (UTC)  
**Escopo original:** RC `rc/a6-trades-ux` auditado contra produção `odisseu-core`  
**Pós-GO:** JVM/Node RC ativos · schema **033** · WhatsApp READY · classificação **SUCCESS técnico**

---

## 1. Preflight `odisseu-core` (read-only)

| Gate | Resultado |
| --- | --- |
| Host / arch | `odisseu-core` · `x86_64` |
| Runtime compose | `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/docker-compose.a6-cutover-runtime.yml` |
| Node container | `odisseu-a6-node` · **healthy** · image `odisseu-node:a6-critical-5088b5b` |
| Node Image ID | `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` |
| WhatsApp | **READY** (`healthcheck` → `{"status":"ok","whatsapp":"READY","chromium":true}`) |
| JVM container | `odisseu-a6-jvm` · **healthy** · `odisseu-jvm:a6-fase12832-20261103T174800Z-b99fb7863975-amd64` |
| JVM Image ID | `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` |
| JVM `/health` | `status=up` · `raidAutoTickEnabled=false` · `platformEnabled=true` · `readsOnly=false` |
| Datomic | volume `odisseu-a6-cutover-datomic` → `/data/datomic-local` (system `odisseu-m90-core` / db `core`) — acessível via cópia RO |
| Raid auto | Compose + env: `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`, `POKEMON_RAID_AUTO_TICK=false` · health confirma OFF |
| Grupos | `ALLOW_ALL_GROUPS=true` preservado (hotfix dispatcher) |
| Release Announcer | nenhum container announcer/release |
| Sessão WA backup | `a6-whatsapp-session-backup-20261104T004608Z.tar.gz` presente (mode 600) |
| Disco | **92%** · **~4.0–4.1G livres** em `/` — ver aborto |
| Inodes | 8% usados — OK |

**Observação:** porta Node publicada em `127.0.0.1:3003` (container `:3001`); health externo por hostport foi instável, mas `docker exec` healthcheck e logs confirmam READY.

---

## 2. Migration definitiva: **033-trade-public-id**

| Verificação | Resultado |
| --- | --- |
| Aplicadas em prod (cópia) | **001…032** (32) · máx `pokemon/032-thematic-events` |
| `030` operacional | `pokemon/030-raid-auto` — **ocupada** |
| `033` livre | `has.033=false` · attrs `:trade/public-id|public-key|trade-seq/*` ausentes |
| Conteúdo 033 | somente public-id/seq — **sem** attrs de economia/bag/ledger |
| Colisão / reserva | nenhuma migration `033-*` rival na linha operacional |
| Ensaio em cópia prod | `apply1=:applied` · `apply2=:already` · `COPY_REHEARSAL_OK` |
| Dados legados pós-033 | 3 trades permanecem **sem** `public-id` (sem backfill silencioso) |

Arquivo: `migrations/pokemon/033-trade-public-id.edn`  
Registry: `schema.clj` inclui 033 após 032.

**Não aplicada em produção nesta etapa.**

---

## 3. Auditoria de propostas (somente leitura)

Fonte: cópia sanitizada do storage (`/tmp/a6-trades-datomic-ro-20261104T134520Z` na VM) — **sem** transact no volume live.

| Métrica | Valor |
| --- | --- |
| Total trades | **3** |
| Estados | `invited` ×3 |
| Com `public-id` | **0** (schema ainda sem attr) |
| Abertas não expiradas | **0** (todas expiradas às ~2026-11-03/04; agora ≥13:45Z) |
| Forma do ID | canônico longo (`false_<chat>@g.us_…`, len ~108–121) |
| Chats | sufixo `…27684548487@g.us` (mesmo grupo amostrado) |

### Compatibilidade com o resolvedor RC

- IDs canônicos continuam resolvíveis por `trade/id` após 033.  
- Sem `public-id` histórico → **não** há `#N` inventado; risco de colisão de contador **nulo** (seq inicia em 1 no create pós-033).  
- Durante janela JVM-new / Node-old: Node antigo ignora public-id; creates novos no JVM-new já gravam `#N` (inofensivo).  
- Durante JVM-old / Node-new: Node-new pediria public-id que o store antigo não aloca → **abortar** essa ordem (JVM+migration **antes** do Node).

**Nenhuma** proposta foi aceita/cancelada/concluída/migrada nesta auditoria.

---

## 4. Identidade do RC

| Artefato | Valor |
| --- | --- |
| Worktree | `/private/tmp/zapbot-trades-rc-a6` |
| Branch | `rc/a6-trades-ux` |
| Tip | `1a380f5679af5cf8c0e73d5404ce050c461ee80c` |
| Código nas imagens | `f4e0972046d360f29081c36184beaa23cd590e09` |
| Content-tree (labels) | `a76b14aeb865e677ac4cf867ee6419acee287402` |
| Diff tip ↔ `f4e0972` (código) | **vazio** (somente `docs/pokemon-a6-trades-rc-isolation.md`) |
| Node Image ID | `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` |
| JVM Image ID | `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` |
| Arch | `linux/amd64` |
| Labels | `io.zapbot.git-sha=f4e0972…` · `io.zapbot.content-tree=a76b14ae…` · `fase=a6-trades-ux` |
| Tars | `/Users/caioclavico/odisseu-recovery/a6-trades-rc-20261104T132934Z/` · checksums em `checksums/IMAGES.sha256` |
| Manifesto | `RELEASE.manifest.md` no mesmo dir |

**Conclusão identidade:** tip posterior **não** contém código fora das imagens — **não** bloqueia o RC; rebuild **não** necessário.

Commits de código: `02f6d5f` (JVM/schema) → `1d8f794` (Node UX) → docs.

---

## 5. Resultados dos testes (descartável)

| Suite | Resultado |
| --- | --- |
| Clojure trocas (`a441` + `http-a443` + `public-id`) | **25 tests · 110 pass · 0 fail** |
| Fluxo create → `#N` → accept → complete (public-id) | coberto em `trade_public_id_test` |
| Idempotência / recovery de domínio | suites a441 + receipts |
| UX Node `pokemon-trade-ux.test.cjs` | **4/4 pass** (sem IDs canônicos nas mensagens) |
| Group allowlist | **28/28 pass** |
| Migration 033 em cópia prod | apply idempotente · legacy sem public-id · **OK** |
| E2E Node→bridge HTTP live-like | **não reexecutado** nesta homologação (harness `scripts/test-pokemon-trade-e2e.cjs` disponível pré-GO) |

Nenhum save de produção foi alterado.

---

## 6. Plano de implantação (somente com GO explícito)

### 6.1 Pré-GO

1. Liberar disco até **≥ 6 GiB** livres em `/` (ou confirmar após `docker load` que restam ≥2 GiB).  
2. Confirmar novamente: Node READY, JVM up, raid ticks **false**, Announcer ausente, `ALLOW_ALL_GROUPS` intacto.  
3. Backup verificável:  
   - cold/warm do volume Datomic `odisseu-a6-cutover-datomic`;  
   - confirmar backup de sessão WA existente ou renovar **sem** tocar Singletons live.  
4. Transferir tars RC e `docker load`; conferir Image IDs completos.  
5. Opcional: smoke E2E em storage descartável com imagens RC.

### 6.2 Sequência cutover

1. **JVM + migration 033 primeiro** (Node permanece `5088b5b`).  
   - Atualizar tag compose JVM → RC.  
   - Recreate **somente** JVM.  
   - Aplicar schema 033 pelo caminho operacional padrão (`install!` / procedimento A6).  
   - Health JVM: `up`, `raidAutoTickEnabled=false`, attrs public-id presentes (probe RO).  
2. Janela compatível JVM-new / Node-old: creates antigos sem `#N` na UX; store já pode gravar public-id se algum create passar pelo bridge — Node antigo pode ignorar o campo.  
3. **Node depois:** tag → `odisseu-node:a6-trades-…-amd64` · recreate **somente** Node · preservar mounts/sessão · **não** apagar Singleton*.  
4. Esperar healthcheck `whatsapp=READY`.  
5. Smoke piloto (grupo autorizado): `!pk troca` / `!pk trocas` / aceitar / confirmar — sem blast.

### 6.3 Comandos na janela

| Fase | Comportamento esperado |
| --- | --- |
| Antes de tudo | produção atual (IDs canônicos na UX) |
| JVM+033, Node old | domínio OK; UX ainda pode ecoar canônico |
| Ambos RC | `#N` + menções; canônico só interno |
| Falha mid-flight | ver rollback |

---

## 7. Rollback

### Imagens (aplicação)

| Camada | Rollback para |
| --- | --- |
| Node | `odisseu-node:a6-critical-5088b5b` · `sha256:508021e8…539cc5` |
| JVM | `odisseu-jvm:a6-fase12832-…-b99fb7863975-amd64` · `sha256:e18c20cb…ebdd820` |

Compose revert + recreate do serviço afetado; sessão WA preservada.

### Schema aditivo

- **Não** há rollback automático do Datomic após txs novas.  
- Atributos 033 podem permanecer após rollback de imagens (código antigo os ignora).  
- Propostas criadas no RC com `public-id` continuam válidas por id canônico no resolvedor antigo se o Node voltar.  
- **Não** remover attrs; **não** reescrever trades; **não** prometer un-migration.

### Dados / inventário

Rollback de app **não** desfaz transferências `completed`. Se corrupção de inventário for suspeita → abortar, restaurar backup Datomic verificado (procedimento destrutivo separado, fora deste RC).

---

## 8. Critérios de aborto

Abortar **antes** ou **durante** o GO se:

1. Disco livres **&lt; 2 GiB** após `docker load` ou antes do recreate.  
2. Image ID carregado ≠ `62414ffe…` (Node) / `d07a379e…` (JVM).  
3. Tip/código divergir com mudanças **não-docs** fora de `f4e0972` sem rebuild.  
4. Health JVM sem `up` ou `raidAutoTickEnabled=true` indevido.  
5. Node não atingir `whatsapp=READY` em tempo razoável pós-recreate.  
6. Migration 033 falhar ou aparecer colisão inesperada no registry live.  
7. Surge proposta `invited`/`accepted` **não expirada** em volume relevante sem plano de espera (5 min) / comunicação.  
8. Qualquer necessidade de mexer em Announcer, raid-auto ON, allowlist ou perfil Chromium para “fazer funcionar”.

---

## 9. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Disco ~4 G livres | Liberar espaço / monitorar load; abort se &lt;2 G |
| 3 invited expiradas | Inertes; resolvedor canônico; sem backfill |
| Ordem Node antes de JVM/033 | Proibida — create UX quebraria |
| E2E bridge não refeito aqui | Rodar `test-pokemon-trade-e2e.cjs` pré-GO se desejado |
| Contador public-id inicia em 1 | Esperado; sem colisão com legado sem public-id |

---

## 10. Decisão (homologação) → deploy

Homologação estava **CONDITIONAL** (disco/imagens). Após limpeza + load + **GO explícito**, o deploy foi executado.

**Resultado produção:** SUCCESS técnico — detalhes e evidências em `docs/pokemon-a6-trades-production-deploy.md`.  
Pendente apenas smoke de troca real entre jogadores (não autorizado nesta janela).
