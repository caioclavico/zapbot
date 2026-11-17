# Etapa 2.1 — fencing dos writers do treinador

## Resultado e limite de segurança

O writer do serviço Pokémon atualizado agora persiste alterações de treinador com LWT sobre a linha Cassandra efetiva, reaplica apenas as contas alteradas sobre a leitura SERIAL mais recente, preserva outras contas do mesmo grupo, recusa conflito sobre a mesma conta e consulta o lock durável antes de cada CAS. Timeouts de LWT são resolvidos por leitura SERIAL; um estado ambíguo bloqueia a partição em memória até reconciliação.

Isso corrige snapshots atrasados entre instâncias que executam esta versão do writer. **Não impede um binário antigo que ainda tenha permissão Cassandra de executar `INSERT` incondicional na mesma linha.** O teste de integração continua demonstrando esse bypass. A Etapa 2.1, portanto, não está concluída e a ativação/alternância de saves deve continuar desabilitada.

## Writers encontrados

| Estado | Partição atual | Caminho | Estado do fencing |
| --- | --- | --- | --- |
| Conta, equipe, PC, enfermaria, itens, XP, moedas e recibos | `treinador` / grupo, mapa de todos os jogadores | `treinador.cljs/persistir!` → `armazenamento/salvar!`; callbacks, watches e timers do domínio | CAS por linha e delta por jogador nesta versão; lock durável verificado antes da mutação; binários antigos ainda podem ignorar ambos. |
| Entregas de atividade que usam update confirmado | `treinador` / grupo | `alterar-treinador-confirmado!` | Compartilha a fila do módulo e usa o mesmo CAS de treinador; conflitos e resultado incerto bloqueiam a partição. |
| Substituição de conta por restauração | `treinador` / grupo | `restauracao-cas-grupo!` | LWT sobre valor anterior e checagem dos locks conhecidos; ainda não é uma operação coordenada com ginásios e não habilita restauração. |
| Ocupações e defensores | `ginasios` / chat | `ginasios.cljs` (`ocupar!`, desgaste, motivação) | Ainda passa por `salvar!` genérico com upsert. Não está cercado pelo fence de treinador. |
| Estatísticas e histórico de ginásio | `ginasios-estatisticas` / chat | resultado de batalhas/ocupação | Upsert genérico, partição diferente da conta e sem CAS. |
| Batalhas, caçadas, trocas, ofertas e remoções pendentes | módulos próprios / chat ou jogador | atoms, filas de comandos, callbacks e retomada | Parte usa filas locais/recibos; snapshots genéricos continuam sem fence Cassandra coordenado. |
| Raids, loja e descobertas | `raids`, `loja`, `pokemon-descobertas` | regras e watches do domínio | Upsert genérico; efeitos podem também atualizar treinador/recompensas. |
| Adventure | tabelas `adventure_*` e estado de atividade | CAS próprio em algumas linhas, filas e consumidor periódico | CAS local ao domínio, sem protocolo de geração compartilhado com treinador/ginásios. |
| Slots, índice e recibos globais | módulos aditivos `pokemon-save-*` | `save-slot-store.cjs` | Escritas imutáveis/LWT; não substituem nem cercam o treinador ativo. |

O inventário anterior detalhado permanece em `multiplos-saves-etapa2.md`. O serviço principal WhatsApp encaminha comandos Pokémon ao serviço independente, mas ainda pode existir imagem/processo antigo ou outro cliente Cassandra autorizado; isso não é excluído pelo código novo.

## Modelo e causa do bypass

`estado_particionado` tem chave primária `((modulo, particao))`. Para `treinador`, a partição é o grupo e o valor é um JSON com várias contas. Os locks do coordenador são linhas distintas, sob o módulo `pokemon-save-transition-locks`. Cassandra não oferece uma condição LWT que leia o lock de uma partição e atualize atomicamente a linha `treinador` de outra partição. Um upsert antigo sem `IF` não consulta o lock e substitui o JSON completo.

O adapter novo usa `UPDATE ... IF valor = ?` (ou `INSERT ... IF NOT EXISTS`) na partição do treinador. Em caso de conflito entre contas diferentes, lê novamente e reaplica apenas o delta cuja conta ainda tem o valor base esperado. Se a própria conta mudou, recusa em vez de sobrescrever. A verificação da linha do lock é separada, mas CAS na linha de dados garante que uma substituição concorrente do mesmo valor-base não seja silenciosamente perdida pelos writers participantes. Essa propriedade não se estende a um upsert antigo sem LWT.

## Compatibilidade entre versões: bloqueador

Não existe controle Cassandra que permita ao mesmo role executar LWT e proíba `INSERT`/upsert incondicional na mesma tabela: o privilégio de escrita é concedido sobre a tabela, não por cláusula `IF`. Um registro de versão/lease em outra linha também pode ser ignorado pelo binário antigo. Portanto, a única implantação segura exige uma barreira externa verificável antes de qualquer transição:

1. migrar para armazenamento canônico por conta/jogador, com versão/fence co-localizados na linha protegida;
2. parar e drenar todas as instâncias antigas e remover workers/PM2/systemd/containers que usem o escritor legado;
3. revogar do principal legado a escrita na tabela de treinador antiga (ou trocar credencial do novo writer e tornar a tabela antiga somente leitura para o principal antigo);
4. impedir rollback para imagens sem a capacidade de writer exigida e validar label/revisão do container antes de abrir readiness;
5. só então migrar o estado por processo aditivo e habilitar o protocolo.

Uma atualização do código do serviço, um lock local, a fila de comandos ou um healthcheck não provam exclusividade entre VMs. Não há autorização nesta etapa para alterar roles, schema ou hosts de produção.

## Startup/reconciliação

O coordenador agora mantém `pokemon-save-transition-index` na linha `active`. Publica a referência hash da operação **antes** do journal por identidade. Se o processo cair entre essas gravações, a próxima reconciliação encontra a referência sem journal e bloqueia. `reconcileStartup()` só limpa operações em `conclusao`, com todos os efeitos aplicados e locks liberados/recuperáveis por LWT; qualquer operação incompleta, índice corrompido ou journal ausente mantém o startup do coordenador bloqueado. O primeiro bootstrap exige `legacyJournalCoverageVerified: true`; o padrão é falso, pois a chave antiga não permite varrer journals sem índice de modo seguro.

O bootstrap do serviço chama a reconciliação antes da inicialização dos slots, da restauração e do Adventure quando `SAVE_TRANSITIONS_ENABLED=true`. O padrão é `false`; o primeiro bootstrap falha fechado se `SAVE_TRANSITIONS_LEGACY_COVERAGE_VERIFIED` não for explicitamente verdadeiro. Essa certificação só cobre a completude dos journals antigos, não prova que processos antigos foram removidos nem impede seus upserts. `ALLOW FILTERING` ou varredura global não foi adicionada.

## Validação desta etapa

Testes Node unitários cobrem lock durável, conflitos por conta, preservação de outra conta, CAS concorrente, resposta LWT perdida e reconciliação do índice. O teste de Cassandra descartável cobre o mesmo adapter e o bypass legado. A integração reporta `legacyWriterBypassObserved: true`; isso é evidência negativa esperada, não aprovação do fencing global.

Ativação, alternância, restauração e desocupação de ginásios continuam bloqueadas até que o writer antigo seja tecnicamente impossível de executar ou gravar e que os outros módulos de estado participem do protocolo.
