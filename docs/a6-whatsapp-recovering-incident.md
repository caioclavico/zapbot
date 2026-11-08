# A6 — Incidente WhatsApp RECOVERING (P0 diagnóstico)

**Host:** `odisseu-core` (us-central1-a)
**Quando (UTC):** incidente `2026-11-08T01:26Z`–ongoing; diagnóstico RO `2026-11-08T03:25Z`–`03:30Z`
**Escopo:** diagnóstico + preparação de recuperação. **Sem restart, recreate, troca de imagem, ACL write, deploy, migration ou alteração de flags.**

---

## Sumário executivo

O Node live **não** está morto: Chromium segue vivo e a sessão LocalAuth continua montada e sendo escrita. O unhealthy Docker reflete estado interno **`RECOVERING`**, causado pelo módulo `whatsapp-startup` ao reentrar em `inject()` após um reload da página WhatsApp Web (~5 min após READY), **sem** concluir o inject (loading travou em 75%).

Não há evidência de `auth_failure`, `disconnected` ou fechamento do browser. Chrome 155 **não** está comprovado como causa raiz.

**GO/NO-GO para solicitar autorização de recuperação:** **GO** (recuperação operacional mínima, **não** deploy da RC gameplay).

---

## 1. Identidade live (RO)

| Item | Valor |
|------|-------|
| Container | `odisseu-a6-node` `2cc29f383473` |
| Image ID | `sha256:eefacb920e88a82a8093889758b9ff237251e639bd9cf8f97c0a00bfadbe2b53` |
| Tag | `odisseu-node:a6-ai-drain-sandbox-chromium155-getchatfix-20261108T001331Z-16b5987-amd64` |
| Chromium | 155.0.8059.79 · UID efetivo 1000 (`ubuntu` no host) |
| Started | `2026-11-08T01:26:48Z` · `RestartCount=0` |
| Health | `unhealthy` · `FailingStreak≥111` · body `{"status":"degraded","whatsapp":"RECOVERING","chromium":true}` |
| Maintenance Node | `phase=open` (`/app/data/a6-maintenance.json`) |
| JVM | `sha256:ad0b7c1b…` · **healthy** · bridge `status=up` · maint `open` |
| Hold / CI | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · `CI/CD=disabled_manually` |

---

## 2. Timeline READY → RECOVERING

| UTC | Evento |
|-----|--------|
| 01:26:49 | Health HTTP sobe; `Inicializando…` |
| 01:26:59 | **QR Code gerado** (`STAGING_ALLOW_QR=1` → `restoreOnly=false`) |
| 01:28:03–09 | Loading 100% → Autenticado |
| 01:28:13 | **READY** (“conectado e pronto”) |
| 01:28–01:32 | Sessão ativa (mtimes em IndexedDB/WebStorage/Network) |
| **01:33:10** | `Estado de inicialização atualizado; sessão preservada.` **×2** = `onState('recovering')` do `installStartupRecovery` |
| 01:33:17 | `Carregando WhatsApp: 0%` (`loading_screen`) |
| 01:33:20 | Novo `onState('recovering')` |
| 01:33:22 | `Carregando WhatsApp: 75%` |
| após | **Sem** READY, DISCONNECTED, AUTH_FAILURE, erros Puppeteer/Protocol nos logs; health permanece RECOVERING |

### Interpretação (código)

`scripts/lib/whatsapp-startup.cjs` define `state('recovering')` **sempre que `client.inject` é (re)entrado**. O callback em `core.cljs` mapeia isso para o diagnóstico `RECOVERING` e a mensagem “sessão preservada”.

Sequência compatível com as evidências:

1. Página WhatsApp Web **navegou/recarregou** (~01:33).
2. O Client pinado chamou `inject()` de novo.
3. O wrapper marcou `zapbotStartupState=recovering` e o health deixou de aceitar READY.
4. `loading_screen` avançou 0%→75% e **não** chegou a 100%/novo `ready`.
5. Inject ficou pendente ou incompleto; **não** houve falha logada de Protocol/context_destroyed nem fechamento do browser.

### O que **não** aparece

- `disconnected` / `auth_failure`
- `AUTH_TRANSITION` / `whatsapp_recovery_failed` / `STARTUP_TIMEOUT` nos logs filtráveis
- Exceções Puppeteer/`Target closed`/`Execution context was destroyed`
- Segundo processo Chromium “browser” (apenas 1 PID principal + zygotes/renderers)

### Distinção de classes de falha

| Classe | Evidência |
|--------|-----------|
| Autenticação perdida | **Improvável** — sem `auth_failure`/`disconnected`; Cookies/Preferences ainda atualizados (~03:21–03:27); Chromium `isConnected` implícito no health (`chromium:true`) |
| Crash Chromium | **Não** — processo principal contínuo desde o boot (~2 h elapsed) |
| Rede host | Disco/inodes OK; sem erros de rede nos logs Node |
| Gerenciamento de estado / inject | **Provável causa proximal** — stuck em `recovering` pós-reload mid-inject |
| Chrome 155 | **Não comprovado** como causa; correlato só pela imagem live |

---

## 3. Persistência de sessão (RO)

| Item | Achado |
|------|--------|
| LocalAuth path | `WWEBJS_DATA_PATH=/app/.wwebjs_auth_a6_cutover` · `WWEBJS_CLIENT_ID=core` → bind host `…/a6-cutover-session-from-core/session-core` |
| Ownership | root sessão `1001:1002`; `session-core` **`1000:1000`** |
| ACL efetiva | `user:1000:rwx` + `mask::rwx` (+ defaults); Chromium do container escreve normalmente |
| EACCES | Sem indício de EACCES **dentro** do container; um probe host `sudo -u ubuntu` falhou de forma inconsistente com o lsof do Chromium (não usado como bloqueio) |
| Estrutura | `Default/` presente (Preferences, Cookies, History, IndexedDB, WebStorage, …) |
| Writers | **Um** browser Chromium (PID host ~3581092) + renderers; sem segundo Client |
| Disco / inodes | `/` ~85% · 7.3 G livres · inodes ~7% |
| QR no boot | Artefatos `qr-payload.txt` / `whatsapp-qr.png` em `/app/data` (01:27) — **não reproduzidos aqui** |

**Integridade:** perfil estruturalmente presente e em uso. Não se apagou cache/cookies/Preferences nesta investigação.

**Fator de configuração:** `STAGING_ALLOW_QR=1` força `restoreOnly=false`, permitindo QR mesmo com pasta de sessão existente. Isso explica o QR no boot **sem** provar corrupção do perfil; a sessão atual é a **re-pareada via QR** às 01:28.

---

## 4. Liveness

| Check | Resultado |
|-------|-----------|
| Processo Node | Vivo |
| Chromium principal | Vivo (~2 h) |
| Renderers/zygotes | Presentes |
| Health interno | `degraded` / `RECOVERING` / `chromium:true` |
| `/diagnostics` | Responde; sem operações pendentes úteis |
| Maintenance | `open` (Node + JVM) |

**RECOVERING ≠ logout comprovado.** É sobretudo o **estado do startup recovery** após inject incompleto. A página pode estar autenticada visualmente no Web, mas o bot **não** publica READY nem aceita o canal como saudável.

---

## 5. Flags temáticas vs RC

| | Produção live | RC gameplay homologada |
|--|---------------|------------------------|
| `POKEMON_THEMATIC_EVENTS_ENABLED` | **`true`** (Node + JVM) | **`false`** (default / cutover docs) |

**Quando:** presente como `true` em `.env.a6-cutover` e backups desde pelo menos **2026-11-05** (`bak-pre-raid-auto`, drain, pokedex, QR 2026-11-07). Não é regressão desta RC A+B+C.

**Uso atual (amostra RO):**

- Flag global `enabled=true`.
- Chats amostrados: `events=[]` (nenhuma ocorrência ativa).
- Pelo menos um chat com `schedule` configurado (catálogo disponível; schedule não-nulo).

**Impacto se a próxima release desligar a flag:**

- Para de ativar/tickar eventos novos.
- **Não** apaga automaticamente schedules/missões/pending já persistidos no Datomic.
- Precisa preservar: DB Datomic (`odisseu-m90-core`), volumes `odisseu-a6-cutover-datomic` / journal / data; decidir GO separado para drain de eventos/missões pendentes antes de desligar em produção.

**Não alterado nesta etapa.**

Outras flags live relevantes (contexto, não alteradas): `POKEMON_RAID_AUTO_TICK=true`, `POKEMON_GYM_CHALLENGES_ENABLED=true`, `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`, `ALLOW_ALL_GROUPS=true`.

---

## 6. JVM / Datomic

| Item | Estado |
|------|--------|
| Health | `ok` / `up` |
| Maintenance | `open` |
| `ODISSEU_BRIDGE_RESET/SEED` | `false` |
| Storage | `/data/datomic-local` → volume `odisseu-a6-cutover-datomic` (~28 MB) |
| System/DB | `odisseu-m90-core` / `core` |
| Raid auto (JVM) | `raidAutoTickEnabled=true` |

Pokémon via bridge permanece operacional; o corte é o **canal WhatsApp**.

---

## 7. Procedimento mínimo de recuperação (proposta — **não executar**)

Objetivo: voltar a **READY** na imagem **atual** (`eefacb92…`), sem RC gameplay.

### Preferência 1 — observação curta + confirmação humana (se ainda RECOVERING)

1. Confirmar RO que Chromium único e maint `open`.
2. Se loading não evoluir (já ~2 h), **não** esperar indefinidamente.

### Preferência 2 — restart Node controlado (mesma imagem) — requer autorização

1. **Congelar expectativa Pokémon:** JVM permanece; jogadores já sem WA.
2. Snapshot RO da sessão: `getfacl -pR` + cópia a frio do bind `a6-cutover-session-from-core` (rsync/`cp -a`) para artefato datado **fora** do mount live.
3. Snapshot volume data (marker + groups + histórico IA).
4. Drain Node via gate/token **existente** até marker `quiescent` (ou documentar se drain indisponível sob RECOVERING).
5. Garantir **zero** segundo Chromium na sessão.
6. `docker compose … up -d odisseu-a6-node` **mesma tag/Image ID** (sem overlay gameplay).
7. Critérios de sucesso: health `whatsapp=READY` + `chromium=true` + FailingStreak=0; logs sem QR **ou**, se `STAGING_ALLOW_QR=1` forçar QR, intervenção humana explícita.
8. **Recomendação de config na mesma janela (opcional, autorização separada):** `STAGING_ALLOW_QR=0` após READY estável, para não reabrir QR em restore.

### Rollback

- Se restart piorar: restaurar container a partir do Image ID `eefacb92…` + sessão do snapshot frio; JVM/Datomic **intocados**.
- Breakglass Node histórico `1d00dd01…` só se autorizado e com aceite de perda de sandbox/features da imagem getchatfix.
- **Não** aplicar RC `e7488e4f` como rollback desta recuperação.

### Riscos

- Novo QR se LocalAuth rejeitar o perfil (mitigar: `STAGING_ALLOW_QR` consciente + operador no telefone).
- Inject pode falhar de novo na mesma imagem — então abrir incidente de código/getChat na imagem `…getchatfix…`, ainda **sem** cutover gameplay.

---

## 8. O que esta recuperação **não** é

- Não é deploy da RC A+B+C (`21771ec` / `e7488e4f`).
- Não é migration/backfill/reset Datomic.
- Não é mudança de flags temáticas/raids/ginásios sem GO próprio.
- Não remove deploy hold nem reabilita CI/CD.

---

## 9. GO / NO-GO

| Decisão | Veredito |
|---------|----------|
| Solicitar autorização para **recuperação operacional** (restart Node mesma imagem + snapshot sessão) | **GO** |
| Solicitar janela de **deploy RC gameplay** | **NO-GO** até WhatsApp READY estável |
| Executar recuperação agora sem autorização | **NO-GO** (restrito por esta tarefa) |

---

## 10. Evidências coletadas (sem segredos)

- Logs Node timeline 01:26–01:33 (QR → READY → recovering → loading 75%).
- Health contínuo `RECOVERING` + `chromium:true`.
- `STAGING_ALLOW_QR=1`, path LocalAuth, ACL `u:1000`, lsof Chromium único.
- JVM health/maint; thematic `enabled=true`, ocorrências ativas amostradas = 0, schedule presente em ≥1 chat.
- Código: `scripts/lib/whatsapp-startup.cjs` (`state('recovering')` em `inject`), `src/zapbot/core.cljs` / `whatsapp_saude.cljs`.
