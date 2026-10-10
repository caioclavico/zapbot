# Aplicativo complementar Pokémon Adventure Sync

Este diretório reserva o cliente Flutter da Fase 5. A implementação de
HealthKit, Health Connect, permissões e execução em segundo plano ainda não foi
ativada. Nenhum dado de saúde é coletado nesta etapa.

## Contrato inicial

O cliente deverá enviar HTTPS ao serviço Pokémon autenticado por token de
dispositivo, sempre com `chatId`, `playerId`, `eventId`, `fromMs`, `toMs` e
`distanceMm` já validados. O servidor permanece responsável por validar a
janela, aplicar idempotência e decidir quando um ovo choca.

O cliente nunca deve enviar um comando de nascimento nem usar o número de
WhatsApp como credencial. O vínculo futuro usará código temporário exibido
pelo bot, com expiração e revogação.

## Próximos passos

1. Criar projeto Flutter com Android/iOS nativos.
2. Implementar telas de vínculo e permissões explícitas.
3. Integrar Health Connect/HealthKit somente após revisão de privacidade.
4. Persistir cursor local e reenviar o mesmo `eventId` em caso de falha.
5. Testar sincronização com celular bloqueado e períodos offline.
