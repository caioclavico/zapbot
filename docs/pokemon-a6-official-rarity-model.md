# Pokémon A6 — Modelo oficial de raridade (PokéAPI)

**Data:** 2026-11-08  
**Branch:** `feature/a6-rarity-ux-help`  
**Catálogo:** `official-rarity-catalog-v1` (`modules/pokemon/domain/pokemon/domain/data/official_rarity_catalog.edn`)  
**Fonte:** PokeAPI CSV (`pokeapi/data/v2/csv`) — docs https://pokeapi.co/docs/v2  
**Built-at:** gravado no EDN (`:built-at`)  
**Política runtime default:** `:legado` — **NO-GO ativação global**

---

## 1. Fonte e versão dos dados

| Campo | Valor |
|-------|-------|
| Origem | CSVs oficiais do repositório PokeAPI |
| Endpoints equivalentes | `/pokemon-species`, `/pokemon`, `/evolution-chain`, encounters via `encounters.csv` |
| Script gerador | `scripts/a6_build_official_rarity_catalog.py` |
| Espécies | 1025 (mesmo universo do raid catalog) |
| Dados incompletos (sem encontros e não lend/mít) | 151 no build; classificador marca `:incerto?` sem inventar extremos |
| Runtime | **sem HTTP** — só leitura do EDN versionado |

Evidências por espécie: `taxa-captura` (capture_rate), flags `is_legendary` / `is_mythical`, `ultra-beast?`, `paradox-api?`, `evo-stage`, `chain-max-stage`, `evo-methods`, `bst-raw`, agregados `encounter-locations|versions|rows`.

---

## 2. Critério atual (legado)

`pokemon.domain.capture/classificar-raridade-legado`:

1. `mitico-api?` → mítico  
2. `lendario-api?` / `paradox-api?` → lendário  
3. Senão: `maior-raridade(por-poder, por-captura)`  
   - poder-original = `2×Σstats_suavizados − 450`  
   - captura: ≤10 épico · ≤45 raro · ≤120 incomum · else comum  

**Problema:** poder eleva espécies “fáceis de obter” (ex. Arcanine taxa 75 → épico).  
**Victreebel:** já é **raro** no legado (não épico). Relatos de “épico” vêm de vizinhos inflados / UI antiga / snapshot.

Distribuição legado (1025): comum 236 · incomum 218 · raro 423 · épico 34 · lendário 91 · mítico 23.  
Elegíveis raid: **571**.

---

## 3. Novo modelo proposto

Namespace: `pokemon.domain.rarity`  
Ativação: `rarity/*politica*` ∈ `#{:legado :oficial-a :oficial-b}` (default `:legado`).  
`capture/classificar-raridade` consulta a política; Node só apresenta o slug canônico.

### Gates absolutos (A e B)

| Condição | Raridade |
|----------|----------|
| `is_mythical` | mítico |
| `is_legendary` ou paradox | lendário |
| Ultra Beast (lista explícita) | épico |
| Pseudo: `bst-raw ≥ 600` e `evo-stage ≥ 2` | épico |

### Âncora capture_rate (não é chance Odisseu)

| capture_rate | Faixa base |
|--------------|------------|
| ≤ 10 | épico |
| ≤ 45 | raro |
| ≤ 120 | incomum |
| > 120 | comum |

Evolução final **não** vira épica automaticamente. BST/Species-HP **não** classificam sozinhos.  
Trade-evo: piso `raro` se taxa ≤ 90 (não sobe a épico).

### Perfil A — Capture-anchored (recomendado)

Só gates + âncora de captura (+ trade floor).  
Épico permanece especial (~26 espécies).

### Perfil B — Capture + disponibilidade confiável

Igual a A, e se `encounter-versions ≥ 5` e `rows ≥ 10`:

- locs ≤ 5 → sobe no máx. até **raro** (nunca épico por escassez)  
- locs ≥ 80 e taxa ≥ 45 → desce 1 degrau a partir de raro  

Ausência de encontros **não** prova raridade extrema (`:incerto?` true).

---

## 4. Distribuição antes / depois

| Categoria | Legado | Oficial A | Oficial B |
|-----------|--------|-----------|-----------|
| comum | 236 | 274 | 267 |
| incomum | 218 | 274 | 275 |
| raro | 423 | 337 | 343 |
| épico | 34 | **26** | **26** |
| lendário | 91 | 91 | 91 |
| mítico | 23 | 23 | 23 |
| **Elegíveis raid** | **571** | **477** | **483** |

Reclassificações vs legado (aprox.): A ~137 · B ~153 (maioria `raro→incomum` por taxa>45).

Épicos oficiais (26): pseudos (Dragonite, Tyranitar, Metagross, Salamence, Garchomp, …) + Ultra Beasts + captura ≤10 (Beldum/Metang/…).

---

## 5. Espécies obrigatórias

| Espécie | Captura | Locs | Legado | A | B | Justificativa |
|---------|---------|------|--------|---|---|---------------|
| Bellsprout | 255 | 29 | comum | comum | comum | comum de rota |
| Weepinbell | 120 | 22 | incomum | incomum | incomum | meio de linha |
| **Victreebel** | 45 | 1 | **raro** | **raro** | **raro** | final taxa 45; sem auto-épico |
| Pidgeot | 45 | 21 | raro | raro | raro | final comum Gen1 |
| Arcanine | 75 | 19 | **épico** | **incomum** | incomum | poder legado inflava |
| Gengar | 45 | 13 | raro | raro | raro | trade → raro ok |
| Alakazam | 50 | 7 | raro | raro | raro | |
| Machamp | 45 | 13 | raro | raro | raro | |
| Dragonite | 45 | 35 | épico | épico | épico | pseudo 600 |
| Snorlax | 25 | 24 | raro | raro | raro | |
| Chansey | 30 | 151 | raro | raro | raro | |
| Blissey | 30 | 106 | raro | raro | raro | |
| Gyarados | 45 | 183 | épico | raro | incomum* | B demove por abundância |
| Tyranitar | 45 | 12 | épico | épico | épico | pseudo |
| Metagross | 3 | 12 | épico | épico | épico | taxa+pseudo |
| Charizard / Blastoise / Venusaur | 45 | — | raro | raro | raro | iniciais finais |
| Mewtwo / Mew | — | — | lend/mít | lend/mít | lend/mít | flags API |
| Buzzwole | 45 | 3 | épico | épico | épico | Ultra Beast |

\* B: Gyarados abundante (183 locs, 30 versões) → demote raro→incomum.

---

## 6. Impacto — raids

| Item | Legado | Oficial A |
|------|--------|-----------|
| Pool elegível | 571 | 477 |
| Pool épico | 34 | 26 |
| Compatibilidade categoria↔chefe | já usa `classify-rarity` | mesma API; política altera o slug |
| Fallback silencioso de categoria | não | não |
| HP 320 / One-Mon / dano 85–100% | intactos | intactos |

Simulação 10k sorteios (RNG seed 42, 1 espécie uniforme por categoria ciclada):  
legado 571 espécies distintas · oficial-a 477 — catálogo épico permanece utilizável.

**Se categoria vazia:** `raid-boss/normalize-weights` já falha com erro — não escolhe outra raridade rotulada.

---

## 7. Impacto — ginásios

Pools NPC temáticos (`gym/ginasios`) **sem** tier de raridade.  
Dificuldade = nível/escala/motivação.  
Raridade exibida = espécie canônica (legado hoje; oficial sob binding).  
Defensores de jogadores **não** alterados.

---

## 8. Impacto — caçadas / saves

| Camada | Comportamento proposto |
|--------|------------------------|
| 1. Canônica da espécie | catálogo + política ativa |
| 2. Snapshot na captura | campo `:raridade` persistido — **não sobrescrever** sem política de transição |
| 3. Economia/recompensas | faixas atuais por slug; sem mudança de valores nesta entrega |

Sem backfill. Sem alteração de chances/moedas/XP tables.  
Estimativa de mons afetados em produção: **somente leitura futura** — inventário RO anonimizado na janela de aprovação.

---

## 9. Arquitetura

```
PokéAPI CSV ──► a6_build_official_rarity_catalog.py ──► official_rarity_catalog.edn
                                                              │
                                              pokemon.domain.rarity
                                                              │
                         ┌────────────────────────────────────┤
                         ▼                                    ▼
              capture/classificar-raridade          (audit/sim/tests)
                         │
              hunt / raid-boss / captura UI
```

- Uma fonte canônica JVM; Node não reclassifica.  
- Formas: reutiliza `species-form-aliases` (Species-HP); classificação por slug canônico.  
- Testes: `pokemon.tests.official-rarity-a6-test` (59 asserts).

---

## 10. UX Lote A (relacionado)

Emoji de efetividade **no final**, sem espaço:

- `Super efetivo!🔥`
- `Pouco efetivo!🛡️`
- `Sem efeito!🚫`

Moedas: exclusivamente `💰`.

---

## 11. GO / NO-GO

| Item | Veredito |
|------|----------|
| Homologação local do catálogo + classificador | **GO** |
| Ativar `:oficial-a` ou `:oficial-b` em produção | **NO-GO** — aguarda aprovação quantitativa |
| Deploy / migrations / backfill / saves | **NO-GO** |
| Recomendação de política | Preferir **A** (mais previsível); B se quiser matizar abundância (Gyarados→incomum) |

### Riscos residuais

1. 151 espécies sem encontros CSV — marcadas incerto; classificação pela taxa.  
2. Ultra Beasts dependem de lista explícita (não vêm como legendary na species).  
3. Elegíveis raid caem 571→477 — aceitável, mas comunicar.  
4. Snapshots legados podem divergir da canônica até política de transição.
