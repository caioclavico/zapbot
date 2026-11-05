# Checklist — cutover ginásios A6 (após GO)

**RC:** `rc/a6-gym-motivation-rewards` @ `2ec5f97`  
**Doc:** `docs/a6-gym-rc-homologation.md`  
**Pacote:** `rc-artifacts/a6-gym-rc-20261105T222938Z/`

**Não executar sem GO operacional.** Migration 034 aplica no boot da JVM RC.

## Preflight

- [ ] Confirmar tags live = par Time Pokémon (`d677429…`)
- [ ] Node/JVM healthy · WA READY
- [ ] Raids auto ON · sem raid ativa
- [ ] Announcer `false` · Pokédex gate piloto
- [ ] `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED` ausente ou `false`
- [ ] Tars + SHA-256 conferidos

## Cutover

- [ ] Drain Node → quiescent
- [ ] Quiescência JVM writers
- [ ] Backup cold + restore isolado OK
- [ ] Aceite explícito migration 034 (aditiva)
- [ ] Load + recreate **JVM** tag RC
- [ ] Load + recreate **Node** tag RC (mesmo tree)
- [ ] Health + WA READY
- [ ] Maintenance `open`

## Homologação piloto (flag off)

- [ ] `!pk gin` / detalhe com motivação
- [ ] Desafio: HP inicial correto · sprites sem caixa
- [ ] Vitória: 0 moedas · PE/insígnia OK
- [ ] Regressão: `!pk tm` · Pokédex · troca · raid

## Pós-cutover (GO separado)

- [ ] Flip `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`
- [ ] Ensaio permanência ≥30 min / teto diário
