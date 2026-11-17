# Hotfix 12.6.2 — Seleção isolada, menções WhatsApp e auditoria de captura/fuga

**Status:** código + testes locais. **Sem deploy / migration / alteração em produção até GO explícito.**

## 1. Causa raiz — seleção incorreta de Pokémon

### O que estava errado

Em `join-raid!` (`bridge_tx.clj`), o modo `:use-active?` (`!pk raid entrar` / `entrar ativo`) fazia **fallback silencioso para `auto`** quando o Pokémon ativo estava inapto (HP, liga ou sem golpe ofensivo).

O jogador via um combatente diferente do ativo **sem mensagem de erro**, o que parecia “seleção compartilhada” ou “ativo ignorado”.

### O que NÃO era bug de isolamento

- A raid grava combatentes em `:participantes` **indexado por `player-id`**.
- Reentrada no lobby atualiza só o snapshot daquele jogador (`build-join-tx`).
- Aliases `raid` / `raide` resolvem o mesmo subcomando (`resolveRaidSubcommand`).

### Correção

| Modo | Comportamento |
|------|----------------|
| `entrar` / `ativo` | Só o Pokémon ativo; inapto → `:invalid_raid_pokemon` (sem auto silencioso) |
| `entrar auto` | Melhor apto **da coleção desse jogador** (stats + melhor poder ofensivo) |
| `entrar N` | Slot N do próprio jogador (`collection-index`) |

Resolução por `:collection-index` canônico (com fallback `nth` legado).

## 2. Menções reais no WhatsApp

### Problema

`formatRaidRewardLines` imprimia `playerId` cru (`121212@lid`).

### Correção

- Vitória retorna `{ text, mentions }` via `formatRaidVictory` / `coerceRaidPresent`.
- Texto usa `@Nome` (pushname gravado no join) ou `Treinador N`.
- `mentions` = JIDs canônicos (`@lid` / `@c.us`) para o whatsapp-web.js.
- Nunca exibe telefone/LID no texto público.
- Crédito de recompensa continua pelo `player-id` persistido.
- `envelope-jvm` propaga `mentions` nas mensagens.

## 3. Fórmula atual de captura (raide)

Base por raridade do chefe (`raid/chance-captura`):

| Raridade | Base |
|----------|------|
| raro (default) | 35 |
| lendário | 8 |
| mítico | 4 |

Bolas (`capture/bolas`):

| Bola | Mult | Cap |
|------|------|-----|
| Pokébola | ×1 | 75 |
| Grande Bola | ×1.5 | 88 |
| Ultra Bola | ×2 | 95 |

Frambo: ×1.5 sobre a chance pós-bola, re-capada no limite da bola.

Exemplos (chefe raro, base 35):

- Ultra sem Frambo → `min(95, round(35×2))` = **70**
- Ultra + Frambo → `min(95, round(70×1.5))` = **95**

RNG: `roll = rand-int 100` (0–99); sucesso se `(< roll chance)`. Testes/smoke injetam `:roll`.

Itens: bola sempre consumida; Frambo consumida se preparada ou passada no request.

## 4. O chefe pode fugir definitivamente?

**Não.** Na captura de **raide** não existe fuga aleatória definitiva.

- Falha de bola = “escapou da Poké Bola”; janela continua se restarem tentativas.
- Encerramento da fase de captura (por jogador):
  1. Captura bem-sucedida
  2. **3 tentativas** esgotadas (`max-tentativas-captura`)
  3. **`captura-expira`** (30 min após vitória) — distinto de fuga RNG
- Recompensas de vitória **já creditadas** não são revertidas.

> A fuga por raridade (`fuga-raridade` + `fuga-roll`) existe só em **caça selvagem** (`capture/attempt-capture`), **não** em `raid_store/build-capture-tx`.

### Probabilidade de fuga definitiva (raide)

**0%** — mecânica inexistente hoje.

### Proposta (não implementada — aguarda aprovação)

Se quiser fuga definitiva equilibrada no futuro, exemplo:

- Após falha de bola, roll secundário `fuga-roll` 0–99
- Chance sugerida: 8–12% (raro) / 14–18% (lendário), **ou** só na 3ª falha
- Mensagem: `💨 O Onix fugiu!` + “recompensas mantidas”
- Manter expiração de janela como caminho separado

## 5. Testes

- Clojure: `raid_capture_rewards_fase126_test.clj`
  - `multi-jogador-entrar-auto-nao-altera-outro`
  - `use-active-inapto-nao-faz-fallback-silencioso`
  - `captura-falha-preserva-janela-sem-fuga-definitiva`
- Node: `test/pokemon-jvm-raid-fase12.test.cjs`
  - vitória com `@lid` / `@c.us` + nomes
  - fallback `Treinador N`
  - captura malsucedida vs tentativas esgotadas

## 6. Arquivos

- `modules/pokemon/application/.../bridge_tx.clj`
- `modules/pokemon/infrastructure/.../raid_store.clj`
- `scripts/lib/pokemon-jvm-present.cjs`
- `src/zapbot/pokemon_http.cljs`
- testes + este doc

## 7. Riscos e plano de deploy (após GO)

1. Build imagens bot + JVM (tag 12.6.2).
2. Deploy **somente** após GO; sem migration nova (031 intacta).
3. Smoke: 2 jogadores (`entrar` vs `entrar auto`), vitória com menções, captura fail + retry, captura ok.
4. Rollback: imagens 12.6.1.
5. Preservar: recovery-v1, fences, unknown-pending, cooldowns, raids auto OFF, battle leave ON.
