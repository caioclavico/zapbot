# A6 Time Pokémon — RC Node+JVM e homologação WhatsApp

**Branch RC:** `rc/a6-time-pokemon`  
**Commit:** `d677429069e04f24913eb4622b1595acb881f1cd`  
**Pacote:** `rc-artifacts/a6-time-pokemon-rc-20261105T193639Z/`  
**Data:** 2026-11-05  
**Status:** **READY FOR PILOT** (sem deploy nesta sessão; smoke WA real aguarda GO)

## 1. Produção atual (fonte de verdade)

| Item | Valor |
| --- | --- |
| Node implantado | `odisseu-node:a6-pokedex-20261105T180900Z-5e6f9caea073-amd64` · `sha256:6c22cc067d39bc0336715c58d06061f0fe0bf626897728f6924cb181c35a8f4f` |
| JVM implantada | `odisseu-jvm:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64` (inalterada no cutover Pokédex) |
| Piloto | `120363427684548487@g.us` |
| Announcer | `RELEASE_ANNOUNCER_ENABLED=false` |
| Pokédex gate | `POKEDEX_SPECIES_PILOT_ONLY=true` |
| Raids auto | ON (preservar) |

## 2. Integração

Estratégia mínima: **fast-forward tip** `feat/a6-tm-xp-evolution-csv` → `rc/a6-time-pokemon` @ `d677429`.

A árvore já contém, em ordem:

1. Base announcer+drain (`dc56a99`)
2. Pokédex espécie + gate piloto (`e6cb000`)
3. Time Pokémon / XP / evo / CSV (`bb32fd7`…`d677429`)

Preservado: maintenance/drain, trocas A6, raids automáticas, Announcer (desligado), saves/Datomic schema (sem migration).

**Dependência de imagens:** Node e JVM **devem** ser recriados **em par** a partir do mesmo `TREE=d677429`.  
Motivo: DTO XP/evolução + CSV + `apply-pokemon-xp-tx` (auto-evo) na JVM; present/media/http no Node.  
Não combinar Node novo com JVM antiga.

## 3. Auditoria de idempotência XP

| Caminho | Proteção |
| --- | --- |
| `ganhar-xp-pokemon!` | **Não** exposto em `bridge.clj` / HTTP. Só testes + API interna. |
| Captura / ginásio / raide / reward | `operation-receipt` + payload-hash; retry → `:duplicate true` |
| `apply-pokemon-xp-tx` | Fragmento TX embutido na operação pai (não é endpoint) |

Testes: `pokemon.tests.xp-idempotency-a6-test` (bridge sem grant XP; captura retry não duplica XP/espécie).

**Resultado:** não BLOCKED — nenhum caminho externo reaplicável sem receipt.

## 4. Imagens RC

| Campo | Node | JVM |
| --- | --- | --- |
| Tag | `odisseu-node:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` | `odisseu-jvm:a6-time-pokemon-20261105T193639Z-d677429069e0-amd64` |
| Image ID | `sha256:dbf88e066e3d79055e1a392a4bd2d8b6b73f9490ba2f11c65ae55f317b52c315` | `sha256:381d67d7185a2e4ec0394513916a19577cfe5e8f843d5efa750351b0448679b6` |
| Tar SHA-256 | `b010397f617c429f8d18470dbd095608fbe5b32e2872023617a8710318faa6a8` | `a927975629e491c9f071bfe887491eedb634c944f003f0286d83c10b84bdf1c6` |
| Plataforma | `linux/amd64` | `linux/amd64` |
| Labels | `fase=a6-time-pokemon`, `git-tree=d677429…` | idem |

Smoke isolado Node (`docker run --network none`): `SMOKE_OK` · caption `🎮 Meu Time Pokémon`.

## 5. Testes (RC)

| Suíte | Resultado |
| --- | --- |
| Node TM/XP + characterization | **pass** |
| Node maintenance + trade UX + pokedex species/pilot/integration | **pass** (bundle anterior 41/41) |
| Node raid fase12 + maint + TM | **26/26 pass** |
| Clojure A6 TM/XP/evo + idempotência + a54 + xp-eq | **26 testes / 0 fail** |
| `formatError` trade (present) | **fail pré-existente** |
| `team-a552b` liga ineligible | **fail pré-existente** (CSV coberta em level-evo) |

## 6. Smoke WhatsApp (piloto — só com GO)

Grupo: `120363427684548487@g.us`

```
!pk tm
!pk tm txt
!pk tm csv
```

Checklist:

- [ ] PNG sem listagem na legenda (só `🎮 Meu Time Pokémon` + nav)
- [ ] XP `a/9` e barra coerentes; `NÍVEL MÁXIMO` / `XP ?` quando cabível
- [ ] Próxima evolução linear correta (ou omitida se especial)
- [ ] `txt` só texto, sem PNG
- [ ] CSV documento `odisseu-time-pokemon.csv`, sem PNG
- [ ] Sem mensagem duplicada
- [ ] Sem mutação inesperada (consulta não concede XP)
- [ ] Pokédex / raids / trocas / Announcer intactos

**Não enviar** mensagens reais sem autorização operacional.

## 7. Gates de cutover (antes do GO)

- [ ] Node/JVM healthy
- [ ] WhatsApp READY
- [ ] Raids auto ON; **sem** raid ativa no momento do recreate
- [ ] Maintenance drain → quiescent (Node **e** confirmar quiescência JVM writers)
- [ ] Backup Datomic restaurável **antes** do recreate JVM
- [ ] Exclusividade writers Datomic
- [ ] Rollback tars/tags carregados no host

Drain Node **não** implica quiescência JVM — tratar JVM à parte.

## 8. Procedimento de cutover (resumo — não executar agora)

1. Backup Datomic + compose runtime.  
2. Drain Node → quiescent.  
3. Confirmar ausência de TX/raid ativa na JVM.  
4. Load tars Node+JVM do pacote RC.  
5. Recreate **JVM** com tag RC (mesmo TREE).  
6. Recreate **Node** com tag RC + envs piloto (`POKEDEX_SPECIES_PILOT_ONLY`, `RELEASE_ANNOUNCER_ENABLED=false`).  
7. Health Node/JVM/WA READY.  
8. Resume maintenance `open`.  
9. Smoke piloto (seção 6).

## 9. Rollback

| Componente | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-pokedex-20261105T180900Z-5e6f9caea073-amd64` | `sha256:6c22cc067d39bc0336715c58d06061f0fe0bf626897728f6924cb181c35a8f4f` |
| JVM | `odisseu-jvm:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64` | (produção atual) |

Recreate em par inverso se necessário; restaurar backup Datomic **somente** se houver escrita indevida (não esperado em leitura `!pk tm`).

## 10. Riscos

1. Auto-evo linear pós-XP muda estado em caminhos de captura/ginásio/raide — coberto por receipts; espécies fora do catálogo não evoluem.  
2. Cutover JVM exige backup (primeira mudança JVM desde announcer-drain).  
3. Smoke WA ainda não executado.  
4. Emulação arm64 no smoke local de imagem amd64 — validar na VM amd64 de produção.

## 11. Classificação

**READY FOR PILOT**

Código + testes + imagens Node/JVM pareadas + auditoria de idempotência OK.  
Deploy e mensagens reais: **fora desta sessão** (aguardam GO operacional).
