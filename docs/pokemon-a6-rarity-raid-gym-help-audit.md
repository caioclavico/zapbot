# Pokémon A6 — Auditoria de raridades, raids, ginásios, UX e ajuda

**Data:** 2026-11-08  
**Branch de trabalho:** `feature/a6-rarity-ux-help` (base `github/rc/a6-abc-p0-integrated` @ `7caf43e`)  
**Produção (RO):** Combat UX + Eventos `3bc5076` — READY/healthy (sem alterações nesta auditoria)  
**Restrição:** sem deploy, migrations, backfill, alteração de saves reais ou ativação global de reclassificação

---

## 0. Fase 0 — Mapa

| Área | Fonte de verdade |
|------|------------------|
| Classificação de raridade | `pokemon.domain.capture/classificar-raridade` (JVM); `hunt/classificar-raridade` e `raid-boss/classify-rarity` delegam |
| Catálogo de chefes | `modules/pokemon/domain/.../data/raid_boss_catalog.edn` (1025 espécies; 571 elegíveis a raid) |
| Seleção de raid | `pokemon.domain.raid-boss` — pesos por categoria; elegíveis = raro/épico/lendário/mítico |
| Ginásios NPC | `pokemon.domain.gym/ginasios` + `pool-npc` / `sortear-especies-npc` (pools temáticos, **sem** tier de raridade) |
| Chefes de raid em ginásio (manual) | `raid/chefes-ginasio` (templates raros fixos) |
| Apresentação / moedas / ajuda | `scripts/lib/pokemon-jvm-present.cjs` + router `src/zapbot/pokemon_http.cljs` |
| Ajuda legada (Cassandra path) | `src/zapbot/pokemon/ajuda.cljs` (não é o caminho JVM A6) |
| Persistência | Datomic (schema 035); raridade de captura pode ir no snapshot do mon |

**Preservado:** Species-HP v1 · schema 035 · One-Mon · HP chefe 320/jogador · economia · eventos 25% · saves · maintenance · WA.

---

## 1. Lote A — IMPLEMENTADO (UX / moedas / ajuda)

### 1.1 Moedas → 💰

- Constante canônica `COIN_EMOJI = '💰'` (`U+1F4B0`) em `pokemon-jvm-present.cjs`.
- `textoMoedas` e todos os fallbacks de recompensa/vitória/derrota/ginásio passaram a usar 💰.
- Removidos 🪙 das strings de moedas; 🌙/🌕 não são usados como moeda.
- `ajuda.cljs` (legado) atualizado na linha de moedas de ginásio.
- **Sem mudança de valores econômicos** — só apresentação.

### 1.2 Efetividade — emoji no final

Fonte JVM: `pokemon.domain.damage/texto-efetividade`  
Fonte Node: `effectivenessLine`

| Mult | Texto |
|------|--------|
| `> 1` | `Super efetivo!🔥` |
| `< 1` (≠0) | `Pouco efetivo!🛡️` |
| `0` | `Sem efeito!🚫` |

Emoji **no final**, colado ao `!` (sem espaço). Multiplicadores/imunidades **inalterados**.

### 1.3 `!pk ajuda`

- Registro declarativo `HELP_TOPICS` + `formatHelp` / `resolveHelpRequest` em `present.cjs`.
- Aliases: `ajuda`, `help`, `?`, `ajd`.
- Contextual: `!pk raid ajuda`, `!pk ginasio ajuda`, `!pk mochila ajuda`, `!pk cacar ajuda`, `!pk troca ajuda`, etc.
- Router JVM (`pokemon_http.cljs`) intercepta pedidos de ajuda antes do despacho de jogo.
- Comando desconhecido sugere `!pk ajuda [módulo]`.
- Sem IDs internos, JIDs, Datomic ou admin.

### 1.4 Testes Lote A

| Suite | Resultado |
|-------|-----------|
| `test/pokemon-a6-ux-help-coins.test.cjs` | PASS (💰 codepoint, ausência 🪙/🌙/🌕, ajuda, efetividade) |
| `test/pokemon-a6-gameplay-present.test.cjs` | PASS |
| `test/pokemon-a6-lote-c-present.test.cjs` | PASS |
| `test/pokemon-a6-raid-gym-capture-p0.test.cjs` | PASS (20) |
| `test/pokemon-jvm-raid-fase12.test.cjs` | PASS |
| `damage/mensagem-ataque` (smoke Clojure) | PASS (`🔥Super efetivo!`) |

**Veredito Lote A:** **GO** para integração (após homologação visual no WhatsApp).

---

## 1b. Efetividade (formato final confirmado)

Emoji **no final** da frase, sem espaço:

- `Super efetivo!🔥`
- `Pouco efetivo!🛡️`
- `Sem efeito!🚫`

Caminhos: `effectivenessLine` (Node), `damage/texto-efetividade` (JVM), `core.cljs` (legado).

---

## 2. Lote B — AUDITADO (raridade / raids / ginásios) — **não ativado**

> Modelo oficial detalhado: [`pokemon-a6-official-rarity-model.md`](./pokemon-a6-official-rarity-model.md)  
> Catálogo: `official-rarity-catalog-v1` · políticas `:oficial-a` / `:oficial-b` · default runtime `:legado`

### 2.1 Critério atual (canônico)

```
mitico-api?     → mitico
lendario/paradox → lendario
senão:
  por-poder  (poder-original = 2×Σstats − 450):
    ≤400 comum · ≤480 incomum · ≤540 raro · else épico
  por-captura (taxa-captura PokeAPI):
    ≤10 épico · ≤45 raro · ≤120 incomum · else comum
  resultado = maior-raridade(por-poder, por-captura)
```

### 2.2 Victreebel — causa-raiz

| Campo | Valor |
|-------|-------|
| BST raw | 490 |
| Stats suavizados (Σ) | 470 → poder-original 490 |
| Taxa-captura | 45 |
| **Raridade atual** | **raro** (não épico) |

**Conclusão:** na fórmula vigente Victreebel **já é raro**. Relatos de “épico” provavelmente vêm de: (a) confusão com espécies vizinhas infladas (Arcanine/Gyarados); (b) snapshot legado; (c) UI antiga. **Não há bug de classificação atual em Victreebel.**

A causa estrutural de “finais fortes = épico” é o `maior-raridade`: **poder eleva acima da taxa**. Ex.: Arcanine (taxa 75 → incomum por captura, poder 556 → épico).

### 2.3 Totais atuais (catálogo 1025)

| Categoria | Espécies |
|-----------|----------|
| comum | 236 |
| incomum | 218 |
| raro | 423 |
| épico | 34 |
| lendário | 91 |
| mítico | 23 |

**Elegíveis a raid (raro+):** **571** — `{raro 423, épico 34, lendário 91, mítico 23}`  
(compatível com o histórico “571 chefes”).

### 2.4 Espécies obrigatórias (atual)

| Pokémon | Atual | Nota |
|---------|-------|------|
| Bellsprout | comum | ok |
| Weepinbell | incomum | ok |
| **Victreebel** | **raro** | já coerente |
| Pidgeot | raro | |
| Arcanine | **épico** | taxa 75 — candidato a deflação |
| Gengar / Alakazam / Machamp | raro | |
| Dragonite / Tyranitar / Metagross | épico | pseudo BST 600 / taxa baixa |
| Snorlax / Chansey / Blissey | raro | |
| Gyarados | **épico** | BST 540, taxa 45 — candidato a deflação |
| Iniciais finais (Charizard/Blastoise/Venusaur) | raro | ok |
| Mewtwo / Mew | lendário / mítico | flags API |

### 2.5 Critério proposto (para aprovação — **não implementado**)

**Política conservadora — “épico legítimo”:**

Manter a fórmula atual, porém **rebaixar `épico → raro`** quando **não** houver:

1. `taxa-captura ≤ 10`, **ou**
2. `bst-raw ≥ 600` (pseudo-lendário / estágio final de linha 600),

exceto se flags API já forem lendário/mítico.

#### Impacto quantitativo (simulação local, RO)

| Métrica | Atual | Proposto |
|---------|-------|----------|
| Épicos | 34 | 15 (−19) |
| Raros | 423 | 442 (+19) |
| Elegíveis raid | 571 | **571** (inalterado — demotions ficam em raro) |
| Pool épico de raid | 34 | 15 |

**19 demotions:**  
`arcanine archeops blacephalon buzzwole celesteela florges gholdengo guzzlord gyarados kartana kingambit milotic nihilego pheromosa stakataka togekiss ursaluna volcarona xurkitree`

**Risco residual da proposta:** Ultra Beasts (ex. Buzzwole) e alguns “sublendários” (Togekiss, Milotic, Volcarona) **não** têm `lendario-api?` no catálogo e cairiam para raro. Antes do GO de ativação: ou marcar UB/paradox no catálogo, ou estender a regra (ex. geração 7 UB list / taxa≤30 / lista allow).

### 2.6 Impacto em saves / recompensas

- Raridade **persistida no snapshot** do Pokémon capturado **não** seria reescrita (proibição explícita).
- Caçadas/raids **novas** usariam a classificação canônica da espécie.
- Chances de captura / XP de raridade em encontros novos mudariam só para as 19 espécies demotadas (épico→raro).
- Economia de moedas / HP raid / One-Mon / Species-HP: **sem mudança**.

**Quantidade de mons capturados afetados em produção:** não alterada nesta fase (somente leitura; sem query de backfill). Estimativa operacional: só exemplares cuja UI/recompensa futura dependa de reclassificar a espécie no catálogo — **requer inventário RO anonimizado na janela de aprovação**.

### 2.7 Raids — compatibilidade de categoria

- Sorteio usa `classify-rarity` = mesma função canônica.
- Categorias vazias: pesos normalizados só entre categorias com espécies (`normalize-weights`); **não** há fallback silencioso para outra raridade rotulada como a pedida.
- Chefe persistido no intent/raid permanece estável após o sorteio.
- Com a proposta conservadora, o pool **épico** encolhe (34→15) mas a categoria continua populada; antirrepetição intacta.

### 2.8 Ginásios

- **Não há tiers formais de raridade** no desafio NPC.
- Seleção = pool temático por ginásio (`gym/ginasios :pool`), com dificuldade via `escalar-nivel` / motivação.
- Separação correta: **raridade da espécie** ≠ **dificuldade do ginásio**.
- Pool planta inclui Victreebel como `raro` — coerente.
- Pool fogo inclui Arcanine como `épico` hoje; com a proposta passaria a `raro` sem mudar o desafio (nível 50 / stats escalados).
- Defensores de jogadores **não** são substituídos pelo sorteio NPC.

**Política sugerida (documental):** manter pools temáticos; não rotular adversário NPC com raridade artificialmente elevada.

### 2.9 Veredito Lote B

| Item | Status |
|------|--------|
| Catálogo oficial versionado (PokéAPI CSV) | **IMPLEMENTADO** localmente |
| Classificador canônico + perfis A/B | **IMPLEMENTADO** (`pokemon.domain.rarity`) |
| Integração preparada (default `:legado`) | **FEITO** |
| Testes automatizados | **PASS** (59 asserts + sims 10k) |
| Ativação global (`:oficial-a`/`:oficial-b`) | **NO-GO** — aguarda aprovação |
| Mudança de saves/produção/deploy | **NO-GO** |
| Política recomendada para aprovação | **A** (capture-anchored); B como alternativa |

---

## 3. Riscos residuais

1. Mensagens JVM com `lastAction.mensagem` legada ainda podem carregar texto antigo até o turno ser gerado pela build nova.
2. Ajuda legada (`ajuda.cljs`) diverge em detalhes históricos (Super Bola etc.); o caminho A6 usa `formatHelp`.
3. Proposta de raridade demove UBs sem flag — precisa de allowlist antes do GO.
4. Produção já está em Combat UX; Lote A ainda **não** está na imagem live.

---

## 4. GO / NO-GO

| Lote | Veredito |
|------|----------|
| **A — UX 💰 + efetividade + `!pk ajuda`** | **GO** integração (homologar WhatsApp → RC → janela) |
| **B — nova política de raridade** | **NO-GO ativação** até aprovação + tratamento UB/sublendários |

---

## 5. Comandos de ajuda (A6)

```
!pk ajuda
!pk ajuda cacar|raid|ginasio|pokemon|mochila|evoluir|troca|presentes|eventos|treinador|recompensas
!pk raid ajuda · !pk ginasio ajuda · !pk mochila ajuda · !pk cacar ajuda
!pk ajd · !pk help · !pk ?
```
