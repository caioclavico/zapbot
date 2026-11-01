# Checkpoint — GO correção `Equipe` em `!pk treinador` (Odisseu Core)

**Quando:** 2026-10-29 ~18:24–18:28 UTC  
**Autorização:** GO (chat) — somente leitura/apresentação; sem mutar equipe/coleção  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| `party-battle-resumo` na JVM (`/v1/trainer`) | Migrations / reimport / Cassandra |
| Present: `Equipe: N` só com evidência; senão `não definida` | Mutação de `inParty`, ativos, saves |
| Rebuild JVM + Node | Reorganização automática da coleção |
| Coleção / shiny / ativo independentes | Economia, grupos, cooldowns |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `trainer-profile-a64-test` | **7 / 49 OK** |
| present Node | **15 / 15 OK** |
| Diff | só `bridge_reads.clj` + `pokemon-jvm-present.cjs` (+ testes) |
| Persistência / batalha | **inalteradas** |
| Migrations | **001–027** inalteradas |
| Cold | `~/odisseu-recovery/a64-party-fix-cold-20261029T182420Z/` |
| Restore | **17=17** · mom `c33da3a67dac…8d39` |
| Pré RO | `recovery-v1` · 119/100 (+19) · **partyCount=119** (bug) · shiny 1 · Nidoqueen 18 · fence 2 |

Imagens **antes:**
- JVM/Node `a64-trainer-profile-20261029T1810-amd64`  
  · `sha256:3140b107…` / `sha256:760be502…`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-party-fix-20261029T1825-amd64` | `sha256:e60a85c17deb210d311b6e8bdb850e23d9a8444b9f958927e2e3527e199ddf12` |
| **Node** | `odisseu-node:a64-party-fix-20261029T1825-amd64` | `sha256:d9a77ec9e854a5012720d369d0bbbf4de7d2086395cfb50e9b7e2a8fad62c342` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-party-a64-party-fix-20261029T1825-amd64`

Env: `HUNT=1800` `JOY=1800` `IDLE=300` `ALLOW_ALL_GROUPS=true` `ODISSEU_BRIDGE_RESET=false`

Volumes preservados: `odisseu-core-a56-{datomic,journal,maint,data}`

---

## Heurística (somente leitura)

- Coleção vazia → `partyDefined=true`, `partyCount=0`
- Mistura `in-party` + fora → equipe definida com `partyCount = count(in-party)`
- Todos iguais (caso V1 unificado / 119 in-party) → `partyDefined=false`, `partyCount=null` → **Equipe: não definida**
- Não inventa os 6 primeiros da coleção

---

## Exemplo real (`!pk treinador` · recovery-v1)

```
🧢 *Treinador: Caio*
⭐ Nível 10
✨ PE 127 · próximo 10/23
[████░░░░░░] 10/23
🏅 Vitórias: 31
🎖️ PE extra: ginásios +90 • raides +6
🔥 Capturas: sequência 6 • recorde 10

📦 Armazenamento Pokémon: 119/100 — CHEIO (19 acima do limite)
👥 Equipe: não definida
✨ Shiny: 1
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
| WhatsApp | **READY** `2026-10-29T18:27:44.770Z` |
| Storage | **119/100 CHEIO (+19)** |
| Equipe | **não definida** (`partyDefined=false`) |
| Shiny | **1** |
| Ativo | **Nidoqueen Nv.18** |
| Economia | `unknown-pending` · sem `Moedas: 0` |
| Mochila | **3/100** |
| Saves | `a`/`b`/`recovery-v1` · ativo `recovery-v1` · fence **2** |
| Integridade | 12/12 · 39 · 119 · **158** · 13 treinadores |
| RO pós | 2× GET trainer sem mudar fence/count |
| Cooldowns | Hunt **1800** · Joy **1800** · Idle **300** |

Arquivos: `a64-party-fix-pre-integrity.json`, `a64-party-fix-post-integrity.json`.

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-party-a64-party-fix-20261029T1825-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Não executar rollback destrutivo automaticamente.
