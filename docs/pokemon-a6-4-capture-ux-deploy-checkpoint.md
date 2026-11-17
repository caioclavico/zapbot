# Checkpoint — GO captura UX (sem “carimbada”) · Odisseu Core

**Quando:** 2026-10-29 ~21:24–21:28 UTC  
**Autorização:** GO (chat) — referência `docs/pokemon-a6-4-capture-carimbada-audit.md`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| Present: KO → menu de bolas; remove “carimbada” | Fórmulas RNG / HP / status |
| DTO turno: `inventory` + `tentativasCaptura` + `somenteBatalha` | Migrations / reimport / Cassandra |
| Rebuild JVM + Node | Capturas artificiais em produção |
| Preservar `!pk capturar` como alternativa | Rollback destrutivo automático |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `capture-m33` | **15 / 15** (124 asserts) |
| `encounter-m44` | **10 / 10** (+ regressões M3.3/M3.4 + smoke HTTP OK) |
| Node present (capture / battle-ui / hunt) | Vitória/menu/falha **OK** · 1 fail pré-existente `formatWorldInfo` (não ligado) |
| Fórmula `domain/capture.clj` | **inalterada** · paridade fixtures master |
| Patch | Sem nova rolagem · sem mudança de persistência de captura |
| Cold | `~/odisseu-recovery/a64-capture-ux-cold-20261029T212408Z/` |
| Restore | **19=19** · mom `37b8fa859e31489e23ced26d68c89afddf438553805b58624d3ccb5b39fc086d` |

Imagens **antes:** JVM/Node `a64-professor-20261029T1925-amd64`  
· `sha256:a8c3eaef…` / `sha256:0f553aa9…`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-capture-ux-20261029T2124-amd64` | `sha256:af4519b3a0d5b8633d21db8813239d81e7ca0aab9af83e17e36c93c1a07ee829` |
| **Node** | `odisseu-node:a64-capture-ux-20261029T2124-amd64` | `sha256:259c45e66a423ab5707390483d07e9d01a9dba5f78e56c4e369774c5dafbc246` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-capture-ux-a64-capture-ux-20261029T2124-amd64`

Env JVM: `LEAVE=true` `HUNT=1800` `JOY=1800` `IDLE=300` `BRIDGE_RESET=false`  
Env Node: `ALLOW_ALL_GROUPS=true` `IDLE=300`  
Volumes: `odisseu-core-a56-{datomic,journal,maint,data}` preservados

Arquivos rsync (autorizados): `bridge_tx.clj` · `scripts/lib/pokemon-jvm-present.cjs`

---

## Validação (sem captura real)

| Probe | Resultado |
|-------|-----------|
| Caps | `encounter-capture` · `encounter-read` presentes |
| Fixture KO → menu | menu de bolas imediato · **sem** “carimbada” |
| Menu não captura sozinho | sem texto “captura concluída” |
| `!pk capturar` alternativo | `formatCaptureMenu` OK (75% pokébola com base 80) |
| Falha não concede | texto de miss **sem** Pokédex |
| Mutação produção | **none** |

### Exemplo do novo menu (fixture implantada)

```
🏁 *Magikarp* foi derrotado!
🏖️ Praia
🐾 *Squirtle* — [███████░░░] 70/100

🎯 *Escolha a bola para capturar Magikarp!*
!pk capturar 1 — 🔴 Pokébola — 5 disponível(is) — 75% de chance
!pk capturar 2 — 🔵 Grande Bola — 2 disponível(is) — 88% de chance
!pk capturar 3 — 🟡 Ultra Bola — 1 disponível(is) — 95% de chance

Tentativas restantes: 3 de 3.
📖 Como capturar: !pk capturar <bola>
```

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** `2026-10-29T21:27:57.049Z` |
| Integridade | 12/12 · 39 · 119 · **158** · 13 treinadores |
| User | `recovery-v1` · fence **2** · **119** |
| Cooldowns | Hunt **1800** · Joy **1800** · Idle **300** |
| Groups | `ALLOW_ALL_GROUPS=true` |

Arquivo: `~/odisseu-recovery/a64-capture-ux-post-integrity.json`

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-capture-ux-a64-capture-ux-20261029T2124-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Não executar rollback destrutivo automaticamente.
