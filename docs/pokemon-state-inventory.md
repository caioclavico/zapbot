# Inventário de estado e persistência Pokémon

Levantamento do código existente antes da extração. Nenhum dado de produção foi
consultado ou alterado. As referências abaixo descrevem a implementação atual;
as recomendações identificam as fronteiras necessárias para o serviço HTTP.

## Convenções e identidade

- `cid` é a chave exata de contexto/chat já existente; a API pode chamá-la
  `chatId` sem reescrever os valores.
- `pid` é a chave exata de jogador; a API deve expô-la como `playerId`.
  Preservar strings com `@lid`, `@c.us` e demais sufixos. O código atual evita
  resolver menções pelo contato quando isso mudaria o identificador.
- Contas são mapas `{cid {pid conta}}`, com chaves string na persistência.
  Índices da equipe e das escalações têm significado no jogo e não podem ser
  reordenados na extração.
- `id-pokemon` é UUID, atribuído a registros novos e completado sob demanda em
  registros antigos. Favoritos, times salvos, recompensas de raid e professor
  dependem desse ID. Alguns combates ainda referenciam índices da equipe.
- ID de raid e de proposta de troca é UUID. Confirmação do professor tem token
  próprio e prazo persistido. Nenhum deles substitui o `requestId` da API.
- O snapshot de combate usa JSON externo com um campo `estado` em EDN.
  Converter esse campo indiscriminadamente em JSON quebra keywords, sets e
  mapas internos; preservar o leitor e a versão `1` existentes.

## Cassandra: estrutura real

`zapbot.armazenamento` é a única implementação de persistência de runtime.
Não existem tabelas relacionais separadas para treinadores, raids e loja.

| Elemento | Implementação existente |
|---|---|
| Keyspace | `CASSANDRA_KEYSPACE`, padrão `zapbot` |
| Nós | `CASSANDRA_CONTACT_POINTS`, lista; padrão `127.0.0.1` |
| Datacenter | `CASSANDRA_DATACENTER`, padrão `datacenter1` |
| Legado | `estado (chave text PRIMARY KEY, valor text)` |
| Atual | `estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo, particao)))` |
| Conteúdo | JSON em `valor`; cada entrada do primeiro nível do mapa vira uma linha |
| Marcadores | `__migrado__` por módulo; `__valor__` para valor não associativo |
| Leitura atual | `SELECT modulo, particao, valor FROM estado_particionado`; `SELECT chave, valor FROM estado` |
| Escrita | `INSERT INTO estado_particionado (modulo, particao, valor) VALUES (?, ?, ?)` preparado |
| Remoção | `DELETE FROM estado_particionado WHERE modulo = ? AND particao = ?` preparado |
| DDL atual | `CREATE KEYSPACE IF NOT EXISTS` com `SimpleStrategy`, RF 1; `CREATE TABLE IF NOT EXISTS` para ambas |

`iniciar!` conecta com retry, carrega todos os módulos, transforma os atoms
registrados e migra automaticamente o legado sem marcador. `salvar!` atualiza o
cache imediatamente, enfileira por módulo e compara com o último snapshot
confirmado. Escritas alteradas são paralelas dentro da operação; há três
tentativas idempotentes por operação. `aguardar-todas!` espera as filas seguras,
que já absorveram erros. Se Cassandra falhar na inicialização, o runtime atual
continua sem persistência.

Consequências para a extração:

1. Uma conta inteira do **chat**, incluindo todos os jogadores, é uma linha de
   `treinador`, `loja` ou `rank`. Dois processos gravando essa mesma linha com
   snapshots diferentes perdem alterações. Fila local não protege dois processos.
2. Cada módulo deve ter um único proprietário ativo. Não iniciar o Pokémon local
   e o serviço simultaneamente contra os mesmos módulos.
3. O bot em HTTP precisa ler apenas seus módulos. Apenas remover `registrar!`
   não basta: o `SELECT` atual continua carregando toda persistência Pokémon.
4. A chave de partição é composta por **módulo e partição juntos**. Consultar só
   `modulo` não é uma leitura por partition key completa. Sem mudar schema, uma
   leitura filtrada na inicialização exige `ALLOW FILTERING` ou um inventário de
   pares já conhecido; documentar o custo, paginar e evitar polling de tabelas.
5. O serviço deve reutilizar o schema e ler legado de forma compatível, sem
   recriar dados nem executar a migração automática antiga. DDL/CLI de migração
   devem ficar fora do startup da extração.
6. Readiness do serviço deve exigir Cassandra disponível e hidratação completa.
   Não aceitar comandos mutantes com `client=nil`, pois o `salvar!` atual retorna
   sucesso em memória nesse caso.
7. Falhas de gravação devem chegar ao processamento HTTP. Esperar somente
   `aguardar-todas!` não prova durabilidade. Não responder sucesso com evento ou
   recibo de comando ainda não persistido.

## Módulos persistidos do Pokémon

Todos são `PERSISTENT_REQUIRED`. O proprietário proposto é pokemon-service,
exceto `rank` e `bugs`, explicados adiante.

| Chave `modulo` | Partição e valor | Dono atual / funções principais | Classificação |
|---|---|---|---|
| `treinador` | `cid` → mapa de jogadores e contas | `treinador/contas`, `persistir!`, funções de captura/equipe/XP/Joy/evolução/professor | DOMAIN + INFRASTRUCTURE |
| `pokemon-descobertas` | chave de espécie → primeira descoberta global | `treinador/descobertas-globais`, `registrar-captura!` | DOMAIN |
| `loja` | `cid` → jogadores com moedas, itens, capacidade, missões e resgates | `loja/contas`, `persistir!`, compras, recompensas, presentes | DOMAIN + INFRASTRUCTURE |
| `raids` | `cid` → raid atual com ID, fase, participantes, HP, turno e captura | `raids/raids`, `criar!`, `comando!`, `registrar-tentativa!` | DOMAIN |
| `raides-agendas` | `cid` → `proxima`, `ultimo-ginasio` | `raids/agendas`, `acompanhar!`, `criar!` | DOMAIN |
| `ginasios` | `cid` → ginásios com líder, time reservado, datas e motivação | `ginasios/ocupacoes`, `ocupar!`, desgaste/cura | DOMAIN |
| `ginasios-estatisticas` | `cid` → ginásios com histórico de até 50 batalhas e placar de líderes | `ginasios/estatisticas`, `registrar-resultado!`, `registrar-permanencia!` | DOMAIN |
| `pokedex-cache` | slug/número/alias normalizado → dados de espécie traduzidos | `pokedex/cache`, `dados-especie`, `salvar-no-cache!` | INFRASTRUCTURE; preservar cache existente |
| `pokemon-batalhas-ativas` | `cid` → versão, atualização, EDN ou terminal | `core/jogos`, watches, `serializar-combates`, `restaurar-combates` | DOMAIN + NEEDS_REFACTOR |
| `pokemon-cacadas-ativas` | `cid` → mesmo envelope | `core/cacadas-selvagens`, mesmos serializadores | DOMAIN + NEEDS_REFACTOR |

`pokemon-descobertas` é global, não por chat. Preservar esse nível de chave e
garantir um único writer mesmo se futuramente houver concorrência entre chats.
O cache da Pokédex é reconstruível, mas já é persistido e evita PokeAPI/tradução
repetida. Não removê-lo em nome da extração.

### Conteúdo da conta do treinador

Inclui equipe e PC legado, Pokémon completo (HP/status/nível/XP/golpes/item/
amizade/shiny/raridade/imagens), ativo, liga e escalações, times salvos,
favorito, inicial escolhido, última caçada, sequência e recorde de capturas,
avistamentos, Pokédex pessoal e shiny, vitórias e PE, insígnias e datas de
ginásio, doações, enfermaria, cartões do professor, confirmação pendente do
professor, marca de recompensa da raid e XP de raid reservado por `id-pokemon`.

Normalizações já existentes convertem excesso de XP, incorporam PC à equipe
sem mudar os índices anteriores, corrigem golpes/tradução/raridade e completam
IDs quando necessário. São compatibilidade do jogo atual; não criar uma nova
migração de dados para a extração.

### Conteúdo da conta da loja

Inclui moedas, inventário, expansão de mochila e coleção, kit inicial,
recompensas pendentes, legado `bolas-pendentes`, sequência/data de bônus diário,
XP de missões, missões diárias/semanais/eventos, resgates e `raid-premiada-dia`.
`missoes` é módulo puro: o estado real vive em **loja**. Bônus diário, missões e
presentes alteram recompensa e marcador na mesma conta/linha, comportamento a
preservar. `loja` é Pokémon mesmo quando exposta pelos comandos gerais `!loja`,
`!mochila`, `!presente` e `!missoes`.

## Todos os atoms e estados transitórios Pokémon

| Estado atual | Durabilidade | Categoria | Tratamento na extração |
|---|---|---|---|
| `core/jogos` | PERSISTENT_REQUIRED | DOMAIN / NEEDS_REFACTOR | Preservar combate e serializador; remover `:message` do modelo neutro |
| `core/cacadas-selvagens` | PERSISTENT_REQUIRED | DOMAIN / NEEDS_REFACTOR | Idem |
| `core/gravacoes-combates` | EPHEMERAL | INFRASTRUCTURE | Promises de confirmação; reconstruir, não serializar |
| `core/cliente-whatsapp` | EPHEMERAL | WHATSAPP_SPECIFIC | Eliminar do serviço; notificações tornam-se eventos neutros persistidos |
| `core/remocoes-pendentes` | PERSISTENT_REQUIRED para intenção; EPHEMERAL para timer | DOMAIN / NEEDS_REFACTOR | Persistir token, prazo, jogador/contexto e snapshot do alvo; rearmar depois de hidratar |
| `core/limites-turno` | EPHEMERAL para handles; prazo é PERSISTENT_REQUIRED | INFRASTRUCTURE / DOMAIN | Usar timestamps do combate, token e relógio local rearmável |
| `core/cache-pokemon-nome` | EPHEMERAL | INFRASTRUCTURE | Cache reconstruível de PokeAPI, sem estado econômico |
| `core/propostas-troca` | PERSISTENT_REQUIRED | DOMAIN / NEEDS_REFACTOR | Guardar proposta, aceitação, prazo, IDs e registros dos dois Pokémon |
| `core/evolucoes-pendentes` | EPHEMERAL | INFRASTRUCTURE | Lock de operações assíncronas em andamento; recriar vazio, apoiado por recibo persistido de comando |
| `core/filas-jogadas` | EPHEMERAL | INFRASTRUCTURE | Promises por chat; preservar serialização local; não é fila externa |
| `core/contextos-filas` | EPHEMERAL | INFRASTRUCTURE | Contextos de medição por chat |
| `core/relogio-raides` | EPHEMERAL | INFRASTRUCTURE | Handle de intervalo local |
| `core/verificando-raides?` | EPHEMERAL | INFRASTRUCTURE | Proteção contra sobreposição de varreduras |
| `:resultado-derrota` em combate ginásio | EPHEMERAL | DOMAIN / NEEDS_REFACTOR | Atom de resumo de uma rodada; não faz parte do snapshot persistido |
| `treinador/contas` | PERSISTENT_REQUIRED | DOMAIN | Hidratar módulo `treinador` |
| `treinador/descobertas-globais` | PERSISTENT_REQUIRED | DOMAIN | Hidratar módulo global |
| `loja/contas` | PERSISTENT_REQUIRED | DOMAIN | Hidratar `loja`; todas entradas passam pelo serviço |
| `raids/raids` | PERSISTENT_REQUIRED | DOMAIN | Hidratar `raids` |
| `raids/agendas` | PERSISTENT_REQUIRED | DOMAIN | Hidratar `raides-agendas` |
| `ginasios/ocupacoes` | PERSISTENT_REQUIRED | DOMAIN | Hidratar `ginasios` |
| `ginasios/estatisticas` | PERSISTENT_REQUIRED | DOMAIN | Hidratar `ginasios-estatisticas` |
| `pokedex/cache` | PERSISTENT_REQUIRED por compatibilidade | INFRASTRUCTURE | Preservar `pokedex-cache`; transformar keywords em memória |

`aventuras`, `mundo`, `shiny`, `golpes`, `missoes` e `ajuda` mantêm catálogos,
regras e funções; não possuem atoms/timers/schedulers próprios. `volatile!`
dentro de mutações guarda resultado local de uma operação e é EPHEMERAL.

## Relógios e tarefas

| Comportamento | Hoje | Persistência e destino |
|---|---|---|
| Nurse Joy | `enviar-ferido-para-enfermaria!` remove da equipe e salva registro completo com `pronto-em = agora + 30min`; `recolher-curados!` cura/reintegra ao executar comando | Já sobrevive a restart. Não há timer de 30min nem aviso espontâneo obrigatório hoje. Preservar recolhimento sob demanda; não inventar notificação |
| Remoção de golpe | `setTimeout` de 30s, depois revalida ativo e nome/posição do golpe; cancelável | Intenção desaparece ao reiniciar hoje. Persistir prazo e alvo; retorno posterior via evento HTTP |
| Caçada esquecida | Watch rearma 5min por mudança no estado; callback foge e quebra sequência | Snapshot persistido; callback usa WhatsApp hoje. Serviço executa regra e persiste aviso |
| PvP esquecido | Watch rearma 30min; sem adversário cancela; com adversário desconta rank se possível, sem recompensa | Snapshot persistido; rank precisa efeito único no dono ZapBot |
| Restart de combate | Descartar snapshot inválido, intermediário, terminal ou vencido; para válido, nova janela completa quando WhatsApp ready | Preservar a semântica e documentar o novo ponto de reativação; nunca restaurar premiação finalizada |
| Terminal de combate | Marca `terminal` por até 24h, removida nas próximas serializações | Impede ressuscitar snapshot antes da recompensa; não confundir com TTL Cassandra |
| Raids automáticas | `setInterval 60s`, também verifica no startup; cada chat usa fila de jogadas; agenda inicial vem dos ginásios | Scheduler no serviço, `raides-agendas` já persistida; aviso de criação vira evento durável |
| Prazos raid | Inscrições 45min, combate 30min, próxima raid 6h, captura após vitória 30min | Datas já no registro; regras sob demanda; não mudar durações |
| Troca negociada | Proposta com `expira` em 5min; limpeza ao receber comando; dois passos aceitar/confirmar | Persistir proposta e aceitação; varredura não precisa criar aviso inexistente |
| Professor | Confirmação com token/prazo de 5min guardada em `treinador` | Já persistido, sem timer; preservar |
| Cooldown de caça | `ultima-cacada` persistida, diferença de relógio de 30min | Sem timer necessário |
| Motivação de ginásio | Calculada sob demanda desde `motivacao-em`/`desde` | Sem cron; preservar perda por hora e piso existentes |
| Missões/bônus | Datas `MISSOES_TIMEZONE`; semana e dia calculados sob demanda | Sem cron; preservar fuso e resgates na conta loja |

Os callbacks de timeout de batalha atuais não passam por `enfileirar-jogada`.
Na nova fronteira precisam usar a mesma exclusão por chat dos comandos e raids,
com revalidação do token/estado, para não escrever durante outra operação lenta.

## Estado compartilhado e o dono correto

| Módulo/estado | Categoria e durabilidade | Dono proposto |
|---|---|---|
| `rank/placares` (`rank`, partição `cid`) | SHARED / PERSISTENT_REQUIRED | ZapBot; usado também por velha, naval, quiz e adedonha |
| `bugs/relatorios` (`bugs`, partição ID do relatório) | SHARED / PERSISTENT_REQUIRED | ZapBot; `pk bug/bugs` é alias de relatório geral, usa histórico/autorização/versão |
| `historico/participantes` (`participantes`, `cid`) | SHARED / PERSISTENT_REQUIRED | ZapBot; envia só contexto solicitado pela API |
| `historico/historicos`, `fila-registros` | SHARED / EPHEMERAL | ZapBot; conversa não deve virar nova persistência no serviço |
| `admins/admins` (`admins-conhecidos`, `cid`) | WHATSAPP_SPECIFIC / PERSISTENT_REQUIRED | ZapBot |
| `bloqueio/estado` (`bloqueio`, `cid`) | SHARED / PERSISTENT_REQUIRED | ZapBot; verificar acesso antes da chamada HTTP |
| `lembretes/lembretes` (`lembretes`, ID) | SHARED / PERSISTENT_REQUIRED | ZapBot; lembrete geral não é Nurse Joy |
| `lembretes/enviando`, `verificador` | WHATSAPP_SPECIFIC / EPHEMERAL | ZapBot |
| `enquetes/enquetes` (`enquetes`, ID) | SHARED / PERSISTENT_REQUIRED | ZapBot |
| `quiz/historico-perguntas` (`quiz-historico`, chat) | SHARED / PERSISTENT_REQUIRED | ZapBot |
| `quiz/quizzes`, velha/naval `jogos`, adedonha `rodadas` | DOMAIN de outros jogos / EPHEMERAL | ZapBot; não extrair |
| `armazenamento/client`, `cache`, `confirmados`, `registros`, `filas-gravacao` | INFRASTRUCTURE / EPHEMERAL | Instâncias independentes, cada uma com allowlist e snapshots de seus próprios módulos |
| `traducao/google-pausado-ate` | SHARED / EPHEMERAL | Implementação pequena reutilizável/extraída; serviço precisa traduções da Pokédex |
| `desempenho/contextos` WeakMap, `sequencia`, `operacoes`, contextos/timer 30s | SHARED / EPHEMERAL | Telemetria independente em cada processo, sem objetos WhatsApp no serviço |
| Spotify token, saúde WhatsApp, startup e shutdown do bot | INFRASTRUCTURE / EPHEMERAL | ZapBot; não extrair |

### Rank sem dois writers

Pokémon chama `rank/pontuar!` na vitória (core:1215) e `rank/penalizar!` na
desistência (2388) e no timeout (4414). Não consulta `vitorias-jogo`: o nível do
treinador usa contador próprio. O retorno booleano de penalização altera o texto
da resposta.

Uma opção pequena, sem novo serviço:

1. Pokémon produz efeito neutro persistido com ID estável, contexto, jogador,
   nome, jogo e operação (`rank.increment` ou `rank.decrement`).
2. ZapBot é único writer de `rank`; aplica o efeito na mesma rotina dos outros
   jogos. No **mesmo registro do jogador**, guardar o ID/result da aplicação
   junto com a pontuação. Não criar um jogador fictício com metadados no mapa do
   chat: `formatar-rank` percorre `vals` e o exibiria no placar.
3. Uma resposta pode carregar duas variantes de um segmento, escolhidas pelo
   resultado do efeito. O adaptador apenas resolve o segmento após aplicar o
   efeito; não calcula regra Pokémon nem adivinha saldo a partir de snapshot.
4. Evento de timeout usa o mesmo protocolo. Confirmar recebimento só depois da
   escrita do rank e do envio WhatsApp. Repetição não aplica nova pontuação.

Snapshot de rank no request não resolve a corrida entre outros jogos e o
retorno HTTP. Um marker de dedupe salvo em linha separada da pontuação também
não é atômico e pode duplicar ou perder pontos após queda.

## Idempotência, eventos e falhas

- `requestId` deve ser a identidade estável da mensagem, enviada pelo adaptador.
  Persistir associação à entrada/fingerprint para rejeitar reutilização do ID
  com comando diferente.
- Registrar intenção antes de executar efeitos; registrar resposta final depois
  de confirmar a persistência. Repetir um request concluído devolve o mesmo
  resultado, sem novo sorteio nem consumo.
- Um recibo `processing` após crash é **resultado incerto**, não autorização
  para executar de novo. Sem transação/diário recuperável entre todos os módulos,
  a resposta segura é sinalizar conflito/pendência e não repetir automaticamente.
- Captura, evolução, ginásio, raid e premiação alteram vários módulos. As
  escritas atuais não são transação Cassandra. A extração não deve prometer
  atomicidade que não existe; proteger contra replay e manter esse risco visível.
- O módulo persistido de eventos pode usar a tabela existente. Incluir ID,
  contexto, conteúdo neutro, efeitos, prazo/estado e ack; não guardar handles,
  objeto de cliente ou mensagem WhatsApp.
- Evento deve existir de forma recuperável quando a ação de domínio conclui.
  Criar aviso apenas depois de salvar a mudança abre janela de perda após crash.
  Guardar intenção de aviso junto ao estado que o originou, ou manter operação
  recuperável com ID estável, e testar a queda entre os dois passos.
- Eventos com mídia precisam sobreviver ao restart. Buffer efêmero com mediaId
  em evento persistido não basta. Guardar receita/asset neutro suficiente para
  regenerar a mídia, ou arquivo local com retenção e volume próprio do serviço;
  nunca depender de filesystem compartilhado entre VMs.
- HTTP polling deve ser configurável e não agressivo. Ack repetido é idempotente.
  Sem idempotência do próprio WhatsApp, envio concluído seguido de falha do ack
  pode repetir **notificação**. Isso é diferente de repetir mutação de jogo/rank.
- Não truncar markers de dedupe antes da janela máxima em que requests/eventos
  podem ser reenviados. Memória/retention devem ter limites explícitos.

## Verificações que devem acompanhar a implementação

1. Carregar fixture com todos os módulos e comparar IDs, inventário, XP,
   enfermaria, ginásios, agenda, cache e EDN antes/depois.
2. Reiniciar durante tratamento Joy, proposta de troca, remoção cancelável e
   batalha; verificar prazos e ausência de premiação repetida.
3. Repetir request mutante com mesmo ID; repetir com payload diferente; simular
   perda de resposta, recibo incompleto e falha de Cassandra.
4. Rodar outros jogos e efeitos Pokémon no mesmo rank; repetir eventos e
   confirmar pontuação e texto de penalização.
5. Raids automáticas criam um evento durável; reinício antes/depois do envio e
   ack mantém recompensa única e comportamento documentado de notificação.
6. Bot HTTP não hidrata/salva os módulos Pokémon e serviço não hidrata/salva
   rank/admins/histórico/bugs/outros jogos.
7. Testes usam fakes/fixtures locais; não enviam mensagens reais nem alteram
   Cassandra de produção.

Referências principais: `src/zapbot/armazenamento.cljs`,
`src/zapbot/pokemon/core.cljs`, `treinador.cljs`, `loja.cljs`, `raids.cljs`,
`ginasios.cljs`, `missoes.cljs`, `pokedex.cljs`, `src/zapbot/rank.cljs`,
`bugs.cljs`, `historico.cljs`, `router.cljs` e `core.cljs`.
