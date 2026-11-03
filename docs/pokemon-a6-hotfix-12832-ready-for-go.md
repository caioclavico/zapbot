# Hotfix 12.8.3.2 — trocas Pokémon

Status: **pronto para revisão local; sem deploy, migration, backup de produção ou alteração de dados reais.**

## Diagnóstico

Em 12.8.3.1, `accept!` agrupa quatro situações no mesmo erro `trade_forbidden`:

1. o ator não é participante literal da proposta ou o chat não confere;
2. o criador tenta aceitar, embora só o destinatário possa fazê-lo;
3. o save ativo do ator não é o save registrado na proposta;
4. o fence do ator mudou desde a leitura anterior.

O item 1 permanece `TRADE_FORBIDDEN`. Os demais agora retornam códigos distintos e mensagens sem informações do outro treinador.

Os identificadores `@lid` e `@c.us` continuam literais. Não há vínculo de identidade autorizado no protocolo de trocas; tratá-los como equivalentes permitiria a uma conta não comprovada confirmar uma proposta. O teste cobre explicitamente essa rejeição.

Não foi possível atribuir a tentativa de produção a uma dessas quatro condições: a revisão automática bloqueou a exportação de linhas de log, mesmo mascaradas. É necessário acesso explícito a uma amostra sanitizada no host para fechar essa atribuição sem expor propostas, saves ou identificadores.

## Correção

- `TRADE_FORBIDDEN`: identidade/chat não participante; mensagem segura de permissão.
- `TRADE_ACTION_FORBIDDEN`: participante executou etapa do outro participante.
- `TRADE_SAVE_MISMATCH`: save ativo mudou desde a proposta.
- `STALE_WRITER_FENCE`: fence desatualizado, já mapeado para orientação de consultar saves.
- Criação e aceite passam a explicar a sequência: destinatário aceita; criador confirma.
- A conclusão também separa participante ausente de confirmação pelo participante errado.

O fluxo continua: `!pk troca <meu índice> <índice do outro> @treinador`, destinatário `!pk troca aceitar <id>`, criador `!pk troca confirmar <id>`. Recusar e cancelar permanecem disponíveis aos participantes conforme o estado.

Não há migration: não muda schema, save, ponteiro, fence, inventário, economia ou dados de trade. A transferência continua uma única transação Datomic com CAS de revisão, fences e saves, portanto ambos os Pokémon mudam de proprietário ou nenhum muda.

## Validação descartável

| Verificação | Resultado |
|---|---|
| Teste Clojure de trade | 20 testes, 74 verificações, aprovado; inclui `recovery-v1` renomeado |
| E2E HTTP/Datomic | aprovado: troca, propriedade, sem duplicação, retry idempotente, recusa, cancelamento, expiração e intruso |
| E2E com dois `@lid` | aprovado |
| Apresentação de erros | 21 testes Node, aprovado |
| Raids lobby/início antecipado | 40 testes, 193 verificações, aprovado |
| Recompensas de derrota 12.8.3 | 15 testes, 80 verificações, aprovado |
| Captura/Frambo | 15 testes, 75 verificações, aprovado |
| Economia/capacidade/idle | 28 testes, 156 verificações, aprovado |
| Eventos temáticos/Festival | 24 testes, 239 verificações, aprovado |

## Arquivos

- `modules/pokemon/infrastructure/pokemon/infrastructure/datomic/trade_store.clj`
- `modules/pokemon/tests/pokemon/tests/trade_a441_test.clj`
- `src/zapbot/pokemon_http.cljs`
- `scripts/lib/pokemon-jvm-present.cjs`
- `test/pokemon-jvm-present.test.cjs`

## Plano de deploy e rollback

Buildar imagens a partir do commit revisado; conferir commit, content hash, arquitetura e digests. Em produção: backup warm/cold, verificar que não há proposta em confirmação ou trade ativo, atualizar JVM, aguardar health, atualizar Node, validar WhatsApp READY e Node → JVM. Não executar migration, backfill ou alteração de scheduler/eventos.

Para rollback, restaurar somente as imagens 12.8.3.1, JVM antes de Node, preservando volumes e toda proposta legítima gravada. Não apagar saves, trades, schema ou recibos. Não repetir deploy automaticamente.
