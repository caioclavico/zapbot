# Fase 12.8 — Missões, Doces Pokémon, Eventos, Clima e Captura Selvagem

**Status:** **CONDITIONAL READY** · auditoria final 2026-11-02 · **AGUARDA GO DEFINITIVO**  
**Base:** piloto com hotfixes de raids / ginásios por tipo / captura / saves (`cd03767` e anteriores)  
**Deploy / migrate / backfill / produção / ativação de flags em live / imagens RC / commit cutover:** **não realizados**

### Gate matrix (auditoria final)

| # | Item | Status | Evidência |
| --- | --- | --- | --- |
| 1 | Flag mochila diária preparada | **IMPLEMENTADO** | `POKEMON_BAG_KIT_DAILY_ENABLED` default `false` (`.env.odisseu-core.example`, `config/pokemon-bag-kit-daily-enabled?`). Suites `bag-kit-daily` 5/5 + `bag-rewards` 6/6 PASS. Não ativada no piloto neste trabalho. |
| 2 | Filtros missões singular/plural/acento | **IMPLEMENTADO** | Domínio `suggest/normalize-scope` + Node `missionScope` + cljs `normalizar-missao-token`. Teste `aliases-missoes-normalizam-acento-e-numero` + Node missions PASS. |
| 3 | `!pk missoes` só sugestões pendentes/fáceis | **IMPLEMENTADO** | `formatMissionsSummary` / `mission-suggest` (≤2 diárias + ≤2 semanais, só `available`/`in_progress`). Testes `resumo-sugere…`, `ranking-prioriza…`, Node `MISSÕES DO TREINADOR` PASS. |
| 4 | Recompensas diversificadas (bolas/Frambo/presente/Doces) | **PARCIAL** | Diárias/semanais: bolas + Frambo + cartão-presente + PE/moedas — `recompensas-diversificadas…` + catálogo. **Doces** só no catálogo de **eventos** (`:doces` por família); claim operacional de missões de evento **ainda sem lote** (não concede Doces em produção). |
| 5 | Doces por família + saldos/custos | **IMPLEMENTADO** | Apresentação Doces; persistência `professor-cards-edn` intacta; `family`/`candy-label`/`grant-candy-map`. Testes `doces-renomeacao…`, `profile-a551`, present professor Doces PASS. Evolução por nível/itens **inalterada**. |
| 6 | Eventos temáticos | **PARCIAL** | Catálogo 7 eventos + bias caça + pools de Doces — `eventos-catalogo…`, `clima-influencia…` PASS. Sem janela → `inactive` (seguro). **Pendente:** schedule/admin por chat e claim de missões/recompensas de evento. |
| 7 | Tiposos PT em `!pk clima` | **IMPLEMENTADO** | 18 tipos via `mundo/tipo-label-pt` / ginásios. `clima-traduz-18-tipos` PASS (sem IDs EN no resumo). |
| 8 | Influência climática real na caça | **IMPLEMENTADO** | `hunt/resolver-tipos-encontro` 35% clima / 65% bioma. Simulação 100 rolls = 35; +evento bias 20 = 20. `clima-influencia-sorteio-real` PASS. |
| 9 | Captura ∝ raridade | **IMPLEMENTADO** | Faixas comum≫lendário/mítico; sem +25 flat; caps 95. `captura-raridade…`, `captura-comuns…`, `capture-m33` PASS. |
| 10 | Frambo na caça selvagem | **IMPLEMENTADO** | `/v1/encounter/frambo` + aliases `fruta`/`frambo`; estado separado da raid. Domínio ×1,5 + Node client; raid Frambo regressão 12.6 PASS. |
| 11 | Mensagem completa pós-Frambo | **IMPLEMENTADO** | `formatCaptureMenu` antes/depois com % reais do DTO. Novo teste Node `menu de captura caça: fruta e chances…` PASS. |
| 12 | Compat raids/economia/saves/IA | **IMPLEMENTADO** | `test-m3-capture` (ledger/saves), `test-a6-economy-capacity`, `raid-capture-rewards-fase126`, `raid-hotfix-fase1231` + Node raid 14/14 PASS. Gemini/Groq não alterados nesta fase. |

**Veredito gates:** núcleo 12.8 (missões UX, Doces rename, clima, captura, Frambo caça) **aprovado em descartável**. **Não** liberar commit cutover / RC / backups live enquanto itens **4/6** (claim+schedule de eventos) permanecerem PARCIAL — salvo GO explícito aceitando eventos só-domínio/inativos.

---

## 1. Auditoria — funcionalidades existentes

| Área | Achado |
| --- | --- |
| Mochila diária | Já implementada. Flag real: `POKEMON_BAG_KIT_DAILY_ENABLED` (`config/pokemon-bag-kit-daily-enabled?`, default `false`). Kit = 10× Pokébola; diário = 3–7 bolas (60/30/10) + Frambo Dourada a cada 7 dias; overflow → pending; idempotente por dia civil (`MISSOES_TIMEZONE`). |
| Missões | Catálogo A4.3.1 em `mission-catalog` / rewards / progress. Aliases Node parcialmente normalizados; cljs só aceitava `diarias`/`semanais` exatos. |
| Cartões de família | Persistidos em `:save/professor-cards-edn` (mapa família→qty). Uso atual: **+3 XP** via `professor usar` (não custo de evolução). Evolução continua por nível/amizade/itens. |
| Clima | Rotação diária em `mundo/climas`; caça usa `hunt/resolver-tipos-encontro` (**35% clima / 65% bioma**) — influência **real** no sorteio. Labels eram IDs EN. |
| Captura selvagem | Fórmula poder×fator raridade + **+25 flat** pós-batalha; Pokébola cap 75 truncava comuns. Frambo só em raide. |
| Eventos | Surto 6h legado + evento histórico “Novo Recomeço”. Sem catálogo temático configurável. |

---

## 2. Flag da mochila diária — estratégia de ativação

| Item | Valor |
| --- | --- |
| Env | `POKEMON_BAG_KIT_DAILY_ENABLED` |
| Default exemplo | `false` (`.env.odisseu-core.example`) |
| Core histórico | já foi `true` após GO 2026-10-29 (checkpoint bag-kit) |
| Piloto A6 cutover | **não alterado neste trabalho** |

**Próximo GO (release):** confirmar presença da flag no `env_file` do Node; se `false`, setar `true` sem recreate de JVM; smoke `!pk mochila diario` (1×/dia). Sem segunda implementação. Sem backfill neste escopo.

Itens concedidos (domínio `bag-rewards`):

- **Kit:** 10× `pokebola` (exige 10 vagas; senão não marca resgatado).
- **Diário:** `3 + min(4, sequência−1)` bolas (3…7); sorteio 60% Poké / 30% Grande / 10% Ultra; a cada 7 dias de sequência: +1 `fruta-dourada`.

---

## 3. Aliases de missões

Normalização NFD + lowercase em:

- `src/zapbot/pokemon_http.cljs` (`normalizar-missao-token`)
- `scripts/lib/pokemon-jvm-present.cjs` (`missionScope`)
- domínio `mission-suggest/normalize-scope`

Aceitos: `diaria(s)`, `diária(s)`, `semanal(is)`, `evento(s)` (+ EN).  
Comandos/aliases existentes (`mis`, `missões`) preservados.

---

## 4. Nova interface de `!pk missoes`

Sem filtro → **resumo compacto** (`formatMissionsSummary`):

- Progresso diárias/semanais
- Evento ativo (se houver)
- Até 2 diárias + 2 semanais sugeridas (domínio `mission-suggest`)
- Links para listas completas / evento

Ranqueamento (domínio, separado da UI): progresso relativo desc → dificuldade asc → meta.

---

## 5. Tabela definitiva de recompensas (catálogo 12.8)

| Missão | Meta | Recompensas base |
| --- | --- | --- |
| Diária Explorador | 3 | 2 Pokébola + 1 Frambo + 2 PE |
| Diária Colecionador | 2 | 2 Super Bola + 1 Frambo + 3 PE |
| Diária Desafiante | 1 | 1 Ultra + 1 cartão-presente + 3 PE |
| Diária Amigo | 2 | 2 Pokébola + 1 Frambo + 2 PE |
| Semanal Ginásios | 2 | 3 Super + 2 Frambo + 60 moedas |
| Semanal Tipos | 5 | 2 Ultra + 1 cartão-presente + 50 moedas |
| Semanal PvP | 3 | 2 Ultra + 1 cartão-presente + 60 moedas |
| Semanal Presentes | 10 | 3 Super + 2 Frambo + 50 moedas |

Bônus aleatórios diários legados (bola extra / reviver / item evolução) e item evolução semanal **preservados**.  
Escalonamento por nível continua multiplicando metas/itens.  
Concessão: uma vez (`resgatadas`), allocate → inventário/pending, ledger operacional intacto.

---

## 6. Doces Pokémon (renomeação)

- **Camada de apresentação:** “Cartões de família” → **Doces Pokémon** / `Doce {Família}`.
- **Persistência:** mesma chave `professor-cards-edn`; **sem migration**.
- **Mecânica atual preservada:** transferência → +1 doce da família; `professor usar` → −1 doce +3 XP.
- **Evolução:** custos atuais (nível/amizade/itens) **inalterados** — exemplos com “12/10 doces para evoluir” são alvo de UX futura, não desta fase.
- Famílias expandidas em `profile/family` (cadeias conhecidas; demais = species).
- Aliases: `professor cartoes` / `doces` na ajuda de apresentação.

---

## 7. Evidência de preservação de saldos / custos

- Testes `profile-a551` + `fase128` (`doces-renomeacao-preserva-saldos-e-familias`).
- `grant-candy-map` normaliza para espécie-base (`dragonite` → `dratini`).
- Nenhuma conversão de EDN; restart preserva mapa existente.

---

## 8. Eventos temáticos

Catálogo em `pokemon.domain.events`:

1. Festival dos Dragões  
2. Noite das Sombras  
3. Tempestade Elétrica  
4. Maré Misteriosa  
5. Despertar da Floresta  
6. Era dos Fósseis  
7. Eclipse Místico  

Cada um: tipos favorecidos, pool de famílias de doces, missões, `bias-tipos` ≤ 35.  
Janelas `inicio`/`fim` configuráveis; sem janela → `inactive` (não liga sozinho em prod).  
Integração de caça: `hunt/resolver-tipos-encontro` aceita eventos ativos (bias → clima → bioma).  
**Não** altera chances permanentes; **não** cria doces genéricos de tipo.

*Pendente para GO futuro:* persistência de schedule por chat / admin HTTP para abrir janelas — domínio pronto; wire operacional no GO.

---

## 9. Doces por evento

`events/grant-candy-map` + pools por evento. Missões de evento carregam `:doces {familia qty}` ilustrativo. Concessão idempotente reutiliza o mesmo saldo do professor (quando o claim de evento for ligado no GO de ativação).

---

## 10. Clima — auditoria e testes

| Item | Resultado |
| --- | --- |
| Fonte | Rotação determinística `mundo/clima-do-dia` (hash do dia civil) |
| Influência na caça | **Sim** — 35% tipos do clima vs 65% bioma |
| Tradução | 18 tipos via `raid-gym-theme` / `mundo/tipo-label-pt` + Node `TYPE_LABEL_PT` |
| Fallback | Sem clima → bioma; eventos opcionais |
| Simulação | 100 rolls: 35 clima; com bias 20 → 20 evento |

---

## 11. Fórmula definitiva de captura por raridade

`chance-captura` (espécie, Pokébola sem status/seq):

| Raridade | Faixa |
| --- | --- |
| comum | 75–90 (centro 82) |
| incomum | 55–75 (65) |
| raro | 30–50 (40) |
| épico | 15–30 (22) |
| lendário | 5–12 (8) |
| mítico | 3–8 (5) |

Leve variação ±4 pp por poder dentro da faixa.  
`chance-base-pos-batalha` = `round(status × espécie) + min(10, sequência)` (cap 95) — **sem +25 legado**, para manter comuns ≫ lendários.  
Caps de bola unificados em **95** (Pokébola não trunca comuns artificialmente).

---

## 12. Multiplicadores

| Item | Fator | Cap |
| --- | --- | --- |
| Pokébola | ×1 | 95 |
| Super (grande-bola) | ×1.5 | 95 |
| Ultra | ×2 | 95 |
| Frambo | ×1.5 sobre pós-bola | lim da bola |

Frambo **não garante** captura. Chance exibida = valor do RNG (`chances-painel` / `attempt-capture`).

---

## 13. Mensagens de captura (caça)

Antes: painel com 3 chances + qty Frambo + `!pk fruta`.  
Depois do prepare: painel “FRUTA FRAMBO UTILIZADA” com chances atualizadas + bônus ativo.  
Comandos: `!pk fruta` / `frambo` / `cacar fruta` (alias); prepare em `/v1/encounter/frambo` (estado separado da raide).  
Consumo idempotente; bônus limpo após a tentativa (sucesso ou falha).

---

## 14. Arquivos modificados (principais)

**Domínio:** `mission_catalog.clj`, `mission_rewards.clj`, `mission_suggest.clj` (novo), `capture.clj`, `hunt.clj`, `mundo.clj`, `profile.clj`, `events.clj` (novo), `ops.clj`  
**App/Infra:** `bridge_reads.clj`, `bridge_tx.clj`, `bridge.clj`, `store.clj`, `capture.clj` (app), `ports.clj`  
**Node/CLJS:** `pokemon-jvm-present.cjs`, `pokemon-jvm-client.cjs`, `pokemon_http.cljs`  
**CLJS apresentação (Doces / Frambo / clima / missões):** `src/zapbot/pokemon/ajuda.cljs`, `src/zapbot/pokemon/core.cljs` (professor), espelhos em `pokemon-service/`  
**Testes:** `fase128_missions_candy_capture_test.clj`, fixtures captura, a431/m33, present/missions/capture/hunt/battle-ui, `professor_test.cljs` (asserts Doces)  
**Smoke:** `odisseu.smoke.fase128`  
**Docs:** este readiness

---

## 15. Testes executados (auditoria final 2026-11-02)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-fase128` | **PASS** — 0 fail / 0 error (fase128 10, a431 9, a551 4, m33 16) |
| `clojure -M:test-m3-capture` | **PASS** — capture/reward/xp/ledger/collection/saves |
| `bag-kit-daily` + `bag-rewards` | **PASS** — 5+6 testes |
| `clojure -M:test-a6-economy-capacity` | **PASS** — economy/capacity/idle |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** — 15/15 |
| `clojure -M:test-raid-hotfix-fase1231` | **PASS** — 4/4 |
| Node `pokemon-jvm-{missions,present,capture,hunt,battle-ui}.test.cjs` | **PASS** (44+; capture 7/7 após assert Frambo) |
| Node `pokemon-jvm-raid-{hotfix-fase1231,fase12}.test.cjs` | **PASS** (14/14) |

---

## 16. Migrations

**Nenhuma.** Estados novos (`:frambo-preparada` na batalha, summary de missões, eventos) cabem em EDN/DTOs existentes.  
Se no futuro houver schedule persistido de eventos por chat, documentar migration descartável antes do GO.

---

## 17. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Rebalance de captura muda % vs legado | Fixtures 12.8 + testes de ordenação; smoke humano no piloto no GO |
| Eventos inativos até janela/admin | Intencional — não liga em prod sem GO |
| Claim de missões de evento ainda sem lote | Mensagem explícita; progresso/domínio preparados |
| Doces ≠ custo de evolução (ainda) | Documentado; rename apresentação only |
| Testes Node de UI acoplados ao copy | Atualizados; vigiar regressões de texto |

---

## 18. Plano de deploy e rollback (quando houver GO)

**Pré-GO (agora):** suítes acima verdes; **sem** commit cutover, **sem** imagens RC, **sem** digests/backups live (gates 4/6 PARCIAL).

Quando houver **GO definitivo** (aceitando PARCIAL de eventos ou após fechar claim/schedule):

1. Commit local só dos artefatos 12.8 (excluir `tmp-*` / previews não relacionados).  
2. Build RC JVM+Node mesma revisão; digests/labels/content-hash.  
3. Backups warm/cold piloto.  
4. Cutover **piloto only**; não liberar outros grupos.  
5. Opcional: `POKEMON_BAG_KIT_DAILY_ENABLED=true` se ainda off (sem recreate JVM).  
6. Smoke: missoes aliases/resumo, professor doces, clima PT, caça+fruta, captura % coerente, raids regressão, Gemini/Groq boot log.  
7. Rollback: imagens anteriores; estados EDN compatíveis; sem apagar ledger/eventos legítimos. Flag diária → `false` se necessário.

**NÃO executar deploy / migrate / flag live / RC sem GO explícito.**
