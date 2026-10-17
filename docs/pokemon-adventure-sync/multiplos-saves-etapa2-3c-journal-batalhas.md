# Etapa 2.3C — journal recuperável de efeitos de batalha

## Implementação adicionada

- `runtime/gym-battle-journal.cjs` registra um plano imutável por batalha, publica índice consultável antes do journal, usa CAS/LWT por geração, valida hashes e identidade, aplica efeitos por adaptadores idempotentes e só marca o journal como concluído depois de confirmar recibos duráveis no destino.
- Respostas perdidas são resolvidas somente pela releitura do recibo do efeito. Se o efeito não puder ser confirmado, o journal permanece pendente/bloqueado e `reconcileStartup` falha fechado. Um índice sem journal também bloqueia reconciliação.
- `scripts/cassandra/gym-battle-journal-v1.cql` define tabelas aditivas de journal e índice. A certificação não é inferida pela existência dessas tabelas.
- Testes CJS cobrem conclusão, replay idempotente depois de resposta perdida, reinício do coordenador, efeito não verificável, identidade imutável e journal já concluído.
- `CanonicalTrainerStore.applyOnce` aplica um efeito sintético de HP com ID e hash de intenção; o recibo e a mutação da conta estão no mesmo LWT. Uma resposta perdida é reconciliada por leitura SERIAL do recibo, e uma geração antiga é rejeitada.
- O harness Cassandra recebeu um cenário sintético trainer/gym com recibos LWT, resposta perdida e corrida de dois eventos sobre a mesma geração. O efeito do treinador usa agora a API canônica, não uma linha fixture paralela.

## Limite de integração real

O journal ainda não está ligado ao finalizador real em `core.cljs`. O adaptador do teste é sintético e não os writers reais de treinador e ginásio. Os efeitos reais de XP, PE, moedas, itens, insígnias, missões e mudanças de HP ainda passam por mutações separadas dos atoms e por `salvar!`. A estatística/desgaste do ginásio tem recibos LWT por registro, mas não participa do mesmo plano durável do treinador.

Por isso, o teste sintético do journal não prova o ciclo completo de batalha e não deve ser usado como certificação dos efeitos do jogo. A constante `canonical-gym-battle-effects-verified?` permanece `false`; batalhas canônicas seguem bloqueadas. Não executar DDL em produção nem habilitar restauração/alternância de saves.

## Resultado de integração

O harness exige Cassandra 5.0.9 em rede Docker privada e valida prontidão por conexão CQL. A certificação da Etapa 2.3C continua pendente: a evidência requerida inclui uma execução limpa do ciclo completo, reinício do Cassandra e reconciliação do journal com o adaptador canônico.

## Bloqueadores para conclusão

1. Planejar, antes de qualquer escrita, efeitos determinísticos completos (incluindo sorteios e recompensa diária) e persistir o plano no journal.
2. Adaptar o finalizador real para aplicar HP/XP/PE/moedas/itens/insígnias e ginásio exclusivamente por esses adaptadores, sem escritores antigos paralelos.
3. Executar reconciliação desses journals antes da readiness e manter writers/timers bloqueados para operações pendentes.
4. Passar a integração Cassandra 5 descartável com as APIs reais dos stores, incluindo reinício, concorrência e injeção de falha em cada fronteira.

Até todos os itens serem comprovados, a Etapa 2.3C está incompleta e o estado permanece fail-closed.
