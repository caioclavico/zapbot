# Odisseu A6 — Preflight do piloto em produção (PR #126)

**Data (UTC):** 2026-11-23T01:05Z  
**Escopo:** preparação para deploy controlado + piloto no **grupo WhatsApp já existente**, com a **sessão live**.  
**Não executado nesta etapa:** deploy, cutover, alteração de hold, cold backup frio, restart de containers, mutação de Datomic/saves, reativação de `deploy.yml`, merge de #127/#128.

**Decisão de entrega:** `PRODUCTION_PILOT_PLANNED` · `DEPLOY_NO_GO`  
**Classificação do preflight:** `PREFLIGHT_PARTIAL`

---

## Tabela-resumo

| Campo | Resultado |
|-------|-----------|
| SHA atual da master | `741034c517a5f583287826324d6a069ee67f3b7c` (só #126 além de `c4deb6a`) |
| SHA em produção | `c4deb6a878fdc914a02532af54c169cb1c738dda` (imagens `odisseu-{node,jvm}:a6-c4deb6a878fd*`) |
| Hotfix #126 | Presente na master; **ainda não implantado** |
| PR #127 e XP residual | Fixture-only; residual **não** bloqueia deploy (ver §2) |
| Node/JVM healthy | Sim (RO 2026-11-23) — Node + JVM `healthy`, ~3h uptime |
| WhatsApp READY | Sim — `{"status":"ok","whatsapp":"READY","chromium":true}` em `:3003` |
| Hold atual | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` (GitHub) |
| Estratégia de backup | Oficial: manutenção → drain → quiescence → writer único → `a6_production_backup` + manifesto |
| Restauração de ensaio | **Não executada nesta etapa** (exige janela; procedimento mapeado) |
| Manifesto canônico | `scripts/a6_fase7_integrity_manifest.clj` + `backup-manifest.txt` (sha256 por arquivo) |
| Compatibilidade de rollback | App rollback → imagens `*-previous` (`215c86c1…`); data restore só com análise pós-TX |
| Scripts disponíveis | `a6-operational-window.sh` / `a6_supervised_deploy.py` / backup / hold-recovery |
| Workflow de deploy | `deploy.yml` = `disabled_manually`; caminho oficial = janela supervisionada na VM |
| Segurança dos saves | Live aberta e íntegra na inspeção; **prova de backup+restore desta release ainda pendente** |
| Plano do piloto | Conta do proprietário no grupo existente; sem seed/RNG force |
| Riscos para outros jogadores | **Alto residual:** não há fence por jogador para `!pk*` — hotfix ficará acessível a todos após reopen |
| Blockers | Imagens `741034c` ausentes na VM; cold backup/restore-verify não ensaiados nesta sessão; raid-auto tick ON |
| Decisão | `PREFLIGHT_PARTIAL` · `PRODUCTION_PILOT_PLANNED` · `DEPLOY_NO_GO` |

---

## 1. Auditoria de produção (somente leitura)

**Host:** `odisseu-core` · zona `us-central1-a` · projeto `project-e8cd7809-1145-4be9-b8d`  
**Método:** `gcloud compute ssh` + `docker inspect` / health HTTP. **Nenhum** recreate, stop, maintenance enter ou mutação.

| Item | Observado |
|------|-----------|
| Containers live | `odisseu-a6-node`, `odisseu-a6-jvm` — `Up … (healthy)` |
| Imagem Node | `odisseu-node:a6-c4deb6a878fd@sha256:e6f40c673ddb2f106b7381dfec53df9c0fc14e54e0b03b2365e11c35f3bb30bc` |
| Imagem JVM | `odisseu-jvm:a6-c4deb6a878fd-amd64@sha256:b31295fa22c62ee2c5d07618be93750cc089d24644d0781c872998312b92f414` |
| Rollback containers | `odisseu-a6-{node,jvm}-previous` → `a6-215c86c1eccc*` (exited) |
| WhatsApp | READY + chromium true (`127.0.0.1:3003/health`) |
| JVM bridge | `status=up`, `maintenance.phase=open`, `raidAutoTickEnabled=true`, `allowTestRng=false` |
| `ADVENTURE_AUTO_HATCH` | Ausente/não-truthy (flag off por default no domínio) |
| `APP_ENV` | `staging` nos containers cutover (legado do cutover; não alterado) |
| Sessão WA path | `/app/.wwebjs_auth_a6_cutover` ← bind cutover (não recriar) |
| Volumes live | `odisseu-a6-cutover-{datomic,journal,maint,data}` montados; `odisseu-core-*` **não** montados no live |
| Writer aparente | Só o par node+jvm cutover; previous/archived exited |
| Manutenção | Node `a6-maintenance.json` e JVM `bridge-maintenance.edn` em **`open`** (jogo aberto) |
| Checkpoint janela | `~/.odisseu-a6-window/a6-window-checkpoint.json` — `deployComplete=true`, `windowId=janela-20261122-2205`, `holdRestored=true`, `blocked=[]` |
| Imagens `741034c` | **Ausentes** no host |
| Cutover em andamento | Não — última janela completa; hold true; containers estáveis |
| `deploy.yml` | `state=disabled_manually` |
| Hold GitHub | `true` |

Contrato estrutural esperado: `scripts/a6_production_contract.py` (VM, mounts cutover, nomes `odisseu-a6-{jvm,node}`).

---

## 2. Versão, master e risco XP (#127)

### Hotfix #126

- Merge na master: `741034c` = merge de #126 sobre `c4deb6a`.
- `git log c4deb6a..741034c`: apenas os 3 commits do hotfix (+ merge).
- Nenhum commit adicional na master após `741034c` no momento da auditoria.
- PRs abertos **fora** da master: #127 (fixtures), #128 (evidências homolog). **Não incorporar automaticamente.**

### XP residual `raid-defeat-rewards-fase1283`

Reprodução local (domínio atual em master):

| Etapa | Valor |
|-------|-------|
| Fixture growth-v1 L5 com `xp-total = limiar(L6)−2` | residual v1 = span−2 = **167** / span **169** |
| Conversão canônica → `flat-9-v2` | `floor(167/169×9) = 8` (nível preservado) |
| Grant derrota +3 XP flat | 8+3 → sobe a L6, residual **2** |
| Asserção do teste legado | residual **1** |

**Conclusão de risco para jogadores**

| Pergunta | Resposta |
|----------|----------|
| XP incorreto / corrupção? | **Não evidenciado** — comportamento = conversão oficial growth-v1→flat-9 + grant flat |
| Duplicação de recompensas? | **Não** — falha é asserção de residual pós-conversão |
| Nível/progressão quebrados? | Level-up ocorre; residual pós-level segue aritmética flat-9 |
| Exige correção antes do deploy #126? | **Não** como defeito do hotfix; é desalinhamento do teste com flat-9 |
| Só expectativa de teste? | **Sim** (após conversão). Atualizar asserção para `2` com nota da fórmula é alinhamento, não green-wash |

PR #127 (Brick Break + retry) melhora a suíte; a asserção XP=1 ainda falha de propósito/documentação. Pode permanecer aberta no piloto.

**Gate:** XP residual **não** impõe `DEPLOY_BLOCKED` para o #126.

---

## 3. Proteção dos saves (procedimento — não executado agora)

Caminho oficial (já implementado na janela supervisionada):

1. Autorizações explícitas (`autorizar-producao-a6`, `autorizar-backup-producao-a6`, `confirmo-alterar-vm-a6`, …).
2. Hold observado `true` → release controlado só dentro da janela.
3. `node_maintenance` + `jvm_drain` → fases draining/quiescent/frozen.
4. Quiescence + **writer único** no volume `odisseu-a6-cutover-datomic`.
5. `stop_jvm` / exclusive → backup frio via `a6_production_backup` / admin backup client.
6. `backup-manifest.txt` (sha256 por arquivo) + `manifestVerified` / `sourcePreserved`.
7. `restore_isolated` em destino descartável (nunca substituir o source live).
8. Manifesto de domínio: `scripts/a6_fase7_integrity_manifest.clj` (players, slots, Pokémon, moedas, inventários, ovos/incubadoras, progressão, agregados + compare).

**Regra:** falha em backup ou restore-verify ⇒ `DEPLOY_BLOCKED` — não atualizar imagens.

**Esta etapa:** cold backup **não** foi disparado (jogo `open`, raid-auto tick ON, active writes > 0 na amostragem). Por isso a prova de integridade desta release permanece pendente → contribui para `PREFLIGHT_PARTIAL`.

---

## 4. Scripts oficiais (mapa operacional)

Não foi criado um segundo sistema de deploy. Reutilizar:

| Operação pedida | Mecanismo existente |
|-----------------|---------------------|
| `preflight` | `a6_production_deploy.preflight` + passo `preflight` em `a6_supervised_deploy` |
| `backup` | passo `backup` + `a6_production_backup` / `a6_admin_backup_client` |
| `restore-verify` | passo `restore_isolated` + manifesto fase7 |
| `deploy` | `scripts/a6-operational-window.sh --window` → `a6_operational_window.py` → `run_supervised` |
| `healthcheck` | JVM HTTP + `scripts/healthcheck.js` / Node `:3003/health`; `HEALTH_WAIT_SECONDS=180` |
| `rollback` | `rollback_allowed` / containers `*-previous` / recovery path no supervised |
| `report` | checkpoint `a6-window-checkpoint.json` + journal da janela |

**Entrada canônica (futura janela — NÃO rodar agora):** ver cabeçalho de `scripts/a6-operational-window.sh` (env gates + `confirmo-alterar-vm-a6`).

**Proibições já codificadas:** recusa de `compose down -v`, `--remove-orphans`, `volume rm/prune`; hold/autorização fail-closed; sem token em logs.

**GitHub Actions:** manter `deploy.yml` **disabled_manually**. Deploy = janela VM supervisionada. Não contornar proteções.

**Complemento desta entrega:** `scripts/a6-pr126-pilot-preflight-ro.sh` — sonda RO reproduzível (não muta).

---

## 5. Plano de implantação (janela futura autorizada)

Ordem alinhada a `SUPERVISED_STEPS`:

1. Autorização explícita de deploy (frase + env + hold liberado só na janela).
2. Preflight (VM, mounts, exclusive writer, digests `741034c`).
3. Manutenção Node → drain JVM → quiescence.
4. Congelar escritas / stop JVM controlado.
5. Backup frio + manifesto + restore isolado + compare canônico.
6. Materializar/puxar imagens **amd64** pinadas em `A6_IMAGE_SHA=741034c…` + digests.
7. `update_jvm` → validate → `update_node` → validate WhatsApp READY (espera até 180s; **não** apagar LocalAuth).
8. Smokes sem mutações perigosas.
9. Resume explícito + `confirm_open` + `validate_game`.
10. Registrar versão implantada no checkpoint/journal.
11. Restaurar hold `true` ao final (já previsto).

**Pré-requisito adicional antes do GO:** construir e transferir imagens `741034c` (hoje inexistentes na VM).

**Durante a janela:** desligar/pausar publicação fora do controle (`raid-auto` tick) via drain/maintenance — não deixar scheduler anunciar com jogo “meio aberto”.

---

## 6. Plano de rollback

| Tipo | Quando | Como | Risco |
|------|--------|------|-------|
| **Rollback de aplicação** | Health/WhatsApp/contrato falham **antes** de reopen com progressão nova material, ou logo após com análise | Recriar a partir de `odisseu-a6-{jvm,node}-previous` / digests `c4deb6a` (ou `215c86c1` se previous for esse pin) **sem** `-v` | Preferencial |
| **Restauração de dados** | Corrupção/volume inconsistente comprovada | Restore do backup da janela em ensaio → só então cutover de dados com reconciliação de TX posteriores | **Nunca** overlay cego sobre saves com progressão legítima pós-backup |

Compatibilidade Datomic: #126 não introduz migration destrutiva nova na superfície auditada do hotfix (PI/ovos/poções/roteamento). Ainda assim, após reopen com escritas novas, rollback de app é seguro; rollback de dados exige inventário de `operationId`/recibos posteriores.

Se, na janela, `rollback_incompatible_battle_edn` / contract fail ⇒ **não reopen** até recuperação.

---

## 7. Piloto no grupo existente

**Decisão do proprietário:** sem novo grupo/sessão; usar grupo Odisseu + sessão produção **após** deploy autorizado.

### Fence de jogador

Não há allowlist de produção equivalente a `STAGING_ALLOWED_USERS` para comandos `!pk*` no grupo live. Pokédex species tem modo pilot-only; **o hotfix #126 não**.

**Risco:** após `confirm_open`, qualquer jogador do grupo pode exercer captura/PI/poção/isolamento. O piloto do proprietário **não** isola os demais.

Mitigações operacionais (sem código nesta etapa):

- Janela curta de observação.
- Comunicação no grupo (manutenção → reopen).
- Critérios de aborto (§8).
- Não seedar itens/moedas/PI; não forçar RNG (`allowTestRng=false` já observado).

### Matriz do piloto (pós-deploy)

| Cenário | Como | Passa se |
|---------|------|----------|
| Captura selvagem | Jogo natural do owner | +15 PI elegível, mensagem = recibo, ovo avança |
| Ovos/incubadoras | Natural | Persistência; drop aleatório ausente ≠ falha |
| `!pk raid pocao-maxima` | Em raid legítima do owner | Cura + revide + estoque −1 + HP no próximo turno |
| Contextos | `!pk raid|cacar|pvp atacar 1` só com combates reais | Sem cruzar batalhas |
| Idempotência | Observar recibos/`operationId` | Sem reenvio mutável proposital |

Mensagens WhatsApp ↔ Datomic devem bater. Drops RNG pendentes OK.

---

## 8. Observabilidade e aborto

**Sinais:** logs Node/JVM, latência de cmds, bridge errors, health Datomic, duplicatas `operationId`, rewards, inventário/HP divergente, scheduler/raid-auto, WhatsApp READY, memória/swap, filas maintenance (`activeWrites`/`rejectedWrites`).

| Condição | Ação |
|----------|------|
| Erros bridge / health down | Suspender mutáveis → maintenance |
| WhatsApp ≠ READY > janela de wait | Não reopen; investigar sessão **sem** apagar auth |
| Duplicação de rewards / inventário divergente | Maintenance imediata; preferir rollback de **app**; data restore só com prova |
| Scheduler publicando indevido | Drain/freeze; não “só ignorar” |
| Incerteza pós-escritas legítimas | **Não** prometer restore de snapshot antigo |

---

## 9. Blockers e decisão

### Blockers concretos para um GO imediato

1. Imagens `741034c` **não** estão na VM.  
2. Cold backup + restore-verify **desta** candidata ainda não ensaiados (dependem da janela).  
3. `raidAutoTickEnabled=true` e maintenance `open` — exige drain na janela.  
4. Risco de superfície: hotfix acessível a todos no reopen.

### Não-blockers

- XP residual da suíte fase1283 (asserção vs flat-9).  
- PRs #127/#128 abertos.  
- `deploy.yml` desabilitado (caminho correto é janela VM).  
- Homolog WhatsApp staging bloqueada (substituída pela decisão de piloto live).

### Classificação

- **`PREFLIGHT_PARTIAL`:** plano e scripts OK; prova de backup/restore e imagens candidatas faltam.  
- **Não `DEPLOY_READY`:** segurança dos dados desta release ainda não demonstrada.  
- **Não `DEPLOY_BLOCKED` por XP:** risco de progressão incorreta do residual **não** comprovado.  
- Entrega: **`PRODUCTION_PILOT_PLANNED`** + **`DEPLOY_NO_GO`**.

Próximo passo: autorização explícita de **janela de deploy** (build de imagens `741034c` + supervised window), depois piloto no grupo existente.

---

## 10. Produção — invariantes mantidos

| Invariante | Estado |
|------------|--------|
| Release live | `c4deb6a` |
| Hold | `true` |
| `ADVENTURE_AUTO_HATCH` | off |
| `deploy.yml` | disabled_manually |
| Sessão WhatsApp | intacta |
| Volumes cutover | intactos |
| Deploy nesta etapa | **não executado** |
