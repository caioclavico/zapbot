# A6 — Fase 12.5: `!pk raide entrar` — value too large

**Data (UTC):** 2026-11-01  
**Branch:** `hotfix/a6-raid-join-value-too-large-fase12.5`  
**Git SHA:** `cc11dbf185a1f02849689b91b69e63a638309ef9`  
**Artefatos RC:** `~/odisseu-recovery/a6-fase12.5-rc-20261101T190850Z/`  
**Modo:** correção mínima + testes + RC imutável — **sem** deploy, recreate, restart, migrate ou mutação de saves live.

Produção live (Fase 12.3.2) permanece intocada nesta fase.

---

## Decisão: **READY**

| Gate | Resultado |
| --- | --- |
| Causa raiz `Item too large` / `DATOMIC_VALUE_TOO_LARGE` no join | **PASS** (comprovada + corrigida) |
| Slim + gzip no `:raid-participant/pokemon-edn` (paridade caça/Joy) | **PASS** |
| Suite sintética Fase 12.5 | **PASS** |
| Smoke alias `raid-join-value-too-large-fase125` | **PASS** (registrado) |
| Schemas / migrations | **intactos** |
| Deploy / recreate / mutate saves live | **não executado** |
| Raides automáticas | **OFF** (inalterado) |

`ALLOW_ALL_GROUPS=false` e piloto exclusivo permanecem obrigatórios no próximo GO.

---

## 1. Causa raiz

**Sintoma (piloto WhatsApp):** `!pk raide entrar` → falha com valor Datomic grande demais (`Item too large` / código `DATOMIC_VALUE_TOO_LARGE`).

**Cadeia:** WhatsApp → Node → `POST /v1/raids/join` → `join-raid!` → TX com `:raid-participant/pokemon-edn` = EDN **cru** do mon da coleção.

**Evidência:**

1. Datomic Local limita blobs ~4096 bytes por valor.
2. Coleção canônica pode carregar `historico`/ofertas/legado grandes (cabe no attr do save; estoura no snapshot do participante).
3. Caça / ginásio / Joy já usam `slim-pokemon-for-battle` + `encode-value-edn` (gzip se plain > limite).
4. Raid join **não** aplicava o mesmo encode — única divergência.

**Não é** o mesmo bug de `!pk sair` (ver §5).

---

## 2. Correção (mínima)

| Arquivo | Mudança |
| --- | --- |
| `modules/.../datomic/raid_store.clj` | `encode-participant-pokemon-edn` → slim + `battle/encode-value-edn` |
| `scripts/lib/pokemon-jvm-present.cjs` | mensagem amigável para `DATOMIC_VALUE_TOO_LARGE` |
| `modules/.../raid_join_value_too_large_fase125_test.clj` | repro sintético + regressões |
| `apps/odisseu/.../raid_join_value_too_large_fase125.clj` | smoke alias |
| `deps.edn` | aliases de teste/smoke |

Coleção canônica **não** é truncada; só o snapshot de combate do participante.

---

## 3. RC imutável

| Campo | Valor |
| --- | --- |
| Stamp | `20261101T190850Z` |
| Content short | `7ad66c1b42e3` |
| Node tag | `odisseu-node:a6-raid-join-vtl-20261101T190850Z-7ad66c1b42e3-amd64` |
| JVM tag | `odisseu-jvm:a6-raid-join-vtl-20261101T190850Z-7ad66c1b42e3-amd64` |
| Node ID | `sha256:60c18addeaae8ec83d6a63649b9948e1b6017c4fd2a798c11d9a52caf19ae653` |
| JVM ID | `sha256:64cd11ab0c652553c1148dbcef676dcd44c1ae6582b845c392f3c84300be086f` |
| Decisão | **READY** |

Tars em `images/`; checksums em `checksums/`.

---

## 4. Plano de implantação (aguardando GO)

1. Backup warm + pre-snap (volumes `a56-*` intactos; sem migrate).
2. Load das imagens RC; atualizar compose com as tags acima.
3. Recreate JVM → Node (sem `-v`); auto-tick OFF; piloto exclusivo.
4. Gate: join com mon “gordo” (histórico) → 200; sem `DATOMIC_VALUE_TOO_LARGE`.
5. Smoke controlado open→join→status (sem recompensas artificiais).
6. Rollback = imagens Fase 12.3.2.

---

## 5. Achado paralelo (fora do escopo 12.5): `!pk sair`

**Sintoma:** `!pk sair` (e também `!pk raide sair`) → *“temporariamente desabilitado”*.

**Causa (RO live, 2026-11-01):**

| Camada | Achado |
| --- | --- |
| Node | ambos os `sair` → `leaveBattle` → `POST /v1/battles/leave` |
| JVM | gated por `POKEMON_BATTLE_LEAVE_ENABLED` (default/`live` = **false**) |
| Probe | HTTP **503** `feature_disabled` — *“Saída de batalha temporariamente desabilitada.”* |
| Health | capability `battle-leave` **ausente** |
| Logs Node | `FEATURE_DISABLED 503` |

**Conclusão:** feature flag intencional (docs `pokemon-a6-4-battle-leave-prepare.md` / checkpoint). **Não** é `value too large`. Código de leave já existe na imagem; falta GO para `POKEMON_BATTLE_LEAVE_ENABLED=true` (+ recreate JVM).

`POST /v1/raids/leave` (lobby) existe na bridge, mas o handler WhatsApp de `sair` **não** o usa hoje.

---

## 6. Restrições respeitadas

- Sem deploy / migrations / Cassandra / mutação de saves/economia/grupos/cooldowns em produção.
- Sem habilitar battle-leave nesta fase.
- Sem ligar raides automáticas.
