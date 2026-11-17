# A6.4 — Runbook operacional de produção (preparação)

**Status:** preparado · **execução em produção: NÃO autorizada neste documento.**  
Branch: `a6-save-slots`. Atualizado: 2026-10-28.

Este runbook cobre a recuperação dos **12** treinadores / **39** Pokémon aprovados
no ensaio descartável. Não inclui os **9** pending nem o excluído `37391862b553`.

Referências: `docs/pokemon-a6-4-reconciliation-matrix.md`,
`docs/pokemon-a6-4-deploy-plan.md`, `docs/pokemon-a6-4-core-ssh-access-report.md`.  
Manifesto imutável privado: `~/odisseu-recovery/a64-reconcile/manifest-immutable-12.edn`
(`content-hash` `2acc4689…df812`).

---

## 0. Restrições vigentes até nova autorização

| Proibido agora | Motivo |
|----------------|--------|
| Deploy / restart de containers de produção | Esta etapa é só preparação |
| Migration 025 no volume live | Ordem documentada; aplicar só após Go |
| `confirm!` / import admin em produção | Aguarda autorização explícita |
| Escrita Cassandra (qualquer DML/DDL) | Histórico é SELECT-only |
| Alterar / remontar / `down -v` volumes `odisseu-core-a56-*` | Rollback e dados platform |
| Incluir pending, ambíguos ou excluído | Fora do manifesto |

---

## 1. Inventário Core A5.6 (estado de referência)

Inventário conforme diagnóstico RO + cold backup (`1273fa4` / relatório SSH).
**Revalidar com os comandos da §9 imediatamente antes do Go** — valores abaixo são
o baseline documentado, não uma leitura live desta etapa.

| Item | Valor de referência |
|------|---------------------|
| Host | `odisseu-core` · `35.192.113.146` |
| SSH | `ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146` |
| Workdir | `/home/caiohclavico/odisseu-core` |
| Compose | `docker-compose.odisseu-core.yml` + `docker-compose.release-2cab639.yml` (+ allow-all-groups) |
| Imagem JVM | `odisseu-jvm:2cab639-amd64` |
| Imagem Node | release `2cab639` (profile `with-node`) |
| Env Node | `APP_ENV=staging`, `POKEMON_ADAPTER=jvm`, `PLATFORM_ADAPTER=datomic` |
| Cassandra no Core | `CASSANDRA_KEYSPACE=zapbot_core_unused` @ `127.0.0.1` (não é Oracle) |
| Volume Datomic montado | **`odisseu-core-a56-datomic` → `/data/datomic-local`** |
| Volumes auxiliares A5.6 | `odisseu-core-a56-journal`, `…-maint`, `…-data` (nomes a confirmar no `docker volume ls`) |
| Volumes **não** montados | `odisseu-core-datomic` / journal / maint (legado pré-A5.6) |
| Auth WhatsApp | `/home/caiohclavico/odisseu-core/.wwebjs_auth_core` |
| Schema `core` (live baseline) | migrations **001–024** (`024-gym-history`); **025 ausente** |
| Treinadores / saves / Pokémon no `core` live | **0 / 0 / 0** |
| Platform no mesmo volume | ~151 `platform.participant` (≠ treinadores) |
| Cold backup existente | `~/odisseu-recovery/a64-preflight/a56-cold-20261027T213056Z/` |
| 025 ensaiada | só em cópias `a56-025-rehearsal` / `rehearsal-batch-*` |

---

## 2. Migration 025 × release A6 — compatibilidade e ordem

### Compatibilidade

- Arquivo: `migrations/pokemon/025-save-slot-metadata.edn`
- Atributos **opcionais** (`:save/display-name`, `:save/origin`, `:save/origin-player-id`,
  `:save/legacy-version`, `:save/import-id`, …) — saves `a`/`b` existentes seguem válidos.
- Importador A6.3/A6.4 grava metadados de proveniência nesses attrs.
- Ensaio: 025 aplicada em cópia A5.6 vazia + import 12/12 ok.

### Ordem correta (produção — quando autorizada)

1. **Backup frio** do volume live (§3) + verificação de integridade.  
2. Build/tag imagens A6 (`a6-save-slots` SHA) preservando **mesmos nomes** de volume.  
3. Deploy código A6 (JVM+Node) **sem** `-v` / sem recriar volumes.  
4. Health: bridge + Node + WhatsApp READY; `recoveryCatalogReady` conforme config.  
5. **Só então** aplicar migration 025 no Datomic **live** (via boot/`schema/install!`
   do release A6 — uma vez, idempotente).  
6. Validar attrs 025 presentes; players ainda 0 (ou estado pré-import).  
7. Montar dump autorizado dos 12 (§4–5) RO; import admin **somente** manifesto.  
8. Pós-checagens (§6); sem ativar `recovery-*`.

**Não** aplicar 025 antes do backup frio.  
**Não** importar antes de 025 estar no schema live (attrs de proveniência).

---

## 3. Backup frio produtivo + restore ensaiado

### Procedimento (produção — futuro)

```bash
# No Core — NÃO executar agora
cd /home/caiohclavico/odisseu-core
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
BK=/home/caiohclavico/odisseu-recovery/a64-prod-cold-${STAMP}

# 1) Parar somente JVM (writer Datomic); Node pode ficar up se política permitir
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml -f docker-compose.release-2cab639.yml \
  stop jvm   # nome do serviço — confirmar no compose

# 2) Cópia RO do volume (Alpine) → $BK
docker run --rm -v odisseu-core-a56-datomic:/data:ro -v "$BK":/out alpine \
  sh -c 'cp -a /data/. /out/'

# 3) Subir JVM de novo; health
docker compose ... start jvm
curl -sfS http://127.0.0.1:18090/health

# 4) Integridade
find "$BK" -type f | wc -l
sha256sum $(find "$BK" -type f | sort) | sha256sum > "$BK.manifest.sha256"
```

### Restore ensaiado (já validado em descartável)

Padrão usado no ensaio: `tar czf …pre.tgz` → extract em dir throwaway →
comparar contagem de arquivos (11=11) + presença de `…/core`.

Antes do Go de produção: **repetir** restore check do novo cold `$BK` em
`~/odisseu-recovery/a64-prod-restore-check-*` (nunca montar no compose live).

---

## 4. Manifesto imutável (12 / 39)

| Campo | Valor |
|-------|-------|
| `manifest-id` | `a64-prod-prep-12` |
| `content-hash` | `2acc4689f5460f425702e9b77511a08be7ceb32c6a8b9693b8420e04428df812` |
| Escopo | `chat\|player` |
| Versão legada | **v1** apenas |
| Esquema ID | `a64-stable-v1` (só unicidade comprovada) |
| Economia | **unknown** → hold; **não** persistir 0 |
| Excluídos | 9 pending + `37391862b553` |

### Entradas (hashes)

| # | player-hash | chat-hash | N Pokémon | account-fingerprint (prefix) | proposed-id-digest (prefix) |
|---|-------------|-----------|-----------|------------------------------|-----------------------------|
| 1 | `1d4a0c2cbc93` | `96a9790cf238` | 6 | `f6b6be2b…` | `ba915a62…` |
| 2 | `bacdc2275203` | `96a9790cf238` | 8 | `e41a8301…` | `231a7f24…` |
| 3 | `d15aaa1f4eaa` | `96a9790cf238` | 2 | `506eb1a7…` | `f5374e45…` |
| 4 | `6e8ad515a7cb` | `96a9790cf238` | 1 | `a0992943…` | `7bc64387…` |
| 5 | `3cf793a69b07` | `96a9790cf238` | 2 | `aa29d2ff…` | `9f5f4453…` |
| 6 | `ee6231ab35de` | `96a9790cf238` | 1 | `d89dc689…` | `0bbcdc8b…` |
| 7 | `9a4322c7bd26` | `96a9790cf238` | 10 | `f42dd73e…` | `5e278118…` |
| 8 | `bb1c8bf84402` | `96a9790cf238` | 5 | `81dd2762…` | `5d974bee…` |
| 9 | `724d1d63e716` | `96a9790cf238` | 1 | `7a26d9fb…` | `4c988e34…` |
| 10 | `74dfb2348c54` | `96a9790cf238` | 1 | `b09a988d…` | `913a8c86…` |
| 11 | `778f2dcd250c` | `96a9790cf238` | 1 | `f52bdb98…` | `0ccd893b…` |
| 12 | `7116331d2e99` | `96a9790cf238` | 1 | `c3366731…` | `9d043c45…` |

**Soma Pokémon = 39.** Proveniência: Cassandra V1 `legacy_estado_particionado` /
`treinador`, dump SELECT-only + `a64-stable-v1` aplicado offline no dump descartável.

Qualquer alteração no dump exige **novo** `content-hash` e re-aprovação.

---

## 5. Plano `discover` / `confirm!` (só os 12)

Quando autorizado:

1. Materializar dump de produção a partir do manifesto (mesmos 12, V1, ids estáveis
   já validados) → path RO na JVM (`ODISSEU_RECOVERY_DUMP_PATH`).
2. `ODISSEU_RECOVERY_IMPORT_TOKEN` forte; não logar.
3. Por jogador (ops/admin ou WA se fluxo ligado):
   - `discover` / `!pk recuperar` → prévia pública
   - seleção **v1** apenas
   - `player-confirm!` / `confirmar`
   - `POST /v1/recovery/import` com token + `import-batch-id` estável  
     `a64-prod|<player-hash>`
4. Allowlist: se `player-hash` ∉ manifesto → **recusar**.
5. Não processar pending / excluído / aliases sem evidência.
6. Após cada import: active inalterado; existe `recovery-v1`; contagem = N da tabela;
   retry → `:duplicate`.

---

## 6. Estratégia de deploy (janela) — futuro

### Janela sugerida

Manutenção curta (JVM stop só no backup frio + redeploy). Comunicar grupo: RPG
indisponível X minutos; recovery após health.

### Passos (resumo)

1. Go/No-go §9 completo.  
2. Cold backup + restore check.  
3. `docker buildx` imagens A6 AMD64; compose release com **mesmos** volumes A5.6.  
4. `up -d` sem `-v`.  
5. Health:  
   - `curl -sfS http://127.0.0.1:18090/health`  
   - Node WhatsApp READY  
   - migrations ≥ 025; attrs 025 presentes  
6. Montar dump RO; import dos 12.  
7. Validações pós-import (§5 + contagens globais).  

### Interromper se

- Volume names mudaram / `down -v` acidental  
- Health falha / WhatsApp não READY  
- 025 não aplica ou players inesperados pré-import  
- Contagem Pokémon ≠ manifesto / active muda / duplicação  
- Qualquer escrita Cassandra detectada  
- Jogador fora do allowlist

---

## 7. Rollback

| Camada | Ação |
|--------|------|
| Código | Redeploy imagens `2cab639-amd64` + compose anterior; **mesmos** volumes |
| Schema 025 | Atributos opcionais — em geral **não** reverter; compatível com A5.6 reads |
| Dados (import parcial) | Slots `recovery-*` podem permanecer; **não** apagar às cegas. Opções: (a) deixar inativos; (b) restore do cold `$BK` em volume **novo** e cutover controlado (só com autorização); (c) retry idempotente `:duplicate` após correção |
| Catálogo | Remover `ODISSEU_RECOVERY_*` para desligar discovery |
| WhatsApp | Não tocar `.wwebjs_auth_core` |
| Cassandra | Nenhuma ação (nunca houve escrita) |

Import parcial: registrar quais `player-hash` sucederam; retomar só os faltantes
com mesmo `import-batch-id` (idempotência).

---

## 8. Economia unknown

Para os 12 do manifesto:

- Status: **unknown**
- Ação: **hold-do-not-persist-zero**
- Importador / transform **não** devem gravar `moedas=0` por ausência de `loja`
- Divergent/known fora deste lote — tratados em fase futura
- Flag legada `:loja-ausente-moedas-zero` no IR: **ignorar** na recuperação A6

---

## 9. Checklist Go/No-go (comandos exatos)

Executar **somente** após autorização explícita de produção. Até lá: **No-go**.

### Pré-condições (leitura)

```bash
# SSH
ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146

cd /home/caiohclavico/odisseu-core
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml -f docker-compose.release-2cab639.yml ps

docker volume ls | grep odisseu-core-a56
# Esperado: a56-datomic (e auxiliares) presentes; sem prune recente

curl -sfS http://127.0.0.1:18090/health
# Esperado: HTTP 200; anotar recoveryCatalogReady

# Schema (via ferramenta JVM/RO já usada no diagnóstico) — Esperado: 24 migrations, 025 ausente
# Players core — Esperado: 0

# Manifesto local (Mac)
test -f ~/odisseu-recovery/a64-reconcile/manifest-immutable-12.edn
# content-hash deve ser 2acc4689f5460f425702e9b77511a08be7ceb32c6a8b9693b8420e04428df812
```

| # | Pré-condição | Esperado | ☐ |
|---|--------------|----------|---|
| 1 | Volumes `a56-*` intactos | nomes iguais ao baseline | ☐ |
| 2 | Cold backup novo + restore check | contagens iguais; sha256 manifesto | ☐ |
| 3 | Imagens A6 buildadas | tags SHA; amd64 | ☐ |
| 4 | Compose release **sem** novos volume names | diff review | ☐ |
| 5 | Dump = manifesto 12/39 | content-hash confere | ☐ |
| 6 | Token import configurado | presente; não commitado | ☐ |
| 7 | Allowlist = só 12 hashes | pending/excluído fora | ☐ |
| 8 | Economia hold unknown | sem persistir 0 | ☐ |
| 9 | Janela comunicada | time ciente | ☐ |
| 10 | Autorização explícita “Go produção A6.4” | mensagem do operador | ☐ |

**Go** somente se todos ☐. Qualquer falha → **No-go** e rollback §7.

### Pós-import (quando executado)

```bash
# Por trainer do manifesto: recovery-v1 count == N; active == a (ou ativo prévio)
# Retry import → duplicate
# Cassandra: sem novas escritas (monitorar se houver métrica/audit)
# WhatsApp READY
```

---

## 10. Estado desta etapa

| Item | Status |
|------|--------|
| Runbook | **entregue** |
| Fase 1 preflight | **concluída** (2026-10-28T20:51Z) |
| Fase 2 backup frio + restore | **concluída** (`a64-prod-cold-20261028T205213Z`) |
| Deploy / 025 live / confirm! prod | **não executado** — checkpoint antes da fase 3 |
| Cassandra write | **não** |
| Volumes prod | **inalterados** (cópia RO apenas) |
| Evidência | `~/odisseu-recovery/a64-reconcile/checkpoint-phase1-2.edn` |
| Próximo passo | Aguardar autorização explícita para **fase 3** (deploy A6) |
