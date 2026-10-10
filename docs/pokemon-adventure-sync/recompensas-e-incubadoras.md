# Integração de recompensas e incubadoras

Esta etapa utiliza exclusivamente o `activity-state` e seus recibos/CAS existentes.
`ADVENTURE_SYNC_ENABLED=false` e `PERFORMANCE_METRICS=false` continuam sendo os
padrões. Não há nova tabela, API pública, loja, distribuição automática de
incubadoras temporárias ou card. A integração textual e as notificações são
descritas em [WhatsApp e notificações](whatsapp-e-notificacoes.md).

## Eventos integrados

- Captura selvagem bem-sucedida: 15 pontos e 10% de chance de ovo comum.
- Combate selvagem, ginásio e PvP concluídos: 10 pontos; vitória soma mais 5.
- Vitória PvP: 3% de ovo épico; quando não há épico, um segundo sorteio tem
  20% de chance de ovo raro.
- Conclusão de missão diária existente: 30 pontos, uma vez por missão/dia.
- Primeiro acesso ao inventário ou primeiro evento processado: ovo comum inicial,
  uma vez por conta de jogador/grupo.

As probabilidades são configuradas por `EGG_CAPTURE_COMMON_PERCENT`,
`EGG_PVP_EPIC_PERCENT` e `EGG_PVP_RARE_PERCENT` (0 a 100). O sorteio usa o ID
da conta e do evento; o resultado é registrado duravelmente. Inventário cheio
impede a recompensa de ovo daquele evento, mas não impede os pontos.

Raids manuais permanecem no fluxo anterior, sem novos hooks de recompensas.
Cancelamentos, fugas e capturas fracassadas não geram esses eventos. Não foi
introduzida uma política adicional contra conluio entre jogadores.

## Persistência e recuperação

O ID do combate é persistido antes de executar seu desfecho. A captura grava
a intenção de recompensa no mesmo snapshot que adiciona o Pokémon à coleção.
Vitória PvP e missão guardam a intenção junto ao respectivo estado de origem.
Esses diários ficam em `adventure-eventos` dos snapshots existentes de treinador
e loja; eles não são um segundo estado de incubação.

Dentro da fila do chat, o processamento espera a persistência de origem,
aplica pontos e eventual ovo com um único recibo/CAS no estado de atividade e
reutiliza a entrega de nascimento existente. Só então confirma/remove a intenção.
A inicialização também recupera intenções persistidas. Respostas de CAS incertas
usam a recuperação existente; reprocessamento não concede pontos ou ovos de novo.
Não se presume transação Cassandra entre origem, atividade e coleção.

Somente incubações iniciadas até o instante do evento recebem pontos. Eventos
ocorridos com a feature desabilitada não são armazenados para crédito retroativo.
Permanece a exigência arquitetural de um único writer do jogo por grupo.

## Incubadoras

A permanente tem usos ilimitados. A concessão interna `conceder-incubadora!`
aceita ID idempotente e tipo `:extra` ou `:especial`; cada uma tem três usos.
Há no máximo uma de cada tipo, permitindo três ovos simultâneos. A especial
acrescenta exatamente 25% aos pontos (10 gera 12,5, sem arredondamento por evento).
Cada entrega concluída consome um uso; a incubadora esgotada é removida. Coleção
cheia preserva o nascimento pendente, sem consumir novamente o evento.

## Validação reproduzível

No ambiente Node.js/Java já configurado do serviço:

```sh
cd pokemon-service
npm test
npm run test:domain
npm run build
ADVENTURE_TEST_HOST=zapbot-adventure-cassandra-validation-v2 node test/adventure-cassandra.integration.cjs
```

O último comando aceita apenas o hostname de Cassandra descartável documentado
no teste. Ele cria um keyspace isolado e aplica o schema de teste; o ambiente
Cassandra descartável deve ser removido após a validação. Requer acesso ao arquivo
`scripts/cassandra/adventure-sync-v1.cql` e não deve apontar para produção.
