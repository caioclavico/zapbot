# A4.5.6 — Readiness de deploy das negociações Pokémon

## Estado e escopo

Branch: `refactor/modular-monolith-datomic`  
Commit de referência: `043e9fe`  
Ambiente alvo: VM `odisseu-core` (staging)

Esta etapa prepara o deploy, mas não executa deploy, migration, parada de serviço ou alteração de volume.

Os commits diretamente relacionados à negociação são:

- `ff6f3d4` — fundação de propostas;
- `b0bc117` — transições persistentes com recibos;
- `043e9fe` — integração da transferência bilateral e bridge.

O working tree ainda contém alterações não commitadas de negociação, missões, testes e scripts de auditoria. Em particular, `store.clj` contém trabalho misturado e não deve ser incluído automaticamente em uma imagem ou commit sem staging seletivo.

## Evidências de validação

Relatadas como concluídas antes desta etapa:

- A4.5.4: `A452_LOCAL_OK`, código 0;
- A4.5.5: SHA-256 validado, `:installed-now` na primeira execução, `:already-installed` na segunda, `A455_AUDIT_OK`, código 0;
- E2E Node → JVM → Datomic: criação, aceite, conclusão, recusa, cancelamento, expiração, autorização e retry.

Essas evidências usaram bases descartáveis ou cópia restaurada. Não provam que o deploy real foi executado.

## Testes e comandos disponíveis

Antes do deploy, executar em ambiente local/CI:

```bash
npm run test:m4-jvm
npm run test:trade-e2e
npx shadow-cljs compile test
node target/test.js
```

Também executar a suíte JVM de negociação via Docker conforme o `Dockerfile.jvm`, incluindo `pokemon.tests.trade-a441-test` e os testes de integração HTTP.

Nesta preparação não foram acessados staging, Docker remoto ou volumes persistentes. Os resultados A4.5.4/A4.5.5 acima são evidências fornecidas pelo histórico operacional; devem ser anexados ao change record.

## Dependências e riscos

- A JVM instala migrations pendentes no bootstrap por `schema/install!`; `020-trades` depende do registro 019.
- O banco canônico é `core`, sistema `odisseu-m90-core`, storage `/data/datomic-local`.
- `odisseu-core-datomic`, `odisseu-core-journal` e `odisseu-core-maint` são volumes persistentes; não podem ser recriados.
- A sessão WhatsApp está em `.wwebjs_auth_core`, separada do Datomic.
- `ODISSEU_BRIDGE_SEED=false` e `ODISSEU_BRIDGE_RESET=false` devem permanecer ativos.
- A alteração de `store.clj` está misturada com missões; um build deve ser produzido a partir de um commit revisado, sem descartar esse trabalho.
- A evolução automática por troca e a corrida ponta a ponta com todos os escritores de batalha/Joy permanecem riscos/coberturas pendentes já registrados.
- O rollback de dados pode perder transações posteriores ao backup frio; não é rollback automático.

## Checklist pré-deploy

- [ ] Working tree revisado; nenhum arquivo de missão incluído por acidente.
- [ ] Imagem JVM construída a partir de commit revisado.
- [ ] Imagem Node construída a partir do mesmo conjunto de fontes.
- [ ] `docker compose ... config -q` aprovado.
- [ ] Token real configurado fora do Git.
- [ ] `ODISSEU_BRIDGE_RESET=false` e `ODISSEU_BRIDGE_SEED=false` confirmados.
- [ ] Backup frio recente, manifesto SHA-256 validado e diretório preservado.
- [ ] Migration 020 confirmada como já aplicada ou pendente por auditoria somente leitura.
- [ ] Plano de observação e janela de rollback aprovado.

## Deploy controlado

Os comandos abaixo são para execução futura autorizada na VM, não foram executados nesta etapa.

```bash
cd /home/caiohclavico/odisseu-core
./scripts/odisseu-core-validate.sh
docker compose -f docker-compose.odisseu-core.yml \
  --env-file .env.odisseu-core config -q
./scripts/odisseu-core-up.sh jvm
```

Após validar a JVM, iniciar Node somente se a janela incluir WhatsApp:

```bash
./scripts/odisseu-core-up.sh full
```

Esses scripts fazem recreate das imagens/containers conforme Compose, mas preservam volumes nomeados. Não usar `down -v`, `ODISSEU_BRIDGE_RESET=true` ou comandos que removam volumes.

## Smoke tests no staging

1. `GET /health` responde na bridge loopback.
2. Bridge reporta o sistema/banco esperados nos logs.
3. Consulta autenticada de coleção e save de um jogador de teste autorizado.
4. `!neg` lista somente propostas do chat correto.
5. Criar proposta com dois jogadores de teste.
6. Aceitar como destinatário e confirmar como proponente.
7. Verificar posse bilateral, IDs e atributos dos Pokémon.
8. Repetir a confirmação com o mesmo `operationId`.
9. Testar recusa, cancelamento, expiração e terceiro não autorizado.
10. Confirmar ausência de erro de schema ou criação de banco inesperado nos logs.

Não usar jogadores reais nem executar smoke tests destrutivos fora do grupo de staging autorizado.

## Rollback de aplicação

Rollback de aplicação preserva o banco:

```bash
cd /home/caiohclavico/odisseu-core
./scripts/odisseu-core-down.sh
# restaurar a tag/commit anterior das imagens
./scripts/odisseu-core-up.sh jvm
```

Se Node fizer parte da janela:

```bash
./scripts/odisseu-core-up.sh full
```

Não aplicar migrations reversas e não remover volumes.

## Recuperação de dados

Usar somente após confirmar corrupção/perda e obter autorização:

1. parar todos os writers;
2. preservar logs e o volume atual para análise;
3. validar o backup e o manifesto SHA-256;
4. restaurar em volume isolado e comparar os dados;
5. somente então decidir uma restauração operacional.

Restaurar o backup frio pode perder transações realizadas depois do timestamp do backup. O script `odisseu-core-restore.sh` é destrutivo para os volumes oficiais e não deve ser usado como rollback automático.

## Critérios objetivos

O deploy só é aprovado quando:

- todas as suítes locais/CI relevantes passam;
- Compose e imagens correspondem ao commit revisado;
- backup frio e integridade foram confirmados;
- JVM inicia sem reset e sem seed destrutivo;
- migration 020 aparece aplicada ou é aplicada uma única vez;
- smoke tests HTTP/WhatsApp passam no grupo autorizado;
- retry não duplica transferência nem recibo;
- rollback de aplicação foi ensaiado ou explicitamente aprovado como não ensaiado.

Até esses critérios e os smoke tests reais serem concluídos, a funcionalidade não deve ser declarada implantada.

## Validação A4.5.7

Execução local realizada nesta revisão:

```bash
npm run test:m4-jvm
node --test test/pokemon-jvm-client.test.cjs test/pokemon-jvm-present.test.cjs test/pokemon-jvm-commands.test.cjs
```

Ambas as execuções foram bloqueadas pelo sandbox ao tentar abrir listeners em `127.0.0.1`, com `listen EPERM`. O resultado observado foi 17 testes, 11 aprovados e 6 falhas ambientais; as falhas ocorreram antes das asserções HTTP que exigem servidor local. Os testes puros de allowlist, identidade e formatação passaram.

Não foi possível executar neste ambiente:

- `npx shadow-cljs compile test`/`node target/test.js` completo;
- contratos Node → JVM que iniciam Clojure;
- `npm run test:trade-e2e`;
- testes JVM Datomic Local;
- Compose ou Docker, pois o daemon não está acessível.

O working tree também contém arquivos não commitados de auditoria, missões, testes e integração. Eles não devem ser usados para produzir uma imagem de deploy sem uma seleção explícita de commit/arquivos. `store.clj` permanece modificado e misturado com trabalho de missões.

### Conclusão

## Validação final A4.5.9

Estado Git observado:

```text
HEAD 043e9fe
```

Commits de negociação já existentes: `ff6f3d4`, `b0bc117` e `043e9fe`. Permanecem fora de qualquer seleção automática as alterações locais de missões em `store.clj`, `missions.clj`, `missions_store.clj` e documentos relacionados. Também permanecem não commitados scripts de auditoria, testes e ajustes de infraestrutura; eles exigem revisão separada antes de formar uma imagem de deploy.

Validações executadas nesta revisão:

```bash
npx shadow-cljs compile test
node target/test.js
```

Resultados:

- compilação ClojureScript concluída;
- 226 testes;
- 1.371 asserções;
- 0 falhas;
- 0 erros.

`npm test` foi iniciado, mas parou na etapa `test:resources` porque quatro testes HTTP tentaram abrir listeners em `127.0.0.1` e receberam `listen EPERM`. A etapa `test:ai` passou com 18/18; os demais testes de recursos não foram considerados aprovação completa.

Os resultados Mac fornecidos para `npm run test:m4-jvm`, `CLOJURE_BIN=... npm run test:trade-e2e`, A4.5.4 e A4.5.5 permanecem evidências externas desta revisão. Não foram repetidos aqui por indisponibilidade do daemon/loopback.

Os Dockerfiles copiam somente o código presente no contexto de build; portanto arquivos não rastreados não devem entrar em uma imagem até serem deliberadamente incluídos no contexto/commit. Backups, `.datomic-local` e sessões WhatsApp não são copiados pelos Dockerfiles. Ainda assim, a construção final deve ser feita a partir de um checkout limpo ou contexto explicitamente revisado.

### Conclusão A4.5.9

**BLOCKED.** A compilação e a suíte ClojureScript completa passaram, e as validações Mac fornecidas incluem contratos, E2E e auditoria Datomic. Porém `npm test` não completou neste ambiente devido ao bloqueio de loopback, e o conjunto de arquivos não commitados ainda mistura negociação, auditoria, missões e ajustes compartilhados. Não há base suficiente para declarar READY ou criar commits/deploy automaticamente.

## A4.5.10 — Release isolado

Foi tentada a criação de worktree Git no diretório do repositório, mas o ambiente recusou a escrita em `.git/worktrees`. Para não tocar no working tree original, foi criado um clone separado e destacado em:

```text
/private/tmp/zapbot-release-043e9fe
```

Esse clone está limpo e aponta exatamente para:

```text
043e9fef2808fc73ab561245492493cd3d1eae39
```

Os três commits de negociação incluem o código rastreado necessário: migration 020, domínio, ports, adapter Datomic, bridge, cliente Node, roteamento, testes JVM/HTTP e E2E. Não foram encontrados arquivos não commitados indispensáveis para o backend de negociação nos commits de referência.

Arquivos locais que permanecem fora do release isolado incluem `store.clj` com trabalho misto, arquivos de missões, `Dockerfile.contract-tests` e scripts de auditoria A4.5.x. Eles não devem ser copiados para uma imagem de produção sem revisão independente.

O `.dockerignore` exclui `node_modules`, `target`, `.shadow-cljs`, sessões WhatsApp, `data`, `.git`, `.env` e logs. Os Dockerfiles copiam apenas fontes e artefatos explicitamente listados; backups e bases Datomic locais não entram no contexto de imagem.

O worktree/clone isolado não foi completamente testado porque não possui dependências instaladas e o daemon Docker/loopback não está disponível neste ambiente. Os comandos reproduzíveis no Mac são:

```bash
cd /private/tmp/zapbot-release-043e9fe
npm ci
npm test
npm run test:m4-jvm
CLOJURE_BIN="$(command -v clojure)" npm run test:trade-e2e
npx shadow-cljs compile test
node target/test.js
docker build -f Dockerfile.jvm -t odisseu-jvm:043e9fe .
docker build -f Dockerfile -t odisseu-node:043e9fe .
```

Conclusão atual permanece **BLOCKED**: o clone isolado está correto e limpo, mas a suíte completa e os builds das imagens não foram executados nele nesta sessão. Nenhum commit, push, deploy, migration ou acesso a volume oficial foi realizado.

## A4.5.12 — Diferenças do release isolado

Comparação realizada entre `043e9fe` e o working tree de desenvolvimento:

- `test/zapbot/armazenamento_test.cljs`: o working tree contém a correção de fluxo assíncrono/Cassandra; não é código de negociação e não deve ser copiada automaticamente.
- `test/zapbot/core_test.cljs`: contém ajustes de aridade de `whatsapp-saude/servir!` e configuração de staging; é uma correção de testes, não uma dependência funcional da troca.
- `test/pokemon-jvm-commands.test.cjs`: a expectativa local usa `Seu time`, compatível com a UX atual; o clone antigo esperava `coleção`.
- `test/pokemon-jvm-present.test.cjs`: o alias `batalhar` é suportado no código atual; a expectativa do clone está desatualizada.
- `src/zapbot/pokemon_http.cljs`: o working tree contém a regra necessária para não tratar respostas citadas como autorização de negociação e uma correção de mídia. A primeira parte é indispensável à segurança de `!neg`; a segunda é independente.
- `src/zapbot/whatsapp_saude.cljs`: guards para objetos HTTP sem `.on`; são estabilidade geral, não lógica de troca.
- `store.clj`: mistura integração de negociação com missões e outros WIP. Não é seguro selecionar o arquivo inteiro. A integração de trade já presente no commit 043e9fe deve ser comparada por hunk antes de qualquer release.

Os sintomas de JVM não saudável no E2E do clone não foram reproduzidos nesta sessão: o clone não possui dependências instaladas e Docker não está acessível. O diagnóstico deve coletar stdout/stderr, exit code e healthcheck no Mac; não há evidência suficiente para alterar o backend.

O conjunto mínimo candidato à negociação é o conteúdo rastreado pelos commits `ff6f3d4`, `b0bc117` e `043e9fe`, mais a correção de segurança de menções em `pokemon_http.cljs` se ela ainda não estiver no commit revisado. Testes e correções de missões ficam fora.

Conclusão após A4.5.12: **BLOCKED**. O clone limpo não foi validado com todas as dependências, há correções locais necessárias ainda não commitadas e `store.clj` não pode ser separado automaticamente. Nenhuma expectativa foi enfraquecida e nenhum arquivo foi copiado para o release.

## A4.5.13 — Separação seletiva

O patch mínimo proposto para revisão contém:

1. a alteração de segurança em `src/zapbot/pokemon_http.cljs`, que remove respostas citadas do caminho de autorização de negociação;
2. o ajuste de diagnóstico em `scripts/test-pokemon-trade-e2e.cjs`, que usa `clojure` resolvido pelo `PATH` e informa executável, cwd, porta, exit code, erro de spawn e tails de stdout/stderr sem credenciais;
3. as expectativas atualizadas de UX/alias em `test/pokemon-jvm-commands.test.cjs` e `test/pokemon-jvm-present.test.cjs`, caso esses testes sejam mantidos no conjunto do release.

`store.clj` fica fora do patch até que seus hunks de negociação sejam comparados individualmente com `043e9fe`; os demais hunks são missões ou WIP. `armazenamento_test.cljs`, `core_test.cljs` e `whatsapp_saude.cljs` ficam fora por serem correções independentes de armazenamento/saúde HTTP. Scripts de auditoria e documentação A4.5 ficam fora da imagem runtime e devem ser distribuídos separadamente, se necessários para operação.

O teste de autorização já existente em `pokemon_http_test.cljs` deve permanecer no conjunto de testes; ele verifica menção real e rejeição de entradas inválidas. Não foi criado um patch automático de `store.clj` nem foram copiados arquivos do working tree para o clone.

`node --check scripts/test-pokemon-trade-e2e.cjs` e `git diff --check` passaram. O E2E não foi repetido nesta sessão porque o Docker não está acessível. A causa mais provável da falha anterior no clone é o caminho fixo `/usr/local/bin/clojure` em ambiente Mac; o runner agora usa `CLOJURE_BIN` ou `clojure` do `PATH` e reporta diagnóstico detalhado quando a JVM não inicia.

Conclusão A4.5.13: **BLOCKED**. O patch seletivo está definido, mas a validação do release isolado continua incompleta e `store.clj` ainda exige separação manual/revisão antes de qualquer commit.
