# Etapa 2.3B — coordenação de batalhas e efeitos

## Implementado nesta etapa

- Registros canônicos de ginásio e estatística agora aceitam recibos de operação no mesmo registro Cassandra. `updateRecordOnce` aplica o recibo e a alteração no mesmo LWT; replay do mesmo ID não reaplica a transformação.
- Resultados canônicos de batalha gravam estatísticas e desgaste de defesa por ID estável da batalha. A transformação das estatísticas parte do estado canônico atual, preservando alterações concorrentes em outros campos/treinadores; conflitos de ocupação antiga são recusados.
- O ID da batalha é preservado no snapshot serializado e restaurado após reinício.
- Batalhas de ginásio ficam bloqueadas quando o armazenamento canônico está habilitado, pois os demais efeitos da conclusão ainda não têm protocolo transacional certificado. A certificação é constante de código `false`, não variável de ambiente.

Schema aditivo: `canonical_gym_records.operation_receipts set<text>`. Instalações que já criaram a primeira versão da tabela devem aplicar o `ALTER TABLE ... ADD IF NOT EXISTS` contido em `scripts/cassandra/canonical-gym-v1.cql` antes de inicializar o runtime atualizado. Nenhum DDL foi aplicado em produção.

## Limite de consistência

Isso não transforma o encerramento de uma batalha em transação distribuída. XP/PE e Pokémon são gravados pelo módulo treinador; moedas/itens, missões, ranking e recompensas usam outros módulos/partições. Ainda não existe journal de conclusão que grave uma intenção completa e permita reconciliar cada efeito com recibo atômico no respectivo destino. Portanto, uma falha depois de algum desses efeitos pode deixar a conclusão parcial, e a repetição do handler não está certificada como idempotente para todos eles.

O ID de operação e o recibo cobrem apenas cada linha canônica de ginásio/estatística. Locks consultados em outra partição não foram considerados fencing para esses efeitos. Timers de timeout também não são retomados como transações de resultado.

## Estado de habilitação

`canonical-gym-battle-effects-verified?` permanece `false`; não habilitar batalhas de ginásio em modo canônico, troca/restauração de saves ou operação administrativa de retirada. A Etapa 2.3B permanece **incompleta** até treinador, loja, missão, ranking, timers e callbacks integrarem um journal recuperável e recibos no mesmo CAS/LWT de cada efeito. As flags de saves permanecem desabilitadas.
