# A6 — Fase 7.2: Preparação final do corte A5.6 → A6

**Branch worktree:** `feature/a6-raid-reconcile-fase5.1`  
**Data:** 2026-11-01  
**Base:** Fase 7 (integridade) + 7.1 (preflight)  
**Modo:** preparação documental + scripts gated — **sem** deploy, migrate live, restart de produção, merge ou escrita em produção.

---

## Decisão: **NO-GO** (pronto para autorização ≠ autorizado)

| Gate | Status |
| --- | --- |
| Procedimento freeze + cold T0 + restore | **Documentado** (`scripts/a6-cutover-t0-prep.sh`) — **não** executado nesta fase |
| Allowlist / `ALLOW_ALL_GROUPS=false` | **CONDITIONAL** — live ainda `ALLOW_ALL_GROUPS=true`; overlay exemplo preparado |
| Imagem A6 imutável com migrations 025–030 | **NO-GO parcial** — código-fonte tem 025–030; **artefato de corte ainda não buildado/pinado** |
| Manifestos / rollback / orçamento | Critérios definidos (Fases 6–7.1) |
| Autorização explícita de execução | **Ausente** |

**Classificação Fase 7.2: NO-GO** até checklist §5 estar 100% marcada e GO escrito.

---

## 1. Congelamento e backup T0

### 1.1 Ordem obrigatória

1. Congelar gameplay (aviso + sem features auto).  
2. Registrar ops em andamento (batalhas/raids/trades) via bridge RO.  
3. Parar **writer JVM** de forma controlada.  
4. Cold T0 dos volumes `a56-{datomic,journal,maint}`.  
5. Religar JVM A5.6 (ainda A5.6) **ou** manter parado se a janela de corte continuar imediatamente.  
6. Restore-check em diretório **isolado** (nunca montar no compose live).  
7. Manifesto Datomic na cópia restore.  
8. **Só então** qualquer escrita no destino A6 (volume novo / migrations).

### 1.2 Comandos exatos (Core — futuro, com `A6_CUTOVER_T0=1`)

```bash
# Pré: GO escrito; disk livre ≥ 2× tamanho do cold (~30–50M tipicamente + margem)
cd /home/caiohclavico/odisseu-core
# Copiar script do repo reconciliado para a VM se ainda não estiver lá:
#   scp -i ~/.ssh/odisseu_core scripts/a6-cutover-t0-prep.sh caiohclavico@35.192.113.146:~/odisseu-recovery/

chmod +x ~/odisseu-recovery/a6-cutover-t0-prep.sh
A6_CUTOVER_T0=1 ~/odisseu-recovery/a6-cutover-t0-prep.sh
# Emite: a6-cutover-t0-cold-<STAMP>/ + restore-check + hashes
```

Manifesto na cópia (Mac ou host com Clojure — **só leitura** do restore-check):

```bash
cd /path/to/zapbot-raid-reconcile
STAMP=<stamp-do-T0>
RC=$HOME/odisseu-recovery/a6-cutover-t0-restore-check-$STAMP
ODISSEU_PROBE_STORAGE=$RC/datomic \
ODISSEU_PROBE_SYSTEM=odisseu-m90-core \
ODISSEU_PROBE_DB=core \
ODISSEU_FASE7_OUT=$HOME/odisseu-recovery/a6-cutover-t0-manifest-$STAMP.edn \
ODISSEU_FASE7_LABEL=cutover-t0 \
clojure -M -e '(load-file "scripts/a6_fase7_integrity_manifest.clj")'

# Comparar com baseline Fase 7 (drift só de gameplay entre agora e T0):
ODISSEU_PROBE_STORAGE=$RC/datomic \
ODISSEU_FASE7_OUT=$HOME/odisseu-recovery/a6-cutover-t0-manifest-$STAMP.after-probe.edn \
ODISSEU_FASE7_COMPARE=$HOME/odisseu-recovery/a6-fase7/manifest-src-restore.edn \
ODISSEU_FASE7_LABEL=cutover-t0-vs-fase7 \
clojure -M -e '(load-file "scripts/a6_fase7_integrity_manifest.clj")'
```

**Proibido antes do restore verificado:** `transact` de schema no live, `up` A6 no volume `a56`, `down -v`.

Referência ensaiada: cold Fase 7 `a6-fase7-cold-20261101T132844Z` (17=17) — **não** substitui T0 do dia do corte.

---

## 2. Grupos WhatsApp (inventário RO)

### 2.1 Sessão e identidade (live)

| Campo | Valor |
| --- | --- |
| Sessão path | `/home/caiohclavico/odisseu-core/.wwebjs_auth_core` · `session-core` (~4.2 M) |
| `WWEBJS_CLIENT_ID` | `core` |
| `APP_ENV` | `staging` |
| `BOT_NAME` | `Odisseu` |
| `DEV_GROUP_ID` | `120363427684548487@g.us` |
| chat-hash (sha12 `id:…`) | `7f683fafa410` |
| `STAGING_GROUP_CONFIRMED` | `1` |
| `STAGING_WHATSAPP_CONFIRM` | `1` |
| `STAGING_ALLOW_QR` | `0` |
| `STAGING_BOOTSTRAP_GROUP` | `0` |
| `STAGING_ALLOWED_USERS` | **vazio** |
| `STAGING_STRICT_USER_ALLOWLIST` | **`0`** |
| `ALLOW_ALL_GROUPS` | **`true`** ← bloqueio de herança |
| `groups.json` | **ausente** no volume `a56-data` |

Com `ALLOW_ALL_GROUPS=true` + allowlist vazia, o bot efetivamente atende **qualquer** grupo da sessão — não há inventário fechado em arquivo. O único ID **explicitamente** configurado é o `DEV_GROUP_ID` acima (grupo piloto/documentado nas fases A6.4).

### 2.2 Allowlist proposta para o corte (não aplicada)

Preencher **manualmente** no `.env` de corte (sem auto-descoberta):

```bash
ALLOW_ALL_GROUPS=false
STAGING_STRICT_USER_ALLOWLIST=1
STAGING_GROUP_CONFIRMED=1
DEV_GROUP_ID=120363427684548487@g.us
# Operadores (LIDs / @c.us) — CONFIRMAR um a um; não popular automaticamente:
STAGING_ALLOWED_USERS=<lid1>,<lid2>
POKEMON_RAID_AUTO_TICK=false
ODISSEU_BRIDGE_RAID_AUTO_TICK=false
```

Overlay YAML: `docker-compose.release-a6-cutover.example.yml` (força `ALLOW_ALL_GROUPS=false` e ticks OFF).

### 2.3 Validação obrigatória antes do GO

- [ ] Confirmar que `DEV_GROUP_ID` ainda é o grupo piloto desejado (hash `7f683fafa410`).  
- [ ] Listar **explicitamente** cada grupo adicional (se houver) — default: **só** o piloto.  
- [ ] Confirmar identidade da sessão `clientId=core` / path `_core` (ou decidir sessão A6 dedicada — GO separado).  
- [ ] `ALLOW_ALL_GROUPS=false` no compose de corte **e** no `printenv` pós-boot.  
- [ ] Raides auto OFF (Node + JVM).

**Não** adicionar grupos automaticamente a partir de discovery/WA.

---

## 3. Imagem definitiva A6

### 3.1 Código-fonte (este worktree)

| Item | Evidência |
| --- | --- |
| Migrations 025–030 em `schema.clj` | `025` … `030-raid-auto` listadas em `install!` |
| Arquivos EDN | `migrations/pokemon/025`–`030` presentes |
| Módulos reconciliados | raides + save-slots + economy + bag + idle (Fases 5.1–5.5) |
| Compose fail-closed | `docker-compose.a6-staging.yml` |

### 3.2 Artefatos **hoje** na VM (A5.6 live — não são o corte A6)

| Container | Tag | Image ID (imutável) |
| --- | --- | --- |
| `odisseu-core-jvm` | `odisseu-jvm:a64-pvp-seats-xp-20261031T163814Z-amd64` | `sha256:c8186f7f0f1a1383b7603e244eea54cf8b67d9eb471ede5a713825f8cae084f1` |
| `odisseu-core-node` | `odisseu-node:a64-pvp-seats-stable-20261031T165616Z-amd64` | `sha256:4a5f2e1ac5f0d11d4237a854bb560a8d5d47395e57e07980b95371474b2ab492` |

Estas imagens **não** estão pinadas como “A6 cutover” e **não** devem ser usadas com tag móvel `latest` / `a6-staging-local` no corte.

### 3.3 Build + pin imutável (comandos futuros — não executar agora)

```bash
# Na VM amd64, a partir do commit reconciliado APROVADO:
cd /path/to/repo   # tree com 025–030 + raid reconcile
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
GIT=$(git rev-parse --short=12 HEAD)
TAG_JVM="odisseu-jvm:a6-cutover-${STAMP}-${GIT}-amd64"
TAG_NODE="odisseu-node:a6-cutover-${STAMP}-${GIT}-amd64"

docker build --platform linux/amd64 -f Dockerfile.jvm -t "$TAG_JVM" .
docker build --platform linux/amd64 -f Dockerfile -t "$TAG_NODE" .

JVM_ID=$(docker image inspect "$TAG_JVM" --format '{{.Id}}')
NODE_ID=$(docker image inspect "$TAG_NODE" --format '{{.Id}}')
echo "PIN_JVM=$JVM_ID"
echo "PIN_NODE=$NODE_ID"

# Verificar migrations embutidas (exemplo: listar resources no jar / health capabilities pós-boot isolado)
# Nunca usar: odisseu-jvm:latest | a6-staging-local | tags sem stamp
```

No overlay de corte, preferir:

```yaml
image: odisseu-jvm@sha256:<digest-sem-prefixo-sha256-se-repo>
# ou tag imutável a6-cutover-<STAMP>-<GIT>-amd64 + registrar Id no relatório de GO
```

### 3.4 Validação de módulos (antes do GO de corte)

Reexecutar em CI/local (já feitos nas fases 5.x — repetir no **mesmo** commit do build):

```bash
./scripts/test-raid-save-slots-reconcile.sh
./scripts/test-a6-economy-capacity.sh
# + suítes raid homolog / auto conforme scripts do repo
bash scripts/a6-staging-static-audit.sh
```

Boot isolado (volume descartável ≠ a56) deve reportar migrations até `030` no manifesto.

---

## 4. Critérios de GO (todos obrigatórios)

O corte **só** pode começar quando:

| # | Critério | Como provar |
| --- | --- | --- |
| 1 | Origem congelada | JVM writer parado; ops drenadas/registradas |
| 2 | Backup T0 restaurado e verificado | `a6-cutover-t0-restore-check-*` filecount+sha256; Datomic abre |
| 3 | Manifestos equivalentes | compare T0 vs destino pré-liberação: sem missing players/pokemon/coins; só schema intencional |
| 4 | Allowlist confirmada | `ALLOW_ALL_GROUPS=false` + `DEV_GROUP` + users explícitos; printenv pós-boot |
| 5 | Memória no orçamento | Node ≤2560 MiB · JVM ≤768 MiB · available host ≥400 MiB · swap estável (Fase 7.1) |
| 6 | Rollback validado | cold T0 + procedimento §5 Fase 7.1; volumes a56 preservados |
| 7 | Autorização explícita | GO escrito do responsável |
| 8 | Imagem pinada | tags `a6-cutover-…` + image Id/digest registrados |

Qualquer falha → **NO-GO**.

---

## 5. Checklist final operacional

### Antes do dia D

- [ ] Commit/tree A6 reconciliado escolhido e registrado (`git rev-parse HEAD`)  
- [ ] Build JVM+Node com stamp+git; anotar `Id=sha256:…`  
- [ ] Overlay corte a partir de `docker-compose.release-a6-cutover.example.yml` (sem `ALLOW_ALL=true`)  
- [ ] `.env` de corte com allowlist preenchida manualmente  
- [ ] Volume destino A6 preparado (recomendado: novo a partir do T0)  
- [ ] Script `a6-cutover-t0-prep.sh` na VM  
- [ ] Runbook rollback impresso/acessível  

### Dia D (após GO escrito)

- [ ] Congelar gameplay  
- [ ] `A6_CUTOVER_T0=1 ./a6-cutover-t0-prep.sh`  
- [ ] Manifesto T0 + compare  
- [ ] Pendências: confirmar `{}` ou plano manual (Fase 7.1)  
- [ ] Aplicar schema/destino **só** no volume preparado  
- [ ] Subir A6 com imagens pinadas + fail-closed  
- [ ] Manifesto pós-boot ≡ T0 (dados)  
- [ ] Smoke controlado (sem auto-raid, sem broadcast)  
- [ ] Liberar só após validação  

### Proibições permanentes nesta preparação

- Deploy / migrate / restart / merge **agora**  
- Tags mutáveis (`latest`, `a6-staging-local`) no corte  
- Herdar `ALLOW_ALL_GROUPS=true`  
- Colocalizar duas stacks completas  
- Rollback automático após escrita A6 sem reconciliar  

---

## 6. Resumo GO/NO-GO

| Pergunta | Resposta |
| --- | --- |
| Preparação documental completa? | **Sim** |
| Pode executar o corte agora? | **NÃO** |
| Bloqueios principais | (1) imagem A6 cutover não pinada; (2) live ainda allow-all; (3) T0 do dia não gerado; (4) falta GO escrito |
| Próximo passo após GO | Executar §1 T0 → build pin §3 → overlay §2 → corte sob Fase 7.1 roteiro |

---

## 7. Artefatos

| Item | Path |
| --- | --- |
| Este relatório | `docs/pokemon-a6-cutover-prep-fase7.2.md` |
| Script T0 | `scripts/a6-cutover-t0-prep.sh` |
| Overlay exemplo | `docker-compose.release-a6-cutover.example.yml` |
| Preflight 7.1 | `docs/pokemon-a6-cutover-preflight-fase7.1.md` |
| Integridade 7 | `docs/pokemon-a6-save-integrity-fase7.md` |
