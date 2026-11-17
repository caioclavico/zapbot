# Etapa 2.2 — armazenamento canônico do treinador

## Estado desta entrega

O código introduz um caminho canônico versionado por grupo/jogador, um importador assistido e a barreira de compatibilidade do deploy. `CANONICAL_TRAINER_ENABLED` permanece `false`; nenhum schema, role, conta, save ou flag foi alterado em produção. A funcionalidade não está certificada para habilitação até a equipe operar o corte de permissões Cassandra e aprovar a integração real abaixo.

O teste da Etapa 2.1 comprovou que o upsert legado pode sobrescrever `estado_particionado` mesmo com o lock novo. CAS em código novo não corrige essa propriedade de versões antigas. A nova fonte de verdade usa tabelas distintas e um login Cassandra dedicado. Após o corte, o runtime lê e escreve treinador somente nas tabelas canônicas. O writer legado pode até continuar alterando a linha antiga, mas a aplicação nova não consulta essa linha como fallback.

## Modelo

O schema aditivo está em `pokemon-service/scripts/cassandra/canonical-trainer-v1.cql`:

- `canonical_trainer_accounts`: uma linha por `(chat_id, player_id)`, com geração monotônica, versões do schema/protocolo, JSON, hash SHA-256, horário, writer e ID de migração.
- `canonical_trainer_groups`: diretório em 16 buckets para hidratação do runtime sem varredura global nem `ALLOW FILTERING`.
- `canonical_trainer_control`: certificado por LWT do estado `migrating` ou `active`. O runtime só inicia em `active` e protocolo exato; a ausência/incompatibilidade falha fechado.
- `canonical_trainer_backups`: snapshots imutáveis por grupo, jogador e ID derivado da migração/hash.

Cada conta é gravada com LWT sobre geração, hash e versão de protocolo. Um CAS vencido falha; resposta perdida é reconciliada por leitura serial. O payload completo continua JSON para preservar os campos atuais, inclusive referências de coleção. O hash é calculado sobre JSON canônico com chaves ordenadas.

## Papéis Cassandra e isolamento

O processo mantém credenciais existentes para os outros módulos e usa `CANONICAL_TRAINER_USERNAME/PASSWORD` somente para treinador canônico. Esse papel precisa ter `SELECT/MODIFY` apenas em `canonical_trainer_accounts` e `canonical_trainer_groups`, e `SELECT` em `canonical_trainer_control`. Não recebe acesso às tabelas legadas nem permissão para alterar backups.

O papel migrador separado lê `estado_particionado` e pode criar linhas canônicas e backups; não é usado pelo serviço. O papel usado pelas versões antigas não deve ter qualquer grant em tabelas canônicas. Antes da migração, suas permissões efetivas para `MODIFY` na partição antiga de treinador precisam ser revogadas e verificadas, e todos os processos antigos precisam estar parados. Um lock de aplicação ou uma variável de ambiente não substitui esta barreira. Herança por roles e grants em keyspace precisam ser auditados; `REVOKE` de um grant direto não prova ausência de grants herdados.

Os testes descartáveis criam três roles distintos e verificam a negação do Cassandra. Isso comprova o mecanismo no fixture, não as permissões reais nem a exclusividade de processos em produção. Não existe aqui acesso às credenciais/roles de produção.

## Corte assistido (não executar sem aprovação)

1. Provisionar schema aditivo no keyspace correto, com Cassandra backup imutável e snapshot externo verificado.
2. Criar roles separados para runtime canônico, migrador e versões antigas. Inspecionar `LIST ALL PERMISSIONS OF <role>` e as relações de role; remover grants herdados que permitam escrita cruzada.
3. Parar todas as instâncias Pokémon antigas e bloquear novas versões antigas no deploy. Revogar o `MODIFY` do writer legado sobre `estado_particionado` e verificar uma tentativa negativa com suas próprias credenciais.
4. Deixar `canonical_trainer_control` em `migrating`. O migrador exige callback de verificação de revogação, lê uma conta da linha legada, grava e relê backup imutável, relê o save para detectar mudança durante o corte e insere canônico com `IF NOT EXISTS`. Repetição com mesmo conteúdo é idempotente; conflito nunca sobrescreve slot/conta. Se qualquer writer antigo ainda puder gravar, não executar.
5. Comparar hash e contagem por jogador, grupo e total com relatório sem payloads; reconciliar diferenças manualmente. Confirmar que o backup é legível e que outro jogador da mesma partição foi preservado.
6. Somente após todas as contas terem cópia confirmada e auditoria aprovada, um operador com papel administrativo muda o certificado por LWT de `migrating` para `active`. O runtime novo precisa usar credenciais do papel canônico e `CANONICAL_TRAINER_ENABLED=true`.
7. Criar, após essa verificação, os arquivos root-owned `canonical-trainer-required` (`1`) e `canonical-trainer-state.json` com `{ "required": true, "version": 1, "label": "1", "legacyWritersRevoked": true }` (modo 0600). O deploy passa a rejeitar imagem/rollback sem label `io.zapbot.canonical-trainer-protocol=1`.
8. Verificar readiness, hashes e amostras sintéticas/autorizadas sem fallback legado. Não apagar a tabela antiga; conservá-la como evidência/backup somente leitura.

Qualquer falha deixa o controle fora de `active`, mantém o runtime novo fora de readiness e mantém a ativação bloqueada. Não restaurar o grant antigo nem iniciar imagem legacy para “desfazer” depois do corte. Rollback exige uma imagem compatível que leia/escreva o protocolo canônico.

## Limites e riscos ainda abertos

- `saveGroup` aplica uma LWT por jogador; um snapshot com múltiplos jogadores pode confirmar alguns antes de falhar. Cada registro fica íntegro e CAS-protegido, mas não há atomicidade Cassandra entre contas. A recuperação/re-hidratação necessária para writes parciais precisa ser validada no runtime completo antes de habilitar o protocolo.
- Restaurador transacional atual não opera no novo formato; consultas/restaurações nesse modo são recusadas explicitamente. Slots continuam inativos e na tabela existente.
- Ginásios, batalhas e demais estados externos não são transferidos para o armazenamento canônico. Fencing global para essa operação e alternância de saves permanecem bloqueados.
- A instalação do marker no host e a revogação efetiva das roles são pré-requisitos operacionais, ainda não realizados nem verificados em produção.
- `legacy_estado_particionado` mantém dados antigos indefinidamente; limpeza não faz parte desta etapa.
- O `verifyLegacyWriterRevoked` é uma dependência explícita do chamador da ferramenta de migração. A revisão das permissões efetivas deve ser feita por operador Cassandra com privilégio administrativo; passar `true` sem essa prova não é seguro.

## Validação pendente

Executar em ambiente descartável Cassandra 5.0.9 o harness `npm run test:save-slots:cassandra`. Ele usa rede Docker privada, roles autenticadas e dados sintéticos; não publica a porta CQL. Node.js e testes Python podem ser executados localmente. ClojureScript/build requerem Java compatível. Nenhum resultado local substitui a auditoria das roles e instâncias no ambiente real.
