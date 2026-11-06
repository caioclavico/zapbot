# Checklist — cutover ginásios A6 (após GO)

**RC:** `rc/a6-gym-motivation-rewards` @ `2ec5f97`  
**Doc:** `docs/a6-gym-rc-homologation.md`  
**Pacote:** `rc-artifacts/a6-gym-rc-20261105T222938Z/`

**Não executar sem GO operacional.** Migration 034 aplica no boot da JVM RC.

## Preflight

- [x] Confirmar tags live = par Time Pokémon (`d677429…`)
- [x] Node/JVM healthy · WA READY
- [x] Raids auto ON · sem raid ativa
- [x] Announcer `false` · Pokédex gate piloto
- [x] `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED` ausente ou `false`
- [x] Tars + SHA-256 conferidos

## Cutover

- [x] Drain Node → quiescent
- [x] Quiescência JVM writers
- [x] Backup cold + restore isolado OK
- [x] Aceite explícito migration 034 (aditiva)
- [x] Load + recreate **JVM** tag RC
- [x] Load + recreate **Node** tag RC (mesmo tree)
- [x] Health + WA READY
- [x] Maintenance `open`

## Homologação piloto (flag off)

- [x] Motivação/tempo/HP via bridge RO (`homolog-gyms.json`) — ver relatório CONDITIONAL
- [ ] Desafio: HP inicial correto · sprites sem caixa (**WA manual**)
- [ ] Vitória: 0 moedas · PE/insígnia OK (**WA manual**)
- [x] Regressão RO: teams/csv · Pokédex · trades · thematic/raid flags

Relatório: `docs/a6-gym-production-cutover-report.md` (**CONDITIONAL**)

## Pós-cutover (GO separado)

- [ ] Flip `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`
- [ ] Ensaio permanência ≥30 min / teto diário
