# A4.3 — inventário e plano de paridade de missões

Data: 2026-10-20. Etapa de investigação, anterior à implementação.
Referência funcional: `master` em `9eb845b576587bb6634e31a7768e17ad68f93a77`.
Core commitado: `97e4e1bd5343c3e26b0f78848118cc37170d4957`.

## Fontes e estado inicial

Foram lidos diretamente com `git show master:...`: `pokemon-service/src/zapbot/pokemon/missoes.cljs`, seu teste, os fluxos de missões/recompensas/presentes de `loja.cljs`, os chamadores em `core.cljs`, o catálogo de evento em `aventuras.cljs`, a configuração de fuso e o roteamento do bot. O diretório local `pokemon-service` diverge da master; não deve substituir essa referência.

Já existiam alterações locais em `ports.clj`, `schema.clj`, `store.clj` e arquivos novos `016-missions.edn`, `missions.clj`, `missions_store.clj`. Foram preservadas integralmente. São trabalho em andamento, não evidência de paridade validada.

## Catálogo completo

Valores abaixo são de base. PE é experiência do treinador, não XP do Pokémon.

| Período / ID | Nome / objetivo | Meta | PE | Moedas | Pokébolas | Grandes | Ultras | Catalisadores |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Diário / selvagens | Explorador: derrotar selvagens | 3 | 2 | 0 | 3 | 0 | 0 | 0 |
| Diário / capturas | Colecionador: capturar selvagens | 2 | 3 | 0 | 4 | 1 | 0 | 0 |
| Diário / vitorias | Desafiante: vencer PvP | 1 | 3 | 0 | 3 | 1 | 1 | 1 |
| Diário / presentes | Amigo generoso: enviar presentes | 2 | 2 | 0 | 3 | 0 | 0 | 0 |
| Semanal / ginasios | Vencer ginásios diferentes | 2 | 0 | 60 | 8 | 4 | 2 | 1 |
| Semanal / tipos | Capturar tipos diferentes | 5 | 0 | 50 | 6 | 3 | 1 | 1 |
| Semanal / pvp | Vencer PvP | 3 | 0 | 60 | 8 | 4 | 2 | 1 |
| Semanal / presentes | Enviar presentes | 10 | 0 | 50 | 6 | 3 | 1 | 1 |

Diárias: multiplicador `1 + floor((max(1, nível)-1)/5)` em meta, PE e todas as bolas. Catalisadores não escalam. Primeiro acesso **ou evento** fixa o nível do dia; subir de nível não muda a faixa já fixada. Não há bloqueio por nível, encadeamento ou escolha aleatória de missões. Todas as quatro são oferecidas. Progresso diário limita-se à meta.

Por diária resgatada: um sorteio exclusivo de bola (10% +1 Ultra, 25% +1 Grande, 65% nada); sorteio independente de 20% +1 Reviver; outro de 20% +1 item de evolução por troca. Itens possíveis: revestimento-metalico, escama-dragao, upgrade, protetor, pedra-rei. O resgate semanal com moedas positivas concede **um** desses itens, por operação de resgate, além das recompensas das missões prontas. Não é um item por missão semanal.

Semanais não escalam por nível. Ginásios e tipos usam valores distintos; Pokémon de dois tipos pode adicionar ambos. Contadores PvP e presentes limitam-se a 3 e 10; coleções de tipos/ginásios não são truncadas na meta.

### Missões de evento existentes

`aventuras/novo-recomeco`: ID `novo-recomeco-2026-09`, nome Novo Recomeço; início inclusivo `2026-09-27T00:00:00-03:00`, fim exclusivo `2026-10-04T00:00:00-03:00`.

| ID | Objetivo | Meta | Recompensa automática |
| --- | --- | ---: | --- |
| selvagens | Vencer selvagens | 3 | 10 Pokébolas |
| capturas | Capturar Pokémon | 3 | 5 Grandes + 2 Reviver |

Não reiniciam diariamente; apenas ações na janela contam; entrega uma vez por objetivo/jogador/chat. Evento encerrado na data desta investigação: preservar limites e identidade, sem reativação. Progresso e entrega aparecem em texto, inclusive `✅ Entregue`. Não foi identificado catálogo de missões permanentes além desses conjuntos.

## Eventos efetivos na master

| Ação confirmada | Efeito |
| --- | --- |
| Derrotar selvagem e preparar captura | Diária selvagens + evento selvagens; conta mesmo se a bolsa impede captura |
| Capturar selvagem com sucesso | Diária capturas + evento capturas; tipos do selvagem na semanal |
| Vitória PvP normal | Diária vitorias + semanal pvp para vencedor |
| Vitória final de ginásio | ID do ginásio na semanal ginasios |
| Presente enviado com cartão consumido | Diária e semanal presentes para remetente |

Caçada iniciada, captura falha, fuga/desistência, evolução, aprendizado de golpe, uso genérico de item e raid não têm incremento dessas missões nos chamadores identificados. Não criar associações adicionais. Tipos são os da captura selvagem; não inferir crédito de ovos, doações ou outros acréscimos à coleção.

`registrar-missao!` também produz evento `:daily-mission` para o subsistema de ovos quando Adventure está habilitado. A integração precisa respeitar essa flag e seu mecanismo existente; não introduzir worker ou expiração de combate.

## Disponibilidade, resgate, apresentação e persistência original

Fuso configurável `MISSOES_TIMEZONE`, padrão `America/Sao_Paulo`. Diárias renovam à meia-noite local; semana começa segunda. Renovação substitui o estado anterior sob demanda. Não existe consulta geral de histórico na interface original. Missões não resgatadas expiram na virada; não carregar recompensa para o próximo período.

`missoes` / `missões`, alias curto `mis` no Core legado; subcomandos `diarias`, `semanais`, `todas`, `resgatar`. Sem argumento ou `todas` mostra diárias e semanais; `missoes resgatar` resgata diárias. Entradas diretas do router `!missoes`, `!missões`; entradas Pokémon via `!pk`/`!pokemon`. Presentes: `presente`, `presentes`, alias `pre`; destinatário mencionado. Confirmar a expansão de aliases em testes de roteamento.

Consulta e resgate de missões são **texto** na master, com emojis e sem cartão próprio. Diárias mostram nível fixado, progresso, recompensas, `⬜`/`✅`/`🎁`, indicação `(resgatada)`, probabilidades e fuso. Conclusão acrescenta `📋 Missão diária concluída!` e instrução de resgate à resposta da ação. Resgate diário vazio: `📋 Nenhuma missão concluída disponível para resgatar hoje.` Resgate semanal vazio mostra zero moedas e a lista, sem erro de domínio visível. Não introduzir erro divergente na apresentação.

Resgate diário agrega todas as prontas e soma `xp-missoes`. Semanal agrega todas as prontas e moedas. Marcadores impedem novo resgate. `guardar-recompensas` respeita capacidade e ordem dos itens; excesso fica pendente para `mochila resgatar`. Essa recuperação é integração indispensável, sem alterar kit inicial/diário existentes.

Presente exige destinatário diferente do remetente e cartão em inventário. Consome um cartão; destinatário recebe 10% uma Ultra, 25% duas Grandes ou 65% três Pokébolas, com excesso pendente. O remetente recebe progresso. A resposta mantém menção ao destinatário.

Estado legado: conta `loja[chat][jogador]`, com `missoes-diarias`, `missoes-semanais`, `missoes-eventos`, `xp-missoes`, moedas, inventário e pendências. Atom registrado em armazenamento e salvo por `armazenamento/salvar!`. Recompensa e marcador de resgate são alterados no mesmo registro; chamadas de captura e progresso são separadas no legado. Não transportar essa fragilidade para Datomic. Saves A/B do Core devem manter isolamento intencional já aprovado.

## Matriz master × Core

| Aspecto | Master | Core commitado / alterações locais |
| --- | --- | --- |
| Catálogo diário/semanal | Completo | Ausente no commit; port local não validado |
| Missões Novo Recomeço | Janela fixa + entrega automática | Port local de missões não contempla esse estado |
| Consulta e aliases WhatsApp | Disponíveis | Sem integração JVM de missões identificada |
| Resgate diário/semanal | Agregado e manual | Builders locais, sem fluxo completo validado |
| Progresso selvagens/capturas/tipos | Fluxos do service | Fragmentos locais em captura e turno |
| Progresso PvP/ginásio | Vencedor / IDs distintos | Fragmentos locais nas transações existentes |
| Presentes | Consumo + entrega + progresso | Builder local; bridge e comando pendentes |
| Pendências | Guardar e recuperar | Campo/builder local; recuperação precisa integração |
| Consistência após retry/restart | Persistência legado, sem prova de concorrência | Recibos e fencing reutilizáveis; faltam testes específicos |
| História | Apenas estados correntes e evento | Definir retenção auditável Datomic sem novo comando |

Riscos concretos do WIP: resgate semanal vazio atualmente lança erro enquanto master exibe zero; ausência de rolls diários resulta em nenhum bônus no domínio local; nível de referência deve ser recalculado na renovação, não reutilizar nível de estado vencido; presente precisa proteção também no save destinatário. Nenhuma garantia de idempotência desse WIP foi atestada.

## Plano em subetapas antes de alterações amplas

1. **Base e regras**: registrar baseline do WIP sem incorporá-lo indiscriminadamente; completar catálogo/evento, fuso injetável, nível fixado, resgates e RNG conforme master. Testes de equivalência de valores, desbloqueio, viradas e mensagens.
2. **Persistência e operações**: integrar portas e builders ao store usando writer fencing, revisão CAS e recibos existentes. Uma transação deve conter ação confirmada, progresso, conclusão automática de evento e recibo. Resgate deve conter inventário, pendências, PE/moedas, marcador e recibo. Vincular operação a jogador/save e payload; repetir resposta a partir do resultado persistido. Concorrência deve causar recomputação segura ou conflito explícito, nunca sobrescrever progresso silenciosamente. Presentes exigem proteção de ambos os saves e seleção consistente do destinatário.
3. **Eventos indispensáveis**: integrar somente os cinco caminhos da tabela, protegendo a identidade do evento de jogo mesmo quando chega outro ID HTTP. Não usar pós-processamento separado para incrementar missões. Manter ginásio, PvP, caçada e captura existentes.
4. **Bridge e WhatsApp**: expor consulta/resgates/presente/pendências; JVM decide regras e concede recompensas, Node formata textos legados e menções. Consulta diária que fixa faixa é uma operação persistente explícita, não um GET com escrita escondida.
5. **Validação e entrega**: testes determinísticos de domínio; Datomic real com retries, dois eventos concorrentes, concorrência com resgate e presentes ao mesmo alvo, troca de save, reabertura de conexão/journal, falha após commit antes da resposta; contratos HTTP, aliases e apresentação. Executar regressões A1–A4.2 e ginásio. Documentar diferenças residuais; criar commit local exclusivo da implementação A4.3, sem incluir WIP alheio, push ou deploy.

Esta entrega altera somente este documento. Não executa testes de implementação nem declara concluída a A4.3. A solicitação exige apresentar primeiro o plano quando a intervenção atravessa vários domínios; essa condição foi encontrada. Implementação, testes e commit final da A4.3 permanecem como próxima etapa.
