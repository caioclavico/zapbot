# Odisseu A6 — preflight final · RC trocas Pokémon (imagens carregadas)

**Status:** READY  
**UTC:** 2026-11-04T14:17Z → 14:22Z  
**Host:** `odisseu-core` (`x86_64`)  
**Autorizado nesta etapa:** preflight RO · transferência · `docker load` · verificação · plano de deploy futuro  
**Não executado:** migration 033 · deploy/recreate/restart · backfill · alteração de saves/propostas · mensagens WA

---

## 1. Identidade do RC

| Campo | Valor completo |
| --- | --- |
| Worktree | `/private/tmp/zapbot-trades-rc-a6` |
| Branch | `rc/a6-trades-ux` |
| Código nas imagens | `f4e0972046d360f29081c36184beaa23cd590e09` |
| Content-tree (labels) | `a76b14aeb865e677ac4cf867ee6419acee287402` |
| Tip branch (docs/scripts) | `e03ee8405d46fc580d085746bdae5ed7f1bb6317` |
| Diff tip ↔ `f4e0972` (excl. docs) | apenas `scripts/a6_033_trade_public_rehearsal.clj` (ensaio local; **fora** das imagens) |
| Migration | `migrations/pokemon/033-trade-public-id.edn` |

### Imagens homologadas (IDs completos)

| | Tag | Image ID |
| --- | --- | --- |
| Node RC | `odisseu-node:a6-trades-20261104T132934Z-f4e0972046d3-amd64` | `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` |
| JVM RC | `odisseu-jvm:a6-trades-20261104T132934Z-f4e0972046d3-amd64` | `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` |
| Arch | `linux/amd64` | labels `io.zapbot.git-sha=f4e0972…` · `io.zapbot.content-tree=a76b14ae…` · `fase=a6-trades-ux` |

### Tarballs

| Arquivo | SHA-256 | Tamanho |
| --- | --- | --- |
| `odisseu-node-amd64.tar` | `226a01717d9106d88793340066cb6d74bac64b8ef7fc4f0da6ab5e9f4fa419a7` | 371 MiB |
| `odisseu-jvm-amd64.tar` | `cf0f30e528ef40c204eccf11c61152d3352a70e68ee31cab20501b1014cd34a5` | 249 MiB |

Staging VM: `/home/caiohclavico/odisseu-recovery/a6-trades-rc-20261104T132934Z/`  
Exportação: **sem rebuild** — tars existentes reutilizados.

**Gate identidade:** Image IDs pós-`docker load` == IDs locais homologados · arch amd64 · labels OK · **PASS**

---

## 2. Preflight remoto

| Gate | Resultado |
| --- | --- |
| Hostname / arch | `odisseu-core` · `x86_64` |
| Node | healthy · tag `a6-critical-5088b5b` · ID `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` |
| WhatsApp | **READY** (`healthcheck`) |
| JVM | healthy · up · ID `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` |
| Raid auto | `false` (compose + env + `/health`) |
| `ALLOW_ALL_GROUPS` | `true` |
| Announcer | ausente |
| Volumes | `odisseu-a6-cutover-datomic` + journal/maint/data preservados |
| Compose | runtime `a6-cutover-runtime-20261101T141408Z` intacto |

Containers **continuam** nas imagens de produção atuais (nenhum recreate).

---

## 3. Backups e rollback

| Artefato | Estado |
| --- | --- |
| Session WA backup | `a6-whatsapp-session-backup-20261104T004608Z.tar.gz` · sha256 `b2b8125e290a2c67f64bf2a7ecd07d1d411df7688effd26783513952c4034151` |
| Session bind live | `a6-cutover-session-from-core` presente |
| Datomic periodic cold/warm 12832 | dirs `…12832-cold…` / `…12832-warm…` (~17 M cada) + manifests `.sha256` |
| Pacote JVM atual | `a6-fase12832-deploy-authorized/` (compose + tars images) |
| Tar Node hotfix | `odisseu-node-hotfix-5088b5b-amd64.tar` |
| Rollback Node (Docker) | `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` **presente** |
| Rollback JVM (Docker) | `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` **presente** |

**Não** foi gerado snapshot inconsistente do Datomic ativo; auditoria usou **cópia** RO em `/tmp`.  
**Não** houve restore.

Procedimento de restauração (referência, não executado): reload tags/IDs de rollback via compose; cold Datomic só sob incidente separado com GO destrutivo.

---

## 4. Capacidade

| Momento | Livres `/` |
| --- | --- |
| Preflight | **17 G** (65%) · 17 392 440 KiB |
| Após SCP tars | ~16 G · 16 739 640 KiB |
| Após `docker load` | **16 G** (67%) · 16 717 784 KiB |
| Inodes | 8% |

Tars 620 MiB + load com layers compartilhadas: margem ampla vs pico pessimista. **PASS**

---

## 5. Transferência e importação

1. SCP dos dois tars + `IMAGES.sha256` + manifesto → VM.  
2. SHA-256 na VM == esperado.  
3. `docker load -i` JVM depois Node.  
4. IDs pós-load:

| | ID verificado |
| --- | --- |
| JVM | `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570` |
| Node | `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` |

5. Labels `git-sha` / `content-tree` / arch confirmados.  
6. Imagens anteriores de rollback **preservadas**.  
7. Serviços live **inalterados** (ainda 5088b5b / e18c20cb).  
8. Tars **mantidos** no staging (sem exclusão).

---

## 6. Schema / propostas (somente leitura)

Cópia storage → query descartável (sem lock no live writer):

| Item | Valor |
| --- | --- |
| Migrations aplicadas | **32** · máx `pokemon/032-thematic-events` |
| `033` | **não** aplicada · attrs public-id **ausentes** |
| Trades | total **3** · estado `invited`×3 · public-id **0** |
| Não expiradas | **0** (todas expiradas) |
| IDs | canônicos longos (`false_…@g.us…`) |

Migration 033 (arquivo RC): somente `:trade/public-id`, `:trade/public-key`, `:trade-seq/chat-id`, `:trade-seq/next` — **sem** economia/bag.  
Compatibilidade: propostas antigas resolvem por id canônico; contador CAS por chat inicia no create pós-033; sem backfill silencioso.

**Não aplicada.**

---

## 7. Sequência do futuro deploy (somente com GO explícito)

### Ordem obrigatória (contratos reais)

1. **Preflight atualizado** (READY, disco, raid OFF, WA READY).  
2. **Backup verificável** (cold/warm Datomic + confirmar session-backup).  
3. **Controle de janela:** evitar `!pk troca*` mid-cutover (propostas atuais já expiradas; recomendar espera de 5 min se surgir invited viva).  
4. **JVM primeiro** → tag RC `…d07a379e…` · recreate **somente** JVM · health `up` · `raidAutoTickEnabled=false`.  
5. **Migration 033** via `schema/install!` (ou procedimento A6 equivalente) no storage operacional — **antes** do Node RC.  
6. Probe RO: attrs public-id presentes · migration-id `pokemon/033-trade-public-id`.  
7. **Node depois** → tag RC `…62414ffe…` · recreate **somente** Node · preservar sessão/Singletons · health WA **READY**.  
8. Smoke leitura: `!pk trocas` / health / JVM health.  
9. Smoke troca controlada em grupo piloto (create `#N` → aceitar → confirmar).  
10. Monitorar erros bridge/trade; encerrar janela.

### Compatibilidade entre versões

| Fase | OK? | Nota |
| --- | --- | --- |
| JVM old + Node old | produção atual | |
| JVM RC+033 + Node old | janela curta OK | Node antigo ignora public-id; store já pode alocar |
| JVM old + Node RC | **PROIBIDO** | Node exige UX/`#N` que store antigo não grava |
| Ambos RC | alvo | |

### Rollback de imagens (sem un-migration)

| Camada | Voltar para |
| --- | --- |
| Node | `sha256:508021e8fbd451319d07134f4f401ac699ea8b98cde102d51873df5aeb539cc5` (`a6-critical-5088b5b`) |
| JVM | `sha256:e18c20cb56ee39fc7efb40962a84df73028e3a3b6a9dd88edba6195ceebdd820` (`a6-fase12832-…-b99fb7863975-amd64`) |

Schema 033 aditivo permanece se já aplicado; código antigo ignora attrs. **Não** rollback automático de Datomic.

### Abortar o futuro GO se

- Image ID carregado ≠ IDs acima  
- WA não READY pós-Node  
- Migration 033 falhar  
- Raid auto ficar `true`  
- Disco &lt; 6 GiB livres no pré-cutover  

---

## 8. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| 3 invited expiradas | Inertes; resolvedor canônico |
| Tip branch ≠ image git-sha | Imagens ancoradas em `f4e0972`; tip só docs/ensaio |
| Tars (~620 M) ainda na VM | OK para re-load; apagar só com GO |
| Ordem Node antes JVM/033 | Explicitamente proibida |

---

## 9. Decisão

**READY**

- Imagens RC Node/JVM **carregadas e verificadas** na VM com IDs completos.  
- Serviços atuais **intactos** (hotfix Node + JVM 12832).  
- Schema ainda em **032**; 033 pronta e não aplicada.  
- Disco ~**16 GiB** livres.  
- Plano de deploy/rollback documentado.  

**Aguardando GO explícito de implantação.** Nenhum container foi recriado nesta preparação.
