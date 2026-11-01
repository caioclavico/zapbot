# A6 — Fase 12.5.1: GO `!pk sair` / battle-leave + fix Client API

**Quando (UTC):** 2026-11-01 ~19:13–19:22  
**Autorização:** GO (chat) após diagnóstico `FEATURE_DISABLED`  
**Host:** `odisseu-core` · stack `odisseu-a6-*`  
**Branch:** `hotfix/a6-raid-join-value-too-large-fase12.5`  
**Git SHA (fix):** `a6cc367a9490fe30d43be5245d4f213b74abfe96`

---

## Status final: **SUCCESS**

`!pk sair` / `POST /v1/battles/leave` habilitado em produção A6. Flag on + bug de query Client API corrigido. WhatsApp **READY** sem novo QR. Raides auto **OFF**; piloto exclusivo.

---

## 1. Escopo

| Incluído | Excluído |
| --- | --- |
| `POKEMON_BATTLE_LEAVE_ENABLED=true` | Migrations / Cassandra / economia |
| Fix `:find` relacional em `open-battle-id-fallback` | Deploy Node 12.5 (join VTL) |
| Rebuild + recreate **somente JVM** | Auto-tick de raides |
| Gates `left` (lobby) + `no_battle` + cap health | Rollback destrutivo |

---

## 2. Causa do 500 pós-flag

Com a flag ligada na imagem `hotfix1231`, `POST /v1/battles/leave` retornava **500** `internal_error`:

```
Only find-rel elements are allowed in client :find
```

`open-battle-id-fallback` usava `:find [?id ...]` (collection find). A Client API do Datomic Local só aceita find relacional. Sem `active-battle-id`, todo `!pk sair` caía nesse path — inclusive `no_battle`.

**Correção:** `:find ?id` + `(mapv first …)`.

Testes locais: **12 / 79 OK** (`pokemon.tests.battle-leave-test`, incl. `saida-sem-nunca-ter-batalhado`).

---

## 3. Artefatos

| Item | Valor |
| --- | --- |
| RC | `~/odisseu-recovery/a6-battle-leave-fix-rc-20261101T191747Z/` |
| Enable log | `~/odisseu-recovery/a6-battle-leave-enable-20261101T191306Z/` |
| Backup warm | `~/odisseu-recovery/a6-periodic-backup-20261101T191306Z/` |
| JVM tag | `odisseu-jvm:a6-battle-leave-fix-20261101T191747Z-1de61da40a57-amd64` |
| JVM ID | `sha256:d5e7fe6a1e892b293f74147a6933fc2fdc4921ff9f57c5f1cc7f8c20a6f2d6c5` |
| Node (inalterado funcional) | `odisseu-node:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` · `sha256:f1833b277245…` |

Compose runtime: `…/a6-cutover-runtime-20261101T141408Z/docker-compose.a6-cutover-runtime.yml`  
Env JVM: `POKEMON_BATTLE_LEAVE_ENABLED=true` · `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`

---

## 4. Gates

| Probe | Resultado |
| --- | --- |
| Health + cap `battle-leave` | **PASS** · `raidAutoTickEnabled=false` |
| Leave com jogador no lobby | **200** `status=left` `modality=raid` |
| Leave sem batalha/raid | **200** `status=no_battle` |
| Rota inventada `/v1/battles/leave-does-not-exist` | **404** |
| Fence piloto | **2** preservada |
| WA | Autenticado `19:22:00Z` · READY `19:22:02Z` · **sem QR** |
| Allowlist | `ALLOW_ALL_GROUPS=false` · piloto `120363427684548487@g.us` |

---

## 5. Incidente durante o GO

Durante o recreate, o compose chegou a apontar temporariamente para tags `a6-raid-join-vtl-…` (RC 12.5) e o Node saiu. Havia smoke paralelo `fase12.5-smoke-open-20261101T192147Z` no chat piloto.

**Recuperação:** compose restaurado para JVM leave-fix + Node hotfix1231; Node recriado; WA READY sem QR. O gate de leave removeu o jogador piloto daquele lobby de smoke (1 participante PeerSmoke permaneceu).

---

## 6. Rollback (não destrutivo)

```bash
RUNTIME=~/odisseu-recovery/a6-cutover-runtime-20261101T141408Z
# Opção A — só desligar leave:
#   POKEMON_BATTLE_LEAVE_ENABLED=false + recreate JVM
# Opção B — imagem anterior hotfix1231:
cp ~/odisseu-recovery/a6-battle-leave-enable-20261101T191306Z/rollback/docker-compose.a6-cutover-runtime.yml \
   $RUNTIME/docker-compose.a6-cutover-runtime.yml
cd $RUNTIME && docker compose --env-file .env.a6-cutover \
  -f docker-compose.a6-cutover-runtime.yml \
  up -d --no-deps --force-recreate odisseu-a6-jvm
```

Não executar rollback destrutivo automaticamente.

---

## 7. Relação com Fase 12.5 (join VTL)

A imagem JVM leave-fix foi buildada a partir da branch que **já contém** o slim `pokemon-edn` da 12.5. O Node permanece na hotfix1231 (present `DATOMIC_VALUE_TOO_LARGE` da 12.5 ainda não está no Node live). Deploy completo Node 12.5 **não** faz parte deste GO.
