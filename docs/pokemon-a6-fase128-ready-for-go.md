# A6 — READY FOR GO · Release 12.8.0

**Decisão prep:** **READY FOR GO** (produção **intacta** — sem cutover nesta etapa)  
**Escopo:** missões UX + recompensas diárias/semanais + Doces (rename) + clima PT + captura por raridade + Frambo caça + flag mochila preparada  
**Fora de escopo (→ 12.8.1):** ativação operacional de eventos temáticos / claim de Doces de evento  
**Commit:** _(preenchido após `git commit`)_  
**Content hash:** _(preenchido após build)_  
**RC dir:** _(local após build)_

---

## 1. Commit exclusivo 12.8.0

Inclui domínio/app/infra/Node/CLJS/testes/docs da fase.  
**Sem** migration · **sem** allowlist · **sem** push obrigatório · **sem** deploy.

Exclui: `tmp-*`, previews, docs de cutover 7–11 não relacionados, smoke raid 12.6.1 avulso.

---

## 2. Imagens RC (mesma revisão)

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase128-YYYYMMDDTHHMMSSZ-<hash12>-amd64` | _(build)_ |
| Node | `odisseu-node:a6-fase128-YYYYMMDDTHHMMSSZ-<hash12>-amd64` | _(build)_ |

Labels: `fase=12.8.0` · `io.zapbot.git-sha` · `io.zapbot.content-hash`  
**Deploy:** não executado.

### Produção atual (inalterada)

Hotfix turno/ginásio/nível/IA (`cd03767` / imagens turn-gym-level-ai) permanece em execução no piloto.

---

## 3. Flag mochila diária

| Item | Valor |
| --- | --- |
| Env | `POKEMON_BAG_KIT_DAILY_ENABLED` |
| Exemplo / prep | `false` (`.env.odisseu-core.example`) |
| Processo | Node (`config/pokemon-bag-kit-daily-enabled?`) — comandos `!pk mochila kit\|diario` |
| Persistência | Domínio `bag-rewards` + Datomic; overflow → pending; 1×/dia civil |
| Ativação live | **NÃO** nesta etapa — só no GO definitivo de cutover |

**Plano de ativação (cutover):** setar `true` no `.env` do Node piloto → recreate **somente** o serviço Node (JVM inalterada) → smoke `!pk mochila diario` 1× → repetir no mesmo dia deve negar.

---

## 4. Eventos — isolamento 12.8.0

| Controle | Evidência |
| --- | --- |
| Catálogo 7 eventos com `inicio`/`fim` nil | `status-at` → `inactive` |
| `POKEMON_THEMATIC_EVENTS_ENABLED` default false | `active-events` → `[]` |
| `montar-plano` | chama `resolver-tipos-encontro` com 3 args (sem bias) |
| Claim Doces de evento | não wired — 12.8.1 |
| Teste | `eventos-catalogo-tematico-sem-doce-generico` + empty `active-events` PASS |

Surto 6h legado (`evento-atual`) **preservado** — independente do catálogo temático.

Plano 12.8.1: `docs/pokemon-a6-fase1281-events-plan.md`.

---

## 5. Fórmula definitiva de captura (selvagem)

| Raridade | Chance base Pokébola |
| --- | --- |
| comum | 75–90 (centro ~82) |
| incomum | 55–75 (~65) |
| raro | 30–50 (~40) |
| épico | 15–30 (~22) |
| lendário | 5–12 (~8) |
| mítico | 3–8 (~5) |

Pós-batalha: `round(status × espécie) + min(10, seq)` cap 95 (**sem +25 legado**).  
Bolas: Poké ×1 / Super ×1,5 / Ultra ×2 · Frambo ×1,5 na **próxima** tentativa · caps 95.  
Texto = RNG (`chances-painel` / attempt). Raids: regressão 12.6 PASS (estado Frambo separado).

---

## 6. Tabela real de recompensas (diárias/semanais)

| Missão | Recompensas base |
| --- | --- |
| Diária Explorador | 2 Poké + 1 Frambo + 2 PE |
| Diária Colecionador | 2 Super + 1 Frambo + 3 PE |
| Diária Desafiante | 1 Ultra + 1 cartão-presente + 3 PE |
| Diária Amigo | 2 Poké + 1 Frambo + 2 PE |
| Semanal Ginásios | 3 Super + 2 Frambo + 60 moedas |
| Semanal Tipos | 2 Ultra + 1 cartão-presente + 50 moedas |
| Semanal PvP | 2 Ultra + 1 cartão-presente + 60 moedas |
| Semanal Presentes | 3 Super + 2 Frambo + 50 moedas |

Mecanismos existentes (allocate / pending / ledger). **Sem** inventário paralelo. Doces = rename de cartões de família (professor); não custo de evolução nesta release.

---

## 7. Testes executados (prep)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-fase128` | PASS |
| `clojure -M:test-m3-capture` | PASS |
| bag-kit-daily + bag-rewards | PASS |
| `clojure -M:test-a6-economy-capacity` | PASS |
| raid-capture-rewards-fase126 | PASS |
| raid-hotfix-fase1231 | PASS |
| Node missions/present/capture/hunt/battle-ui | PASS |
| Node raid hotfix + fase12 | PASS |

Integração Datomic: suites bag-kit / capture / economy usam storage descartável local (`.datomic-local/…`).

---

## 8. Compatibilidade / proteções

Preservados: `recovery-v1`, fences, `unknown-pending`, ledger, migration 031, cooldowns, battle leave ON, raids auto (`dailyLimit=12`, `specialReserve=2`), lobbies/solo, ginásios por tipo, Frambo raid, PNGs ovo/revelação, Gemini/Groq, allowlist piloto.  
**Sem** alteração de dados reais.

---

## 9. Cutover e rollback (após GO definitivo)

1. Backups warm/cold piloto.  
2. Tag imagens RC no compose piloto (JVM+Node mesma revisão).  
3. Opcional: `POKEMON_BAG_KIT_DAILY_ENABLED=true` no Node.  
4. Manter `POKEMON_THEMATIC_EVENTS_ENABLED=false`.  
5. Smoke: missoes aliases/resumo, professor Doces, clima PT, caça+fruta, captura %, raids, mochila diario.  
6. Rollback: imagens anteriores (`cd03767` / turn-gym-level-ai); flag diária → `false` se necessário.

---

## 10. Pendências 12.8.1

Ver `docs/pokemon-a6-fase1281-events-plan.md`.

---

**READY FOR GO 12.8.0.** Aguarda autorização explícita de cutover / flag live.  
**NÃO** deploy · **NÃO** `POKEMON_BAG_KIT_DAILY_ENABLED=true` em produção nesta etapa.
