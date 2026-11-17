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

## Evolução por troca

O legado calcula a evolução consultando a cadeia da PokeAPI, considerando
parceiro e item equipado, e aplica o resultado depois da transferência. O Core
agora usa o catálogo versionado `pokeapi-trade-evolutions-2026-10` em
`pokemon.domain.trade-evolution`. As condições (espécie, parceiro e item) e
stats-base são resolvidos antes da transação; `trade-store/complete!` aplica os
efeitos de ambos os Pokémon, consumo do item e transferência na mesma
transação Datomic. O recibo registra os alvos evoluídos e retries retornam o
resultado original.

O catálogo cobre as espécies de troca presentes no legado e os itens de troca
presentes no inventário Core. Formas que exigem itens fora desse catálogo não
evoluem no Core, pois não há como equipá-las pelo fluxo normal; nenhum item é
criado implicitamente.

## Cobertura adicionada

`pokemon.tests.evolution-moves-a54-test` verifica elegibilidade de
nível/amizade, evolução com item, troca Onix/Shelmet com retry, reabertura,
ciclo de oferta e aprendizado de golpe com retry e rejeição de remoção
inválida. O smoke `:test-a5-4-evolution-moves` executa essa suíte e a regressão
M3.3.

Ainda é necessário ampliar a cobertura HTTP/Node e testar cada espécie do
catálogo; a transação JVM e o caso de parceiro/item já estão cobertos no smoke
Datomic.
