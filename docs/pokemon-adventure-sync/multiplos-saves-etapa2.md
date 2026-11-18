# Múltiplos saves — Etapa 2 (coordenação, ainda bloqueada)

## Estado e decisão

Esta etapa adiciona um coordenador Cassandra durável experimental para reserva de recursos, gerações monotônicas, journal de fases e efeitos CAS recuperáveis. O construtor exige `enabled: true`; o padrão é desabilitado. Nenhum comando ou endpoint o ativa. Não existe ainda prova de que os writers do jogo participem do protocolo, portanto ativação de slots, restauração e encerramento administrativo de ginásios continuam bloqueados.

O coordenador não é uma transação distribuída entre partições Cassandra. Um lock LWT em uma linha não impede um processo antigo de fazer `INSERT` incondicional em outra linha. A verificação de fence seguida por CAS no estado também tem uma janela entre partições. A propriedade só será válida depois que cada caminho de escrita usar CAS/versionamento no próprio estado e validar a geração vigente na mutação efetiva, e que processos antigos tenham sido retirados.

## Inventário dos writers atuais

O armazenamento é `estado_particionado` com chave `((modulo, particao))`. O módulo `treinador` guarda uma partição por chat/grupo contendo o mapa de contas do grupo, não uma linha por jogador.

| Estado/fluxo | Módulo/partição | Escritores encontrados | Proteção atual e risco |
|---|---|---|---|
| Conta, coleção, itens, XP, moedas | `treinador` / chat | `treinador.cljs` e callbacks de `core.cljs`; gravação comum por `salvar!` | Fila local por módulo, mas `gravar-parte!` é upsert sem LWT. `alterar-treinador-confirmado!` é CAS para fluxos selecionados, não todos. Um snapshot do grupo pode concorrer com updates de outro jogador. |
| Ocupação, defensores, motivação | `ginasios` / chat | `ginasios.cljs`: `ocupar!`, `desgastar-defesa!`, recuperação de motivação | `salvar!` por atom; sem CAS/LWT do registro do ginásio. Guardar cópia do time mantém referências externas ao treinador. |
| Histórico/ranking de ginásios | `ginasios-estatisticas` / chat | permanência e resultado de batalha | `salvar!` sem CAS; separado da ocupação e treinador. |
| Batalhas PvP/ginásio e caçadas | `batalhas-ativas`, `cacadas-ativas` / chat | `core.cljs` via `add-watch`, callbacks e expiração | Persistência por upsert; fila de jogadas é local ao processo. Timers são rearmados no startup. |
| Raids | `raids` / chat | `raids.cljs` e handlers de comandos | `salvar!` por atom; resultados também escrevem treinador/loja em outras etapas. |
| Loja, recompensas, inventário não Pokémon | `loja` / chat ou conta | `loja.cljs`, missões, ginásios, raids e capturas | `salvar!` sem CAS; efeitos entre módulos não atômicos. |
| Trocas e ofertas | `pokemon-propostas-troca` / chave serializada por chat/jogadores | handlers/callbacks em `core.cljs` | `add-watch`/`salvar!`; não compartilha lock durável. |
| Remoções de golpes pendentes | `pokemon-remocoes-pendentes` | timer/callback em `core.cljs` | persistência comum; callback pode retomar após restart. |
| Eventos Adventure e incubação | tabelas `adventure_*` e `activity-state` | `adventure.cljs`, consumidor periódico e fila de chat | Alguns recibos/estado usam CAS/LWT no domínio Adventure, mas atualização do treinador/notificações cruzam sistemas. Não participam do fence de save. |
| Descobertas globais | `pokemon-descobertas` | `treinador.cljs` | `salvar!`, fora da conta jogável mas relacionado ao progresso. |

`salvar!` serializa localmente por módulo e conserva snapshots confirmados contra certas atualizações confirmadas; isso não é exclusão entre VMs/processos e não transforma o upsert Cassandra em CAS. `reservar!` e `alterar-treinador-confirmado!` protegem operações específicas, não todos os writers acima.

## Protocolo experimental

`runtime/save-transition-coordinator.cjs` usa somente o schema já existente, sem DDL:

- `pokemon-save-transition-journal`: operação por hash de grupo/jogador, fases em ordem e efeitos com hashes (não guarda os valores sensíveis dos efeitos).
- `pokemon-save-transition-locks`: recurso por hash de grupo/tipo/ID, owner da operação e geração monotônica; locks não expiram por relógio e não são roubados após timeout.
- Aquisição em ordem determinística e LWT `IF valor = ...`/`IF NOT EXISTS` com consistência serial.
- Lock parcial permanece bloqueado após erro ou restart; nova chamada da mesma operação pode retomar. Nenhum cleanup automático abandona a reserva.
- Efeito segue `intent no journal → LWT da linha esperada → leitura SERIAL → confirmação no journal`. Resposta perdida é resolvida comparando hash do estado; divergência permanece incerta e bloqueada.
- Fases: `preparacao → reserva → backup → encerramento → ativacao → verificacao → conclusao`. Só transições adjacentes; não liberar locks antes de `conclusao` nem com efeitos pendentes.
- A geração antiga é rejeitada quando o lock muda. A flag construtiva é `enabled`, cujo padrão é `false`.

Esse mecanismo é uma fundação, não uma prova de fencing global: como o estado atual é particionado diferentemente dos locks e há upserts antigos, existe janela entre checar o lock e gravar o dado, além de writers que ignoram o lock por completo.

## Ginásios

`ginasios.cljs/ocupar!` é a operação oficial de troca: derrota normal registra histórico, aplica XP/moedas e retorna Pokémon. Reutilizá-la para restauração concederia recompensa indevida. `desgastar-defesa!` altera motivação e histórico, também inadequado. Não foi criada operação administrativa de retirada: hoje não há primitiva sem recompensa e com journal coordenado entre treinador, ocupação e estatísticas. Uma mutação direta de `ocupacoes` poderia deixar caches/stats divergentes e batalhas em andamento com cópia antiga.

Logo, todas as ocupações de ginásio relevantes devem continuar bloqueando ativação. Antes de encerrar ocupações será necessário um fluxo interno próprio, sem recompensa/derrota, com plano de referências e journal por ginásio, integrado às filas de batalha e ao writer de ocupação.

## Recuperação e limites atuais

Journal e locks sobrevivem a reinícios e podem ser consultados quando se conhece o grupo/jogador/operação. A descoberta global de operações no startup ainda não está ligada ao boot: o schema antigo não tem índice de operações ativas nem consulta eficiente para descobri-las sem `ALLOW FILTERING`. Não iniciar a futura ativação até adicionar índice aditivo com protocolo de publicação/recuperação e fazer startup reconciliar todos os registros antes de abrir writers.

Também é necessário mudar writers para versionar a mesma partição de dados na persistência efetiva e rejeitar gerações antigas, coordenar o `treinador` por chat (partição compartilhada por todos os jogadores) e o ginásio por sua partição real, e garantir que o serviço principal/instâncias antigas não gravem diretamente. Locks locais não bastam.

## Condição para a próxima etapa

Não habilitar alternância nem chamar o coordenador em produção. Bloqueadores exatos:

1. upserts comuns de `salvar!` ignoram fences e ainda são usados por treinador, ginásios, batalhas, raids, loja, trocas e timers;
2. treinador é salvo como snapshot completo do grupo, incompatível com lock isolado por jogador;
3. não há índice de startup/reconciliação global de journals;
4. sem operação administrativa oficial não recompensável, a ocupação de ginásio não pode ser encerrada com segurança;
5. não há exclusividade operacional demonstrada contra processo antigo/segundo writer.

Os testes Cassandra desta etapa cobrem LWT do journal/locks, concorrência, geração velha, efeito com resposta perdida, restart e preservação/identificação do bypass legado em linhas sintéticas. Eles validam as primitivas e também demonstram o bloqueador: o upsert legado passa enquanto existe lock do coordenador.
