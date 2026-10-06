# Controle das métricas de desempenho

`PERFORMANCE_METRICS=false` é o padrão do Odisseu e do Pokémon Service, tanto
quando a variável está ausente quanto nas imagens Docker e nos exemplos de
ambiente. Configure `PERFORMANCE_METRICS=true` para recuperar a instrumentação
detalhada. Ambos os serviços leem o valor ao iniciar; aceitam `true` sem distinção
de maiúsculas/minúsculas ou espaços externos. Outros valores desligam métricas.

## Instrumentação existente reaproveitada

| Ponto | Com `false` | Com `true` |
| --- | --- | --- |
| `zapbot.desempenho` nos dois serviços | Sem contextos, cronômetros, timer de aviso de desempenho, etapas, serialização ou logs `[Desempenho]`/`pokemon_command` | Tempos por comando, fila, predecessores, operações, imagens e Base64 |
| `pokemon-service/runtime/metrics.cjs` | Execução direta, sem AsyncLocalStorage de métricas ou wrappers de Promise | Contexto por requisição e tempos de Cassandra, PokéAPI, sprites e imagens |
| `pokemon-service/runtime/service.cjs` | Sem tempos e sem `command_completed`; envelope mantém `timings: {}` | Tempos detalhados na resposta e log de conclusão |
| `pokemon-service/src/zapbot/http.cljs` | Fetch direto, sem envolver métodos de leitura do corpo para medir | Mede cabeçalhos e leitura do corpo |
| `pokemon-service/runtime/images.cjs` | Exporta as funções de imagem sem wrappers de medição | Mede processamento, incluindo fila e cache |
| `zapbot.recursos` / coletor Node e Chromium | Não inicia coletor, histograma ou timer; sem leitura de `/proc`, CPU, RAM ou latência | Coleta se `ODISSEU_RESOURCE_METRICS_ENABLED=true`, no intervalo configurado |
| Shutdown Pokémon | Mantém etapas, pendências, avisos e erros, sem calcular durações | Inclui `elapsed_ms` e `duration_ms` |

As medições existentes de Cassandra e Sharp passam pelo mesmo módulo Node;
não há sistema paralelo. O mapa auxiliar de predecessores não recebe entradas
sem contexto de desempenho. As funções desligadas preservam valores, identidade
das Promises e exceções originais, sem adicionar continuations de medição.
Os thunks já presentes nas chamadas continuam existindo; não houve reescrita dos
comandos para removê-los.

## Segurança e compatibilidade

Continuam ativos os logs de erros/exceções, falhas de persistência, raids e
comunicação HTTP. Os logs de shutdown são diagnósticos essenciais: etapas e
contadores continuam disponíveis, assim como avisos aos 10/30/50 segundos e o
prazo de 60 segundos. A flag não descarta gravações nem força uma saída de sucesso.

Relógios necessários a prazos HTTP, cooldowns, TTL, cache e segurança continuam
funcionando: eles controlam operações, não medem desempenho. Contadores de
pendências e erros usados nos diagnósticos de shutdown também permanecem.
Benchmarks explícitos em `scripts/benchmark-*.cjs` continuam medindo, pois sua
finalidade é uma medição solicitada e eles não são iniciados pelo serviço.

Não mudamos agendadores de jogo, regras de combate, persistência, sessões ou
volumes. Replays de comandos já confirmados retornam a resposta persistida,
inclusive eventuais tempos antigos; não regravamos histórico para removê-los.
Não acrescentamos conteúdo de mensagens, imagens, tokens ou credenciais aos logs.

## Configuração e comparação

Defina a flag no arquivo de ambiente privado de **cada serviço**. Os Dockerfiles
fornecem `false` quando não há override. O deploy existente preserva o ambiente
efetivo do container: um valor explícito anterior continua sendo preservado.
Esta mudança não altera arquivos `.env` nas VMs nem habilita um deploy.

Para comparar, use períodos de carga semelhantes, com a mesma imagem, comandos
e estado de cache. Colete CPU/RAM por uma ferramenta externa quando a flag estiver
desligada. Com `true`, acompanhe os logs de desempenho e recursos; para os recursos
do Odisseu, a flag local também precisa estar ligada. O ganho de CPU/RAM depende
da carga e não foi medido em produção nesta alteração.

Para rollback da configuração, restaure `PERFORMANCE_METRICS=true` no próximo
reinício autorizado. Editar `.env` não muda um processo ou container já iniciado;
`docker restart` também não recarrega seu ambiente. Nenhum restart ou deploy foi
executado nesta implementação.

## Testes

As suítes são executadas com a flag ligada e desligada. Há testes de execução
direta sem acesso ao relógio, sem timers de métricas e sem logs; retorno síncrono,
Promise, exceções; isolamento dos contextos quando ligados; HTTP/idempotência,
filas, mídias, persistência incerta e shutdown. Os testes de configuração Node
usam processos separados para verificar leitura inicial e cache de módulos.

Os testes locais usam mocks e containers temporários sem rede ou volumes de
produção. Não verificam consumo ou conectividade com serviços reais.

## Resultados desta implementação

| Suíte | `false` | `true` |
| --- | --- | --- |
| Pokémon Node.js | 54 testes aprovados | 54 testes aprovados |
| Pokémon ClojureScript | 175 testes / 1.167 assertions | 175 testes / 1.170 assertions |
| Odisseu Node.js e contratos HTTP | 27 testes aprovados | 27 testes aprovados |
| Odisseu ClojureScript | 206 testes / 1.315 assertions | 206 testes / 1.319 assertions |

Zero falhas ou erros nas execuções finais. Builds `release app` e
`release domain` concluídos. Os warnings existentes de inferência e Promesa
permanecem (138 no build do Odisseu e dois no Pokémon).

## Arquivos alterados

- `.env.example`
- `Dockerfile`
- `docs/deploy-github-actions.md`
- `docs/medicoes-treinador.md`
- `docs/performance-metrics.md`
- `docs/recursos-chromium.md`
- `pokemon-service/.env.example`
- `pokemon-service/Dockerfile`
- `pokemon-service/runtime/images.cjs`
- `pokemon-service/runtime/metrics.cjs`
- `pokemon-service/runtime/service.cjs`
- `pokemon-service/runtime/shutdown.cjs`
- `pokemon-service/src/zapbot/config.cljs`
- `pokemon-service/src/zapbot/desempenho.cljs`
- `pokemon-service/src/zapbot/http.cljs`
- `pokemon-service/src/zapbot/pokemon/core.cljs`
- `pokemon-service/test/api.test.cjs`
- `pokemon-service/test/metrics.test.cjs`
- `pokemon-service/test/pokemon_service/command_http_test.cljs`
- `pokemon-service/test/shutdown.test.cjs`
- `pokemon-service/test/zapbot/desempenho_test.cljs`
- `pokemon-service/test/zapbot/pokemon/core_test.cljs`
- `src/zapbot/config.cljs`
- `src/zapbot/desempenho.cljs`
- `src/zapbot/pokemon/core.cljs`
- `src/zapbot/recursos.cljs`
- `test/zapbot/core_test.cljs`
- `test/zapbot/desempenho_test.cljs`
- `test/zapbot/pokemon/core_test.cljs`
- `test/zapbot/recursos_test.cljs`
