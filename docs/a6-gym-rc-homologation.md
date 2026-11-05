# A6 Ginásios — RC Node+JVM e homologação

**Branch RC:** `rc/a6-gym-motivation-rewards`  
**Tree imagens:** `2ec5f97649ef32a320007dbb4a36b6eb9bf9612a`  
**Pacote:** `rc-artifacts/a6-gym-rc-20261105T222938Z/`  
**Data:** 2026-11-05  
**Status:** **READY FOR PILOT** (sem deploy / sem migration em produção / sem flip de flag nesta sessão)

Feature: `docs/a6-gym-motivation-rewards.md` · origem `feat/a6-gym-motivation-rewards` @ tip RC.

---

## 1. Produção atual (fonte de verdade)

Fonte: `docs/a6-time-pokemon-production-cutover-report.md` (cutover Time Pokémon).  
SSH ao host de produção **não** foi usado nesta preparação (escopo: só RC local).

| Item | Valor |
| --- | --- |
| Node implantado | `odisseu-node:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` · `sha256:dbf88e066e3d79055e1a392a4bd2d8b6b73f9490ba2f11c65ae55f317b52c315` |
| JVM implantada | `odisseu-jvm:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` · `sha256:381d67d7185a2e4ec0394513916a19577cfe5e8f843d5efa750351b0448679b6` |
| Tree produção | `d677429069e04f24913eb4622b1595acb881f1cd` |
| Piloto | `120363427684548487@g.us` |
| Announcer | `RELEASE_ANNOUNCER_ENABLED=false` |
| Pokédex gate | `POKEDEX_SPECIES_PILOT_ONLY=true` |
| Raids auto | ON (preservar) |
| Eventos temáticos | flag/schedule conforme ativação vigente (preservar) |
| Permanência ginásio | **não implantada**; flag default `false` |

Não reutilizar imagens Time Pokémon / Pokédex / announcer-drain para este RC — faltam HP/sprites/economia 8h/UX.

---

## 2. Integração

Estratégia: tip `feat/a6-gym-motivation-rewards` → branch `rc/a6-gym-motivation-rewards` @ `2ec5f97` (FF do tip de feature).

`d677429` (Time Pokémon) é ancestral do tip RC. A árvore contém, em ordem:

1. Base announcer+drain / Pokédex / Time Pokémon (`d677429`+)
2. Política de permanência + teto 100/dia (`882270c` / `5153373`)
3. Reforma ginásio: HP, sprites, fórmula 8h/100, vitória=0 moedas, UX (`b39a7a9`…`f974fe9`)
4. Teste migration 034 descartável (`24e02a3` / `2ec5f97`)

**Preservado:** Pokédex A6, Time Pokémon, trocas, raids automáticas, eventos temáticos, bag kit diário, maintenance/drain, Release Announcer (desligado).

**Dependência de imagens:** Node e JVM **devem** ser recriados **em par** a partir do mesmo `TREE=2ec5f97…`.  
Motivo: DTO/batalha/HP + migration 034 + ledger na JVM; present/media/http/arena no Node.

---

## 3. Migration 034 (local apenas)

Arquivo: `migrations/pokemon/034-gym-permanence-daily.edn`  
Attr aditivo: `:save/gym-permanence-daily-edn` (string EDN `{day-key → moedas}`).

| Ensaio (Datomic descartável) | Resultado |
| --- | --- |
| Instalação limpa via `schema/install!` | OK |
| Reaplicação idempotente | OK (mesmo `:db/id`) |
| Save legado sem o attr | OK (`nil` → válido) |
| Escrita/leitura do mapa diário + teto | OK |
| Suite `gym-migration-034-a6-test` | **3/3 pass** |

**Atenção operacional:** `schema/install!` aplica migrations pendentes no boot da JVM.  
Cutover deste RC **implica** aplicar 034 no volume Datomic (aditivo).  
Requer GO explícito + backup cold restaurável **antes** do recreate JVM.  
**Não aplicado em produção nesta etapa.**

Rollback operacional sem restore destrutivo: manter flag `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false` (attr pode permanecer unused). Remoção de schema Datomic não é suportada — rollback de imagem para o par Time Pokémon.

---

## 4. Economia (confirmada nos testes)

| Regra | Evidência |
| --- | --- |
| Vitória/conquista = 0 moedas | `recompensa-vitoria` + `gym_m35` / permanence |
| < 30 min = 0 | domínio + store (flag on) |
| 8 h = 100; >8 h = 100 | `permanencia-bruta-moedas` |
| Teto diário compartilhado | dois ginásios / saldo 99→100 |
| Crédito só no encerramento | gym-store + receipt `gym-reward:…` |
| Flag default off | `permanence-rewards-enabled?` false; sem crédito |
| Sem caminho alternativo de moedas por vitória | `coins-delta` de vitória = 0 |

Concorrência CAS ocupação: `gym_m36_test` **pass**.

---

## 5. HP, motivação e imagens

| Item | Resultado |
| --- | --- |
| HP inicial / nil / parcial / 0 legítimo | `gym_battle_hp_a6_test` **pass** |
| Motivação → resistência na cópia | fator + `hp-atual` efetivo cheio |
| HP persistido intocado pelo tempo | domínio |
| Sprites alpha / sem cantos opacos | `docs/evidence/a6-gym/report.json` (`corners*=0`) |
| PNGs inspecionados | `battle-hp-full/partial`, `sprite-*`, `motivacao-*`, `retorno-defensor` |

---

## 6. Imagens RC

Pacote: `rc-artifacts/a6-gym-rc-20261105T222938Z/` (`BUILD_OK`)

| Campo | Node | JVM |
| --- | --- | --- |
| Tag | `odisseu-node:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` | `odisseu-jvm:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` |
| Image ID | `sha256:cdb25591680f8f63d33923282ad8c25a4ffeee6fc4f50da5847ac96d97c6c094` | `sha256:629391dc1930dea6c9360b503b929b78e856f10f1868517e9fb459031ed06d14` |
| Tar SHA-256 | `dd22915fc85249a6aa5444d686f631997b1681ca5618363cea7a4f402b4a94b4` | `7e0be8a60fcb84ba8f6feecf9a6293abb0efb4a42435f287e9be3a0efccd0b18` |
| Plataforma | `linux/amd64` | `linux/amd64` |
| Labels | `fase=a6-gym-motivation-rewards`, `git-tree=2ec5f97…` | idem |

Smoke isolado:

- Node (`docker run --network none`): `SMOKE_OK` · Node `v22.23.3` · `target/main.js` presente  
- JVM: `034-gym-permanence-daily.edn` + `gym.clj` presentes · `JVM_SMOKE_OK`

### Config cutover (preservar)

```bash
POKEDEX_SPECIES_PILOT_ONLY=true
RELEASE_ANNOUNCER_ENABLED=false
POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false   # obrigatório no 1º cutover
# raids auto / thematic: manter valores atuais de produção
```

### Rollback (par anterior)

| Serviço | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` | `sha256:dbf88e066e3d79055e1a392a4bd2d8b6b73f9490ba2f11c65ae55f317b52c315` |
| JVM | `odisseu-jvm:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` | `sha256:381d67d7185a2e4ec0394513916a19577cfe5e8f843d5efa750351b0448679b6` |

---

## 7. Testes executados (RC)

| Suite | Resultado |
| --- | --- |
| `gym-migration-034-a6-test` | **3/3 pass** |
| `gym-battle-hp-a6-test` | **3/3 pass** (17 asserts) |
| `gym-permanence-rewards-a6-test` | **7/7 pass** (33 asserts) |
| `gym-m36-test` (concorrência) | **2/2 pass** |
| `test/pokemon-jvm-gym.test.cjs` | **7/7 pass** |
| Evidências PNG + `report.json` | regeneradas / corners 0 |

---

## 8. Plano de cutover (NÃO executar agora)

1. **Preflight** — disco, health Node/JVM, WA READY, raids auto ON, Announcer off, sem raid ativa.  
2. **Drain Node** → `quiescent` (`activeOperations=0`).  
3. **Quiescência JVM** writers (`activeRequests=0`) + exclusividade de lock Datomic.  
4. **Backup cold** Datomic + manifesto SHA-256.  
5. **Restore isolado** + open JVM descartável (validar boot).  
6. **Migration 034** — ocorre no boot da JVM RC via `install!` (GO explícito necessário). Validar attr no restore isolado antes do cutover real.  
7. **Load tars** Node+JVM do pacote RC; recreate **JVM** depois **Node** (mesmo TREE).  
8. Envs: permanência **false**; Pokédex gate; Announcer false; raids/events intactos.  
9. Health + WA READY + resume maintenance `open`.  
10. **Homologação piloto** (sem flag econômica):
    - `!pk gin` / `!pk gin pedra`
    - Desafiar e conferir HP inicial + sprites
    - Vitória: **sem** moedas por conquista; PE/insígnia OK
    - Motivação no detalhe
    - Regressão: `!pk tm`, Pokédex, troca, raid status
11. **Ativação posterior** da flag `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true` — só após GO econômico separado (não neste cutover).  
12. **Rollback** — recreate par Time Pokémon; restore Datomic só se escrita indevida.

---

## 9. Riscos

1. Boot da JVM RC aplica 034 automaticamente — planejar GO de schema junto com cutover de imagem.  
2. Emulação amd64 em host arm64 no smoke local — validar na VM amd64.  
3. Smoke WhatsApp real **não** executado.  
4. Confirmação live das tags de produção via SSH **não** refeita nesta sessão (usar relatório Time Pokémon + revalidar no preflight do GO).  
5. Flag off no 1º cutover: jogadores veem UX/HP/sprites; **não** recebem moedas de permanência até flip autorizado.

---

## 10. Próximos passos

1. Preflight live no host (tags atuais, health, raids).  
2. GO operacional de cutover Node+JVM **com** aceite da migration 034 aditiva.  
3. Homologação piloto (seção 8.10).  
4. GO separado para `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`.

---

## 11. Classificação

**READY FOR PILOT**

Código + testes (HP, economia, concorrência, migration 034 descartável) + imagens Node/JVM pareadas amd64 + evidências visuais OK.  
Flag econômica permanece **false**.  
Deploy, migration em produção, pagamento real e flip de flag: **fora desta sessão**.
