# Diagnóstico do graceful shutdown do Pokémon

## Diagnóstico

O fluxo anterior fechava a entrada HTTP, interrompia timers, aguardava comandos
ativos com `Promise.allSettled`, chamava `domain.shutdown()`, drenava as filas
do domínio e da persistência e fechava o driver Cassandra. O deadline de 60 s
saía com código 1 sem registrar a etapa bloqueada; rejeições de `service.close()`
também não tinham um tratamento estruturado no handler do sinal.

O deadline anterior tinha `unref()`: uma Promise pendente sem outros handles
podia permitir saída natural de Node com código 0. O deadline agora permanece
referenciado até a conclusão, mantendo o limite de 60 s e evitando essa falsa
conclusão; os timers de aviso continuam sem manter o processo vivo sozinhos.

Os testes da ponte ClojureScript também identificaram um problema na espera do
driver: o `p/finally` da implementação ClojureScript do Promesa 12 retorna a
Promise original sem aguardar o trabalho assíncrono do callback. `encerrar!`
podia concluir antes de `client.shutdown()`. Somente esse finalizador de
shutdown passou a usar `Promise.finally` nativo, que aguarda o driver e propaga
suas rejeições; a ordem de drenagem das gravações permanece a mesma. Esse
problema não prova a origem do timeout informado, mas comprometia a confirmação
do encerramento e foi revelado pelos testes da instrumentação.

Isso explica a falta de observabilidade. O timeout informado não permite
atribuir o bloqueio ao driver Cassandra ou a uma jogada específica: faltavam
tempos intermediários. Nenhuma VM foi acessada nem alteração implantada nesta
revisão. O timeout não foi aumentado e a lógica de rollback não foi alterada.

## Eventos e etapas

Os eventos JSON são `shutdown_started`, `shutdown_stage_started`,
`shutdown_stage_completed`, `shutdown_stage_failed`, `shutdown_active_failed`,
`shutdown_waiting`, `shutdown_timeout`, `shutdown_failed` e `shutdown_completed`.
`elapsed_ms` usa relógio monotônico desde o início da parada. Etapas concluídas
ou com falha incluem `duration_ms`; avisos/timeout incluem a lista das etapas
ainda abertas e suas durações.

| Etapa | Espera observada |
|---|---|
| `service.close` | Fechamento completo do serviço |
| `active.allSettled` | `Promise.allSettled([...this.active.values()])` |
| `domain.shutdown` | Parada do domínio |
| `aguardar_operacoes_BANG_` | Filas de jogadas, inclusive trabalho automático |
| `aguardar_todas_BANG_` | Confirmações das gravações e falhas de durabilidade |
| `client.shutdown` | Fechamento do driver Cassandra |

Os avisos são agendados aos 10, 30 e 50 segundos da parada inteira, sem
reiniciar o prazo ao trocar de etapa. Aos 60 segundos, o processo registra
`shutdown_timeout` e sai com código 1. Timers dependem do event loop: seu atraso
real aparece em `elapsed_ms`. Não existe prazo maior nem sucesso forçado.

`pending` contém contagens, sem IDs de jogador/chat, conteúdo das mensagens,
CQL, payloads ou credenciais:

- `http_active`: comandos HTTP ativos.
- `http_chat_queues` e `state_queues`: filas HTTP e do adaptador de estado;
  contagens de filas, não de ações individuais.
- `game_operations`: ações em execução ou enfileiradas no domínio.
- `game_chat_queues`: filas de chats do domínio.
- `persistence_pending`: gravações lógicas aguardando confirmação, incluindo
  espera na fila e retries; não é quantidade de partições/CQL.
- `persistence_failed_modules`: módulos com gravação não confirmada.

Contadores do domínio/persistência são fornecidos pela fachada `shutdownPending`
e também pelas etapas internas, sem consultar Cassandra. Campos não observados
ou com `diagnostics_unavailable` não devem ser interpretados como zero. Durante
`active.allSettled`, os contadores ajudam a localizar comandos pendentes.
Etapas são aninhadas: não
somar suas durações como se fossem operações independentes.

Erros usam o redator já existente em `runtime/errors.cjs`. As rejeições dos
comandos ativos são registradas individualmente e contadas no resultado de
`allSettled`. Mantém-se seu comportamento: erro de comando não implica, por
si só, falha de shutdown se a persistência terminou com confirmação. Uma falha
de persistência ou fechamento do driver continua impedindo saída com sucesso.

## Integridade e limites

Não foram alterados retries, ordem das filas, reservas LWT, schema ou dados.
Os novos contadores são apenas memória local. O fechamento do Cassandra continua
no `finally` da drenagem da persistência; a rejeição da drenagem continua
propagada. Se o driver também falhar, ambas as etapas registram a falha, e o
processo continua saindo com código 1.

Novos comandos deixam de ser aceitos antes da drenagem. O patch não acrescenta
cancelamento de gravações, descarte de filas, logout, exclusão de mídia/dados,
kill de processos ou mudanças de Docker. A proteção de timeout e o rollback
existentes permanecem. A instrumentação só cria contexto de observação durante
o shutdown; chamadas normais às funções de persistência não geram esses logs.

Sinais repetidos não iniciam uma segunda parada. Timers de aviso são cancelados
ao concluir/falhar, e uma conclusão tardia depois do timeout não registra
`shutdown_completed` nem tenta sair com código 0. Falha do logger não mascara
o erro da operação. Uma parada forçada pelo Docker antes do deadline da aplicação
pode impedir o log final; esta revisão não altera os tempos do Docker.

## Revisão e validação

Consulte o diff de `runtime/main.cjs`, `runtime/service.cjs`,
`runtime/shutdown.cjs`, `src/pokemon_service/{entry,shutdown}.cljs`,
`src/zapbot/armazenamento.cljs`, os contadores em `src/zapbot/pokemon/core.cljs`
e os testes novos de shutdown. Não foi feito commit, push ou deploy.

Validação final: 42 testes Node e 158 testes ClojureScript, com 1.078 assertions,
zero falhas/erros; build `domain` concluído com os dois avisos preexistentes do
Promesa. As suítes rodaram em container local temporário sem rede, com 1 CPU e
512 MiB, usando Cassandra simulado e diretórios temporários, sem acesso às VMs
ou volumes de produção. Sintaxe JavaScript e `git diff --check` passaram.

Os testes automatizados cobrem encerramento normal, operações pendentes,
avisos 10/30/50 s, falha de persistência com limpeza do driver, timeout de 60 s,
rejeições de comandos ativos e falha do logger. Os testes ClojureScript usam
Cassandra simulado e verificam a propagação real da falha de persistência após
`encerrar!`, os logs da ponte JavaScript e a ordem de duas ações na mesma fila.

Para reproduzir localmente, em `pokemon-service`:

```sh
npm test
npm run test:domain
npm run build
```

Depois de uma implantação separadamente autorizada, o último
`shutdown_stage_started` sem conclusão e as etapas abertas em `shutdown_timeout`
identificarão a espera bloqueada. Sem esses eventos de execução, a causa
operacional permanece indeterminada; não aumentar o timeout como substituto
de diagnóstico.
