# Comandos e notificações de ovos

Implementação local, desligada por padrão: `ADVENTURE_SYNC_ENABLED=false` e
`PERFORMANCE_METRICS=false`. Não exige novo schema Cassandra. Nenhum deploy ou
alteração de produção faz parte desta etapa.

## Comandos textuais

O roteamento existente de `pokemon` e `pk`, via `/commands`, é reutilizado:

- `!pokemon ovos`: lista numerada dos ovos disponíveis, com ocupação até 9.
- `!pokemon ovo`: pontos e minutos restantes das incubações; mostra nascimento
  pendente quando a coleção não permite receber o Pokémon.
- `!pokemon incubadoras`: disponibilidade, ocupação e usos de cada incubadora.
- `!pokemon incubar 1`: seleciona a primeira incubadora livre.
- `!pokemon incubar 1 permanente|extra|especial`: seleção explícita.
- `!pokemon ovo ajuda`: instruções, limites e metas.

Os números se referem à lista atual de ovos **disponíveis**. A validação final
de disponibilidade continua no motor CAS existente. O recibo da operação usa
o ID estável da mensagem WhatsApp; um resultado incerto exige consultar o estado
antes de enviar outro comando. Consultas podem reconciliar prazos vencidos.

Exemplo de notificação (a espécie e o treinador vêm da conta/nascimento):

```text
🎉 SEU OVO CHOCOU!

🥚 Ovo epico
👤 Treinador: @identificador
✨ Nasceu dratini!
⭐ Nível 1
❤️ HP cheio

O Pokémon foi adicionado à sua coleção!
```

A menção usa o identificador WhatsApp do jogador. Nenhum card é gerado.

## Fluxo durável

1. O motor existente fixa o resultado e confirma Pokémon+recibo na coleção.
2. O mesmo CAS que conclui o ovo acrescenta `hatch-notifications` ao envelope
   versionado existente, contendo ID do nascimento, espécie, categoria e instante.
3. O worker transfere o evento para `pokemon-http-events` com ID
   `egg:<hatch-id>`. Só após confirmar essa escrita remove a intenção de origem.
4. O Odisseu consulta `/events/pending`, envia pelo fluxo WhatsApp existente,
   persiste seu recibo de mensagem e só então chama `/events/<id>/ack`.
5. Eventos de nascimento confirmados mantêm um tombstone durável no outbox.
   Repetir um handoff após resposta perdida não recria uma mensagem já confirmada.
   Eventos antigos sem ID estável preservam o comportamento anterior de ack.

Falha antes do handoff mantém a intenção. Falha após o handoff pode repeti-lo
com o mesmo ID. Falha de WhatsApp mantém a mensagem pendente; o polling existente
do Odisseu tenta novamente. Reinício recupera intenções, outbox e recibos.
Coleção cheia mantém o resultado e não produz notificação de entrega antecipada.
Não há transação entre essas partições: cada fronteira tem confirmação e replay.

**Garantia do transporte:** tentativas pelo menos uma vez, enquanto serviços,
persistência e WhatsApp voltarem a funcionar. Uma mensagem pode aparecer duas
vezes se o WhatsApp a aceitar e o bot falhar antes de persistir seu recibo.
Isso não implica segunda entrega do Pokémon. Não se afirma exatamente uma vez.

## Prazos e carga

O índice de contas com incubação/entrega/notificação pendente é reconstruído
na varredura paginada já existente na inicialização. Operações de incubação e
reconciliação atualizam o índice. Os prazos e intenções continuam no Cassandra;
o índice e o timer não são a autoridade.

Há um ciclo de 60 segundos, sem sobreposição, processando até dez contas por
vez, em ordem de prazo, dentro da fila existente de cada chat. Não existe
varredura global Cassandra por ciclo. Contas com entrega bloqueada ou erro têm
nova tentativa após pelo menos 60 segundos, sem impedir outras contas do lote.
Uma leitura SERIAL da coleção resolve entregas incertas antes de tentar novamente.
Shutdown interrompe admissão de novos ciclos e espera operações admitidas nas
filas existentes. Nenhum scheduler de raid automática é reintroduzido.

## Limitações

- Notificações podem atrasar em filas ocupadas, backlog superior a dez contas,
  indisponibilidade ou coleção cheia. Não há promessa de envio imediato.
- O índice usa memória proporcional às contas pendentes. Tombstones do outbox
  e recibos do consumidor crescem; compactação segura fica pendente, sem TTL
  arbitrário que permita recriar notificações antigas.
- Nascimentos históricos já concluídos antes desta implementação não recebem
  notificações retroativas.
- Permanece a arquitetura existente de um writer por grupo. Testes locais não
  substituem a validação futura do WhatsApp real.

Validação: `npm test`, `npm run test:domain`, `npm run build` no serviço, testes
do adaptador Odisseu e `test/adventure-cassandra.integration.cjs` exclusivamente
contra Cassandra descartável. Não usar esse teste contra produção.
