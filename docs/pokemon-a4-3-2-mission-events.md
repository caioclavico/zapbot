# A4.3.2 — eventos de progresso, sem recompensas de missões

Base: `58d3fe2`; referência funcional: master `9eb845b`.
Plano A4.3 e relatório A4.3.1 lidos integralmente antes das alterações.
Implementa a integração de progresso das ações canônicas existentes no Core.
**Presentes ainda não possuem produtor canônico no Core e permanecem pendentes.**

## Matriz de evidências da master

Os caminhos abaixo são de `pokemon-service/src/zapbot/pokemon/` na master,
consultados com `git show`/`git grep`, não a cópia local divergente do service.

| Missão | Origem / condição | Incremento | Limite | Arquivo / função original |
| --- | --- | --- | --- | --- |
| Diária selvagens | Selvagem derrotado; entrada na fase de captura, inclusive bolsa cheia | +1 | Meta diária escalada | `core.cljs`, `preparar-captura-pos-batalha`, chamada de `registrar-missao!` (~3755) |
| Diária capturas | Captura selvagem confirmada | +1 | Meta diária escalada | `core.cljs`, `capturar-selvagem` (~3795) |
| Diária vitorias | Vitória PvP normal, para o vencedor | +1 | Meta diária escalada | `core.cljs`, `finalizar-vitoria-pvp` (~1256) |
| Diária presentes | Cartão consumido e presente enviado | +1 para remetente | Meta diária escalada | `loja.cljs`, `enviar-presente!` |
| Semanal tipos | Captura selvagem confirmada | Tipos distintos do selvagem; dois tipos podem contar | Meta 5; conjunto não é truncado na master | `core.cljs`, `encerrar-cacada!` (~3726); `missoes.cljs`, `registrar-semanal` |
| Semanal pvp | Vitória PvP normal | +1 para vencedor | Contador limitado a 3 | `core.cljs`, `finalizar-vitoria-pvp` (~1254) |
| Semanal ginasios | Vitória final do desafiante no ginásio | Adiciona ID distinto do ginásio | Meta 2; conjunto não é truncado na master | `core.cljs`, `finalizar-ginasio` (~4617) |
| Semanal presentes | Presente enviado com consumo do cartão | +1 para remetente | Contador limitado a 10 | `loja.cljs`, `enviar-presente!`; `missoes.cljs`, `registrar-semanal` |

Não contam: iniciar caça, captura falha, derrota, fuga/desistência, raid,
evolução, aprendizado de golpe, uso genérico de item e adição de Pokémon à
coleção por outros motivos. Cancelamento/forfeit PvP não é vitória normal.
Nenhuma fórmula de captura, dano, XP, ocupação ou recompensa existente foi alterada.

## Integrações efetivas

O writer Datomic acrescenta o processamento antes do `d/transact`, somente
para os tipos de recibo explicitamente reconhecidos:

| Fluxo Core | Identidade estável do evento | Missões |
| --- | --- | --- |
| `battle-turn`: transição ativa → vitória selvagem | `wild-defeated` + battle-id persistido | selvagens |
| `encounter-capture`: captura confirmada | `wild-captured` + battle-id persistido | capturas + tipos |
| `pvp-turn`: transição ativa → vencedor x/o | `pvp-won` + battle-id persistido | vitorias + pvp |
| `battle-turn`: transição ativa → vitória final de ginásio | `gym-won` + battle-id persistido | ginasios |
| Captura direta da API JVM/harness | `direct-capture` + operation-id persistido | capturas + tipos |
| Resolução direta de ginásio da API JVM/harness | `direct-gym-resolve` + operation-id persistido | ginasios |

As duas últimas APIs já existiam: cada operação representa uma ação nova.
Não são novos endpoints de injeção de progresso. No fluxo WhatsApp, os encontros
e batalhas fornecem a identidade durável, independente do ID da mensagem/retry.
Vitória e captura do mesmo encontro são eventos diferentes. Repetir uma fase
com outro operation-id não gera outro crédito; estado canônico e ledger impedem isso.

As regras puras de `gift-sent` estão cobertas por teste, mas não existe um
produtor ativado: habilitar o esboço de presentes exigiria consumir cartão e
distribuir itens ao destinatário, fora desta fase. A consulta informa
`unavailableProgressEvents: ["gift-sent"]`. Não há progresso fictício ou evento
público para contornar esse limite. Integrar presentes permanece necessário
para declarar paridade de todas as oito missões.

## Atomicidade, identidade e recuperação

Migration 018 acrescenta o ledger `mission-event`:

- `key`: identidade da origem em EDN, com unicidade `db.unique/value`;
- `save`: referência ao save que recebeu progresso;
- `operation`: referência ao recibo da ação canônica, usando tempid na mesma TX;
- `data-edn`: origem, tipo, jogador/save, tipos/IDs relevantes, instante, fuso,
  dia, semana e operation-id efetivamente processados.

Ação original, recibo, evento e progresso entram **na mesma transação**. Falha
em qualquer validação ou gravação aborta todos os efeitos. Não existe janela
entre ação confirmada e incremento, nem callback em memória, outbox pendente
ou segunda gravação cujo sucesso precise ser presumido.

O CAS de revisão existente serializa eventos diferentes para o mesmo save.
O builder é refeito contra o DB atualizado em conflito. Se o vencedor PvP for
outro jogador, acrescenta-se CAS também à revisão desse save e fencing do
destinatário do progresso. Isso evita sobrescrever progresso concorrente por
captura ou preparação. Os cálculos econômicos da ação original continuam intactos.
Também se confere chat, dono/participante e save da batalha persistida contra
o writer; uma tentativa de atribuir batalha alheia a outro treinador é rejeitada
antes da transação, sem mudar a batalha ou qualquer progresso.

O nível da primeira missão diária respeita o perfil do treinador depois dos
componentes da própria ação (a master registra a vitória antes de calcular a
missão). Faixas já fixadas permanecem fixas até a renovação.

Recibo já confirmado é reutilizado após falha/retry, sem novo processamento.
O ledger impede um segundo processamento da mesma origem mesmo sob outra
operação. Identidade incompatível ou origem já processada sem recibo compatível
é rejeitada explicitamente. Há teste de duas chamadas simultâneas com a mesma
operação, verificando uma única captura, um único evento e um único incremento.

O relógio é congelado na entrada do writer e mantido durante os retries CAS;
não é a chave de deduplicação. Nenhum parâmetro HTTP novo permite retroagir o
relógio. Se outra operação já avançou o estado para dia/semana posterior, uma
ação ainda não confirmada com período antigo falha integralmente com
`mission_period_conflict` (HTTP 409). O cliente pode repetir a operação; o
instante de uma ação que ainda não foi confirmada será o da nova execução.
Um retry de ação já confirmada recupera o recibo original e nunca migra seu
progresso para o período novo.

Os conjuntos semanais preservam a regra original de valores distintos sem
truncamento arbitrário. Contadores diários/PvP/presentes saturam nas metas
originais. Não há escrita em `missoes-eventos-edn`: o Novo Recomeço permanece
histórico, sem reativação nem entregas automáticas nesta fase.

### Cenários de falha

| Cenário | Resultado verificado |
| --- | --- |
| Rejeição ou falha antes do commit | Nenhuma ação, evento ou progresso parcial confirmado |
| Retry de operação não confirmada | Ação e progresso confirmam juntos na nova tentativa |
| Falha depois do commit, antes da resposta | Recibo existente recuperado; progresso não é repetido |
| Encerramento da JVM logo após `d/transact` | Outra JVM reabre Datomic e recupera ação, recibo e progresso |
| Dois eventos simultâneos | CAS/rebuild preserva ambos, respeitando saturação da meta |
| Mesmo evento simultâneo | Uma confirmação e um recibo duplicado; não duplica ação ou missão |
| Save ativo alterado | Fencing rejeita escrita antiga; nenhuma atribuição ao outro save |
| Virada de dia/segunda-feira | Eventos confirmados preservam seu período; novos renovam sob demanda |

“Recuperação de evento pendente” não requer um worker nesta implementação:
não há evento pendente depois de uma ação confirmada. Os testes cobrem a
tentativa não confirmada e a recuperação do recibo após commit. Não se declara
suporte a recuperar eventos de um futuro produtor que faça gravações separadas.
Não há backfill automático de ações anteriores à A4.3.2.

## Consulta e superfície exposta

Reutiliza `GET /v1/missions` e `POST /v1/missions/prepare` da A4.3.1. O GET
continua sem escrita e retorna estado, objetivo, período e identidade do save.
`progressIntegrationEnabled: true` vem acompanhado de `progressEvents` com os
quatro produtores disponíveis e da pendência de presentes. `claimsEnabled`
continua `false`. Não há comando WhatsApp novo, endpoint de eventos arbitrários,
resgate, RNG de missão ou concessão de PE/moedas/itens por missões.

## Preservação e arquivos

Foi guardado baseline local antes de qualquer edição e conferida a preservação
dos bytes dos esboços de `ports.clj`, `missions.clj`, `missions_store.clj`,
inventário A4.3 e todos os hunks originais de `store.clj`.

O workspace precisa de uma ponte temporária que neutraliza a execução do
helper WIP antigo, pois o novo writer já processa o evento. Seu corpo original
permanece preservado. Essa ponte e os hooks WIP **não entram no commit**.
O conteúdo candidato foi montado sobre `58d3fe2` em diretório temporário e
testado independentemente, sem importar os esboços de recompensas.

Arquivos da A4.3.2:

- `domain/mission_progress.clj`: regras puras;
- `infrastructure/datomic/mission_events.clj`: seleção de origem e fragmentos transacionais;
- `infrastructure/datomic/store.clj`: integração no writer comum;
- `infrastructure/datomic/mission_queries.clj`: nível compartilhado e metadados de capacidades;
- `infrastructure/datomic/schema.clj` e `migrations/pokemon/018-mission-events-processed.edn`;
- `infrastructure/http/bridge.clj`: erros de conflito explícitos;
- `tests/missions_a432_test.clj` e `tests/missions_a432_regression.clj`;
- este relatório.

## Testes e regressões

Todas as bases Datomic usadas são temporárias. Os runners redirecionam também
o armazenamento das suítes antigas: nenhuma base de desenvolvimento é removida.
As rotas HTTP foram testadas em loopback com permissão de execução fora do
sandbox, que bloqueia abertura de sockets locais.

```sh
clojure -M -m pokemon.tests.missions-a432-test
clojure -M -m pokemon.tests.missions-a431-test
clojure -M -m pokemon.tests.missions-a432-regression
node --test test/pokemon-jvm-client.test.cjs test/pokemon-jvm-present.test.cjs test/pokemon-jvm-commands.test.cjs test/pokemon-jvm-battle-ui.test.cjs
```

Resultados no conteúdo isolado candidato:

- A4.3.2: 14 testes, 115 assertions, zero falhas/erros.
- Fundação A4.3.1: 9 testes, 77 assertions, zero falhas/erros, também executada
  em conjunto com a suíte de eventos durante a validação do conteúdo isolado.
- Regressões JVM ampliadas: 97 testes, 461 assertions, três falhas preexistentes,
  zero erros; mesmo resultado no commit-base `58d3fe2`.
- Regressões Node: 28 testes, 25 passam, três falhas preexistentes; mesmos testes
  falham com os arquivos originais do commit-base.
- `git diff --check`: sem problemas.

Falhas preexistentes reconfirmadas, separadas dos testes novos:

| Camada | Falha |
| --- | --- |
| JVM | `nocaute-recolhe-status-persistido`, assertions nas linhas 285 e 291 de gym_m48_test |
| JVM | `cura-status-sem-mudar-hp-emite-retract`, linha 302 |
| Node | `formatWorldInfo lista clima e áreas` (nome Praia Azul) |
| Node | `comandos simulados: treinador e coleção formatados` (texto coleção/time) |
| Node | `resolveCommand mapeia aliases do legado` (expectativa antiga de batalhar) |

Não foram encontradas novas falhas nas regressões comparadas. A regressão geral
continua com essas seis falhas; não foi declarada totalmente verde.

## Limitações e A4.3.3

Falta integrar o envio canônico de presentes (consumo/entrega atômicos nos dois
saves), antes de poder confirmar suas duas missões em gameplay real. Não houve
mudança improvisada na economia para viabilizar esse evento nesta fase.

A4.3.3 ainda deve implementar recibos e transações de resgate, RNG e valores
originais, PE/moedas/itens, pendências de mochila, reprocessamento da resposta e
concorrência entre progresso/resgate/presentes. A integração Adventure condicionada
à flag, identificada no plano, também permanece pendente; não se cria evento de
ovo ou entrega secundária em memória. Só depois cabe habilitar a apresentação
WhatsApp e anunciar resgate funcional.

Sem push, deploy, alteração da master, VMs, onboarding ou dados persistentes.
