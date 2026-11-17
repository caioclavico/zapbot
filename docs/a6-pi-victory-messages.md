# A6 — Mostrar PI efetivamente ganho nas mensagens de vitória

**Status:** especificação de implementação; não implantada.
**Escopo:** batalha selvagem, caça, ginásio, PvP, raids, missões se produzirem mensagem de vitória e PI. Não alterar valores de PI/XP/PE, distribuição de ovos, limites ou economia nesta PR.

## Regra de apresentação

Sempre que a atividade vencedora liquidar PI, adicionar à **mesma mensagem de resultado**, próximo a XP/PE/moedas:
- `🥚 +15 PI de incubação` (valor **efetivamente creditado**);
- opcionalmente `🧪 Ovo raro: 65/250 PI` se houver ovo incubando e o progresso tiver mudado;
- se o evento não conceder PI (cap, duplicidade ou atividade inelegível), **não** afirmar `+15`;
- com teto diário atingido, opcionalmente `🥚 PI: limite diário atingido` **apenas se confirmado** e útil;
- com incubadoras múltiplas, mostrar **PI do evento** e opcionalmente alocação por ovo; nunca somar a bonificação de incubadora como PI concedido ao jogador;
- sem incubadora ativa, distinguir PI elegível do evento e PI realmente acumulado; não anunciar progresso inexistente.

Exemplo:
```
🏆 Vitória! Seu Pikachu derrotou Onix!
✨ Pokémon: +3 XP
💰 +10 moedas
🥚 +15 PI de incubação
🧪 Ovo comum: 45/100 PI
```
Valores acima são **exemplos**, não regras novas.

## Encadeamento de dados (requisito fundamental)

No sistema existente, o evento é gravado na fonte e liquidado por fluxo assíncrono; `pokemon-service/src/zapbot/pokemon/adventure.cljs` usa `aplicar-evento!`, `drenar-eventos!` e recibo/CAS, enquanto a apresentação atual ocorre em `scripts/lib/pokemon-jvm-present.cjs`. **Não** copiar a tabela de recompensas no presenter para inferir PI. Propagar confirmação persistida de liquidação com event-id/receipt, quantidade real, cap efetivo, por-ovo (opcional) e estado de duplicidade; somente então incluir PI na vitória.

Escolher entre aguardar a liquidação durável antes da mensagem (com timeout limitado, sem bloquear indefinidamente) ou enviar mensagem complementar curta correlacionada com a vitória caso a liquidação aconteça depois. Não enviar mensagens duplicadas em retries.

Se `PI` se referir aos pontos creditados **a ovos ativos** e não a saldo do jogador, usar vocabulário correto, sem inventar carteira de PI. Esclarecer semantics consultando DTOs canônicos e projeção `:pi-daily`. O mesmo evento pode distribuir PI entre vários ovos, e incubadora especial acrescenta bônus na parcela do ovo.

## Alterações necessárias

1. Auditar todos os pontos de vitória, caçada, PvP, ginásio e raid e seus eventos de activity. Descobrir quais concedem PI **de fato**; adicionar suporte quando a regra já prever mas o presenter não receber o resultado. Não atribuir PI a modalidades sem evento elegível.
2. Expor metadados canônicos de PI confirmados, idempotentes, sem alterar grants.
3. Criar helper único de formatação de PI e integrar ao resultado de vitória; preservar menções WhatsApp e demais recompensas.
4. Testar débito/creditamento condicional, limites, corrida do evento, zero ganho, sem incubadora, um ou vários ovos, bônus especial, raid cooperativa, replay, restart, event-id duplicado, falha em writer.
5. Testes Node + JVM/Datomic relevantes e ensaio isolado, sem mutação de saves live.

**Gates:** nenhuma mensagem de vitória anuncia PI não confirmado; nenhum duplo crédito ou dupla mensagem; XP/PE/moedas inalterados; números conferem com o recibo persistido.

## Operação

Sem GO para merge/deploy/backfill. Implementar e validar em branch de trabalho, mantendo integridade após PR #86.