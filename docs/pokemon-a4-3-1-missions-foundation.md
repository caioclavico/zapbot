# A4.3.1 — fundação de missões

Implementa a parte de catálogo/leitura das subetapas 1, 2 e 4 do plano A4.3.
Não integra novos eventos de jogo, comandos WhatsApp ou concessão de recompensas.

## Modelo e comportamento

O catálogo JVM `pokemon.domain.mission-catalog` preserva as quatro diárias,
quatro semanais, multiplicadores, valores e metadados das chances de bônus.
Novo Recomeço mantém ID, objetivos, recompensas e janela original, com fim
exclusivo em 2026-10-04 às 00:00 de São Paulo. Consultá-lo depois dessa data
retorna `status: ended` e `available: false`, mesmo após reinicializações.
Não existe renovação anual ou ativação automática de um novo evento.

Reutiliza a migration local 016 sem modificar seu conteúdo. Estado canônico:

| Atributo no save | Conteúdo EDN com chaves originais |
| --- | --- |
| `:save/missoes-diarias-edn` | dia, nível fixado, progresso por ID, IDs resgatados |
| `:save/missoes-semanais-edn` | semana, ginásios/tipos distintos, contadores PvP/presentes, resgatadas |
| `:save/missoes-eventos-edn` (017) | ID do evento → progresso e objetivos premiados |

As identidades usam as chaves de treinador/save já existentes. Leituras obtêm
treinador, ponteiro ativo e estado do save de um único snapshot Datomic.
Não há cache canônico no Node. O schema mantém o histórico Datomic padrão;
não há comando novo de histórico nem importação automática do legado.

Estado de cada missão: `available` com progresso zero; `in_progress` com
progresso parcial; `completed` ao atingir a meta; `rewarded` quando o marcador
original de resgate/entrega está presente. Disponibilidade temporal do evento
é separada do estado histórico dos seus objetivos. Os objetivos encerrados
podem preservar progresso e marcadores sem permitir novas ações.

Consulta sem preparação retorna uma projeção do período corrente com
`initialized: false`: não fixa silenciosamente a faixa. `prepare` fixa o nível
no primeiro acesso explícito e renova os períodos vencidos. Considera PE do
treinador e XP persistido, sem confundir nível de Pokémon. O fuso usa
`MISSOES_TIMEZONE`, padrão `America/Sao_Paulo`; relógio e fuso são injetáveis
internamente nos testes, sem override temporal na API HTTP.

Preparação usa CAS de revisão do save e fencing de save ativo/geração na mesma
transação. Em conflito, relê e recompõe até oito retries; esgotamento é erro
explícito. Repetir a preparação do mesmo período não altera dados ou revisão.
Não usa recibo de recompensa: esta operação apenas inicializa períodos e é
naturalmente idempotente. Não há incremento, RNG, entrega ou resgate nessa API.
Futuras operações econômicas ainda precisam do recibo transacional previsto no plano.

## Bridge

Todas as rotas exigem o Bearer já usado pela bridge.

| Rota | Contrato |
| --- | --- |
| `GET /v1/missions?chatId=...&playerId=...&saveId=...` | Snapshot sem escrita; `saveId` opcional seleciona o ativo |
| `POST /v1/missions/prepare` | Corpo `chatId`, `playerId`, `saveId`, `fenceGeneration`; apenas inicializa os períodos do save ativo |

O envelope segue `{ok, data}` e chaves JSON camelCase. O DTO inclui identidade,
diárias, semanais, evento histórico, progresso, metas, recompensas possíveis e
`claimsEnabled: false`, `progressIntegrationEnabled: false` para o escopo desta entrega.
Não há rota de claim, presente, seed ou escrita arbitrária de progresso.
Nenhum comando WhatsApp foi anunciado ou habilitado.

## Isolamento do trabalho preexistente

Preservados byte a byte: `ports.clj`, `missions.clj`, `missions_store.clj`,
`016-missions.edn` e o inventário A4.3. No `store.clj`, os hunks preexistentes
também foram preservados; adicionaram-se somente as dependências e a
implementação do protocolo separado `MissionQueries`. No schema, a inclusão
preexistente da 016 foi reutilizada e adicionou-se a 017.

O commit contém a 016 e seu registro por pertencerem diretamente à fundação.
Não contém o protocolo de claims/presentes, o domínio WIP de resgates nem os
hooks WIP de captura/PvP/ginásio. O `store.clj` foi preparado para o índice com
somente os hunks desta etapa, mantendo o restante no diretório de trabalho.
O catálogo testado desta entrega é independente do domínio WIP não commitado;
esse domínio deverá reutilizá-lo quando for reconciliado na próxima etapa.

A implementação foi testada também numa exportação temporária de HEAD com
somente o conteúdo desta entrega, para excluir dependências acidentais do WIP.

## Validação

Com Clojure CLI e Java 21 configurados:

```sh
clojure -M -m pokemon.tests.missions-a431-test
clojure -M -m pokemon.tests.missions-a431-regression
node --test test/pokemon-jvm-client.test.cjs test/pokemon-jvm-present.test.cjs test/pokemon-jvm-commands.test.cjs test/pokemon-jvm-battle-ui.test.cjs
```

A suíte específica usa Datomic real em diretórios temporários. Inclui catálogo,
estados, leitura sem escrita, saves/treinadores, faixa fixa/renovação e limites
de fuso, duas preparações concorrentes, fence antigo, migrations idempotentes,
HTTP autenticado e persistência entre duas JVMs independentes.

O runner de regressão redireciona o armazenamento das oito suítes existentes
para diretório temporário exclusivo; nenhuma base de desenvolvimento é apagada.

Resultados:

- Fundação: 9 testes, 77 assertions, zero falhas/erros.
- JVM existente (A2, A3, A4.1, A4.2, onboarding, caça, ginásio, loja): 69 testes,
  351 assertions, três falhas preexistentes de status no ginásio, zero erros.
  Reproduzidas com o mesmo runner numa exportação de HEAD sem a implementação.
- Node: 28 testes, 25 passam e três falham. Falhas preexistentes: nome Praia Azul,
  texto de coleção e expectativa antiga do alias `batalhar`. Reproduzidas com
  os arquivos Node originais da exportação de HEAD.
- `git diff --check`: sem problemas.

O sandbox bloqueia bind de servidores locais; testes HTTP foram executados
com permissão ampliada, em loopback. As falhas preexistentes não foram corrigidas
porque estão fora desta entrega. A regressão geral, portanto, não está verde.

## Próximas etapas e limites

Integrar eventos confirmados nas transações de captura, derrota de selvagens,
vitória PvP/ginásio e envio de presentes; persistir identidade e resultado dos
eventos; impedir contagem dupla com IDs HTTP diferentes; completar recibos de
resgates, proteção dos dois saves em presentes e recuperação das pendências.
Depois, integrar apresentação WhatsApp conforme a master e testar falha após
commit, concorrência de progresso/resgate e restart desses fluxos econômicos.

Esta etapa não prova nem habilita idempotência de recompensas. O WIP preexistente
continua fora do commit, inclusive seus hooks já presentes no workspace. Para
avaliar somente A4.3.1, usar o commit isolado, não o conjunto desses arquivos locais.
