# Hotfix P0 — Ginásio zera coleção + caixa preta do líder

**Data:** 2026-11-06  
**Escopo:** diagnóstico RO produção + correção local (sem deploy)  
**Produção analisada:** `odisseu-core` · Node/JVM `a6-gym-20261105T222938Z-2ec5f97649ef-amd64`  
**Flag econômica:** permanece `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false`

---

## Classificação

| Dimensão | Status |
| --- | --- |
| Integridade dos saves | **AFFECTED** (pelo menos 1 desafiante pós-cutover com ~99% da coleção em HP 0) |
| HP em batalha / persistência pós-derrota | **FIXED** (local; aguarda RC/deploy) |
| Imagem do líder (lado O da arena) | **FIXED** (local; rehydrate + team-edn com URLs) |
| Homologação | **CONDITIONAL** (testes + PNGs OK; sem deploy / sem reparo prod) |

---

## 1. Pergunta principal — HP=0 é persistido ou só DTO?

**É persistido em Datomic.** Não é só o card.

### Evidências RO (produção)

| Evidência | Detalhe |
| --- | --- |
| Versões | Node `sha256:cdb25591…c094` · JVM `sha256:629391dc…0d14` (par ginásio A6) |
| Histórico piloto | Derrotas **pós-cutover** em `pedra` e `agua` (mesmo desafiante) |
| Coleção do desafiante (hash `6635c7f7bf15`) | **n=97 · pos=1 · zeros=96 · zero_share=0.9897** |
| Ocupante do ginásio (hash `4a246c206119`) | n=9 · zeros=0 (não afetado pela derrota alheia) |
| Leitor amostral | 301/302 com HP>0 (1 Kyurem com 0 — isolado) |
| Logs Node | `GYM_TEAM_UNHEALTHY` com índices — consistente com time desmaiado após wipe |
| Start de desafio | **não** grava HP (só cria `battle/state-edn`) |

Conclusão operacional: o wipe ocorre no **fim da derrota** (`outcome :lose`), via sync de HP da coleção inteira — o jogador percebe “ao desafiar” porque o desafio termina rápido e `!pk meus`/time mostra tudo a 0.

Evidências host:  
`/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/gym-hp-diag-20261106T092557Z/`

---

## 2. Causa raiz — HP zero na coleção

Arquivo: `modules/pokemon/infrastructure/pokemon/infrastructure/datomic/store.clj`  
Função: `sync-colecao-from-gym-battle`

**Antes (bug):**

```clojure
defeated? (= :lose (:outcome battle))
;; …
defeated?
(assoc poke :hp-atual 0 :status nil)  ;; para CADA pokemon da query-collection
```

`gym-battle-hp-txs` é chamado com a **coleção completa** (`query-collection`). Na derrota, **todos** os Pokémon do save recebiam `:pokemon/hp-atual 0`.

**Depois (fix):**

- Na derrota, só índices em `:team-x-indices` (time 3×3) vão a 0.
- `fainted-x` e combatente ativo continuam atualizados normalmente.
- Pokémon fora do desafio preservam HP/status.

Invariantes respeitados: motivação não altera HP persistido; dano de turno só no combatente; derrota não zera a box.

---

## 3. Causa raiz — caixa preta do “líder”

Na arena do desafio, o “líder” é o **Pokémon defensor** (`pokemonO`), não um avatar humano.

1. `encode-gym-team-edn` gravava o time da ocupação com `keep-images? false` → **sem URLs** em `:gym-occupation/team-edn`.
2. Produção confirmada: ocupação `pedra`/`agua` — Onix/Larvitar/Snorlax com `has_imagem=false`.
3. `renderHuntArena` → `spriteProporcional` sem URL → placeholder / artefato escuro (caixa preta).
4. O hotfix anterior (`ensureAlpha` em PNGs com alpha) **não** cobre ausência total de sprite nem JPEG com fundo preto já nos pixels RGB.

**Correções:**

| Camada | Mudança |
| --- | --- |
| JVM | `encode-gym-team-edn` passa a `keep-images? true` (URLs curtas) |
| Node | `ensurePokemonSprite` rehidrata via PokeAPI por `species`/`nome` quando falta URL (ocupações legadas) |

---

## 4. Correções implementadas (arquivos)

- `modules/pokemon/infrastructure/pokemon/infrastructure/datomic/store.clj` — sync HP só no time 3×3
- `modules/pokemon/domain/pokemon/domain/battle.clj` — team-edn com imagens
- `scripts/lib/pokemon-jvm-media.cjs` — `ensurePokemonSprite` + uso em `renderHuntArena`
- `modules/pokemon/tests/pokemon/tests/gym_collection_hp_integrity_a6_test.clj` — suite P0
- `docs/evidence/a6-gym-hotfix/*.png` — evidências visuais
- Este documento

**Não alterado:** raids, trocas, Pokédex, bag, thematic, maintenance, ledger, migration 034, moedas por vitória, flag de permanência.

---

## 5. Testes

| Suite | Resultado |
| --- | --- |
| `gym-collection-hp-integrity-a6-test` | **6/6 pass** (21 asserts) |
| `clojure -M:test-m4-gym-battle` (m48+m47+m35+HTTP) | **OK** |
| `gym-battle-hp-a6-test` | 3/3 |
| `gym-permanence-rewards-a6-test` | 7/7 |

Cobertura P0: sync unitário 6 mons; derrota preserva bench; vitória preserva bench; abandon; team-edn com imagem; HP parcial no start.

---

## 6. PNGs reais

Diretório: `docs/evidence/a6-gym-hotfix/`

| Arquivo | Conteúdo |
| --- | --- |
| `lider-ocupacao-sem-imagem-onix.png` | Onix **sem URL** → rehydrate · sem caixa |
| `lider-misty-starmie-sem-imagem.png` | Starmie sem URL · sem caixa |
| `turno1-hp-parcial.png` | Barras parciais |
| `troca-segundo-pokemon.png` | 2º combatente com HP cheio |
| `derrotado-barra-zero.png` | Só o combatente em 0 |
| `lider-jpeg-fundo-preto-opaco.png` | Limitação documentada (RGB preto opaco) |

Inspeção visual: Onix/Starmie rehidratados **sem** fundo/caixa preta.

---

## 7. Dano em saves existentes — proposta (NÃO executar)

**Estado:** AFFECTED confirmado para desafiante hash `6635c7f7bf15` (96/97 a 0).

### Proposta de reparação seletiva (requer GO separado)

1. Listar saves com `zero_share` anômalo pós `2026-11-05T22:56Z` e histórico de derrota de ginásio.
2. Para cada Pokémon com `hp-atual=0` **fora** de `:time-ginasio` na janela da derrota:
   - recuperar último `hp-atual` positivo no histórico Datomic **antes** da TX de sync da derrota;
   - se inexistente, usar `:hp` (máximo) só quando evidência de wipe indevido (não confundir com nocaute legítimo do time 3).
3. Ensaio obrigatório em Datomic **descartável** (cópia cold).
4. **Não** restaurar cold antigo sobre live; **não** curar a coleção inteira às cegas.

Desabilitar só desafios (se GO operacional pedir): não há flag dedicada hoje; opção fail-closed seria bloquear `POST /v1/gyms/challenge` via maintenance seletiva — **não ativado** nesta sessão.

---

## 8. Rollback do hotfix (código)

Reverter os três arquivos de código acima. Schema 034 e imagens de produção atuais não são tocados por este hotfix.

---

## 9. Imagens RC

**Não geradas nesta sessão** (sem deploy). Próximo passo operacional: build Node+JVM a partir do tip com este hotfix, mesmo tree, AMD64, checklist de cutover.

---

## 10. Resumo executivo

1. **HP:** bug de persistência na derrota — wipe da coleção inteira; corrigido para só o time 3×3.  
2. **Sprite do líder:** ocupação sem URL de imagem; corrigido (gravar URL + rehydrate no Node).  
3. **Produção:** saves afetados existem; reparo **não** aplicado (aguardando GO).  
4. **Deploy:** não realizado.
