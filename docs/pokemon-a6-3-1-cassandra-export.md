# A6.3.1 — Exportação operacional somente leitura

O exportador é `pokemon-service/scripts/export-historical-saves.cjs`. Ele usa
apenas as duas leituras canônicas do legado:

```sql
SELECT valor FROM <keyspace>.legacy_estado_particionado
WHERE modulo = ? AND particao = ?
```

com `['treinador', chat-id]` para V1, e:

```sql
SELECT valor FROM <keyspace>.legacy_estado WHERE chave = ?
```

com `['treinador']` para V2. Todas as consultas usam `prepare=true` e
`consistency=LOCAL_ONE`; `SERIAL` é reservado para operações condicionais de
escrita. O processo não contém código de INSERT, UPDATE, DELETE,
DDL ou migration.

## Listagem de jogadores V1

Para descobrir somente os `player-id` presentes em partições V1, sem imprimir
contas, Pokémon ou moedas, use o modo `--list-players`. Ele consulta apenas
`zapbot.legacy_estado_particionado`, com `modulo='treinador'`, parâmetros
preparados e `LOCAL_ONE`:

```bash
export CASSANDRA_KEYSPACE='zapbot'
export RECOVERY_CHAT_IDS='120363231939572938@g.us,120363427684548487@g.us'
node pokemon-service/scripts/export-historical-saves.cjs --list-players
```

Também é possível passar os grupos diretamente:

```bash
node pokemon-service/scripts/export-historical-saves.cjs --list-players \
  '120363231939572938@g.us' '120363427684548487@g.us'
```

A saída contém somente `{chatId, playerIds}`. O modo exige o keyspace `zapbot`
e não executa escrita, DDL, importação ou acesso ao Datomic.

Como `LOCAL_ONE` é aplicado por leitura, e não existe snapshot transacional entre
as tabelas V1 e V2, cada origem é lida duas vezes e o hash é comparado. Uma
alteração concorrente da mesma origem interrompe a exportação. Ainda assim,
uma mudança entre as leituras de V1 e V2 pode ocorrer; nesse caso o relatório
deve ser tratado como inconsistente e a exportação repetida em uma janela
estável.

## Pré-requisitos

- Usuário Cassandra dedicado com permissão `SELECT` somente nas duas tabelas
  legadas. Não usar o usuário da aplicação nem credenciais com `MODIFY`.
- Cópia do repositório em uma máquina de recuperação, fora da VM de produção.
- Diretório de saída fora do Git, com permissão `0700`.
- Confirmação independente do `chat-id` e `player-id` pelo operador; os valores
  nunca são impressos junto com credenciais.

## Execução

```bash
mkdir -p "$HOME/odisseu-recovery/a63" && chmod 700 "$HOME/odisseu-recovery/a63"
export CASSANDRA_CONTACT_POINTS='127.0.0.1'
export CASSANDRA_LOCAL_DC='datacenter1'
export CASSANDRA_KEYSPACE='zapbot'
export CASSANDRA_USERNAME='pokemon_migrator_ro'
export CASSANDRA_PASSWORD='(ler de um mecanismo de segredo; não salvar no shell history)'
export RECOVERY_CHAT_ID='<chat-id-confirmado>'
export RECOVERY_PLAYER_ID='<player-id-confirmado>'
export RECOVERY_VERSIONS='both'       # v1, v2 ou both
export RECOVERY_OUTPUT="$HOME/odisseu-recovery/a63/<player>-historical.edn"
node pokemon-service/scripts/export-historical-saves.cjs
chmod 600 "$RECOVERY_OUTPUT"
sha256sum "$RECOVERY_OUTPUT"  # no macOS: shasum -a 256 "$RECOVERY_OUTPUT"
```

Quando apenas uma versão existir, `both` registra a versão ausente em
`:missingVersions` e exporta somente o snapshot encontrado. Se uma versão
específica for solicitada e não existir, o processo falha sem criar arquivo de
sucesso. Os snapshots recebem IDs baseados no hash do registro original,
preservam `originalRecordId`, `sourceHash`, versão e o payload completo do
treinador. O `loja` é derivado somente do próprio payload legado.

## Validação e prévia

O arquivo é uma cópia offline autorizada e compatível com o contrato A6.3:

```bash
clojure -M -e '(load-file "scripts/a6_recovery_preview.clj")
 (a6-recovery-preview/-main
  (System/getenv "RECOVERY_OUTPUT")
  (System/getenv "RECOVERY_CHAT_ID")
  (System/getenv "RECOVERY_PLAYER_ID"))'
```

Confirme contagens, IDs e hashes antes de qualquer chamada administrativa de
`pokemon.recovery/confirm!`. A confirmação gera apenas `recovery-v1` e/ou
`recovery-v2`; os slots não são ativados automaticamente.

## Campos ainda não mapeados

O exportador preserva o mapa legado integralmente em `:treinador`, mas o
transformador Datomic materializa somente os campos canônicos conhecidos:
Pokémon, equipe, HP, golpes, progresso, moedas, inventário, ginásios,
sequências e Pokedex derivada. Campos operacionais pendentes, batalhas,
ofertas, raids ativas, tarefas e estruturas desconhecidas permanecem no
payload de origem/quarentena e não tornam o save jogável automaticamente.

## Ensaio integrado descartável

Com Docker disponível e a imagem `odisseu-contract-tests` construída:

```bash
cd pokemon-service
npm run test:historical-recovery:e2e
```

O runner cria uma rede interna e um Cassandra 5.0.9 efêmeros, insere fixtures
V1/V2 somente nesse banco, executa o exportador real, valida o EDN e roda o
importador/preview em Datomic Local descartável. O resultado inclui retry
idempotente, slots separados, save ativo preservado e reabertura do banco.
Todos os recursos Docker são removidos ao final; nenhum volume oficial é
referenciado.

## Recuperação em caso de falha

O arquivo de exportação é imutável e pode ser revalidado pelo SHA-256. Não
remova nem substitua um snapshot existente. Uma importação parcial deve ser
interrompida e analisada no Datomic descartável; repita somente com o mesmo
`import-batch-id` após confirmar os recibos. Nunca execute o exportador ou o
importador diretamente contra volumes oficiais sem uma janela e autorização
separadas.
