# Relatório — cutover produção Ginásios A6 (motivação / HP / sprites / migration 034)

**Status:** CONDITIONAL  
**Motivo CONDITIONAL:** par Node+JVM implantado e saudável; migration 034 confirmada (restore isolado + evidência live no `db.log`); leituras RO do piloto OK (motivação, tempo, HP, economia OFF); smoke WhatsApp visual (`!pk gin` / sprites / barras / desafio) **pendente** — sem participante autenticado seguro nesta sessão.  
**Não declarar SUCCESS** até homologação WA no grupo piloto.  
**Créditos por permanência:** **NÃO ativados** (`POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false` em Node e JVM). Exige GO separado.

| Campo | Valor |
| --- | --- |
| Cutover | 2026-11-05 ~22:47Z (quiescência) → ~22:48Z (cold) → ~22:52–22:54Z (validate 034) → ~22:56–22:58Z (par + reopen) |
| Relatório | 2026-11-05T23:03Z |
| Host | `odisseu-core` / `35.192.113.146` / arch `x86_64` (AMD64) |
| Branch RC | `rc/a6-gym-motivation-rewards` |
| Tree imagens | `2ec5f97649ef32a320007dbb4a36b6eb9bf9612a` |
| Pacote | `rc-artifacts/a6-gym-rc-20261105T222938Z/` |
| Evidências host | `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/gym-cutover-20261105T223637Z/` |
| Piloto | `120363427684548487@g.us` |

---

## 1. Resultado

| Gate | Resultado |
| --- | --- |
| Preflight host/arch/disco / par anterior Time Pokémon | OK |
| Imagens RC (ID + tar SHA-256 + AMD64 + mesmo tree) | OK |
| Janela (≥10 min da próxima raid; sem raid/batalha/troca ativa) | OK (próxima raid 23:30Z) |
| Drain Node → `quiescent` | OK (`activeOperations=0`) |
| Quiescência JVM writers | OK (`activeRequests=0` + stop ordenado) |
| Backup cold + manifesto SHA-256 | OK |
| Restore isolado + probe migration 034 | OK (`pokemon/034-gym-permanence-daily` + attr) |
| Cutover par Node+JVM | OK |
| Migration 034 no volume live | OK (delta `db.log` = validate pós-`install!`) |
| Node/JVM healthy | OK |
| WhatsApp READY | OK |
| Bridge UP | OK |
| Raids auto ON | OK |
| Agenda temática preservada | OK (`Noite das Sombras` ativa) |
| Bag kit diário flag preservada | OK (`POKEMON_BAG_KIT_DAILY_ENABLED=true`; sem POST mutante) |
| Announcer preservado OFF | OK |
| Pokédex gate piloto | OK |
| Time Pokémon / trocas (API RO) | OK (CSV teams + trades list) |
| Maintenance final `open` | OK (Node + JVM) |
| Flag economia permanência OFF | OK (Node+JVM `false`; DTO sem `economiaPermanenciaAtiva`) |
| Homologação WA sprites/HP bars/desafio | **PENDENTE (manual)** |

---

## 2. Imagens

### Antes (rollback disponível)

| Serviço | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` | `sha256:dbf88e066e3d79055e1a392a4bd2d8b6b73f9490ba2f11c65ae55f317b52c315` |
| JVM | `odisseu-jvm:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` | `sha256:381d67d7185a2e4ec0394513916a19577cfe5e8f843d5efa750351b0448679b6` |

Imagens anteriores **permanecem** no host Docker.

### Depois (par autorizado)

| Serviço | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` | `sha256:cdb25591680f8f63d33923282ad8c25a4ffeee6fc4f50da5847ac96d97c6c094` |
| JVM | `odisseu-jvm:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` | `sha256:629391dc1930dea6c9360b503b929b78e856f10f1868517e9fb459031ed06d14` |

Containers recriados:

| Serviço | Container ID (prefix) | StartedAt (UTC) |
| --- | --- | --- |
| JVM | `f9627273e19e…` | `2026-11-05T22:56:56Z` |
| Node | `445164e900b6…` | `2026-11-05T22:57:45Z` |

Tar SHA-256 (manifesto RC, conferidos antes do load):

- Node tar: `dd22915fc85249a6aa5444d686f631997b1681ca5618363cea7a4f402b4a94b4`
- JVM tar: `7e0be8a60fcb84ba8f6feecf9a6293abb0efb4a42435f287e9be3a0efccd0b18`

Compose backup: `docker-compose.a6-cutover-runtime.yml.bak-pre-gym-20261105T225442Z`  
Volumes preservados: `odisseu-a6-cutover-datomic` (+ journal/maint/data/sessão). **Não** usado `docker compose down -v`.

---

## 3. Quiescência global (evidências)

Não usada “silêncio de logs” como prova.

| Etapa | Evidência |
| --- | --- |
| Node enter → quiescent | `phase=quiescent` · `activeOperations=0` · `quiescentAt=2026-11-05T22:47:51.507Z` |
| JVM enter → quiescent | `phase=quiescent` · `activeRequests=0` · `quiescentAt=2026-11-05T22:47:52.142Z` |
| Writers | Único writer A6 no volume; containers `odisseu-core-*` exited; lock exclusivo |
| Stop JVM | Ordenado após quiescência (`jvm-stopped` / exit registrado nas evidências) |
| Admissão | Node permaneceu fechado até reopen controlado pós-READY |

Arquivos: `node-quiescent.json`, `jvm-quiescent.json`, `writers-before-stop.txt`, `jvm-stop.txt`.

---

## 4. Backup cold Datomic

| Item | Valor |
| --- | --- |
| Backup | `/home/caiohclavico/odisseu-recovery/datomic-cold-backups/cold-20261105T224822Z` |
| Momento | Após JVM stopped (cold consistente) |
| Manifesto | `MANIFEST.FILES.sha256` · digest `b7862dc65876b57bc5dab433f5e8ae696fd01e5661288fe94098d8586961409e` |
| Backups anteriores | Preservados no mesmo diretório `datomic-cold-backups/` |
| Restore SHA check | `restore-sha-check.txt` — todos **OK** |
| Validate path | `datomic-cold-restore-validate-20261105T224822Z` |
| Validate health | bridge `up` (`restore-validate-health.json`) |
| Probe 034 (isolado) | `:mid pokemon/034-gym-permanence-daily` · `:attr :save/gym-permanence-daily-edn` · `:033` presente |

---

## 5. Migration 034

| Check | Resultado |
| --- | --- |
| Aditiva / idempotente (suite + restore) | OK |
| Compatível com 033 e saves legados | OK (attr ausente = `nil` válido) |
| Aplicação | `schema/install!` no boot da JVM RC |
| Evidência isolada | `restore-validate-034.txt` |
| Evidência live | `core/db.log` cold `10283517` → live `10283809` (= validate pós-install, Δ=+292 B); roots `db-root-0000001357` inalterados (append no log) |
| Backfill / alteração de ocupações históricas | **Não realizado** |

Attr: `:save/gym-permanence-daily-edn` (string EDN `{day-key → moedas}`).  
Com flag OFF o attr permanece ocioso (sem crédito).

---

## 6. Cutover

1. Compose atualizado para o **par** RC + `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false` em Node e JVM.
2. `force-recreate` JVM → healthy / bridge up / maintenance retomado a `open`.
3. `force-recreate` Node (admissão ainda controlada) → WhatsApp **READY**.
4. Resume Node → marker final **`open`**.
5. Caps preservadas: `gyms-read`, `team-csv-export`, `bag-daily`, `thematic-events-*`, `raid-auto-schedule`, etc.

---

## 7. Estado preservado (final)

| Item | Estado final |
| --- | --- |
| Node/JVM health | `healthy` |
| WhatsApp | `READY` · chromium true |
| Bridge | `up` · maintenance `open` · `activeRequests=0` |
| Raids automáticas | `raidAutoTickEnabled=true` |
| Próxima raid (à época do reopen) | `2026-11-05T23:30:00Z` |
| Evento temático piloto | **Noite das Sombras** ativo (`enabled=true`) |
| Bag kit diário | env `true` (capability `bag-daily` presente; sem claim nesta sessão) |
| Release Announcer | `RELEASE_ANNOUNCER_ENABLED=false` |
| Pokédex gate | `POKEDEX_SPECIES_PILOT_ONLY=true` · API `vistos=299` |
| Time Pokémon | `GET /v1/teams` + `/v1/teams/csv` OK (save `recovery-v1`) |
| Trocas A6 | `GET /v1/trades` OK (`trades=[]`) |
| Permanência economia | **OFF** Node+JVM |
| Maintenance gate | Node `open` (`resumedAt=22:58:09Z`) · JVM `open` (`resumed-at=22:56:09Z`) |

---

## 8. Homologação piloto (flag OFF)

Grupo: `120363427684548487@g.us`  
Leituras via bridge (não mutantes). Evidências: `homolog-*.json` no diretório do cutover.

### Motivação / tempo / HP (API)

| Ginásio | Ocupação | Motivação | Tempo defesa | Defensores (amostra HP) |
| --- | --- | --- | --- | --- |
| `pedra` | Samuel | `[88,88,88]` + `motivacaoGinasio=88` | `1h12m` (`tempoDefesaMs≈4.3e6`) | Onix hp 111 · Larvitar 72 · Snorlax 118 |
| `agua` | Samuel | `[95,95,95]` + `motivacaoGinasio=95` | `1h10m` | Bulbasaur 60 · Starmie 111 · Pikachu 54 |

- Campos `economiaPermanenciaAtiva` / `recompensaEstimada` **ausentes** com flag OFF.
- HP de collection/party do jogador de leitura permanece populado (ex.: Skwovet `hpAtual=73`).
- Decaimento sob demanda (valores de motivação < 100 com permanência >1h) coerente com DTO.

### Economia

| Check | Resultado |
| --- | --- |
| Flag OFF em runtime | Node `false` · JVM `false` |
| DTO sem estimativa de crédito | OK |
| Migration 034 presente | OK |
| Backfill / moedas de teste / saves manuais | **Não** |
| Vitória → 0 moedas (código RC) | coberto por testes locais; **smoke WA de conquista não executado** |

### Sprites / barras / desafio (WhatsApp)

**PENDENTE — execução manual no piloto** (não provocar derrota/evolução/pagamento em save real só para teste):

```text
!pk gin
!pk gin pedra
!pk gin agua
```

Validar visualmente: transparência/proporção/centralização dos sprites; barras de HP; motivação na mensagem; troca de combatente sem cura indevida.

### Regressão RO já observada

- Pokédex OK · teams/csv OK · trades list OK · thematic active OK · gyms overview OK.

---

## 9. Riscos e pendências

1. Smoke WA visual e desafio real ainda pendentes → status **CONDITIONAL**.
2. Rollback de **imagens** para o par Time Pokémon está disponível; **schema 034 é aditivo** e permanece no Datomic se as imagens forem revertidas (seguro com flag OFF). **Não** restaurar cold antigo sobre estado mais novo.
3. Evento temático estava/está ativo — não foi cancelado nem re-disparado.
4. Não ativar `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true` sem GO econômico separado.
5. Disco host ~13G livres (73% uso) — monitorar antes do próximo cutover.

---

## 10. Rollback operacional

| Item | Valor |
| --- | --- |
| Par anterior | tags/IDs da seção 2 “Antes” (ainda no Docker) |
| Compose bak | `…bak-pre-gym-20261105T225442Z` |
| Cold | `cold-20261105T224822Z` (só emergência; não sobrescrever live mais novo) |
| Procedimento | Drain → quiescência → recreate **par** anterior → health/READY → reopen |
| Schema | 034 permanece; manter flag OFF |

Fail-closed se houver ambiguidade de writers/schema parcial.

---

## 11. Conclusão

**CONDITIONAL** — cutover Node+JVM + migration 034 + serviços saudáveis + preservações + evidências RO de motivação/HP/economia-OFF no piloto.  
Homologação WhatsApp de sprites/barras/desafio e prova de “vitória = 0 moedas” em batalha real ficam como checklist manual antes de promover a **SUCCESS**.
