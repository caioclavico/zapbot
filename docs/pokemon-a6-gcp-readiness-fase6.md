# A6 — Fase 6: Readiness de infraestrutura na Google Cloud

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-11-01  
**Base:** versão reconciliada + homologada até Fase 5.5  
**Modo:** auditoria **somente leitura** + documentação — **sem** provisionar, deploy, migrate remoto, firewall, merge, restart, alteração no Core A5.6 ou WhatsApp  

---

## Decisão: **CONDITIONAL**

A arquitetura A6 isolada está pronta para **autorização** de uma VM GCP exclusiva. Colocar Node+JVM A6 na VM Core atual é **desaconselhado** (RAM). Nada foi provisionado nesta fase.

| Critério | Status |
| --- | --- |
| Levantamento GCP/Core (RO) | **PASS** |
| Requisitos A6 dimensionados | **PASS** |
| Necessidade de VM nova | **SIM** (recomendado) |
| Arquitetura isolada documentada | **PASS** |
| Segurança fail-closed documentada | **PASS** |
| Procedimentos operacionais (não executados) | **PASS** |
| Homologação software (Fase 5.5) | **READY** (pré-requisito ok) |
| Provisionamento / deploy | **não autorizado nesta fase** |
| Grupo piloto / conta WA teste confirmados | **CONDITIONAL** (pendente) |
| Aprovação de custo GCP | **CONDITIONAL** (pendente) |

---

## 1. Levantamento (somente leitura)

### 1.1 Projeto e região (metadata da VM Core)

| Campo | Valor |
| --- | --- |
| Project ID | `project-e8cd7809-1145-4be9-b8d` |
| Project number | `320125207473` |
| Zona | `us-central1-a` |
| Região | `us-central1` |
| Rede VPC | `default` |
| Instância | `odisseu-core` |
| IP externo (SSH conhecido) | `35.192.113.146` |
| OS | Ubuntu 24.04.5 LTS · kernel `…-gcp` |
| Machine type | **`e2-custom-2-6144`** (2 vCPU · 6 GiB) |

Fonte: `http://metadata.google.internal/…` via SSH BatchMode RO · `2026-11-01`.

### 1.2 Recursos da VM Core (live)

| Recurso | Evidência |
| --- | --- |
| CPU | 2 vCPU (Xeon @ 2.20 GHz) |
| RAM | 5.8 GiB total · ~3.3 GiB available |
| Swap | 2.0 GiB (~152 MiB usados) |
| Disco | `sda` 50 G · `/` ~48 G · **~19 G livres (61%)** |
| Node | `odisseu-core-node` · ~1.28 / 2.5 GiB · CPU variável alta |
| JVM | `odisseu-core-jvm` · ~284 / 768 MiB |
| Docker images | ~9.2 GB total · build cache ~11 GB reclaimable |
| Volumes A5.6 | `odisseu-core-a56-{datomic,journal,maint,data}` |
| Portas loopback | `127.0.0.1:18090`, `:3001` |

### 1.3 Requisitos da stack A6 (compose homologado)

| Componente | Limite Compose | Uso típico observado (lab/Core) |
| --- | --- | --- |
| JVM bridge | `mem_limit` 512 MiB · `-Xmx320m` · 1 CPU | ~250–320 MiB |
| Node + Chromium | `mem_limit` 2560 MiB · 1.5 CPU · heap 384 MiB | ~1.1–1.5 GiB |
| SO + Docker | — | ~0.8–1.5 GiB |
| **Total confortável** | | **~4–6 GiB RAM · 2 vCPU** |
| Disco imagens + logs + Datomic | | **≥ 30 GiB** (recomendado **40–50 GiB**) |
| Rede bridge | compose `a6-*-internal` · portas só loopback host | |

Identidade Datomic A6 (nunca Core): system `odisseu-a6-staging` · db `core-a6-staging` · volumes `odisseu-a6-staging-*`.

### 1.4 Necessidade de nova VM

| Opção | Viabilidade | Nota |
| --- | --- | --- |
| **Colocar A6 completo na Core** | **Não recomendado** | Core já usa ~1.5 GiB container + available ~3.3 GiB; Node A6 sozinho pede até 2.5 GiB → risco de OOM/swap |
| JVM-only A6 na Core | Possível tecnicamente | Sem WhatsApp; útil só para smoke JVM; **não** cobre piloto com sessão WA |
| **VM GCP exclusiva A6** | **Recomendado** | Isola volumes, sessão WA, falhas e custos; preserva Core A5.6 |

### 1.5 Custos estimados (lista on-demand `us-central1`, ~USD/mês)

Estimativas **aproximadas** de preço de lista (730 h), **sem** egress, IP estático extra, snapshots frequentes ou Sustained Use/CUD. Validar no Billing Calculator antes de aprovar.

| Configuração | Compute (aprox.) | Disco pd-balanced | Total aprox. |
| --- | --- | --- | --- |
| **A6 recomendado:** `e2-standard-2` (2 vCPU / 8 GiB) + 40 GB | ~$49 | ~$4 | **~$53/mês** |
| **A6 lean:** `e2-custom-2-4096` (2 vCPU / 4 GiB) + 30 GB | ~$31 | ~$3 | **~$34/mês** |
| **A6 mínimo arriscado:** `e2-medium` (1 vCPU / 4 GiB) + 30 GB | ~$24 | ~$3 | **~$28/mês** — Chromium pode sofrer |
| Core atual (referência): `e2-custom-2-6144` + 50 GB | ~$34 | ~$5 | **~$39/mês** |

**Alternativas de dimensionamento:**

1. Spot/preemptible — barato, **inadequado** para sessão WhatsApp persistente.  
2. CUD 1 ano em `e2-standard-2` — reduz compute se o piloto virar permanente.  
3. Disco menor (30 GB) no início + expandir se build cache/Datomic crescer.  
4. Manter A6 só Mac local — $0 GCP, sem piloto WA remoto.

---

## 2. Arquitetura proposta (VM exclusiva)

```text
                    [SSH admin :22]          (sem portas app públicas)
                           |
                    ┌──────▼──────┐
                    │  odisseu-a6 │  e2-standard-2 · us-central1-*
                    │  (Ubuntu)   │
                    │             │
                    │  Docker Compose project: odisseu-a6-staging
                    │  ┌─────────────────┐   ┌──────────────────┐
                    │  │ a6-node         │──▶│ a6-jvm           │
                    │  │ :3001 loopback  │   │ :18090 interno   │
                    │  │ WA sessão A6    │   │ Datomic Local    │
                    │  └────────┬────────┘   └────────┬─────────┘
                    │           │ rede a6-staging_internal
                    │  volumes: datomic · journal · maint · data
                    └──────────────────────────────────────────┘
                              ≠ odisseu-core / a56-*
```

### 2.1 Componentes

| Peça | Especificação |
| --- | --- |
| Node + JVM | Imagens buildadas **na VM amd64** a partir do repo reconciliado (Fase 5.5) |
| Datomic Local | Volume Docker `odisseu-a6-staging-datomic`; system/db exclusivos |
| Persistência | `odisseu-a6-staging-{datomic,journal,maint,data}` + host bind `.wwebjs_auth_a6_staging` |
| Bridge HTTP | Bind `0.0.0.0:18090` **só** na rede Docker; publish host `127.0.0.1:18091:18090` |
| Node health | `127.0.0.1:3002:3001` |
| Firewall GCP | Ingress: SSH (IP admin) apenas; **sem** 18091/3002 na internet |
| Backups | Snapshots de disco + `docker run` cópia fria dos volumes A6 (JVM parado); retenção ≥ 7 dias |
| Observabilidade | `docker logs` json-file (já no compose); health `/health` + `/jvm-health`; opcional Agent Ops/Cloud Logging depois |
| Sessão WhatsApp | Path/clientId **novos**; conta de **teste**; nunca copiar `.wwebjs_auth_core` |

### 2.2 Artefatos de software já prontos

- `docker-compose.a6-staging.yml`  
- `.env.a6-staging.example`  
- `scripts/a6-staging-static-audit.sh`  
- `scripts/a6-local-homolog-fase5.4.cjs` / `a6-temporal-outbox-fase5.5.cjs`  
- Guardrail storage: recusa paths a56 / `odisseu-m90-core`

---

## 3. Segurança (obrigatório no piloto)

| Regra | Default |
| --- | --- |
| Não reutilizar banco Core A5.6 | system/db/volumes A6 distintos |
| Não compartilhar volumes/dirs | sem mounts `odisseu-core-a56-*` |
| Não compartilhar sessão WA | `WWEBJS_CLIENT_ID=a6-staging` · path `_a6_staging` |
| `ALLOW_ALL_GROUPS` | **`false`** |
| Raides automáticas | `POKEMON_RAID_AUTO_TICK=false` · `ODISSEU_BRIDGE_RAID_AUTO_TICK=false` até liberação explícita |
| Grupos | `STAGING_GROUP_CONFIRMED=0` até confirmação; só `DEV_GROUP_ID` piloto |
| Mensagens | Não enviar a grupos reais; bootstrap/QR só conta teste |
| Secrets | Token bridge **novo** (`openssl rand -hex 32`); nunca copiar `.env` do Core |
| Rede | App bind loopback no host; firewall sem publish público da bridge |

---

## 4. Preparação operacional (documentar — **não executar agora**)

### 4.1 Provisionar a VM (futuro)

1. Aprovar custo (~$35–55/mês) e nome `odisseu-a6` (ou similar).  
2. `gcloud compute instances create odisseu-a6 --zone=us-central1-a --machine-type=e2-standard-2 --boot-disk-size=40GB --boot-disk-type=pd-balanced --image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud --tags=odisseu-a6,ssh`.  
3. Firewall: allow `tcp:22` from IPs admin → tag `odisseu-a6`; **não** abrir 18091/3002.  
4. Criar user + chave SSH dedicada (não reutilizar de forma insegura).

### 4.2 Instalar Docker (futuro)

```bash
# na VM A6
sudo apt-get update && sudo apt-get install -y ca-certificates curl
# instalar Docker Engine + Compose plugin (docs oficiais Ubuntu)
sudo usermod -aG docker $USER
```

### 4.3 Criar volumes (futuro)

Compose cria automaticamente `odisseu-a6-staging-*` no primeiro `up`. Não importar/copiar volumes Core.

### 4.4 Implantar imagens A6 (futuro)

```bash
git clone … && cd repo   # commit reconciliado pós-5.5
cp .env.a6-staging.example .env.a6-staging
# preencher token NOVO; manter fail-closed / tick OFF / WA disabled até checklist
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging build
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging up -d
# depois, com conta teste: --profile with-node
```

### 4.5 Inicializar banco isolado (futuro)

Primeiro start da JVM aplica migrations 001–030 em volume **vazio** `core-a6-staging`. Confirmar health:

```bash
curl -fsS http://127.0.0.1:18091/health
# raidAutoTickEnabled deve ser false
```

### 4.6 Smoke tests (futuro)

```bash
bash scripts/a6-staging-static-audit.sh
ODISSEU_BRIDGE_TOKEN=… node scripts/a6-local-homolog-fase5.4.cjs
# opcional lab: temporal/outbox 5.5 com tick ON só sob janela controlada
```

### 4.7 Grupo piloto autorizado (futuro)

1. Conta WhatsApp Business de **teste**.  
2. `STAGING_ALLOW_QR=1` temporário → parear → desligar QR.  
3. Descobrir `DEV_GROUP_ID` (bootstrap se necessário).  
4. Allowlist usuários; `STAGING_GROUP_CONFIRMED=1` só após revisão.  
5. Manter `ALLOW_ALL_GROUPS=false` e tick OFF até liberação de raides.

### 4.8 Pausar e recuperar (futuro)

```bash
# pausar
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node stop
# NÃO usar down -v em operação normal

# backup frio (JVM parado) → tar volumes / snapshot disco
# recuperar
docker compose … up -d
curl -fsS http://127.0.0.1:18091/health
curl -fsS http://127.0.0.1:3002/health
```

---

## 5. Checklist de autorização (go / no-go)

Antes de qualquer provisionamento:

- [ ] Aprovar VM nova em `us-central1` (não colidir com Core).  
- [ ] Aprovar faixa de custo (~$35–55/mês on-demand).  
- [ ] Confirmar que Core A5.6 permanece intocado (sem mounts cruzados).  
- [ ] Definir conta WhatsApp de teste (≠ produção/Core).  
- [ ] Definir grupo piloto e donos da allowlist.  
- [ ] Confirmar tick auto **OFF** no primeiro go-live.  
- [ ] Confirmar `ALLOW_ALL_GROUPS=false`.  
- [ ] Plano de backup/snapshot antes do primeiro `up` com dados reais de teste.  
- [ ] Janela de smoke + rollback (`stop`, sem `down -v`).

---

## 6. Riscos

| Risco | Mitigação |
| --- | --- |
| OOM se A6 for para a Core | VM exclusiva |
| Copiar sessão/env Core por engano | Templates A6; paths/clientId distintos; audit script |
| Abrir bridge na internet | Firewall só SSH; publish `127.0.0.1` |
| Ligar tick cedo demais | Flags OFF + checklist |
| Disco da Core cheio (build cache) | Não buildar A6 na Core; limpar cache só com aprovação |
| Custo surpresa | Alertas Billing; disco 40 GB; evitar Spot para WA |

---

## 7. Relação com fases anteriores

| Fase | Resultado relevante |
| --- | --- |
| 5.2 | BLOCKED piloto no Core (volume a56 + allow-all) |
| 5.3 | Compose isolado preparado |
| 5.4 | Homolog HTTP local Mac |
| 5.5 | Scheduler/outbox/recovery READY em lab sintético |
| **6** | **Readiness GCP — CONDITIONAL até autorizar VM + piloto** |

---

## 8. Próximo passo sugerido (fora desta fase)

1. Aprovar checklist §5.  
2. Provisionar `odisseu-a6` (`e2-standard-2` + 40 GB).  
3. Deploy compose A6 fail-closed.  
4. Smoke 5.4 → pareamento WA teste → grupo piloto.  
5. Só então considerar liberação controlada de raides (tick) sob runbook dedicado.
