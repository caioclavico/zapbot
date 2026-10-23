# A5.4 — Auditoria de evoluções e golpes

## Resultado da auditoria

O Core já possui evolução explícita por dados resolvidos pelo chamador em
`pokemon.domain.evolution` e `pokemon.application.evolution`. A transação usa
fence do save, revisão CAS, recibo global e hash do payload; o consumo de uma
pedra ocorre na mesma transação. A regra de nível/amizade está disponível como
`elegivel-nivel?`, enquanto a resolução da cadeia e das condições especiais
continua fora do domínio, como no legado.

O ciclo de golpes está implementado em `pokemon.domain.moves` e no adapter
Datomic: aprender, substituir, oferecer, recusar oferta, remover e
reaprender. As operações persistem histórico/ofertas, usam revisão do save,
recibos e são idempotentes por `operationId`. MT é consumida na mesma
transação do aprendizado.

## Lacuna comprovada

O legado calcula evolução por troca consultando a cadeia da PokeAPI no cliente,
considerando parceiro e item equipado, e aplica a evolução depois da
transferência. `trade-store/complete!` no commit-base somente move os dois
registros; não há no Core um resolvedor canônico persistido de cadeia/espécie
que possa ser usado pelo JVM. Não foi adicionada uma tabela parcial ou uma
regra inventada. A evolução por troca permanece bloqueada até que a fonte
canônica e seus dados de stats sejam disponibilizados ao fluxo JVM.

## Cobertura adicionada

`pokemon.tests.evolution-moves-a54-test` verifica elegibilidade de
nível/amizade, evolução com item e retry após reabertura, ciclo de oferta e
aprendizado de golpe com retry e rejeição de remoção inválida. O smoke
`:test-a5-4-evolution-moves` executa essa suíte e a regressão M3.3.

Essa cobertura confirma as invariantes persistentes existentes; não classifica
evolução por troca como concluída.
