# Auditoria de paridade — Pokémon `master` × Odisseu-Core

**Data:** 2026-10-20  
**Branches:** `master` (referência funcional/visual) × `refactor/modular-monolith-datomic` (Core)  
**Escopo:** somente auditoria. Sem alteração de código de produção, sem merge da master, sem deploy/push.  
**Exceção obrigatória:** onboarding M9.6 / M9.6.1 do Core é referência oficial (não regressão).  
**WIP preservado (não commitado nesta auditoria):** `modules/pokemon/infrastructure/.../datomic/store.clj`, `modules/pokemon/tests/.../gym_m48_test.clj`.

**Fontes:** inventário live em `pokemon-service/` (master), `docs/pokemon-command-inventory.md`, `docs/m4-12-auditoria-cutover.md`, bridge/present Node, domínio JVM, commit `5e7c6c0` (UI caça/batalha).

---

## 0. Estimativa fundamentada de paridade

| Fatia | Estimativa | Evidência |
| --- | --- | --- |
| Loop caça → turno → captura (regras + WA) | **~75–85%** | Domínio hunt/capture/battle alinhado (cooldown 30 min, fuga/raridade, chance-base); UI HP/golpes restaurada em `5e7c6c0`; faltam `defender`, poção mid-caça, `clima`/`areas`, troca mid-caça |
| Ginásio 3×3 desafio | **~70%** | Desafiar/time/heal/turnos JVM; ranking/histórico BLOQ; SVG 3 slots incompleto; WIP KO status |
| Raid (loop jogador) | **~65%** | entrar/iniciar/atacar/capturar; abrir/cancelar BLOQ (master também degradou abrir) |
| Economia loja/mochila | **~55%** | comprar/inventário/poção fora de batalha; kit/diário/resgatar/MT uso/equipar BLOQ |
| Joy / ovos | **~60% / ~40%** | Joy WA OK (collect só em `!pk joy`); ovos sob demanda, sem push 60s |
| Progressão (XP domínio) | **~80% domínio / ~30% WA** | XP em batalha/gin; `evoluir`/`aprender`/`escolher` ativo sem comando WA |
| PvP | **0% Core** | Ausente; `!pk` vazio virou menu onboarding (intencional) |
| Missões / trocas / doar / presentes | **0% WA** | BLOQ |
| Pokédex / grade coleção | **~25%** | Persistência seen/caught; sem `!pokedex` JVM; time = texto |
| **Superfície de comandos player-facing** | **~40–45%** | Contagem aproximada: ~90 comandos/aliases master service vs ~35–40 caminhos JVM WA úteis |
| **Experiência “jogo completo” ponderada** | **~45–55%** | MVP jogável (caça/gin/raid/loja/joy/saves) existe; sistemas sociais e PvP ausentes |

**Conclusão:** Core **não** é paridade integral com a master. É um **subset jogável** com arquitetura correta (JVM regras + Node apresentação + Datomic), onboarding melhorado, e lacunas P0/P1 listadas abaixo.

---

## 1. Inventário master (autoritativo = `pokemon-service`)

Arquitetura: bot `pokemon_http` → HTTP → `pokemon-service/src/zapbot/pokemon/*` (Cassandra). Espelho antigo em `src/zapbot/pokemon/*` não é a referência.

### 1.1 Áreas funcionais

| Área | NS principais (master) | Comandos-chave |
| --- | --- | --- |
| Caça / mundo | `core`, `mundo`, `shiny`, `aventuras` | `cacar`, `clima`, `areas`, `mapa`, `eventos` |
| Captura | `core`, `loja` | `capturar` + menu numérico pós-KO |
| Batalha PvE | `core` | `atacar`, `defender`, `curar`, `pocao*`, `trocar` (1× mid-caça) |
| Batalha PvP | `pvp`, `core` | `!pk` / `batalhar`, `sair`; faixa ±3 níveis; rank |
| Coleção / time | `treinador`, `core` | `time`, `meus`, `escolher`, `favorito`, `shiny`, CSV |
| Pokédex | `pokedex` | `!pokedex` espécie + coleção pessoal |
| XP / evoluir / golpes | `core`, `treinador`, `golpes` | `evoluir`, `aprender`, `reaprender`, `mt`, `rmg` |
| Loja / mochila | `loja` | `!loja`, `mochila`, kit, diário, resgatar, expansão |
| Joy | `treinador` | `joy`, `reviver` |
| Ginásios | `ginasios`, `aventuras` | `ginasio desafiar/time/fruta/pocao/ranking/historico` |
| Raids | `raids` | `raid` entrar/iniciar/atacar/capturar; abrir≈stub |
| Missões | `missoes` + estado em `loja` | `missoes` diárias/semanais |
| Troca / doar / professor | `core` | `negociar`, `doar`, `professor` |
| Ovos / Adventure | `egg_*`, `ovos`, `adventure*` | gated por flag |
| Saves / recovery | `recuperacao*` | `recuperar`, `restaurar` |
| Navegação | `navegacao` | menus `!pk` / `0` / números |
| Bug reports | `bugs` | `bug` |

### 1.2 Visuais master

Arenas SVG (caça/PvP/gin), barras HP texto, menu golpes com efetividade, cartas (treinador, time×12, evolução, Joy, loja, professor, captura, ovos), sprites PokeAPI/CDN.

### 1.3 Persistência Cassandra (gameplay)

`treinador`, `loja`, `ginasios`, `ginasios-estatisticas`, `raids`, `pokemon-batalhas-ativas`, `pokemon-cacadas-ativas`, `pokemon-propostas-troca`, `pokemon-descobertas`, `pokedex-cache`, etc. Rank compartilhado no bot.

Detalhe: `docs/pokemon-command-inventory.md`, `docs/pokemon-state-inventory.md`.

---

## 2. Matriz de equivalência (resumo)

Legenda: **EQ** equivalente · **PAR** parcial · **AUS** ausente no Core WA · **ALT** alteração intencional aprovada · **DOM** domínio JVM existe sem WA.

| Funcionalidade | Master | Core | Estado | Pri | Notas |
| --- | --- | --- | --- | --- | --- |
| Onboarding `!pk iniciar` + 3 iniciais + kit + card | Inicial antigo / fluxo service | M9.6/M9.6.1 | **ALT** | — | Referência = Core |
| Menu `!pk` vazio | Abre/entra PvP | Menu principal onboarding | **ALT** | — | Não reverter |
| `!pk 1`–`4` golpe | Sim (navegacao) | Sim (`5e7c6c0`) | **EQ/ALT** | — | Manter |
| Caça `cacar` + bioma/clima no plano | Sim | Sim | **PAR** | P1 | Falta `clima`/`areas`/`mapa` texto |
| Arena + HP + golpes na caça | Sim | Sim (`5e7c6c0`) | **PAR→EQ visual** | P2 | Validar sprites/efeitos vs master frame-a-frame |
| `defender` em caça | Sim | DOM `:defend` / WA BLOQ | **PAR** | **P0** | Reaproveitar domain; ligar present+http |
| Poção/curar **durante** caça | Sim | Fora de batalha OK; mid-fight BLOQ | **PAR** | **P0** | |
| Troca ativo mid-caça (1×) | Sim | AUS | **AUS** | P1 | |
| Captura 3 tries + bolas + fuga | Sim | Sim | **EQ** (domínio) | — | Conferir status mults |
| PvP 1×1 | Sim | AUS | **AUS** | **P0*** | *Ou decisão produto “offline no cutover” |
| Ginásio desafiar 3×3 | Sim | Sim | **PAR** | P1 | WIP status KO; ranking BLOQ |
| Raid loop jogador | Sim | Sim | **PAR** | P1 | abrir BLOQ (master stub) |
| Joy 30 min | Sim | Sim | **PAR/ALT** | P2 | Collect só em `!pk joy` |
| Loja comprar | Sim | Sim | **PAR** | P1 | kit/diário/resgatar |
| Evoluir / aprender golpes | Sim WA | DOM+app, WA BLOQ | **PAR** | **P0** | Portar comandos |
| Escolher Pokémon ativo | Sim | BLOQ | **AUS** | **P0** | |
| Coleção grade 12 / CSV | Sim | Texto | **PAR** | P2 | |
| Pokédex | Sim | Persistência só | **AUS** WA | P1 | |
| Missões / presente | Sim | AUS | **AUS** | P1 | |
| Negociar / doar | Sim | AUS | **AUS** | P1 | |
| Ovos + push | Flag | Sob demanda | **PAR/ALT** | P2 | |
| Saves A/B + fence | Service | JVM | **EQ/melhor** | — | Modelo por save |
| Cassandra gameplay | Sim | Proibido no Core | **ALT arch** | — | Correto |

---

## 3. Ausentes / incompletos / divergentes

### P0 — centrais quebradas ou ausentes para “mesmo jogo”

1. **PvP** — zero no Core; master `!pk`/`batalhar`. Decisão: implementar **ou** anunciar offline (bloqueia “paridade integral” se obrigatório).  
2. **`defender` no WhatsApp** — domínio já tem `:defend`; não ligado em `pokemon_http` / aliases.  
3. **Itens de cura mid-caça** — legado gasta turno; Core só fora de combate.  
4. **`escolher` / trocar ativo** — sem WA; bloqueia gestão de time.  
5. **`evoluir` / `aprender` (e correlatos)** — domínio M3.3 existe; sem comando WhatsApp.  
6. **Política explícita de cutover** se P0 sociais (PvP/missões/trocas) ficarem offline — senão expectativa de paridade falha.

### P1 — mecânica e progressão

- `clima` / `areas` / `mapa` / `eventos`  
- Ranking/histórico ginásio  
- Kit / diário / resgatar / presente  
- MT / equipar / reaprender / removergolpe / reviver  
- Missões diárias/semanais  
- Negociar / doar / professor  
- Pokédex espécie + pessoal  
- Troca mid-caça; times nomeados (`time salvar`)  
- Raid abrir (admin) / cancelar  
- Grade SVG coleção; `time` ficha completa  
- Validação fórmula a fórmula (crit, abilities, held items, Intimidate) em testes de paridade com RNG fixo  

### P2 — visual / mensagens / usabilidade

- Cartões Joy/loja/professor/evolução pixel-paridade  
- Efeitos overlay turno (já parcial em media)  
- Guias `!pk ajuda <tema>`  
- `shiny` filtro comando; títulos; amizade; favorito  
- Mensagens de erro alinhadas ao tom legado  

### Intencional (aprovado)

| Item | Justificativa |
| --- | --- |
| `!pk iniciar` + 3 iniciais + kit + card | M9.6 / M9.6.1 |
| `!pk` → menu (não PvP) | Onboarding |
| `!pk 1`–`4` | Usabilidade; alinhado à navegação master |
| Moedas/itens **por save** | Modelo A/B correto |
| Joy collect só em `!pk joy` | Menos surpresa; documentado m4-12 |
| Ovos hatch sob demanda | Sem worker push |
| Expansão mochila +50 | Preferir service |
| Datomic / sem Cassandra gameplay | Arquitetura |

---

## 4. Código legado reutilizável (preferir adaptar, não reescrever)

| Master | Uso no Core |
| --- | --- |
| `pokemon-service/.../core.cljs` combate (`calcular-dano`, status, defender, menu-golpes) | Já espelhado em `domain/{damage,status,battle,types}.clj` — **auditar diffs** com testes RNG |
| `mundo.cljs` / climas / áreas | `domain/mundo.clj` + `hunt.clj` |
| `loja.cljs` catálogo / missões estado | `domain/shop.clj`; missões ainda não |
| `ginasios.cljs` / `aventuras.cljs` líderes | `domain/gym.clj` + seeds |
| `raids.cljs` | `domain/raid.clj` |
| `pvp.cljs` | **Ainda não portado** — maior bloco P0 |
| `missoes.cljs` | Portar regras → JVM |
| `imagens.cljs` + `runtime/media.cjs` / assets PNG | `scripts/lib/pokemon-jvm-media.cjs` + assets |
| `navegacao.cljs` menus numéricos | Present + http (parcial) |
| `ajuda.cljs` textos | Portar para present (sem mudar regras) |
| Fórmulas captura (`chance-captura`, `fuga-raridade`) | `domain/capture.clj` — manter sync |

**Não reutilizar:** writers Cassandra; bootstrap onboarding antigo (substituído).

---

## 5. Plano incremental (por dependências)

Ordem sugerida **após autorização** (sem implementar agora):

| Fase | Conteúdo | Depende de |
| --- | --- | --- |
| **A0** | Congelar WIP ginásio (`store`/`gym_m48`) em commit próprio ou stash nomeado | — |
| **A1** | Paridade caça: WA `defender`, poção mid-caça, `clima`/`areas`; testes RNG fixo vs master | A0 |
| **A2** | Progressão WA: `escolher`, `evoluir`, `aprender`/`reaprender`/`rmg` (bridge já tem evolve/learn) | A1 |
| **A3** | Coleção UX: ficha, grade opcional, `!pokedex` leitura | A2 |
| **A4** | Economia: kit/diário/resgatar **ou** anunciar offline; equipar/MT uso | A2 |
| **A5** | Ginásio: ranking/histórico; fechar WIP KO; SVG 3 slots | A0 |
| **A6** | Decisão produto PvP: port `pvp.cljs`→JVM **ou** changelog “offline” | Política |
| **A7** | Missões + presentes + negociar/doar | A4, política |
| **A8** | Ovos push / Adventure (se flag) | Não bloqueia MVP |
| **A9** | Suite paridade contínua + staging cutover | A1–A7 conforme MVP |

Cada fase: domínio JVM → bridge → present/media → `pokemon_http` → testes; **sem** regras no Node.

---

## 6. Estratégia de testes de paridade / regressão

1. **Golden RNG:** mesmos seeds → mesmo dano/fuga/captura (portar fixtures de `pokemon-service` tests + `xp_equivalence_test`).  
2. **Contratos bridge:** startHunt / turn / capture / gym / raid sem mutação em GET/re-render.  
3. **Idempotência:** mesmo `operation-id` → sem double XP/itens.  
4. **Present puro:** `formatBattleState` / arena sem TX (já coberto em `pokemon-jvm-battle-ui.test.cjs`).  
5. **Onboarding:** kit/starter únicos; não duplicar (M9.6 smokes).  
6. **Comparativo visual:** snapshot PNG arenas (opcional) contra master fixtures.  
7. **Comandos inválidos:** `formatUnavailable` / erros de domínio estáveis.  
8. **Restart:** Datomic storage-dir persiste saves; sem Cassandra no path Pokémon.  
9. **Não alterar regras** só para passar teste — ajustar implementação ou documentar ALT.

---

## 7. Riscos e recomendações (saves / consistência)

| Risco | Recomendação |
| --- | --- |
| Expectativa “100% master” sem PvP/missões | Comunicar BLOQ ou implementar antes do cutover |
| Dual-write Cassandra+Datomic | **Proibido** em gameplay |
| Import contas reais | Pipeline M5: export offline → equivalência → quarentena; não `ensure` pré-import |
| WIP ginásio perdido | Não tocar `store.clj` / `gym_m48_test.clj` até merge dedicado |
| Sessão WhatsApp / volumes | Rebuild só imagens; `.dockerignore` protege auth |
| Moedas por save vs conta legado | Mapear na migração; nunca somar inventários A+B |
| Rank Cassandra vs vitórias Pokémon | Coordenar se PvP voltar |
| `cutover-ready?=false` | Manter até critérios m4-12 §10 |

---

## 8. Arquivos-chave por coluna da matriz

| Camada | Master | Core |
| --- | --- | --- |
| Entrada WA | `src/zapbot/pokemon_http.cljs`, `router.cljs` | idem + `POKEMON_ADAPTER=jvm` |
| Domínio | `pokemon-service/src/zapbot/pokemon/*.cljs` | `modules/pokemon/domain/...` |
| App/TX | service HTTP | `modules/pokemon/application/...` |
| Persistência | Cassandra `armazenamento` | Datomic `.../datomic/store.clj` |
| Apresentação | service `imagens` + bot media | `scripts/lib/pokemon-jvm-{present,media}.cjs` |
| Auditoria prévia | `docs/pokemon-*-inventory.md` | `docs/m4-12-auditoria-cutover.md` |

---

*Fim da auditoria. Aguardar autorização para implementar correções.*
