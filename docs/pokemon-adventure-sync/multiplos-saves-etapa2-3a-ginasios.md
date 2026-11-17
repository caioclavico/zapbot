# Múltiplos saves — Etapa 2.3A: persistência canônica de ginásios

## Estado desta etapa

Foi adicionado um armazenamento canônico versionado para `ginasios` e
`ginasios-estatisticas`. O caminho é aditivo: o runtime só o usa quando
`CANONICAL_GYMS_ENABLED=true`; o padrão continua `false`. Não há migração
automática no startup, e nenhuma tabela legada é removida ou reescrita.

O corte ainda não está certificado para produção. A máquina de transição de
saves permanece desabilitada e `save-transition-writer-coverage-verified?`
continua `false`. Esta etapa não autoriza troca de saves, restauração ou
encerramento administrativo de ocupações.

## Estado e CAS

Cada registro de ginásio ou estatística tem chave `(chat_id, record_id)`,
geração monotônica, versão do protocolo, JSON canônico, hash, indicador de
tombstone e metadados de escrita. Atualizações usam LWT condicionada à geração,
ao hash e à versão lidos. Timeout ou resposta perdida é resolvido por leitura
SERIAL do registro exato; se não for possível provar o resultado, a gravação
falha e o grupo fica em quarentena até nova hidratação. Tombstones conservam a
geração e impedem que um snapshot antigo recrie um defensor removido.

`armazenamento/salvar!` encaminha os dois módulos ao store canônico quando a
flag está ativa. `carregar-modulo!` então lê apenas o store canônico e falha
fechado se a capability ou a conexão estiver ausente; não faz fallback para
`estado_particionado`. A operação genérica `reservar!` também recusa escrita
legada nesses módulos. Callbacks de domínio que chegam com uma imagem diferente
do atom registrado são descartados antes do CAS; conflitos entre processos não
são repetidos cegamente.

O índice de grupos é uma tabela auxiliar em 16 buckets, necessária para
reconstrução do estado no startup sem `ALLOW FILTERING`. A capability tem os
estados `migrating` e `active`; leitores do runtime exigem `active`.

## Migração controlada

O schema aditivo está em
[`canonical-gym-v1.cql`](../../pokemon-service/scripts/cassandra/canonical-gym-v1.cql).
O migrador lê `ginasios` e `ginasios-estatisticas` do estado legado, cria e
confirma um backup por grupo/módulo com `INSERT IF NOT EXISTS` e leitura SERIAL
do hash e conteúdo, importa cada registro sem sobrescrever conflito, relê a
origem para verificar que ela não mudou e só então muda a capability para
`active` via CAS. Se houver interrupção, os backups e registros já gravados
permanecem e a repetição com o mesmo ID de migração valida o conteúdo existente.

O migrador exige uma evidência externa de exclusividade de writers no início e
repete a verificação antes de ativar a leitura canônica; a impressão digital de
permissões precisa permanecer igual. Ela deve ser produzida por um procedimento
que pare/remova instâncias incompatíveis e verifique permissões Cassandra. O
callback de teste é apenas uma injeção de evidência sintética; ele não certifica
a Oracle, Google Cloud ou qualquer VM de produção. A confirmação final da
migração também relê o legado, mas essa releitura não substitui a
parada/revogação efetiva do writer durante o corte.

Runtime e legado devem usar roles distintos. O role do runtime recebe somente
SELECT na capability e SELECT/MODIFY em `canonical_gym_records` e
`canonical_gym_groups`, além de SELECT nos backups. O migrador recebe os
as permissões de leitura do legado e gravação necessárias no schema canônico e nos
backups. O role legado não recebe nenhuma permissão em `canonical_gym_*`.
`MODIFY` do Cassandra inclui mais operações do que apenas `INSERT`; portanto a
imutabilidade de backup é garantida pelo código (LWT `IF NOT EXISTS`, sem API
de edição/remoção e readback do hash), não por uma permissão CQL granular.

## Limites que mantêm a ativação bloqueada

- A migração/capability ainda não está conectada a um instalador operacional
  que prove que todos os processos antigos foram parados e que as credenciais
  correspondem a roles restritos.
- O domínio altera atom de ocupações e estatísticas separadamente. O CAS
  serializa cada registro, mas não torna atômica uma ação que altera ginásio,
  treinador, loja/recompensas e histórico em partições diferentes.
- Uma gravação de snapshot que altere vários ginásios pode aplicar alguns
  registros e depois encontrar conflito. O grupo fica bloqueado/quarentenado;
  não existe ainda journal de domínio para reconciliar uma ação multiginásio.
- Batalhas e operações de ocupação não adquiriram ainda um fencing comum por
  jogador e ginásio ao longo de toda a operação. O CAS rejeita o write atrasado
  na mesma linha, mas não coordena todos os efeitos distribuídos do combate.
- Não foi implementado o encerramento administrativo transacional de
  ocupações, nem a reconstrução de trainer/loja sem duplicar recompensas.

Consequentemente, não habilitar `CANONICAL_GYMS_ENABLED`,
`SAVE_TRANSITIONS_ENABLED`, alternância de saves ou confirmação de restauração
em produção até completar o journal de domínio, integrar o fencing nas batalhas
e comprovar exclusividade operacional.

## Validação descartável

`npm run test:save-slots:cassandra` executa Cassandra 5.0.9 e runner Node na
mesma rede Docker interna, sem publicar portas. O harness usa apenas dados
sintéticos, reinicia somente o Cassandra de teste e remove container, volume e
rede no `trap` de saída. A prova cobre CAS, concorrência, tombstone, resposta
perdida, importação interrompida após backup, retry, isolamento de role, leitura
canônica sem fallback, alteração tardia do snapshot legado e preservação da
linha sintética de loja.
