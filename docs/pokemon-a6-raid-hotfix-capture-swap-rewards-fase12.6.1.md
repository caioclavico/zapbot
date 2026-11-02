# A6 — Hotfix raides: captura, combate ativo, recompensas e Fruta Frambo

**Branch:** `hotfix/a6-raid-capture-rewards-fase12.6`  
**Base:** Fase 12.6 (`c9476f1`) + este patch  
**Modo:** implementação + testes + doc — **sem deploy / migrate live / mutação de dados reais**

---

## Decisão: **CONDITIONAL**

Pronto para GO de deploy **junto** com migration `031-raid-capture-frambo` (Frambo).  
Captura RNG + troca de combatente + UX de recompensas **não** exigem migration.

Produção live (ainda sem este patch) continua com captura quebrada (`roll=100`) e sem `!pk raide fruta`.

---

## 1. Causas raiz

### 1.1 Captura sempre falha

| Camada | Bug |
| --- | --- |
| Node | não envia `roll` |
| Bridge | só aceita `roll` com `allowTestRng` |
| `build-capture-tx` | `(or roll **100**)` |
| Sucesso | `(< roll chance)` com chance ≤ 95 |

**Reprodução:** qualquer bola em prod → falha + consumo. Ultra Bola só era a mais usada.

**Fix:** `(or roll (rand-int 100))` + `collection_full` antes do débito.  
**Validação:** testes com `roll 0` sucesso, `roll 70` falha em chance 70, sem roll → `0≤roll≤99`.

### 1.2 Troca de Pokémon ativo não afeta a raide

| Fato | Detalhe |
| --- | --- |
| Join | usava `auto` (melhor score), **ignorava** `ativo-index` |
| Snapshot | combatente selado em `raid-participant` no entrar |
| `!pk escolher` | só muda `:save/ativo-index`; não toca o participante |
| Re-entrar | era no-op (`duplicate`) mesmo com outro mon |

**Fix (política):**

| Fase | Comportamento |
| --- | --- |
| Entrada default | **Pokémon ativo** (`!pk raide entrar` / `entrar ativo`) |
| Opt-in | `entrar auto` = melhor apto; `entrar <n>` = índice |
| Lobby | re-`entrar` com outro índice/`ativo` **atualiza** o snapshot |
| Combate | troca **travada** (`raid_combatant_locked`) + mensagem clara |

### 1.3 Recompensas sem descrição

Vitória creditava +50 moedas / +6 PE / +6 XP Pokémon, mas o WhatsApp só dizia “recompensas creditadas…”.

**Fix:** mensagem `RAID CONCLUÍDA` com valores **efetivamente** no DTO da TX.  
Domínio **não** dropa bolas/frutas na vitória (só economia + PE + XP) — a UI não inventa itens.

### 1.4 Frambo

Não existia na imagem live. Em 12.6: item `fruta`, mult 1.5, prepare sem consumir.  
Este patch: aliases `!pk raide fruta` (+ `frambo` / `fruta frambo` via sub `fruta`).

---

## 2. Regras finais de captura

| Raridade | Base |
| --- | --- |
| raro | 35% |
| lendario | 8% |
| mitico | 4% |

| Bola | Mult | Cap |
| --- | --- | --- |
| pokebola | ×1 | 75 |
| grande-bola | ×1.5 | 88 |
| ultra-bola | ×2 | 95 |

Frambo: ×1.5 sobre chance pós-bola, re-cap no limite da bola.  
Ex.: raro + ultra + Frambo = `min(95, 105) = 95%`.

Tentativas: 3 · janela: 30 min · elegível: dano > 0.

---

## 3. Tabela de recompensas reais (vitória)

Por participante com `dano > 0` (idempotente via `raid-reward\|raidId\|playerId`):

| Recurso | Quantidade |
| --- | --- |
| Moedas (ledger operacional) | **+50** |
| PE treinador (`pe-raids`) | **+6** |
| XP Pokémon combatente | **+6** |
| Itens (bolas/frutas) | **nenhum** neste domínio |

Captura do chefe: mon nv.1 na coleção — sem crédito econômico extra.

---

## 4. Exemplos WhatsApp

**Vitória**

```
🏆 *RAID CONCLUÍDA!*
👾 Chefe derrotado: *Onix*

🎁 *RECOMPENSAS* (creditadas e persistidas)
💰 Moedas: +50
⭐ XP do Pokémon: +6
🏅 PE do treinador: +6

🎯 *CAPTURA DO CHEFE*
!pk raide capturar          — ver bolas / chances
!pk raide capturar ultra-bola
!pk raide fruta             — Frambo (+chance)
!pk mochila
```

**Fruta**

```
🍓 *Fruta Frambo utilizada!*
✨ Sua próxima tentativa terá uma chance maior de capturar o chefe!
🎯 Agora escolha sua Poké Bola.
```

**Entrada / troca**

```
!pk raide entrar          → Pokémon ativo
!pk raide entrar auto     → melhor apto
!pk raide entrar 2        → nº 2 da coleção
!pk escolher + !pk raide entrar  → atualiza no lobby
```

---

## 5. Arquivos principais

| Área | Arquivos |
| --- | --- |
| Captura / Frambo | `raid_store.clj`, `capture.clj`, `031-…edn`, bridge capture/frambo |
| Combatente | `raid.clj` `entrar`, `build-join-tx`, `bridge_tx/join-raid!`, `pokemon_http` entrar |
| UX | `pokemon-jvm-present.cjs` |
| Testes | `raid_capture_rewards_fase126_test.clj`, `pokemon-jvm-raid-fase12.test.cjs` |

---

## 6. Testes

```
clojure -M:test-raid-capture-rewards-fase126  → 54/54
clojure -M:test-m4-raid                       → OK
clojure -M:test-raid-lobby-fase2              → 76/76
node --test test/pokemon-jvm-raid-fase12.test.cjs
     test/pokemon-jvm-present.test.cjs        → pass
```

Cenários novos: join por ativo, refresh no lobby, lock em combate, UI vitória/Frambo.

---

## 7. Riscos / pendências

| Risco | Mitigação |
| --- | --- |
| Prod ainda sem patch | Captura continua quebrada até GO |
| Migration 031 | Frambo precisa do attr; GO deve aplicar schema |
| Default entrar mudou de `auto`→`ativo` | Documentado; `auto` permanece |
| Sem drop de itens na vitória | Explícito na mensagem; não inventar loot |
| RC 12.6 anterior | Rebuild RC recomendado após este commit |

---

## 8. Plano de deploy (aguardar GO)

1. Backup warm A6.  
2. Aplicar migration `031` (ou imagem que instala schema no boot).  
3. Deploy Node+JVM desta branch; preservar leave ON, auto-raides OFF, piloto exclusivo.  
4. Gates: captura sem roll com `roll∈[0,99]`; `entrar` usa ativo; vitória lista +50/+6/+6; `raide fruta` prepara.  
5. Rollback = imagens leave-fix / 12.3.1 anteriores.

**Não** corrigir inventários/capturas históricas automaticamente.
