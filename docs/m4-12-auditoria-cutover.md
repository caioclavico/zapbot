# M4.12 — Auditoria de cobertura e preparação do M5

**Branch:** `refactor/modular-monolith-datomic`  
**Base M4.11:** `d3df79c`  
**Escopo:** somente auditoria e documentação. Sem implementação, sem produção, sem migração de dados reais, sem alteração de deploy/flags.

**Legenda de status**

| Código | Significado |
| --- | --- |
| **JVM** | Completo no adaptador Node→JVM→Datomic (dev: `POKEMON_ADAPTER=jvm` + `APP_ENV=development`) |
| **PARCIAL** | Domínio/bridge existe, mas UX legado incompleta ou divergente |
| **BLOQ** | Bloqueado de propósito no JVM (sem suporte seguro) |
| **NODE** | Permanece no bot Node (Cassandra/memória); não exige JVM |
| **AUSENTE** | Não existe no roteador atual (mencionado só para clareza) |

Produção continua forçando `POKEMON_ADAPTER=legacy` (`src/zapbot/config.cljs`).

---

## 1. Matriz — comandos fora do Pokémon

| Comando | Aliases | Persistência | Status | Notas |
| --- | --- | --- | --- | --- |
| `!piada` | — | nenhuma | NODE | |
| `!curiosidade` | — | nenhuma | NODE | |
| `!noticias` | — | nenhuma | NODE | |
| `!cotacao` | — | nenhuma | NODE | |
| `!previsao` | `tempo`, `clima` | nenhuma | NODE | |
| `!horoscopo` | — | nenhuma | NODE | |
| `!filme` / `!filmes` / `!oscar` / `!genero` | — | nenhuma | NODE | TMDB |
| `!defina` | `definir` | nenhuma | NODE | |
| `!traduza` | — | nenhuma | NODE | |
| `!resuma` | `resumo`, `resumir`, `resume` | histórico em memória + `participantes` | NODE | IA (Gemini) |
| `!lembrete` / `!lembretes` / `!cancelarlembrete` | — | Cassandra `lembretes` | NODE | Timers no processo Node; `cancelarlembrete` no `despachar`, fora da lista `comandos` de `!ajuda` |
| `!enquete` / `!votar` | — | Cassandra `enquetes` | NODE | `votar` no `despachar`, fora de `comandos`/`!ajuda` |
| `!apuracao` | — | nenhuma | NODE | TSE |
| `!pergunta` | — | nenhuma | NODE | IA |
| `!bola8` | — | nenhuma | NODE | |
| `!sorteio` | — | `participantes` | NODE | |
| `!velha` | — | memória + rank | NODE | Jogo em memória |
| `!naval` | — | memória + rank | NODE | |
| `!adedonha` | `stop` | memória | NODE | |
| `!musica` | — | nenhuma | NODE | Spotify |
| `!ban` | — | — | NODE | Admin |
| `!status` | — | — | NODE | |
| `!quiz` | — | Cassandra `quiz-historico` + memória | NODE | |
| `!rank` | — | Cassandra `rank` | NODE | Vitórias jogos + Pokémon legado |
| `!bloquear` / `!desbloquear` | — | Cassandra `bloqueio` | NODE | |
| `!meuid` | — | — | NODE | |
| `!ajuda` | — | — | NODE | |
| **Forca** | — | — | **AUSENTE** | Não há comando no `router.cljs` atual |

**Conclusão:** jogos de grupo (velha, naval, adedonha, quiz), lembretes, IA e utilitários **não precisam migrar para JVM**. Só precisam continuar operacionais com o bot Node + Cassandra (ou, no futuro, outro store genérico se `armazenamento` for abstraído). Cutover Pokémon não deve quebrar esses módulos.

---

## 2. Matriz — Pokémon (roteador + `!pk`)

### 2.1 Top-level Pokémon no router

| Comando | Status | Milestone | Observação |
| --- | --- | --- | --- |
| `!pokemon` / `!pk` | PARCIAL | M4.3–M4.11 | Só subset JVM; resto `formatUnavailable` |
| `!pokedex` / `!dex` / `!pdx` | BLOQ/PARCIAL | — | Top-level despacha `executar "pokedex"`; JVM não tem alias `pokedex` → indisponível no adapter |
| `!loja` | JVM | M4.11 | Catálogo + comprar + detalhes |
| `!mochila` | PARCIAL | M4.11 | Inventário sim; kit/diario/resgatar BLOQ |
| `!presente` / `!presentes` | BLOQ | — | |
| `!missoes` / `!missões` | BLOQ | — | Diárias/semanais |

### 2.2 Subcomandos `!pk` / aliases (`atalhos-comandos` + JVM `ALIASES`)

| Comando legado | Aliases | Status JVM | Notas / divergências |
| --- | --- | --- | --- |
| `treinador` | `tre`, `perfil` | JVM | Perfil + ativo |
| `time` / `equipe` | `tm`, `meus`, `colecao` | PARCIAL | Texto/paginação; grade SVG/CSV/`time ativo` ficha completa legada incompletos |
| `save` / `saves` | `ativo` | JVM | M4.2/M4.3 |
| `iniciar` | `criar` | JVM | Ensure A/B |
| `ativar a\|b` | — | JVM | Fence |
| `recibo <id>` | `op` | JVM | |
| `cacar` | `cac`, `caçar`, `hunt` | JVM | M4.5–M4.6 |
| `capturar` | `cap` | JVM | M4.4 |
| `atacar` | `atk`, `ataque` | JVM | Caça/raid/gin (contexto) |
| `ginasio` | `gin`, `ginásio`…; sub: `des`/`tm`/`pot`/`fru`/`ran`/`hist`/`ent`… | PARCIAL | Overview/time/heal/desafiar 3×3 (M4.7–M4.8); **ranking/histórico BLOQ** |
| `raid` / `raide` | `raides`; sub: `ent`/`abr`/`ini`/`atk`/`sai`/`can`/`cap` | PARCIAL | Status/entrar/sair/iniciar/atacar/capturar (M4.9); **abrir/cancelar BLOQ** |
| *(vazio)* `!pk` | abre/entra PvP liga | BLOQ | Sem args no legado = PvP; no JVM → `formatUnavailable` |
| `joy` | `enfermeira`, `enfermaria`, `hospital` | JVM | M4.10; índices = coleção (não só equipe) |
| `ovos` / `ovo` / `incubar` / `incubadoras` | — | PARCIAL | M4.10 sob demanda; **sem notificação push**; grant só harness |
| `loja` (via `!pk` ou top) | — | JVM | M4.11 |
| `mochila` | `mch` | PARCIAL | Ver inventário; kit/diario/resgatar BLOQ |
| `curar` | `cur`, `cura` | JVM | Fora de batalha; em combate PvP ainda NODE |
| `pocao` / `pocao-maxima` | `pot`, `vida`, `pmax`, `maxima` | JVM | Idem |
| `inicial` | `ini`, `iniciais` | BLOQ | |
| `pokedex` (coleção pessoal) | `dex`, `pdx` | BLOQ | Confunde com top-level espécie |
| `escolher` / `trocar` | — | BLOQ | Ativo |
| `equipar` | `eqp`, `item` | BLOQ | |
| `mt` | — | BLOQ | Compra possível; uso não |
| `aprender` / `reaprender` / `removergolpe` | `apr`/`reap`/`rmg`/`can` | BLOQ | |
| `evoluir` | `evo` | PARCIAL | Domínio M3.3 + consume pedra; **comando WhatsApp não ligado** |
| `negociar` | `neg` | BLOQ | Trocas |
| `doar` | `doa` | BLOQ | |
| `liga` | `lig`, `ligas` | BLOQ | PvP 3×3 entre jogadores |
| `defender` | `def`, `defesa`, `esquivar`, `evasiva` | BLOQ | Turno PvP |
| `sair` | `sai` | PARCIAL | Caça/raid sim; desistência PvP BLOQ |
| `times` / time salvar\|usar\|excluir | sub time: `sal`/`usa`/`exc`… | BLOQ | Times prontos (não confundir com `time`/`equipe` coleção) |
| `reviver` | `rev` | BLOQ | |
| `titulo` / `amizade` / `favorito` | `fav` | BLOQ | |
| `clima` / `areas` / `mapa` | — | BLOQ | Texto mundo |
| `eventos` | `evt` | BLOQ | |
| `shiny` | — | BLOQ | |
| `professor` | — | BLOQ | |
| `espaco` / `pc` / `centro` | `espaço`, `computador` | BLOQ | Expansão PC / depósito |
| `bug` | — | BLOQ | Cassandra `bugs` |
| `ajuda` (guias) | — | BLOQ | Textos em `ajuda.cljs` |
| **PvP** (`!pk` sem args / entrar) | — | BLOQ | Motor 1v1/liga Node |
| **Missões** | `mis` | BLOQ | |
| **Presente** | `pre` | BLOQ | |
| **Adventure pedras/surtos** | — | PARCIAL | Surto pode entrar no plano de caça; pedras/surtos de `aventuras.cljs` não migrados como módulo |

### 2.3 Resumo por área Pokémon

| Área | JVM | Pendência crítica para MVP cutover? |
| --- | --- | --- |
| Conta / saves A·B / fence / recibos | Sim | Não |
| Coleção / XP Pokémon (domínio) | Sim (leituras/comandos parciais) | Evoluir/aprender no WA |
| Caça + captura + mídia | Sim | Polimento SVG grade |
| Ginásios 3×3 + heal | Sim | Ranking/histórico opcional |
| Raids multiplayer | Sim | Abrir admin; cancelar |
| Joy + ovos sob demanda | Sim | Notificações; auto-grant |
| Loja compra + curar/poção | Sim | Kit/diario/MT/equipar |
| PvP / missões / trocas / doar | Não | **Sim se forem MVP de jogo** |
| Adventure Sync completo (pontos/eventos) | Não | Pode adiar com aprovação |

---

## 3. Divergências de regras (legado × JVM)

| Tema | Legado | JVM | Impacto |
| --- | --- | --- | --- |
| Expansão mochila | `src`: +25 em `capacidade-mochila`; service: +50 via `expansoes-mochila-50` | +50 (`capacity.clj` / M4.11) | Preferir service; documentar cutover |
| Joy índices | Equipe 1-based | Coleção 1-based | Comunicação aos jogadores |
| Collect Joy | Lazy em todo `!pk` | Só em `!pk joy` | Menos automático |
| Ovos | pokemon-service + outbox 60s | Hatch sob demanda | Sem push |
| Raid abrir | Removido / admin | Harness `allowTestRng` | Igual intenção |
| Moedas/itens | Chave Cassandra `loja` (conta) | Por **save** Datomic | Modelo mais correto A/B; migração deve mapear |
| Treinador | Chave `treinador` (equipe unificada) | Coleção por save | Migração cuidadosa |
| Ginásio ranking/histórico | Cassandra `ginasios-estatisticas` | BLOQ | UX secundária |
| Rank pontos | Cassandra `rank` (inclui vitórias Pokémon) | Só se Node ainda registrar | Coordenar cutover PvP |

---

## 4. Outros jogos e organização modular

Velha, naval, adedonha, quiz, lembretes, IA, rank, bloqueio e utilitários vivem em namespaces Node e usam `zapbot.armazenamento` (Cassandra) ou memória. **Não há requisito de migrá-los para a JVM Pokémon.** Critério: após cutover Pokémon, `npm start` + Cassandra (ou store genérico) deve manter esses comandos verdes.

---

## 5. Dependências efetivas do Cassandra

### 5.1 Modelo físico

| Item | Valor |
| --- | --- |
| Tabelas | `{keyspace}.estado` (legado), `{keyspace}.estado_particionado` (atual) |
| Keyspace default | `zapbot` |
| Escritores | Bot Node **e** `pokemon-service` compartilham o **mesmo** keyspace — dois writers na mesma partição perdem updates (`docs/pokemon-state-inventory.md`) |

### 5.2 Chaves — bot Node (não-Pokémon + entrega)

| Chave | Módulo |
| --- | --- |
| `rank`, `bloqueio`, `admins-conhecidos`, `participantes` | rank / bloqueio / admins / historico |
| `lembretes`, `enquetes`, `quiz-historico`, `bugs` | respectivos namespaces |
| `pokemon-http-deliveries` | `pokemon_http.cljs` |

### 5.3 Chaves — domínio Pokémon (bot stub e/ou pokemon-service)

| Chave | Notas |
| --- | --- |
| `treinador`, `loja`, `raids`, `raides-agendas` | Economia / raids / agenda |
| `ginasios`, `ginasios-estatisticas` | Ocupação / ranking |
| `pokedex-cache`, `pokemon-descobertas` | Cache / first-catch global |
| `pokemon-batalhas-ativas`, `pokemon-cacadas-ativas` | Snapshots de combate |
| `pokemon-remocoes-pendentes`, `pokemon-propostas-troca` | Ops pendentes |
| `pokemon-http-requests`, `pokemon-http-events` | Transporte service |
| `pokemon-save-slots`, `pokemon-save-slot-index`, `pokemon-save-global-receipts` | Snapshots históricos (Etapa 1) |
| `pokemon-save-transition-*`, `pokemon-playable-save-*`, `pokemon-active-save-pointer` | Ativação A/B (flags fail-closed) |
| `pokemon-restauracao-*` | Restore journals |
| Tabelas CQL canônicas | `canonical_trainer_accounts` (+ gym/adventure adapters) |

### 5.4 Agendamento / boot

| Job | Onde | Intervalo |
| --- | --- | --- |
| Lembretes | Node `lembretes.cljs` | ~1s após `ready` |
| Raid scheduler | pokemon-service | 60s |
| Adventure hatch tick | pokemon-service | 60s |
| Poll eventos HTTP Pokémon | `pokemon_http.cljs` | `POKEMON_EVENT_POLL_MS` (15s) |
| Joy beds | sob demanda (`pronto-em`) | sem push 30 min |
| `armazenamento/iniciar!` | bot core | antes do WhatsApp |

### 5.5 Deploy

| Artefato | Cassandra | JVM |
| --- | --- | --- |
| `docker-compose.yml` (dev) | container `cassandra:5`, heap ~1 G, `127.0.0.1:9042` | bridge só via `clojure -M:bridge-http` local |
| `docker-compose.production.yml` | **externo** (outra VM); bot `mem_limit: 2560m`, Node heap 384 MB | **ausente** na imagem do bot |
| `Dockerfile` bot | — | runtime = Node + Chromium **sem** JRE |
| `pokemon-service/Dockerfile` | via env | Node-only (domínio HTTP) |

**Flags:** `POKEMON_ADAPTER=jvm` só com `APP_ENV=development`; fora disso força `legacy`. Ativação A/B legada exige `SAVE_TRANSITIONS_*` (default **false**).

**Cutover Pokémon:** writer canônico único (Datomic). Cassandra permanece para módulos NODE. **Proibido dual-write** prolongado de moedas/coleção/raids.

---

## 6. MVP vs adiável (aprovação explícita)

### 6.1 Bloqueios críticos do primeiro deploy JVM Pokémon (MVP sugerido)

Sem estes, cutover de gameplay Pokémon é inseguro ou incompleto demais:

1. **Migração de dados** treinador+loja+ginasios(+raids ativos) → Datomic com A/B e fence (ver §8–11).
2. **Single-writer** após cutover (sem Cassandra no path de gameplay Pokémon).
3. **Comandos já JVM** estáveis em staging: saves, caça, captura, ginásio, raid, joy, ovos sob demanda, loja, curar/poção.
4. **Memória VM** medida: Node + Chromium + JVM sem Cassandra concorrente (§13).
5. **Rollback** documentado e ensaiado (§14–15).
6. **Política explícita** sobre PvP/missões/trocas: ou implementados no M5, ou anunciados como offline no cutover.

### 6.2 Pendências não críticas (adiáveis com aprovação)

- Notificações de ovo / worker 60s  
- Kit / diário / resgatar / presente  
- MT, equipar, reaprender, remover golpe  
- Ranking/histórico de ginásio  
- Cancelar raid; abertura admin via canal seguro  
- Grade SVG completa / CSV time  
- Auto-grant de ovos e pontos Adventure  
- Pedras/surtos como módulo `aventuras`  
- Top-level `!pokedex` espécie no JVM  
- Evoluir/negociar/doar no WhatsApp JVM  
- Expansão PC (`espaco`)  
- Missões diárias/semanais  

---

## 7. Mapeamento Cassandra → Datomic (preparação; sem exportar produção)

### 7.1 Entidades

| Conceito legado | Chave / local | Modelo Datomic | Escopo |
| --- | --- | --- | --- |
| Conta jogador | `treinador` chat×player | `:player/*` | chat×player |
| Save ativo / A·B | ponteiro (service) ou conta única | `:player/active-save-id`, `:player/fence-generation` | conta |
| Coleção / equipe | `treinador` equipe | `:pokemon/*` + `:save/*` | save |
| Enfermaria | `treinador` enfermaria | `:enfermaria-bed/*` | save |
| Moedas / inventário | `loja` | `:save/coins`, `:inventory-item/*` | save |
| Expansões | `loja` expansoes-* | `:save/expansoes-pokemon-50`, `:save/expansoes-mochila-50` | save |
| Ginásios | `ginasios` | entidades gym + ocupação grupo | grupo + save |
| Raids | `raids` | `:raid/*`, `:chat-active-raid/*` | grupo |
| Adventure ovos | pokemon-service / tabelas sync | `:adventure-account/*`, `:egg/*` | conta (não save) |
| Rank / quiz / lembretes | próprias chaves | **fora** do cutover Pokémon | Node |

### 7.2 Dois históricos de save (V1 + atual)

Clarificações de nomenclatura (auditoria infra):

- Não existe namespace `historico-save` no repo.  
- “V1/V2” nos slots = `sourceVersion` de origem (`legacy_estado_particionado` → `"1"`, `legacy_estado` → `"2"`), não um segundo “histórico de chat”.  
- `save-bound-v1` / `json-account-v1` = versão de schema da **loja**, não snapshot de save.  
- Ativação A↔B no legado ainda **fail-closed** em produção (`SAVE_TRANSITIONS_*` false; writers laterais sem fence). JVM A/B funciona na bridge de **dev**.

Documentação: `docs/pokemon-adventure-sync/multiplos-saves-etapa1.md` (+ etapas 2.x).

**Política M5 proposta**

1. Importar save **ativo** canônico → slot `a` (ou id canônico).  
2. Importar snapshot **histórico** (slot inativo Etapa 1) → slot `b` ou slot dedicado, somente leitura até ativar.  
3. Nunca concatenar equipes; nunca somar inventários.  
4. Relatório de equivalência: contagens, hashes, moedas, IDs Pokémon.  
5. Dados ambíguos → quarentena + revisão humana; **sem perda silenciosa**.  
6. Não rodar `ensure-trainer!` JVM contra IDs reais **antes** do import (cria A/B vazios e mascara falha de migração).

### 7.3 Riscos de dados

| Risco | Tratamento |
| --- | --- |
| Conta só em `treinador` sem `loja` | Criar inventário vazio; moedas 0; flag no relatório |
| `loja` sem treinador | Quarentena; não criar save fantasma |
| Duplicidade chat/player | Chave canônica `chat|player`; falhar import se colisão |
| Raid/ginásio em andamento no export | Congelar janela; importar só estados terminais ou mapear fase |
| IDs WhatsApp lid vs phone | Normalizar com mesma regra do bot atual |
| Missões/diario mid-flight | Adiar ou zerar com aviso explícito se módulo BLOQ |

### 7.4 Pipeline proposto (só bases descartáveis nesta fase)

1. **Export** offline de dump Cassandra (não nesta etapa).  
2. **Transform** → EDN/JSON staging por entidade.  
3. **Import** em Datomic Local descartável (`storage-dir` dedicado).  
4. **Equivalência** automatizada (contagens + amostragem + hashes).  
5. **Backup** snapshot do storage Datomic + dump Cassandra pré-cutover.  
6. **Rollback:** religar `POKEMON_ADAPTER=legacy` + restore Cassandra; Datomic fica read-only/arquivo.

---

## 8. Infraestrutura

### 8.1 Memória (deploy coordenado)

| Componente | Ordem | Nota |
| --- | --- | --- |
| Chromium | Dominante | Sessão WhatsApp |
| Node bot | Moderado | prod: heap 384 MB / cgroup ~2560 MB |
| Cassandra heap | ~1 G | Dev local; prod em VM separada |
| JVM Datomic | `-Xmx256m`–`512m` (smokes RSS ~210–280 MB) | **Ainda fora** da imagem Docker do bot |

**Regra:** não empilhar Cassandra + Datomic + Chromium em VM ≤2–2.5 GB por longo período. Cutover exige imagem/compose com JVM + volume durável — hoje o `Dockerfile` do bot **não** inclui JRE.

### 8.2 Datomic Local

- Storage: `.datomic-local/` (gitignored); bridge default `ODISSEU_BRIDGE_STORAGE=.datomic-local/m41-bridge`.  
- Reinício: reabrir mesmo `system` + `storage-dir` + `db-name` restaura dados (provado nos smokes M4).  
- Backup: snapshot do diretório com processo parado + dump Cassandra pré-flip.  
- Falha mid-TX: TX atômica; `operation-id` + payload-hash impedem duplicar em retry.  
- Sem cluster: um nó de arquivos.

### 8.3 Substituição dos serviços

| Antes | Depois (alvo) |
| --- | --- |
| Node → pokemon-service / Cassandra gameplay | Node → bridge JVM → Datomic |
| Escrita Cassandra Pokémon | **Uma** autoridade: Datomic |
| Dual-write | **Proibido** em produção (harness descartável só para diff) |
| Imagem bot sem JVM | Incluir JRE + volume Datomic no plano M5 (ainda não feito) |

### 8.4 Go / no-go (infra)

| Check | Status |
| --- | --- |
| `POKEMON_ADAPTER=jvm` em produção | Bloqueado (força legacy) |
| JVM na imagem Docker do bot | Ausente |
| Ativação A↔B legada certificada | Não (`SAVE_TRANSITIONS_*` false) |
| Import Cassandra→Datomic de contas reais | Fora desta etapa |
| Plano single-writer | Documentado (este doc + arquitetura) |
| Dual-write longo em prod | **Não fazer** |

---

## 9. Ordem recomendada dos próximos marcos (M5+)

1. **M5.0 — Harness de migração descartável**  
   Export sintético + import + relatório de equivalência + backup/rollback ensaiados.  
2. **M5.1 — Completar MVP WhatsApp gaps** (se aprovados como críticos)  
   Ex.: `evoluir`, `escolher` ativo, `!pokedex` top-level ou alias, PC/`espaco` se necessário.  
3. **M5.2 — Decisão de produto: PvP / missões / trocas**  
   Implementar no JVM **ou** anunciar offline no cutover.  
4. **M5.3 — Staging cutover**  
   Adapter JVM em staging; Cassandra Pokémon somente leitura ou desligado no path.  
5. **M5.4 — Cutover produção**  
   Flag + runbook; janela curta; métricas RSS/Chromium.  
6. **M5.5 — Adventure completo / notificações / kit-diario** (pós-MVP).  
7. **M6 — Opcional:** abstrair Cassandra dos módulos NODE (rank, lembretes) — fora do caminho crítico Pokémon.

---

## 10. Critérios objetivos: “projeto pronto para cutover Pokémon”

Todos devem ser verdadeiros:

1. Relatório de equivalência em base descartável com **0** perdas silenciosas (falhas só em quarentena listada).  
2. Suite M3.x + M4.x verdes; smoke HTTP bridge verdes.  
3. Staging com `POKEMON_ADAPTER=jvm` jogável nos fluxos MVP (≥ caça, captura, ginásio, raid, joy, loja, saves A/B).  
4. Medição de memória: Chromium estável com JVM no ar e **sem** dual Cassandra+Datomic prolongado.  
5. Runbook de rollback ensaiado (&lt; X minutos definidos pela ops).  
6. Lista de comandos BLOQ comunicada aos jogadores (changelog).  
7. Decisão assinada sobre PvP/missões/trocas (no MVP ou adiados).  
8. Produção: um único writer de gameplay Pokémon.

---

## 11. Referências

- Router: `src/zapbot/router.cljs`  
- Comandos Pokémon: `src/zapbot/pokemon/core.cljs` (`jogar-comando`, `atalhos-comandos`)  
- Adapter JVM: `src/zapbot/pokemon_http.cljs`, `scripts/lib/pokemon-jvm-present.cjs`  
- M4.x: `docs/m4-1-*.md` … `docs/m4-11-shop.md`  
- Saves/Adventure: `docs/pokemon-adventure-sync/*`, `docs/m3-7-adventure-joy.md`  
- Memória: `docs/arquitetura-monolito-modular-datomic.md`, `docs/recursos-chromium.md`  
- Inventário legado: `docs/pokemon-command-inventory.md`  
- Estado Cassandra: `docs/pokemon-state-inventory.md`  
- Auditoria writers/saves: `docs/pokemon-adventure-sync/multiplos-saves-etapa*.md`  
- Cobertura de aliases (auditoria M4.12): `atalhos-comandos` / `atalhos-subcomandos` em `core.cljs`; JVM `ALIASES` em `pokemon-jvm-present.cjs`

---

*Documento gerado na etapa M4.12 — sem acesso a produção e sem alteração de código de gameplay.*
