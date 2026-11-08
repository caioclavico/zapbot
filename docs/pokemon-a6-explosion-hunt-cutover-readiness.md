# A6 Explosion + Hunt + Raid — Pré-Cutover Final / Readiness

**Stamp auditoria:** `2026-11-08T22:18Z` (somente leitura)  
**Autorização vigente (pós-janela):** cutover **executado com SUCCESS** — ver `docs/pokemon-a6-explosion-hunt-deploy-report.md` (`20261108T222347Z`)  
**Veredito histórico desta auditoria:** **GO** plano · janela autorizada em seguida e concluída

---

## 1. Identidade da release

| Campo | Valor |
|-------|-------|
| Branch RC | `github/rc/a6-abc-p0-integrated` |
| Commit executável (imagens) | `2afd9975ef695163a4d8f98eed632c644431c72f` |
| Content-tree | `ad8a3cf41c4851237b535b15fad82e8e024b92f0` |
| Tip operacional (docs/pins) | `948426ba2d79609936d2f71b63c248deb5c16029` (PR #61) |
| PRs | [#59](https://github.com/caioclavico/zapbot/pull/59) feature · [#60](https://github.com/caioclavico/zapbot/pull/60) homolog · [#61](https://github.com/caioclavico/zapbot/pull/61) image pins |
| Schema esperado | **035** (`035-species-hp-formula.edn`) — sem migration nova nesta RC |
| Raridade | `:legado` (inalterada) |
| Fase imagem | `a6-explosion-hunt-raid-integrated` |
| Arch | `linux/amd64` · Chromium **156** · UID **1000** |

### Artefatos locais

`artifacts/a6-explosion-hunt-raid-integrated-20261108T203624Z/`

### Inbox host

`/home/caiohclavico/odisseu-recovery/rc-inbox/a6-explosion-hunt-raid-2afd997/`

### Imagens candidatas (carregadas; **não** em execução)

| Serviço | Tag | Image ID completo |
|---------|-----|-------------------|
| Node | `odisseu-node:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64` | `sha256:77d0d9a255125c74d59168713dc025c42d9d189d9bd7278bf1813be981464d2b` |
| JVM | `odisseu-jvm:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64` | `sha256:9443b80134bf5f2bb47009354b705785f065ff438c31de10496621a35d2198d1` |

### SHA256 dos tars (completos)

```
739e61fb04c1625a7f48afcab1e51be5ea020e85374d82942d502a11f137a100  odisseu-node-a6-explosion-hunt-raid-integrated-2afd9975ef69-amd64.tar
152d5523faf06ebd65501db96976bca6197fdcbb401d603d1fe80cdd5b753f7c  odisseu-jvm-a6-explosion-hunt-raid-integrated-2afd9975ef69-amd64.tar
```

Inbox `IMAGES.sha256` ≡ `REMOTE.sha256` ≡ artefato local. VERIFY host **PASS** (amd64 · labels `2afd997` / `ad8a3cf4`). **Não reconstruir.**

---

## 2. Estado real de produção (RO)

Runtime: `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
Projeto Compose: `odisseu-a6-cutover`

| Gate | Evidência | Status |
|------|-----------|--------|
| Node live Image | `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` (Combat UX `3bc5076`) | OK |
| JVM live Image | `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` (Combat UX `3bc5076`) | OK |
| Node StartedAt | `2026-11-08T17:17:51.337099983Z` · Restarts=**0** · healthy | OK |
| JVM StartedAt | `2026-11-08T17:16:32.699831734Z` · Restarts=**0** · healthy | OK |
| WhatsApp | `{"status":"ok","whatsapp":"READY","chromium":true}` (`:3003/health`) | OK |
| Bridge JVM | `http://127.0.0.1:18092/health` → `status=up` · `maintenance.phase=open` · `activeRequests=0` | OK |
| Chromium / UID | **156.0.8078.79** · `uid=1000` · CapEff=`0000000000000000` · NoNewPrivs=1 · seccomp + `cap_drop: [ALL]` | OK |
| Datomic | volume `odisseu-a6-cutover-datomic` montado; journal `odisseu-a6-cutover-journal` | OK |
| Schema 035 | migration file presente na imagem live; audit Combat UX cutover `migration-035? true` (984 species-hp-v1 / 5 legacy-unset / 0 invalid-hp) | OK |
| Hold | `HOLD=true` (evidência prep + `hold-from-prior-prep.txt`) | OK |
| CI/CD | GitHub workflow `deploy.yml` **state=`disabled_manually`** (API; ver `a6-species-hp-final-preflight-…/github.txt`) | OK |
| Disco | **6.5G** livres · 87% · inodes **5%** usados (6130443 livres) | OK (margem = piso registrado) |
| Maintenance Node | `{"phase":"open","resumedAt":"2026-11-08T17:19:48.020Z"}` | OK |

### Combates / raids no instante da auditoria

- Bridge: `activeRequests=0`.
- Último `raid_auto_started`/`lobby_expired→combate` observado: ciclo **anterior** (`…:1794171600000`).
- Em `2026-11-08T22:15:09Z` houve `raid_auto_spawn` do lobby `…:1794176100000` (próximo tick `23:30Z`). **Sem** `raid_auto_started` subsequente nos logs da janela auditada; lobby vazio não conta como combate ativo, mas **é gate de preflight** antes do freeze (aguardar expire/quiescent).

Nenhum item crítico diverge → auditoria **não** interrompe com NO-GO de produção.

---

## 3. Significado de `active == prepared-explosion-hunt-2afd997`

### Arquivos

| Arquivo | Papel |
|---------|-------|
| `docker-compose.a6-ai-drain-sandbox.overlay.yml` | Overlay **efetivo** do projeto Compose (`com.docker.compose.project.config_files` nos containers) |
| `…overlay.yml.prepared-explosion-hunt-2afd997` | Cópia/marcador da pinagem candidata · **diff idêntico** ao overlay efetivo (`diff_exit=0`) |
| `…overlay.yml.bak-pre-explosion-hunt-prep-20261108T220453Z` | Backup íntegro do overlay **Combat UX** (imagens `619b4756` / `09e7605e`) |

### O que `active == prepared` significa

1. O arquivo de overlay **on-disk** já foi reescrito na prep para as tags/Image IDs **candidatas** (`77d0d9a2…` / `9443b801…`).
2. `docker compose config` (sem up) já resolve `image:` para Explosion/Hunt/Raid.
3. Os **containers em execução** continuam nas imagens Combat UX — `StartedAt` inalterado; labels `io.zapbot.git-sha=3bc5076…` / `fase=a6-combat-ux-events`.
4. Prep **modificou** apenas o overlay (+ criou bak + prepared). Não houve `compose up`, recreate, restart, migration, flag, WA, hold ou CI.
5. Overlay preparado usa Image IDs/tags corretos (conferidos vs META/inbox/VERIFY).
6. Bak Combat UX íntegro e alinhado às imagens live.

### Configuração preparada ≠ estado executado

| Camada | Imagens |
|--------|---------|
| Overlay / `compose config` | candidata `2afd997` |
| Containers live | Combat UX `3bc5076` |

**Risco residual:** qualquer `docker compose up` acidental neste diretório **aplicaria** o cutover. Tratar o path runtime como zona quente até a janela autorizada.

---

## 4. Compatibilidade de rollback

### Alvo de rollback imediato (obrigatório)

| | Node | JVM |
|--|------|-----|
| Tag | `odisseu-node:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` | `odisseu-jvm:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` |
| Image ID | `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` | `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` |
| Overlay | restaurar `…bak-pre-explosion-hunt-prep-20261108T220453Z` → `docker-compose.a6-ai-drain-sandbox.overlay.yml` | idem |

Imagens presentes no host. Overlay bak verificado. Plano: restaurar bak → `compose up` coordenado JVM→Node (mesmos volumes/sessão).

### Schema 035 / Species-HP v1

| Critério | Resultado |
|----------|-----------|
| Combat UX já roda schema 035 + Species-HP | **Sim** (live atual) |
| RC candidata introduz migration nova | **Não** |
| Rollback schema-incompatível | **Não** |
| Dados Species-HP / `hp-atual` / `hp-base` legíveis no rollback | **Sim** |

### Novos comportamentos × leitura reversa

| Mudança candidata | Persistência | Combat UX após rollback | Veredito |
|-------------------|--------------|-------------------------|----------|
| Explosion `fainted-x` / HP 0 | `:pokemon/hp-atual=0` | Lê OK; **bug Explosion pode voltar** em vitórias futuras | Dados OK · regressão de comportamento |
| `streak-unit-v1` | usa attrs já existentes (`sequencia-capturas`, ledger, `vitorias-treinador`) | Volta política antiga (`recompensa-pos-captura` c/ 💰=2+seq) | Sem quebra de schema · economia futura muda |
| Liberar (`/v1/encounter/release`) | receipts `encounter-release` | Endpoint **ausente** no Combat UX | Encontros mid-flight: capturar pelos caminhos antigos; não restaurar DB |
| XP/PE de batalha na vitória | flag efêmera `recompensas-batalha-concedidas?` + ledger | Risco teórico de **dupla XP** se rollback no meio de aguardando-captura | **Mitigar com drain total** antes de cutover e antes de rollback |
| Raid 1º frame / emojis | apresentação | Visual regride | Não-crítico |
| One-Mon / HP chefe 320 | inalterado | Preservado | OK |

### Operações já confirmadas no Datomic

- **Não** restaurar backup antigo do banco por padrão (apagaria progresso).
- Tratar txs confirmadas (capturas, liberar, ledger, HP 0) como fato.
- Rollback = só imagens/código + overlay; saves permanecem.

### Declaração

Rollback Combat UX é **seguro para schema 035 / Species-HP / volumes / sessão WA**, com **limites**:

1. Drain encounters/batalhas/raids antes de reverter.
2. Aceitar regressão Explosion HP e ausência de `liberar`.
3. Não declarar “rollback econômico bit-a-bit” sem drain — política de caça diverge.

Species-HP Final (`17520a1e`/`c13140fa`) permanece disponível como rollback secundário (também 035), mas o **imediato** é Combat UX (bak da prep).

---

## 5. Sequência exata de cutover (só após GO explícito de deploy)

Idempotente; parada segura em cada gate. **Preservar:** LocalAuth WA · Chromium 156 · UID 1000 · seccomp · CapEff=0 · NoNewPrivileges · volumes Datomic/journal/data · backups · flags · hold · CI disabled. **Um** Chromium por sessão.

Diretório:

```bash
RT=/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z
cd "$RT"
```

Compose base (padrão do projeto):

```bash
DC=(docker compose
  --env-file .env.a6-cutover
  --env-file .env.node-maintenance
  -f docker-compose.a6-cutover-runtime.yml
  -f docker-compose.a6-ai-drain-sandbox.overlay.yml)
```

### 1) Preflight

- Confirmar hold `true` · CI `disabled_manually` · disco ≥ ~6G · candidatas `77d0d9a2`/`9443b801` presentes.
- Overlay efetivo == prepared (já pinado).
- `curl -fsS :3003/health` → READY · `:18092/health` → up · `activeRequests=0`.
- **0** batalhas / **0** raids em combate / lobby vazio ou expirado.
- Registrar `docker inspect` Image/StartedAt (baseline).

### 2) Maintenance / freeze

- Entrar maintenance (Node + bridge) até `draining` → `quiescent`.
- Fail-closed em mutações; leituras OK.

### 3) Graceful drain

- Aguardar `activeRequests=0` e ausência de encontro/raid/batalha em andamento.
- Não iniciar segundo Chromium.

### 4) Confirmar quiescência

- Revalidar health + zero ops ativas. **STOP** se falhar.

### 5) Registrar estado dos containers

```bash
sudo docker inspect odisseu-a6-node odisseu-a6-jvm \
  --format '{{.Name}} {{.Image}} {{.State.StartedAt}} {{.RestartCount}}'
```

### 6) Aplicar imagens pinadas

- Overlay **já** aponta para candidatas — não re-pin na janela salvo drift.
- Recreate coordenado (JVM primeiro, depois Node), **sem** rebuild/load:

```bash
# APENAS COM GO DE DEPLOY — NÃO EXECUTAR NESTA AUDITORIA
sudo "${DC[@]}" up -d --no-deps --force-recreate odisseu-a6-jvm
# gate JVM (passo 7) antes do Node
sudo "${DC[@]}" up -d --no-deps --force-recreate odisseu-a6-node
```

### 7) JVM — health / bridge / Datomic

- `:18092/health` → `status=up`.
- Volumes datomic/journal intactos.
- Schema 035 sem migration nova.
- **STOP** + rollback se unhealthy.

### 8) Node — sessão WA preservada

- Mesmo volume `.wwebjs_auth` / data path.
- UID 1000 · seccomp · CapEff=0.
- **STOP** se segundo browser/session lock.

### 9) WhatsApp READY

- `:3003/health` → `whatsapp=READY` persistente (≥2–3 min).
- **STOP**/rollback se QR inesperado ou não-READY.

### 10) Liberar maintenance

- Somente após gates 7–9.
- `phase=open` Node + bridge.

### 11) Smoke controlado

- Ver §6 (não-destrutivo / fixtures isoladas).

### 12) Monitorar ≥ 20 min

- Restarts=0 · READY · bridge up · sem erro persistente de HP/bridge/Datomic · sem duplicata de rewards nos logs.

---

## 6. Plano de smoke pós-cutover

Preferir **não-destrutivo**. Mutações só em fixture/conta descartável.

### Explosion

- Vitória com Explosion → HP atual **0** na equipe.
- Persiste após restart de leitura (`!pk time` / collection-read).
- Distinguir de bug pré-RC (HP cheio).

### Caçadas

- Fluxo vitória → opção **liberar**.
- XP/PE de batalha creditados na vitória (antes de capturar/liberar).
- Liberar: **sem** bônus de captura · sequência → **0**.
- Capturar: aplica `streak-unit-v1`.

### Sequência (`streak-unit-v1`)

| Seq | XP | PE | 💰 |
|----:|---:|---:|---:|
| 1–2 | 2 | 1 | 0 |
| 3–4 | 3 | 2 | 0 |
| 5–9 | 5 | 3 | 1 |
| 10–19 | 8 | 5 | 3 |
| 20–29 | 12 | 7 | 6 |
| 30+ | 15 | 9 | 10 |

- 💰 apenas a partir da 5ª captura.
- Retry mesmo `operation-id` não duplica.

### Raids

- 1º frame sem quadriculado / summary completo.
- Emojis de golpe + efetividade.
- HP chefe **320**/jogador.
- One-Mon preservado.

### Regressão preservada

- Species-HP · schema 035 · raridade `:legado` · chance-surto 25% · moedas UI 💰.

---

## 7. Gates de rollback

### Críticos → interromper / reverter (após validar segurança + autorização do plano)

- WhatsApp sem READY persistente.
- Node/JVM unhealthy.
- Falha R/W Datomic.
- Incompatibilidade de schema (inesperada nesta RC).
- Erro persistente de bridge.
- Corrupção/perda de saves.
- Duplicação de recompensas.
- Falha crítica de cálculo de HP (não cosmético).
- Rollback compatível indisponível (imagens/bak ausentes).

### Não-críticos (não disparam rollback automático)

- Arte visual menor (sprite/emoji) sem impacto de estado.
- Lobby auto-raid vazio.
- Mensagens de UX não bloqueantes.

### Política

- **Não** rollback automático sem checagem de segurança (drain + bak + imagens).
- **Não** restaurar backup Datomic antigo por padrão.
- Rollback = restaurar bak Combat UX + recreate JVM→Node.

```bash
# ROLLBACK (só com autorização) — NÃO EXECUTAR NESTA AUDITORIA
sudo cp -a docker-compose.a6-ai-drain-sandbox.overlay.yml.bak-pre-explosion-hunt-prep-20261108T220453Z \
           docker-compose.a6-ai-drain-sandbox.overlay.yml
sudo "${DC[@]}" up -d --no-deps --force-recreate odisseu-a6-jvm
# gate health JVM
sudo "${DC[@]}" up -d --no-deps --force-recreate odisseu-a6-node
# gate WA READY + maintenance open
```

---

## 8. Riscos residuais

| Risco | Severidade | Mitigação |
|-------|------------|-----------|
| Overlay on-disk já candidata | Alta (acidente) | Sem `compose up` até GO; comunicar zona quente |
| Disco 6.5G | Média | Não carregar imagens extras; inbox ~622M; limpeza só com GO |
| Lobby raid auto na janela | Média | Preflight: esperar expire / 0 combate |
| 5× `legacy-unset` Species-HP | Baixa | Fora do escopo desta RC |
| Rollback mid-hunt | Média | Drain total antes de reverter |
| Smoke em conta real | Alta | Só fixtures isoladas |

---

## 9. GO / NO-GO

| Item | Status |
|------|--------|
| Identidade imagens + SHA256 + VERIFY | **GO** |
| Produção live Combat UX saudável / WA READY / Restarts=0 | **GO** |
| Schema 035 / Species-HP / bridge / volumes | **GO** |
| Hold + CI disabled_manually | **GO** |
| Disco ≥ 6.5G / inodes OK | **GO** |
| Overlay ambiguity resolvida (prepared ≠ running) | **GO** |
| Bak rollback Combat UX íntegro + imagens presentes | **GO** |
| Rollback schema/Species-HP compatível (c/ limites hunt) | **GO** (condicional ao drain) |
| Plano cutover + smoke + gates documentados | **GO** |
| **Executar deploy / drain / restart agora** | historicamente **NO-GO** nesta auditoria; **autorizado e SUCCESS** em `20261108T222347Z` |

### Pós-execução

Cutover concluído — relatório: `docs/pokemon-a6-explosion-hunt-deploy-report.md`.  
Live Node/JVM = candidatas `77d0d9a2` / `9443b801`. Hold/CI inalterados. Rollback não necessário.

---

**Fim da auditoria (pré-janela).** Janela posterior: **CUTOVER_RESULT=SUCCESS**.
