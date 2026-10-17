# M4.1 — Integração vertical Node ↔ JVM/Datomic

Comprova o caminho local **Odisseu Node → bridge loopback → módulo Pokémon JVM → Datomic Local**, sem substituir o serviço Pokémon legado nem tocar deploy/produção.

## Arquivos

| Papel | Path |
| --- | --- |
| Leituras de domínio | `modules/pokemon/application/pokemon/application/bridge_reads.clj` |
| HTTP loopback | `modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj` |
| JSON mínimo | `modules/pokemon/infrastructure/pokemon/infrastructure/http/json.clj` |
| Processo longo (dev) | `apps/odisseu/src/odisseu/bridge/http_server.clj` |
| Smoke JVM | `apps/odisseu/src/odisseu/smoke/m4_bridge.clj` |
| Cliente Node | `scripts/lib/pokemon-jvm-client.cjs` |
| Contrato/e2e Node | `scripts/test-pokemon-jvm-contract.cjs` |
| Unit Node | `test/pokemon-jvm-client.test.cjs` |
| Runner | `scripts/test-m4-bridge.sh` |
| Config | `src/zapbot/config.cljs`, `.env.example` |
| Roteamento WhatsApp | `src/zapbot/pokemon_http.cljs` (branch `POKEMON_ADAPTER`) |
| Aliases | `deps.edn` (`:test-m4-bridge`, `:bridge-http`) |

## Contratos HTTP (somente GET, `127.0.0.1`)

Auth: `Authorization: Bearer <token>` em todas as rotas de leitura.
Envelope sucesso: `{ "ok": true, "data": … }`  
Envelope erro: `{ "ok": false, "error": { "code", "message", "details?" } }`

| Método | Path | Query | Notas |
| --- | --- | --- | --- |
| GET | `/health` | — | `{ status, role, readsOnly }` (sem auth obrigatória) |
| GET | `/v1/trainer` | `chatId`, `playerId` | Conta + save ativo / fence |
| GET | `/v1/collection` | `chatId`, `playerId`, `saveId?` | Coleção do save (ativo se omitido) |
| GET | `/v1/active-save` | `chatId`, `playerId` | Save + party + inventário |

Rejeições explícitas:

- `POST`/`PUT`/`DELETE` → `405 method_not_allowed`
- `/admin*`, rotas de seed/migrate → `404` (seed só in-process via env)
- Host ≠ loopback no bind → falha ao subir
- Cliente Node recusa `POKEMON_JVM_URL` fora de loopback

Códigos de negócio (exemplos): `player_not_found` (404), `no_active_save` (404), `save_not_found` (404), `invalid_query` (400), `unauthorized` (401).

Timeouts Node (defaults): connect 3 s, total 8 s — sem retry.

## Configuração (somente development)

```bash
APP_ENV=development
POKEMON_ADAPTER=jvm          # default: legacy; em produção jvm é ignorado
POKEMON_JVM_URL=http://127.0.0.1:18090
POKEMON_JVM_TOKEN=dev-bridge-token
POKEMON_JVM_CONNECT_TIMEOUT_MS=3000
POKEMON_JVM_TIMEOUT_MS=8000
```

Com `POKEMON_ADAPTER=jvm` + `APP_ENV=development`:

- Leituras WhatsApp mapeadas (`treinador`/`perfil`/`tre`, `meus`/`pokemons`/`colecao`, `save`/`ativo`/`saves`) → bridge JVM.
- Qualquer outro subcomando → recusa textual; **não** chama o serviço legado nem Cassandra.
- Sem fallback automático de escrita.

Com `POKEMON_ADAPTER=legacy` (default): comportamento atual via `pokemon-http` → `pokemon-service`.

## Comandos de teste local

```bash
# Tudo (smoke JVM + unit Node + e2e Node↔JVM)
chmod +x scripts/test-m4-bridge.sh
./scripts/test-m4-bridge.sh

# Isolados
clojure -M:test-m4-bridge
node --test test/pokemon-jvm-client.test.cjs
node scripts/test-pokemon-jvm-contract.cjs

# Bridge longa (outro terminal) + seed fictício
ODISSEU_BRIDGE_SEED=true ODISSEU_BRIDGE_TOKEN=dev-bridge-token clojure -M:bridge-http
```

## O que ainda depende do legado

Tudo que não está nas três leituras acima continua no **pokemon-service + Cassandra** (e, no bot, em `zapbot.pokemon-http` / rank / bloqueio / bugs):

| Área | Exemplos de comando |
| --- | --- |
| Captura / caça / inicial | `!pk cacar`, `!pk inicial`, bolas |
| Batalha selvagem / PvP | `!pk batalhar`, turnos, fuga |
| Ginásio / raid | `!pk gin*`, `!pk raid*` |
| Adventure / ovos / Joy | `!pk adventure`, ovos, Joy |
| Economia / loja / missões | `!pk loja`, missões, compras |
| Evolução / golpes / itens | `!pk evoluir`, aprender golpe, usar item |
| Trocas / doações / professor | `doar`, `negociar`, professor |
| Mídia / eventos assíncronos | `/media`, `/events/pending` |
| Bugs / admin de jogo | `!pk bug`, restauração de save |
| Persistência WhatsApp auxiliar | entregas HTTP, rank effects |

O núcleo JVM (M2–M3.7) já cobre domínio desses fluxos **dentro da JVM**, mas **não** há bridge de escrita nem cutover WhatsApp para eles nesta etapa.

## Divergências de integração (esperadas)

1. Envelope WhatsApp legado é `{ requestId, messages[], effects[] }` com renderizadores ricos; a bridge JVM devolve DTO JSON e o adaptador Node monta texto simples.
2. Identidade de jogador no WhatsApp (`author`/`from`) ainda não foi reconciliada com dados migrados — e2e usa IDs fictícios.
3. Sem paridade de filtros/paginação de `!pk time` / imagens.
4. Eventos assíncronos e mídia permanecem só no serviço legado.
5. `POKEMON_ADAPTER=jvm` fora de `development` é forçado para `legacy` no config.

## Segurança / limites desta entrega

- Sem acesso a produção, sem migração de dados reais, sem mudança de deploy/flags de prod.
- Sem operações de escrita na bridge HTTP.
- Serviço Pokémon legado e Cassandra intactos.
- Sem commit/push nesta etapa (a critério do operador).

## Resultados locais (esta máquina)

Smoke JVM (`clojure -M:test-m4-bridge`):

| Métrica | Valor |
| --- | --- |
| Latência health / trainer / collection / active-save | ~26 / 2 / 4 / 4 ms |
| RSS JVM (após leituras) | ~279 MB |
| Heap usado / max | ~64 / 256 MB |

Contrato Node↔JVM (`scripts/test-pokemon-jvm-contract.cjs`):

| Métrica | Valor |
| --- | --- |
| Latência trainer / collection / active-save | ~3 / 6 / 6 ms |
| RSS Node (script) | ~40 MB |
| RSS JVM (bridge + Datomic) | ~267 MB |

Chromium **não** foi ligado nesta medição (sem sessão WhatsApp).

## Medição Chromium (opcional, separada)

Este marco mede **Node + JVM** no contrato e **só JVM** no smoke. Para incluir Chromium:

1. Subir bridge (`clojure -M:bridge-http`) e o bot (`npm run build && node target/main.js`) com `APP_ENV=development` + `POKEMON_ADAPTER=jvm`.
2. Em outro shell: `ps -o rss= -p <pid-node> <pid-jvm> <pid-chrome>` (Chrome/Chromium filho do Puppeteer).
3. Não misturar Cassandra neste perfil se o objetivo for isolar pressão da bridge.
