# Relatório — cutover Pokédex piloto

**Status:** CONDITIONAL  
**Motivo CONDITIONAL:** cutover Node SUCCESS; smoke WhatsApp real **pendente** (sem meio autenticado para enviar como participante no piloto).

## Cutover

| Item | Valor |
| --- | --- |
| Quando | 2026-11-05 ~18:35 UTC |
| Host | odisseu-core / 35.192.113.146 |
| Container | só `odisseu-a6-node` recreate |
| Tag | `odisseu-node:a6-pokedex-20261105T180900Z-5e6f9caea073-amd64` |
| Image ID | `sha256:6c22cc067d39bc0336715c58d06061f0fe0bf626897728f6924cb181c35a8f4f` |
| JVM | **não** recriada (Started 17:11:50Z preservado) |
| Pilot env | `POKEDEX_SPECIES_PILOT_ONLY=true` · `RELEASE_PILOT_CHAT_ID=120363427684548487@g.us` |
| Announcer | `RELEASE_ANNOUNCER_ENABLED=false` |
| Maintenance | drain → quiescent → recreate → resume **`open`** |
| WhatsApp | READY |
| Bridge | UP |
| Raids | ON · nextAt **19:45 UTC** · rev 32 · lastRaid spawn 18:30 preservado |

## Smoke WhatsApp (manual — executar no piloto)

```
!pokedex pikachu
!dex 150
!pdx gengar
```

Pipeline interno no container (PokeAPI+moldura) validado pós-deploy; **não** substitui o smoke no chat.

## Rollback

`odisseu-node:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64`  
`sha256:1dcb24f7b2fcc3c692c07eba306f67e091b959f1f3959dc405ab2de48462800d`  
Backup compose: `docker-compose.a6-cutover-runtime.yml.bak-pre-pokedex-20261105T181640Z`
