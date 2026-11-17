# M7.6 — Platform completa em staging + auditoria Cassandra

**Status:** as **8 chaves** do inventário M7.0 têm implementação Datomic em staging.  
**Base:** `8603ef8`  
**Produção:** Cassandra. **Sem dual-write. Sem cutover.**  
**Ginásio WIP:** permanece fora deste marco.

## 1. Destino de `bugs` e `participantes`

| Chave | Destino | Evidência |
|---|---|---|
| `bugs` | **Ativa → migrada** | `!pk bug` / `!pk bugs` / resolver; shape no fixture M7.0 |
| `participantes` | **Ativa → migrada** | `historico/registrar!` a cada msg; `!sorteio` consome |

Não são código morto. Mensagens do histórico (`!resuma`) continuam **só em RAM** (proposital).

## 2. Inventário Cassandra (cobertura real)

### Classificação

| Ocorrência | Classe |
|---|---|
| 8 chaves platform Node (`bloqueio`…`participantes`) + adaptador Datomic | **2 — Datomic disponível** (staging) |
| `zapbot.armazenamento` CQL + boot | **1 — legado produção** + boot staging ainda *tenta* C* se houver `registrar!` |
| Pokémon Node: `loja`, `treinador`, `ginasios`, `raids`, `pokedex-cache`, batalhas/caçadas, entregas HTTP | **3 — não migrado neste marco** (JVM Pokémon é trilha paralela M5/M6) |
| `pokemon-service/` + scripts `*-cassandra*.sh` | **1 — legado / testes isolados** |
| Docker Compose Cassandra | **1 — infra produção/dev legado** |
| Rank PvP core fire-and-forget | **5 — documentado**; log de erro (M7.5), await completo = trabalho futuro |

### Oito chaves M7.0 → Datomic staging

| Chave | Marco | Schema |
|---|---|---|
| bloqueio | M7.1 | 001 |
| admins-conhecidos | M7.1 | 001 |
| enquetes | M7.2 | 002 |
| quiz-historico | M7.3 | 003 |
| rank | M7.4 | 004 |
| lembretes | M7.5 | 005 |
| bugs | M7.6 | 006 |
| participantes | M7.6 | 006 |

## 3. Schema / endpoints / adaptadores (M7.6)

- `migrations/platform/006-bugs-participantes.edn`
- Portas: `BugStore`, `ParticipantStore`
- HTTP:
  - `GET/POST /v1/platform/bugs`, `/bugs/create`, `/bugs/resolve`
  - `GET/POST /v1/platform/participants`, `/participants/upsert`
- Node: `zapbot.bugs`, `zapbot.historico` com `PLATFORM_ADAPTER=datomic` (fail-closed)

## 4. Consistência (auditoria pontual)

| Tema | Status |
|---|---|
| Rank jogos (velha/naval/quiz/adedonha) | promises aguardadas |
| Rank PvP core | não bloqueia vitória; **loga** falha (não silencioso total) |
| Lembretes claim/lease/fence | M7.5 intacto |
| Caches bloqueio/admins/enquetes | hidratação boot + update pós-commit |
| Isolation poke vs platform DB | smoke M7.6 confirma stores distintos |
| Sem fallback Cassandra em staging platform | `when-not (platform/enabled?)` nos 8 módulos |

## 5. Testes

```bash
./scripts/test-m7-6-platform.sh
npx shadow-cljs compile app
```

M7.6 cobre CRUD bugs, resolve idempotente, upsert participantes, isolamento chat, reopen, import fixture.

## 6. Bloqueios que ainda exigem Cassandra

Para **Odisseu Node sem Cassandra algum**:

1. **Pokémon legado** ainda registra/grava várias chaves via `armazenamento` (exceto quando `POKEMON_ADAPTER=jvm` cobre o fluxo HTTP — e mesmo assim há caminhos Node residuais: entregas, caches, etc.).
2. **`armazenamento/iniciar!`** sempre instancia client Cassandra; se não houver módulos registrados e C* cair, o bot sobe “sem persistência” — OK para platform-only staging, **não** OK se Pokémon legado estiver ativo.
3. Containers/`docker-compose` Cassandra e scripts de integração `pokemon-service`.
4. Cutover de **dados reais** (export dump → IR → import) nunca feito em produção.
5. WhatsApp autenticado + poller de lembretes ainda não validados em staging real.

## 7. Preparar migração de dados reais

1. Dump sintético já prova equivalência das 8 chaves.
2. Dump real: exportar `estado_particionado` só dos módulos platform (nunca misturar Pokémon).
3. Import offline em Datomic Local staging → `equivalence-report`.
4. Feature flag por domínio já existe (`PLATFORM_ADAPTER`); cutover = flip + monitoring.
5. Manter Cassandra read-only por janela de rollback.

## 8. Próximo menor marco (staging sem Cassandra)

**M7.7 sugerido:** “boot platform-only sem Cassandra”

- Skip/`no-op` de `armazenamento/iniciar!` quando `PLATFORM_ADAPTER=datomic` **e** nenhum módulo platform registra C*.
- Checklist: `POKEMON_ADAPTER=jvm` + bridge única; documentar chaves Pokémon ainda em C* ou já na JVM.
- Smoke: processo Node sobe com C* parado; só falha se Pokémon legado exigir persistência.

**Não declarar Cassandra aposentado** — platform staging está completa; o processo Odisseu completo ainda não.
