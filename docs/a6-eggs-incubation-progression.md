# Odisseu A6 — Ovos e incubação (progressão)

## Auditoria (estado na tip `a6/live-baseline-8ef41c8` / `120f841`)

### O que já existe (JVM / Datomic)

| Peça | Onde | Notas |
|------|------|--------|
| Entidades `:egg/*` | migration `008-adventure-joy.edn` | Ovos persistidos por conta Adventure (`chat\|player`), não no blob do save |
| Shell incubadoras + recibos PI | `:adventure-account/state-edn` | Incubadora `permanente` no ensure |
| Domínio puro | `pokemon.domain.eggs` | Categorias comum/raro/épico (100/250/500 PI) + tempo; incubar/hatch/CAS |
| HTTP | `/v1/adventure`, incubate, hatch; grant só harness | WhatsApp: `!pk ovos`, `incubar`, `ovo nascer` |
| Joy | leitos separados | Sem relação com PI |
| Ovos de raid (arte) | `raid-egg-*min.png` | **Só mídia de lembrete** — não são entidades de ovo |

### O que faltava

- Concessão de ovos por caça/ginásio/raid/PvP/missão na JVM
- Aplicação de PI nas TXs de conclusão de atividade
- Categoria **especial** (800 PI)
- Catálogo versionado de nascimentos por categoria
- Teto diário de PI e anti-farm PvP
- Incubadoras extras com API de progressão
- Coleção cheia → nascimento pendente (JVM falhava com `:collection_full`)
- Nascimento com shiny/IVs alinhados às regras de caça
- UI de progresso PI / `!pk incubacao` / `!pk chocar` em lote
- Ocultar espécie antes do nascimento na apresentação WhatsApp

### Legado (`pokemon-service`)

Fluxo completo Adventure Sync (pontos, RNG, catálogo km 2/5/10, incubadoras extra/especial, worker 60s). **Não** é o caminho WhatsApp canônico deste tree (Node → JVM → Datomic). A progressão A6 espelha a intenção do legado com balanceamento novo, sem reabrir Cassandra.

## Proposta final (esta branch)

### Categorias (metas imutáveis por ovo)

| Categoria | PI | Tempo (legado dual-path) |
|-----------|----|---------------------------|
| comum | 100 | 2 h |
| raro | 250 | 6 h |
| épico | 500 | 12 h |
| especial | 800 | 24 h |

`points-goal` / `time-goal-ms` ficam **gravados no ovo** na concessão. Mudar a tabela global não altera ovos históricos.

Espécie sorteada **na concessão** (catálogo `egg-hatch-catalog-v1`); estável em retries. Apresentação WhatsApp **não revela** a espécie até o nascimento.

### Aquisição (sorteio canônico por `event-id`)

| Evento | Ovo | PI |
|--------|-----|----|
| Primeira caçada diária (captura) | 20% comum | — (PI da caça abaixo) |
| Vitória selvagem (sem captura no mesmo encontro) | 5% comum | 8 |
| Caça concluída (captura) | — | 5 |
| Vitória ginásio | 12% raro | 15 |
| Derrota ginásio | — | 6 |
| Raid rara | 15% raro | 20 |
| Raid épica | 25% épico | 30 |
| Raid lendária/mítica | 35% especial | 45 |
| Derrota raid | — | 10 |
| PvP vitória (1×/oponente/dia UTC) | 8% raro | 12 |
| PvP derrota (1×/oponente/dia) | — | 5 |
| Missão diária resgatada | explícito no pacote se houver | 20 |
| Boas-vindas (1×/conta) | 1 comum | 0 |

Inventário cheio (máx. 9): PI aplica; ovo vai para `:pending-egg-grants` no shell (não perde).  
Vitória + captura no mesmo encontro: só PI de caça (5), sem somar PI de vitória.

### PI

- Teto diário configurável: `ADVENTURE_PI_DAILY_CAP` (default **150**).
- Distinto de PE do treinador (036) e XP do Pokémon (037).
- Distribuição: PI do evento é **dividido** entre ovos incubando (sem multiplicar).
- Idempotência: `event-id` em `:receipts` do shell + enrich na mesma TX da atividade.

### Incubadoras

- 1 permanente ilimitada.
- Até 2 temporárias (`extra` / `especial`, 3 usos; especial +25% no ganho do ovo daquela incubadora).
- Concessão via `egg-activity/grant-incubator` (progressão/harness), identidade canônica.

### Nascimento

1. Valida ovo pronto / save carimbado  
2. Fixa resultado uma vez (espécie + shiny 1/512 + IVs 0–31 seeded)  
3. Se coleção cheia → permanece `:delivery-pending` **sem** consumir ovo  
4. Senão cria Pokémon, consome ovo, libera incubadora, recibo `egg-hatch`

### Comandos WhatsApp

| Comando | Função |
|---------|--------|
| `!pk ovos` | Inventário + barras PI/tempo (sem espécie) |
| `!pk incubadoras` | Status das incubadoras |
| `!pk incubar <n> [id]` | Coloca ovo |
| `!pk chocar` / `ovo nascer` | Um ou todos os prontos |
| `!pk incubacao` | PI do dia, teto, atividades |

Raid egg PNGs permanecem só como lembrete visual de chefe.

## Schema

Sem migration nova nesta etapa: categorias/shell/pending cabem em `008` + `state-edn`.  
036/037 intocados.

## Feature

Enrich `adventure-activity/attach` roda em `transact-with-writer!` após missões/PE.  
Desligar: `ADVENTURE_EGG_ACTIVITY=0`.
