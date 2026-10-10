# PvP com Pokémon ativo

No grupo, `!pokemon` ou `!pk` sem argumentos abre um desafio com o Pokémon ativo
saudável. Um segundo treinador envia o mesmo comando para aceitar. O anúncio
identifica o Pokémon, seu nível e a faixa permitida. Nível 28 aceita 25 a 31,
inclusive; 24 ou 32 são recusados sem cancelar nem renovar o desafio. Use
`!pokemon escolher <número>` antes de abrir/aceitar para selecionar outro ativo.
Jogadores sem ativo disponível recebem orientação; Pokémon desmaiado não entra.

O prazo de espera é **5 minutos**. `!pokemon sair` cancela apenas o desafio de
quem o abriu. Ninguém pode aceitar o próprio desafio. Depois da aceitação, o
combate é **1 × 1**, com os atributos, HP atual, status, itens, tipos, habilidades,
golpes e regras de dano existentes. O prazo de turno continua em 30 minutos.
Caçadas e ginásios mantêm seus combates e escalações atuais.

## Parâmetros e estado

`pokemon-service/src/zapbot/pokemon/pvp.cljs` concentra `diferenca-maxima-niveis`
(3), `minutos-espera` (5), os limites inclusivos e os textos do desafio. Ajustar
essa constante altera os novos desafios, o anúncio e o guia. Um desafio que já
foi salvo conserva seu próprio prazo absoluto.

O estado continua no atom `jogos` e no módulo existente
`pokemon-batalhas-ativas`, sem nova tabela, schema ou migração. O desafio guarda
`:faixa-niveis` e `:desafio-expira-em`. O timer existente calcula o tempo restante
e entra na mesma fila do chat; seu token e identidade impedem que um callback
antigo cancele um desafio novo. O aviso de expiração segue a outbox HTTP atual.
Uma mensagem que chega após o prazo também encerra a espera antes de executar
o próximo comando, mesmo se o callback ainda estiver aguardando na fila.

Antes e depois da preparação assíncrona, o serviço verifica a identidade do
desafio, prazo, ativo e participação em outros chats. A validação e o registro
no atom são síncronos; duas aceitações não podem registrar dois adversários.
As filas existentes serializam as rodadas, e `PokemonService` reaproveita a
reserva/recibo por `requestId` para mensagens duplicadas, inclusive replay de
respostas completas. Um resultado incerto não é repetido automaticamente.

## Compatibilidade

O Odisseu continua apenas transportando comandos/respostas por HTTP. Não recebe
uma segunda implementação de regras. O código legado em `src/zapbot/pokemon`
não é o domínio usado pelo bot de produção; não executar os dois domínios sobre
os mesmos dados. Não há alteração de infraestrutura, deploy ou autenticação.

`liga`, `ligas`, `lig` e `time liga` retornam o novo guia, sem selecionar uma liga.
Aplicar uma escalação com destino `liga` ou sem destino também orienta sobre
PvP; `time usar <nome> ginasio` mantém a aplicação explícita de escalações PvE.
Salvar, consultar ou excluir escalações nomeadas permanece disponível.

As contas, índices, Pokémon, inventários, histórico, ligas e escalações salvas
não são apagados. Faixas históricas continuam disponíveis nos filtros de
coleção e nas raids já existentes. Isso não restringe a entrada em PvP.

Uma partida 3 × 3 já iniciada é restaurada sem alterar sua escalação, etiqueta
ou regras. Um desafio legado aberto usa o nível do Pokémon registrado e o
timestamp original para calcular a expiração; não ganha mais cinco minutos a
cada restart. A restauração agenda prazo zero se a espera já terminou.

XP, moedas, rank, missões e progresso usam os caminhos existentes. As categorias
de Pokébola por nocaute são mantidas: até nível 25, Pokébola; até 60, Grande;
acima de 60, Ultra. Nos novos desafios, a categoria vem do nível do desafiante,
sem liga selecionada; partidas legadas iniciadas mantêm sua categoria salva.
Desistência e espera expirada continuam sem prêmio de vitória.

## Validação local

`pokemon-service/test/zapbot/pokemon/pvp_test.cljs` cobre abertura/aceitação,
níveis abaixo/acima e nos limites, próprio desafiante, ausência de ativo,
desmaio, troca após recusa, entradas e aberturas concorrentes, exclusividade
entre chats, timer e prazo durante entrada assíncrona, restauração e comandos
legados. A deduplicação executa `PokemonService.execute` com o domínio
ClojureScript real e uma persistência de recibos fictícia. As regressões de
combate/PvE e transporte HTTP existentes continuam no conjunto de testes.

Os testes usam estado, timers e driver Cassandra fictícios. Nenhum comando é
enviado ao WhatsApp ou aos serviços de produção.
