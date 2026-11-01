# Checkpoint — GO `!pk treinador` perfil consolidado (Odisseu Core)

**Quando:** 2026-10-29 ~18:09–18:12 UTC  
**Autorização:** GO (chat) — apresentação master + saves/capacidade/economia A6  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| DTO `/v1/trainer` consolidado (somente leitura) | Migrations / reimport / Cassandra |
| `formatTrainer` com barra XP, CHEIO, saves, economia segura | Mutação de moedas/Pokémon/saves |
| Rebuild JVM + Node | Consultas com RNG de caça |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `trainer-profile-a64` + `capacity-storage` | **13 / 109 OK** |
| present Node | **14/14 OK** |
| `/v1/trainer` RO (2×) | fence/count inalterados |
| Migrations | **001–027** inalteradas |
| Cold | `~/odisseu-recovery/a64-trainer-profile-cold-20261029T180903Z/` |
| Restore | **17=17** · mom `365b0b15…e99d` |
| Pré | `recovery-v1` · 119/100 (+19) · bag 3/100 · fence 2 · econ false |

Imagens **antes:**
- JVM/Node `a64-capacity-inicial50-20261029T1655-amd64`  
  · `sha256:28399cd7…` / `sha256:e52c17b3…`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-trainer-profile-20261029T1810-amd64` | `sha256:3140b1073fec150c16b623559c3edffd48f779a0a451c2683d5ab3fd9c1c2d69` |
| **Node** | `odisseu-node:a64-trainer-profile-20261029T1810-amd64` | `sha256:760be5029249b93866e2abd6ea997c328544b4e4eee6a5065ed46053c62a9ae6` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-trainer-a64-trainer-profile-20261029T1810-amd64`

Env: `HUNT=1800` `JOY=1800` `IDLE=300` `ALLOW_ALL_GROUPS=true` `ODISSEU_BRIDGE_RESET=false`

---

## Exemplo real (`!pk treinador`)

```
🧢 *Treinador: Caio*
⭐ Nível 10
✨ PE 127 · próximo 10/23
[████░░░░░░] 10/23
🏅 Vitórias: 31
🎖️ PE extra: ginásios +90 • raides +6
🔥 Capturas: sequência 6 • recorde 10

📦 Armazenamento Pokémon: 119/100 — CHEIO (19 acima do limite)
👥 Equipe: 119 · ✨ Shiny: 1
⚡ Ativo: #94 *Nidoqueen* • Nv.18

💾 Save ativo: *recovery-v1*
• a: 1 Pokémon
• b: 0 Pokémon
• recovery-v1 👉: 119 Pokémon
_Gerenciar: !pk saves_

💰 Moedas: saldo histórico em reconciliação
🎒 Mochila de itens: 3/100
🎯 Caça: disponível
```

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** `2026-10-29T18:12:37.496Z` |
| Storage | **119/100 CHEIO (+19)** |
| Economia | `unknown-pending` · sem `Moedas: 0` |
| Mochila | **3/100** |
| Saves | `a`/`b`/`recovery-v1` · ativo `recovery-v1` · fence **2** |
| Integridade | 12/12 · 39 · 119 · **158** |
| RO pós | 2× GET trainer sem mudar fence/count |

Arquivos: `a64-trainer-profile-pre-integrity.json`, `a64-trainer-profile-post-integrity.json`.

---

## Rollback

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-trainer-a64-trainer-profile-20261029T1810-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```
