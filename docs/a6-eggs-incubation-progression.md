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

### Aquisição (sorteio canônico por `event-id`) — PI table v2

| Evento | Ovo | PI |
|--------|-----|----|
| Primeira caçada diária (captura) | 20% comum | — (PI da captura abaixo) |
| Captura selvagem | — | 15 |
| Vitória selvagem (sem captura no mesmo encontro) | 5% comum | 15 |
| Derrota selvagem | — | 5 |
| Vitória ginásio | 12% raro | 25 |
| Derrota ginásio | — | 5 |
| Raid (participação concluída) | por tier | 20 |
| Raid (vitória) | por tier | +10 (total 30) |
| PvP vitória (1×/oponente/dia, fuso do jogo) | 8% raro | 20 |
| PvP derrota (1×/oponente/dia) | — | 5 |
| Missão diária resgatada | explícito no pacote se houver | 30 |
| Boas-vindas (1×/conta) | 1 comum | 0 |

Inventário cheio (máx. 9): PI aplica; ovo vai para `:pending-egg-grants` no shell (não perde).  
Vitória + captura no mesmo encontro: só PI de captura (15), sem somar PI de vitória.

### Incubadoras consumíveis

| Fonte | Recompensa |
|-------|------------|
| 1ª vitória de ginásio da semana (ISO, fuso do jogo) | 1 extra |
| A cada 7 missões diárias resgatadas | 1 extra |
| Raid épica | 10% extra |
| Raid lendária | 15% especial |
| Raid mítica | 25% especial |
| Loja | extra 200 / especial 400 moedas |

Limite: pending se inventário de incubadoras cheio (`:pending-incubator-grants`).

### PI

- Teto diário configurável: `ADVENTURE_PI_DAILY_CAP` (default **150**).
- Dia civil no fuso `MISSOES_TIMEZONE` / `America/Sao_Paulo` (não UTC da VM).
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
| `!pk ovos` | Só ovos `inventory` (números 1..N, sem barras PI) |
| `!pk incubadoras` / `!pk incubadora` | Progresso PI, usos, pronto |
| `!pk incubar <n> [id]` | Coloca ovo (índice = card de inventário) |
| `!pk chocar` / `ovo nascer` | Um ou todos os prontos |
| `!pk incubacao` / `!pk pi` | PI do dia, teto, atividades |

Raid egg PNGs permanecem só como lembrete visual de chefe.

## Schema

Sem migration nova nesta etapa: categorias/shell/pending cabem em `008` + `state-edn`.  
036/037 intocados.

## Feature

Enrich `adventure-activity/attach` roda em `transact-with-writer!` após missões/PE.  
Desligar: `ADVENTURE_EGG_ACTIVITY=0`.
