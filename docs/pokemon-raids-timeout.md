# Diagnóstico e proteção das raids automáticas

## Evidências e limites do diagnóstico

O incidente informado em 05/10/2026 teve uma raid acima de 30 segundos às
21:11:40 UTC e shutdown incompleto às 21:53:37 UTC. A relação causal não está
comprovada: não reproduzimos o incidente nem consultamos a VM nesta alteração.

O código já aplicava `AbortSignal.timeout(8000)` no wrapper HTTP. Portanto,
`buscar-golpe` não tinha prazo próprio, mas não era uma consulta sem qualquer
timeout. O cancelamento dependia do Fetch, inclusive durante a leitura do corpo.
As consultas disparadas por `p/all` não tinham limite global ou cache. Elas
ocorrem dentro da fila do chat, aguardada pelo shutdown.

Também havia um risco independente: o `p/all` das raids de vários chats rejeitava
na primeira falha e liberava o marcador do scheduler enquanto outras filas
ainda estavam trabalhando. Raid e agenda são persistidas em módulos separados;
uma gravação parcial não constitui uma transação entre os dois módulos.

## Alterações

- Cada consulta de golpe tem prazo total de 15 segundos, incluindo espera na
  fila, cabeçalhos e corpo. O cancelamento é propagado ao HTTP; não há retries.
- Até quatro consultas ficam em execução, com limite de 128 consultas pendentes.
  Chamadas do mesmo golpe compartilham a Promise. O cache guarda apenas resultados
  válidos, por seis horas, com no máximo 256 entradas e descarte LRU.
- Timeout, erro HTTP e fila cheia usam o caminho de falha existente: conservar
  golpes válidos ou usar Investida onde o código já permite. Aprendizado não
  transforma uma consulta falha em um golpe fictício.
- O scheduler aguarda todos os chats antes de liberar seu marcador, inclusive
  quando um deles falha. A falha continua sendo registrada.
- Antes de criar uma raid, aguardamos a persistência anterior. Falha ou resultado
  incerto impede nova criação e anúncio. A barreira posterior permanece.
  Quando a raid persistida pertence a um ginásio diferente daquele da agenda,
  respeitamos seu cooldown persistido para cobrir uma agenda desatualizada após
  reinício, sem reparar ou regravar dados.
- Os snapshots existentes de shutdown incluem o scheduler e contadores de
  consultas: pendentes, enfileiradas, em execução, abortando, cache, hits,
  timeouts e falhas. Não incluem nomes de chats ou dados dos jogadores.

## Shutdown e riscos remanescentes

As filas de jogo recebem um resultado limitado no tempo para as consultas de
golpes. A ordem das filas e a espera das gravações são preservadas. Os avisos aos
10, 30 e 50 segundos, o prazo de 60 segundos e a saída com erro em encerramento
incompleto permanecem; nenhuma gravação é descartada para forçar sucesso.

Se um loader ignorar o abort, sua Promise interna pode continuar pendente. Seu
slot permanece ocupado, sem duplicar a consulta nem ultrapassar quatro loaders;
os chamadores ainda expiram em 15 segundos. Esses casos aparecem em `aborting`.
Bloqueio do event loop também pode atrasar timers. Cassandra, composição de
imagens e outros HTTP continuam tendo seus próprios limites: não garantimos que
todo shutdown termine em 60 segundos. Falhas de persistência exigem diagnóstico,
e a proteção entre módulos não substitui uma transação atômica.

O cache pode conservar metadados por seis horas. Sob sobrecarga, mais consultas
podem cair no fallback existente. Regras de combate, recompensas e probabilidades
não foram alteradas.

## Validação local

- Build `release domain`: sucesso; dois warnings preexistentes.
- Compilação `test`: sucesso; 16 warnings preexistentes.
- Node.js: 49 testes aprovados, executados serialmente.
- ClojureScript: 166 testes, 1.113 assertions, zero falhas ou erros.
- Novos cenários: cache e concorrência, prazo incluindo fila/corpo, abort
  ignorado, resposta tardia, erros e falhas parciais, fallback, persistência
  incerta, agenda parcialmente persistida, scheduler e shutdown durante raid.
- A suíte inclui os testes existentes de caçadas, ginásios e aprendizado.

Os testes usam mocks e container temporário sem rede ou volumes de produção.
Um teste existente de imagens falhou por timing na execução paralela; a suíte
serial passou sem alterações nesse teste. Não houve deploy, push, reinício de
serviços ou acesso ao Cassandra de produção.
