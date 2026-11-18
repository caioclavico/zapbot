# A6 — Readiness pré-GO: Hotfix raides 12.6.2

**Branch:** `hotfix/a6-raid-capture-rewards-fase12.6`  
**Base live:** 12.6.1 `e80073f`  
**Modo:** preparação RC + testes — **sem** deploy, migrate, restart, recreate ou mutação de produção.

**Decisão:** **READY FOR GO** (aguarda autorização definitiva).

Auditoria de fuga / seleção: `docs/pokemon-a6-raid-hotfix-selecao-mencoes-fuga-fase12.6.2.md`

---

## 1. Commit e imagens RC

| Campo | Valor |
| --- | --- |
| Git SHA | `ea037ded39d958fe912b32acfe42c67e8a6a7176` |
| Content hash | `2252380c57bbf026b09bada27ccbd3f9f38bab5748bc78e05ef3fa92a33241d3` |
| JVM tag | `odisseu-jvm:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64` |
| JVM digest | `sha256:348e59cb05f06c3de24d95ed2e55905604b192e82781081dc3d144d5c3fa0b8a` |
| Node tag | `odisseu-node:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64` |
| Node digest | `sha256:2eb5c00703205b2d85f5f385dc40a383b8513a34dd02c8cf9fb6920622208f83` |
| Manifest | `696cd684838b34f2461dd6961a07922a47e159ea3f3bdd0ede1d77a93e4200c0` |
| RC dir | `~/odisseu-recovery/a6-fase12.6.2-rc-20261102T013909Z/` |
| Labels | `io.zapbot.git-sha=ea037de…` · `fase=12.6.2` · `content-hash=2252380c57bb…` |
| 031 na JVM | **presente** (`OK_031`) |
| Migration nova | **Nenhuma** (031 permanece) |

---

## 2. Gates funcionais confirmados

| # | Gate | Resultado |
| --- | --- | --- |
| 2 | `entrar` = só ativo; inapto → erro claro, **sem** fallback auto | **PASS** (`use-active-inapto-nao-faz-fallback-silencioso`) |
| 3 | `entrar auto` = melhor apto **só** da coleção do jogador | **PASS** (`multi-jogador-entrar-auto-nao-altera-outro`) |
| 4 | Menções `@lid` + `@c.us`, sem IDs técnicos no texto | **PASS** (node + `ENVELOPE_MENTIONS_OK`) |
| 5 | `{text,mentions}` no envelope → mensagens de vitória | **PASS** (`coerceRaidPresent` + `envelope`) |
| 6 | Captura OK/fail, 3 tentativas, expira 30 min, bola/Frambo | **PASS** (suite 12.6 + teste fuga 12.6.2) |
| 7 | Falha de captura **não** remove recompensas | **PASS** (coins/PE estáveis pós-fail + pós-expira) |

Preservado por desenho (sem alteração nesta fase): recovery-v1, fences, unknown-pending, cooldowns, battle leave ON, raids auto OFF, piloto.

---

## 3. Suítes de regressão (reexecutadas)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-capture-rewards-fase126` | **15/15 · 75 asserts · 0 fail** |
| `clojure -M:test-raid-fase12` | **8/8 · 25 · 0 fail** |
| `clojure -M:test-raid-hotfix-fase1231` | **4/4 · 28 · 0 fail** |
| `clojure -M:test-raid-join-value-too-large-fase125` | **7/7 · 30 · 0 fail** |
| `clojure -M:test-raid-lobby-fase2` | **23/23 · 76 · 0 fail** |
| `clojure -M:test-m3-raid` | **PASS** (0 fail / 0 error) |
| `node --test test/pokemon-jvm-raid-fase12.test.cjs` | **11/11** |
| `node --test test/pokemon-jvm-present.test.cjs` | **19/19** |
| Envelope mentions smoke | **PASS** |

---

## 4. Backup / deploy / smoke / rollback (após GO definitivo)

### 4.1 Backup (no GO, antes do recreate)

```bash
# Em odisseu-core — NÃO executar agora
A6_PERIODIC_BACKUP=1 A6_BACKUP_MODE=warm \
  ./scripts/a6-periodic-backup-a6.sh
# Preferível também cold se janela permitir:
A6_PERIODIC_BACKUP=1 A6_BACKUP_MODE=cold \
  ./scripts/a6-periodic-backup-a6.sh
```

Âncoras já existentes (não tocar): backups 12.6.1 warm/cold + RO readiness 12.6.1.

### 4.2 Deploy (somente após GO)

1. `docker load` JVM+Node do RC 12.6.2; gate SHA ≡ commit deste readiness.
2. Registrar rollback = tags **12.6.1** atuais live.
3. Freeze Node → recreate **JVM** (tag 12.6.2) → healthy / health `ok`.
4. Recreate **Node** → Autenticado + READY (sem QR).
5. **Sem** migration nova; **sem** alterar volumes/Datomic/sessão WA.
6. Manter envs: `POKEMON_BATTLE_LEAVE_ENABLED=true`, `POKEMON_RAID_AUTO_TICK=false`, `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`, `ALLOW_ALL_GROUPS=false`, piloto.

### 4.3 Smoke pós-deploy

| Check | Critério |
| --- | --- |
| 2 jogadores | A `entrar auto`, B `entrar` → mons distintos / ativos corretos |
| Ativo inapto | erro claro (sem auto silencioso) |
| Vitória WA | `@Nome` + mentions; sem `@lid` no texto |
| Captura fail | escapou da bola; ainda tenta; bola/Frambo −1 |
| Captura OK | roll∈[0,99]; chefe na coleção |
| Rewards | +50/+6/+6 1×; fail captura não reverte |
| Leave / auto | leave ON; auto tick false |

### 4.4 Rollback → 12.6.1

| Role | Tag rollback |
| --- | --- |
| JVM | `odisseu-jvm:a6-raid-cap-1261-20261102T004544Z-7a71bca74aff-amd64` |
| JVM digest | `sha256:85ab9918f5bed7536e7054641f60639ebf704dd018477f79cb9a32d9fe48ac20` |
| Node | `odisseu-node:a6-raid-cap-1261-20261102T004544Z-7a71bca74aff-amd64` |
| Node digest | `sha256:3a59711cf5e54cc210cc1c89a0df044bedf5d06abdea06c725d438f5722a4301` |

Procedimento: freeze Node → recreate JVM 12.6.1 → recreate Node → health/WA READY. Sem down-migration.

---

## 5. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Menção WA depende de pushname no join | Fallback `Treinador N`; JID ainda em `mentions` |
| Ativo inapto agora erra (antes entrava via auto) | UX intencional; jogador usa `auto` ou `entrar N` |
| Sem fuga definitiva | Documentado; balance inalterado até aprovação |
| Drift de imagem ≠ SHA | Gate load SHA ≡ RC obrigatório no GO |

---

## 6. Arquivos do hotfix (commit exclusivo)

- `modules/pokemon/application/.../bridge_tx.clj`
- `modules/pokemon/infrastructure/.../raid_store.clj`
- `scripts/lib/pokemon-jvm-present.cjs`
- `src/zapbot/pokemon_http.cljs`
- `modules/pokemon/tests/.../raid_capture_rewards_fase126_test.clj`
- `test/pokemon-jvm-raid-fase12.test.cjs`
- `docs/pokemon-a6-raid-hotfix-selecao-mencoes-fuga-fase12.6.2.md`
- `docs/pokemon-a6-raid-readiness-fase12.6.2.md`

---

## 10. Artefato RC

```
STAMP=20261102T013909Z
GIT_SHA=ea037ded39d958fe912b32acfe42c67e8a6a7176
CONTENT_HASH=2252380c57bbf026b09bada27ccbd3f9f38bab5748bc78e05ef3fa92a33241d3
JVM_TAG=odisseu-jvm:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64
JVM_ID=sha256:348e59cb05f06c3de24d95ed2e55905604b192e82781081dc3d144d5c3fa0b8a
NODE_TAG=odisseu-node:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64
NODE_ID=sha256:2eb5c00703205b2d85f5f385dc40a383b8513a34dd02c8cf9fb6920622208f83
MANIFEST=696cd684838b34f2461dd6961a07922a47e159ea3f3bdd0ede1d77a93e4200c0
OUT=~/odisseu-recovery/a6-fase12.6.2-rc-20261102T013909Z
DECISION=READY_FOR_GO_NO_DEPLOY
```

Tars: `images/odisseu-jvm-a6-raid-cap-1262.tar` (~249 MiB) · `images/odisseu-node-a6-raid-cap-1262.tar` (~371 MiB).

**Nenhum serviço foi reiniciado. Nenhum deploy foi executado.**
