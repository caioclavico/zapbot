# M4.5 — Caça e encontros selvagens no Datomic

## Mapa do legado (`!pk cacar`)

| Etapa | Onde | Regra |
| --- | --- | --- |
| Área | `mundo/areas` + rotação diária (3 biomas) | Pedido do jogador validado contra `areas-do-dia`; default = bioma do horário |
| Clima | `mundo/climas` + hash da data | Determinístico por dia (`MISSOES_TIMEZONE`) |
| Tipos do encontro | 35% clima / 65% bioma | RNG por caçada |
| Evento/surto | `aventuras/surtos` a cada 6h, chance 50% | Se surto: espécie sorteada da lista do evento via PokeAPI |
| Spawn normal | PokeAPI `pokemon/{1..1025}` até 25 tentativas | Filtra tipo ∩ pool e `poder ≤ alvo+60`; fallback = mais fraco do bioma visto |
| Poder-alvo | `320 + 25*(min(nível,11)-1)` | Nível de treinador do save |
| Stats | `suavizar-stat` (média 75, fator 0.5) | Aplicado nos dados da PokeAPI |
| Espécie | `pokemon-species` | Flags lendário/mítico/paradox + taxa-captura |
| Raridade | `classificar-raridade` | Maior entre poder-original e taxa-captura (mitico/lendário por flag) |
| Shiny | `1/512` | Após espécie; troca sprite |
| Golpes | `com-golpes` / PokeAPI moves | Até 4 golpes |
| Cooldown | 30 min (`ultima-cacada`) | Por jogador |
| Escopo | `cacadas-selvagens` por **chat** | Um encontro por grupo |
| Bolsa cheia | `somente-batalha?` | Sem captura |
| Pós-vitória | `aguardando-captura?` → `!pk capturar` | Já no M4.4 |

## Separação Node × JVM

| Responsabilidade | Camada |
| --- | --- |
| Fetch PokeAPI (pokemon, species, moves) + suavizar-stat + montar golpes | **Node** |
| Mundo/clima/áreas, poder-alvo, seleção entre candidatos, raridade, shiny, surto, cooldown, exclusão por chat, fence, TX | **JVM** |
| Apresentação WhatsApp (+ sprite se URL baixável) | **Node** |

Valores do texto do comando: só `área`. Rolls de teste (`rollTipos`, `rollSurto`, `shinyRoll`) só com `allow-test-rng` na bridge (dev/smoke) — Nunca pelo caminho WhatsApp.

## Fluxo ponta a ponta

1. `GET /v1/hunt/plan` → área, clima, tipos, surto?, evento, poder-alvo, elegibilidade.
2. Node busca candidatos na PokeAPI (surto: 1 espécie; senão: até 25 ids).
3. `POST /v1/hunt/start` (save-id + fence + operationId + plan echo + candidates).
4. JVM valida, escolhe selvagem, shiny, inicia batalha M3.4, grava `ultima-cacada-at`, trava chat.
5. Jogador batalha → vitória → `!pk capturar` (M4.4).

## Divergências / bloqueios

1. **PvP `outro-combate?`**: ainda não no JVM — caça não consulta combates PvP de outros grupos.
2. **Imagem composta** da arena por bioma: não portada; envia sprite oficial se o download HTTP do Node funcionar.
3. **Missões/eventos de progresso** (`registrar-missao!` selvagens): não nesta milestone.
4. **Avistamento Pokédex** enriquecido (primeiros shiny globais): marca `seen` no save; métricas globais ficam para depois.

## Comandos habilitados (JVM)

| Comando | Status |
| --- | --- |
| `!pk cacar` / `cac` `[área]` | Integrado — plan + PokeAPI + start |
| `!pk atacar` / `atk` `[n]` | Integrado — turno selvagem M3.4 |
| `!pk capturar` / `cap` | M4.4 |

## Testes

```bash
./scripts/test-m4-hunt.sh
```

Cobre: unit JVM (mundo/raridade/shiny, caça→batalha→captura, idempotência+reinício, A↔B+stale fence, isolamento chat+cooldown), regressão M4.4, smoke HTTP, Node (aliases/apresentação/PokeAPI suavizar), contratos M4.1/M4.2.

## Validado neste marco

Fluxo completo: `plan` → candidatos PokeAPI → `start` → `atacar` (M3.4) → vitória `aguardando-captura` → `capturar` (M4.4). Combate não ficou incompleto — `!pk atacar` foi habilitado junto com a caça.
