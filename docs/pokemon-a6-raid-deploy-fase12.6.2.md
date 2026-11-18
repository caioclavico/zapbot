# A6 — Deploy hotfix raides 12.6.2 (pós-GO definitivo)

**Decisão:** **SUCCESS**  
**Commit implantado:** `ea037ded39d958fe912b32acfe42c67e8a6a7176`  
**RC:** `~/odisseu-recovery/a6-fase12.6.2-rc-20261102T013909Z/`  
**Deploy artefatos:** `/home/caiohclavico/odisseu-recovery/a6-fase12.6.2-deploy-20261102T014609Z/`  
**VM:** `odisseu-core` (`35.192.113.146`)

---

## 1. Commit e imagens em execução

| | Valor |
| --- | --- |
| Git | `ea037ded39d958fe912b32acfe42c67e8a6a7176` |
| Content hash | `2252380c57bbf026b09bada27ccbd3f9f38bab5748bc78e05ef3fa92a33241d3` |
| JVM tag | `odisseu-jvm:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64` |
| JVM digest | `sha256:348e59cb05f06c3de24d95ed2e55905604b192e82781081dc3d144d5c3fa0b8a` |
| Node tag | `odisseu-node:a6-raid-cap-1262-20261102T013909Z-2252380c57bb-amd64` |
| Node digest | `sha256:2eb5c00703205b2d85f5f385dc40a383b8513a34dd02c8cf9fb6920622208f83` |
| SHA gate (load ≡ RC) | **PASS** |
| Labels | `io.zapbot.git-sha=ea037de…` · `fase=12.6.2` · content-hash ok |
| Migration nova | **Nenhuma** (031 preservada) |

---

## 2. Backups

| Tipo | Path | Manifest SHA-256 |
| --- | --- | --- |
| Warm | `/home/caiohclavico/odisseu-recovery/a6-periodic-backup-20261102T014609Z-warm1262` | `3b15eae347d68b3d927eef4c923661af70f183603c6c91a558912e47099d5c73` |
| Cold | `/home/caiohclavico/odisseu-recovery/a6-periodic-backup-20261102T014609Z-cold1262` | `30499815db5dba5a2ad92b3b7eb2414f17f88aee68d4f068d1fae0622d2d1dee` |

Rollback 12.6.1 registrado em `$DEPLOY/rollback/previous-versions.txt`:

- JVM `a6-raid-cap-1261-…7a71bca74aff` · `sha256:85ab9918…`
- Node `a6-raid-cap-1261-…7a71bca74aff` · `sha256:3a59711c…`

---

## 3. Sequência executada

| UTC (aprox.) | Passo |
| --- | --- |
| `01:46Z` | Transfer RC tars → Core; tar checksums OK |
| `01:48Z` | Snapshot 12.6.1 healthy · registro rollback |
| `01:48Z` | Backup **warm** + **cold** A6 |
| `01:50Z` | `docker load` + SHA gate PASS · patch compose |
| `01:50Z` | Freeze Node → recreate **JVM** → healthy |
| `01:50–01:51Z` | Recreate **Node** → **READY** (sem QR) |
| `01:55Z+` | Smoke piloto (seleção, vitória, captura, inapto, leave) |

---

## 4. Datomic / saves

| Check | Resultado |
| --- | --- |
| Schema 031 | preservada (sem migration nova; capability `raid-frambo` presente) |
| Piloto P1 ativo | `recovery-v1` · fence `2` |
| Piloto P2 ativo | `recovery-v1` · fence `4` |
| Volumes cutover | intactos (`odisseu-a6-cutover-*`) |
| `raidAutoTickEnabled` | `false` |
| `allowTestRng` | `false` |

---

## 5. Smoke tests (piloto)

Contas: `207700373848240@lid` (CaioSmoke), `5511946342101@c.us` (PeerSmoke)  
Chat: `120363427684548487@g.us`  
Artefatos: `$DEPLOY/smoke/results.json`, `mentions-live.json`, `inapto-result.json`

### Seleção

| Gate | Resultado | Evidência |
| --- | --- | --- |
| P1 `entrar auto` | **PASS** | `mode=auto` → **Arboliva** (melhor), ativo fraco era Wishiwashi |
| P2 `entrar` (ativo) | **PASS** | `mode=ativo` → **Quaxly** |
| Isolamento | **PASS** | P1 Arboliva inalterado quando P2 entrou |
| Reentrada lobby | **PASS** | P1 → Wishiwashi (`refreshed=true`); P2 Quaxly intacto |
| Ativo inapto (nv>liga) | **PASS** | `400 invalid_raid_pokemon` · `reason=liga` · Darmanitan nv.11 |

### Menções WhatsApp

| Gate | Resultado |
| --- | --- |
| Texto com `@CaioSmoke` / `@PeerSmoke` | **PASS** |
| `mentions` = `[lid, c.us]` | **PASS** |
| Sem `@lid` / telefone no texto | **PASS** |

Exemplo (present live no container Node):

```
🏆 *RAID CONCLUÍDA!*
👾 Chefe derrotado: *Onix*

👥 *PARTICIPANTES*
@CaioSmoke — Pokémon: Charizard
@PeerSmoke — Pokémon: Blastoise

🎁 *RECOMPENSAS*
@CaioSmoke
💰 +50 moedas
🏅 +6 PE
⭐ +6 XP
```

### Captura / recompensas

| Gate | Resultado | Evidência |
| --- | --- | --- |
| Vitória +50/+6/+6 ambos | **PASS** | rewards DTO `@lid` + `@c.us` |
| Captura sucesso | **PASS** | `roll=13` · `chance=35` · `capturou=true` · pokebola −1 |
| Captura falha live | **N/A RNG** | 1ª tentativa acertou (13<35); falha coberta por unit 12.6.2 |
| Frambo | **PASS (unit+path)** | fruta em inventário; prepare/uso cobertos em suite; live sem harness de roll |
| Leave ON | **PASS** | `POST /v1/battles/leave` → `no_battle` |
| Auto OFF | **PASS** | env + health |

**FINAL smoke:** 28/28 PASS (após gate inapto dedicado).

---

## 6. Invariantes finais

```
POKEMON_RAID_AUTO_TICK=false
ODISSEU_BRIDGE_RAID_AUTO_TICK=false
POKEMON_BATTLE_LEAVE_ENABLED=true
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
raidAutoTickEnabled=false
allowTestRng=false
WhatsApp READY (2026-11-02T01:51:22.663Z)
```

Containers healthy com tags/digests do RC 12.6.2.

---

## 7. Riscos / incidentes

| Item | Severidade | Nota |
| --- | --- | --- |
| Captura fail não reproduzida no live (RNG) | Baixa | Unit `captura-falha-preserva-janela-sem-fuga-definitiva` PASS; live capturou de 1ª |
| Ultra Bola esgotada no piloto | Baixa | Item de missão; smoke usou Pokébola; Frambo comprável |
| Smoke creditou +50/+6/+6 e 1 captura | Esperado | Contas piloto; `recovery-v1` preservado |
| Sem fuga definitiva | Info | Comportamento auditado; sem mudança de balance |

**Rollback não acionado.**

---

## 8. Escopo

- Sem `ALLOW_ALL_GROUPS=true`  
- Sem auto-raides ON  
- Sem migration/backfill  
- Sem tocar volumes a56  
- Sem mudanças fora do hotfix 12.6.2  
