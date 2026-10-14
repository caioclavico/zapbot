# Consulta de saves legados

`!pk recuperar` é somente consulta. Argumentos (inclusive `confirmar` ou IDs)
são rejeitados sem consultar o banco. A identidade vem de `playerId`/`chatId`
do contrato HTTP existente, autenticado pelo token entre Odisseu e Pokémon.
O HTTP não é autenticação de jogador externo: a proteção continua dependendo
de o token do serviço permanecer privado e de o Odisseu montar o contexto.

A associação exige igualdade exata do grupo `@g.us` e do jogador `@lid` ou
`@c.us`. Não há inferência pelo número, nome, menções ou IDs fornecidos no
comando. Não existe vínculo comprovado para converter LID para c.us: nesses
casos o resultado pode ser ausente, mesmo havendo save com outro identificador.
Duas fontes com contas divergentes retornam ambiguidade sem exibir conteúdo.

A camada de leitura faz somente dois SELECTs parametrizados:
- `legacy_estado_particionado WHERE modulo='treinador' AND particao=<grupo>`;
- `legacy_estado WHERE chave='treinador'`.

Não consulta caches de PokéAPI ou os demais módulos. Os 417 registros não são
interpretados como treinadores. Não há ALLOW FILTERING, índice, DDL ou writer.
Cada consulta tem readTimeout de 5 segundos e somente as linhas retornadas são
processadas. O registro monolítico pode conter vários grupos; lê-se essa única
chave e seleciona-se apenas a conta exata, sem armazenar o JSON no cache atual.
Limite de JSON aceito: 8 MiB; isso limita parsing, não bytes já recebidos do driver.
Não há varredura dos 417 registros. Um índice só será necessário se o registro
monolítico tornar-se grande demais; requer proposta/ aprovação separadas.

**Schema legado confirmado pelo usuário:** as queries usam o formato documentado
pelo código do schema original (`chave, valor` e `modulo, particao, valor`).
A existência de 11 e 406 registros foi informada pelo usuário, sem inspeção remota por este trabalho. Nenhum acesso a produção foi realizado. Verificação opcional somente leitura, em sessão
administrativa autorizada, antes do deploy:

```sql
SELECT table_name, column_name, type, kind, position FROM system_schema.columns
WHERE keyspace_name = 'zapbot' AND table_name = 'legacy_estado';
SELECT table_name, column_name, type, kind, position FROM system_schema.columns
WHERE keyspace_name = 'zapbot' AND table_name = 'legacy_estado_particionado';
```

É necessário que `chave` seja a chave de acesso da primeira tabela e
`(modulo, particao)` permita a consulta completa da segunda, com `valor` text.
O schema informado é `legacy_estado(chave text PRIMARY KEY, valor text)` e `legacy_estado_particionado(modulo text, particao text, valor text, PRIMARY KEY ((modulo, particao)))`. Schema diferente retorna erro genérico, sem migração automática. Ausência ou
JSON inválido não altera saves. Não há suposição de que os 417 registros
possuam o formato: não existem amostras locais confirmadas dessa importação.

O resumo informa Pokémon guardados (equipe, PC e enfermaria) e quantidade de
ginásios registrados quando disponível. Não afirma total histórico de capturas,
nome ou nível do treinador: o nível atual é derivado de várias fontes, incluindo
missões/insígnias, e um registro legado isolado não comprova esse valor.
Não mostra nomes de terceiros, identificadores nem conteúdo dos Pokémon.

A consulta entra antes de navegar/recolher-curados/resgatar XP, evitando escritas
de domínio. A camada HTTP existente continua registrando seus próprios recibos
operacionais de comandos, conforme sua arquitetura; isso não restaura saves.

## Futura restauração

`recuperar confirmar` permanece bloqueado. A segunda fase precisará confirmar
identidade e grupo, congelar a versão de origem, persistir backup durável do
estado atual e uma intenção auditável, reservar uma operação única com CAS,
executar etapas recuperáveis por módulo e oferecer reversão. Não assumir
atomicidade entre treinador, loja, rank ou ovos. Resultado incerto deve bloquear
novo writer até reconciliação. Nenhuma dessas gravações foi implementada.

## Revisão final local

O schema foi confirmado pelo usuário. Um registro real do export particionado
foi extraído localmente com `csv.DictReader(escapechar=chr(92))`, sem alterar os
CSVs. JSON, referência e manifesto ficam em `~/odisseu-recovery/validacao/`, com
permissão 600, fora do Git. O teste `recuperacao_arquivo_test.cljs` recebe
`LEGACY_VALIDATION_MANIFEST` e não imprime conteúdo, identidades ou contagens.
O manifesto contém caminhos `legacyFile`, `currentFile`, `format` (`partition`
ou `state`), `chatId`, `playerId`, `expectedLegacySummary` e
`expectedCurrentSummary`. A referência desta execução foi derivada da mesma
conta legada, não de um save atual de produção. A validação de uma conta real
não demonstra que todos os registros recuperados tenham o mesmo formato.

Diagnóstico do encerramento: espera circular na fila do chat. O handler de ovos
entrava na fila externa e aguardava a fila interna do Adventure. O watchdog
reproduziu a pendência em 15 segundos (exit 2). Encaminhar comandos diretos de
ovos à fila que o próprio domínio mantém removeu o bloqueio sem mudar regras.
A imagem local de ferramentas também não tinha o catálogo de ovos; montar
`pokemon-service/assets` corrigiu essa limitação de teste.

Validação final após estabilização do PvP: Node 60/60; suíte completa 285
testes/1720 assertions, zero falhas e erros, incluindo o JSON real privado;
build release passou. Permanecem avisos de compilação preexistentes.

As 31 falhas anteriores estavam em 11 casos PvP: abertura (8), aceitação (6),
incompatibilidade de nível (3), desafio próprio/saída (2), ativo ausente ou
inconsciente (2), troca após recusa (3), finalização por nocaute (2), outro
combate (1), expiração (1), restauração de prazo (1) e recibos HTTP duplicados (2).
Todos tentavam abrir/aceitar PvP com comando vazio. O commit `a326b65` tornou o
comando vazio um menu e manteve `batalhar` como abertura/aceitação explícita.
As expectativas foram preservadas, mudando somente o comando usado nos testes,
inclusive `pokemon batalhar`/`pk batalhar` na integração HTTP. Não houve mudança
na aplicação para satisfazer testes de PvP. Um teste adicional verifica que o
comando vazio abre menu sem combate e que `3` → `2` abre/aceita PvP exatamente
uma vez. Os testes PvP isolados passaram: 20 testes, 92 assertions.

A divergência no teste de `loja` era anterior: o commit `9dfbc89` passou a encaminhar
`loja` ao menu do Pokémon. A expectativa foi atualizada, preservando esse fluxo.
A autenticação HTTP usa token do serviço; não há autenticação móvel nem alias
LID/telefone implícito. Recibos operacionais da API permanecem independentes dos
saves e podem ser gravados pelo transporte, mesmo numa consulta de domínio.
