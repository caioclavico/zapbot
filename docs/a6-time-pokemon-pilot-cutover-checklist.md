# Checklist cutover — Time Pokémon piloto

**RC:** `rc/a6-time-pokemon` @ `d677429`  
**Pacote:** `rc-artifacts/a6-time-pokemon-rc-20261105T193639Z/`  
**Não executar** sem GO operacional.

## Pré

- [ ] Backup Datomic restaurável
- [ ] Compose runtime backup
- [ ] Tars Node+JVM no host (`BUILD_OK` + SHA-256 conferidos)
- [ ] Sem raid ativa
- [ ] Raids auto ON (manter)
- [ ] Announcer `ENABLED=false`
- [ ] `POKEDEX_SPECIES_PILOT_ONLY=true` + piloto `120363427684548487@g.us`

## Cutover

- [ ] Drain Node → quiescent
- [ ] Quiescência writers JVM (drain Node **não** basta)
- [ ] Load + recreate **JVM** tag RC
- [ ] Load + recreate **Node** tag RC
- [ ] Health Node/JVM/WA READY / bridge UP
- [ ] Maintenance `open`

## Smoke (piloto)

```
!pk tm
!pk tm txt
!pk tm csv
```

- [ ] Card-only + XP/evo
- [ ] Texto sem PNG
- [ ] CSV documento
- [ ] Sem duplicatas / sem mutação em leitura

## Rollback

Node `a6-pokedex-…5e6f9caea073` · JVM `a6-announcer-drain-…dc56a993cb3b` (ver `docs/a6-time-pokemon-rc-homologation.md`).
