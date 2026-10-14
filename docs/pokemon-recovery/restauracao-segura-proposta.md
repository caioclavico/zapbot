# Pré-requisito de restauração durável

Revisão feita na branch `feature/pokemon-legacy-cards`, base `6cfa7f8`.
Nenhuma restauração foi habilitada. Cards e consultas existentes permanecem intactos.

## Lacunas comprovadas

`armazenamento/alterar-treinador-confirmado!` serializa com `filas-gravacao`,
lê SERIAL, faz CAS e instala snapshots confirmados no atom registrado. Em erro,
`partes-bloqueadas` recebe o bloqueio e a Promise original rejeita. Esse bloqueio
é somente em memória. Não há identidade durável da restauração, recibo persistido
na conta ou diário que permita distinguir backup criado, CAS não iniciado e CAS
aplicado com resposta perdida após reinício.

`salvar!` grava snapshots usando INSERT comum e retries. `geracoes-partes`
impede snapshots anteriores de sobrescrever uma alteração CAS no mesmo processo,
mas também é somente memória. Portanto não usar salvar! para substituir contas
nem para controlar o diário da operação. O módulo de backup não pode ser tratado
como uma coleção mutável gravada por snapshots comuns.

`recuperar-treinador!` lê SERIAL e libera a parte bloqueada, mas isso sozinho não
prova qual restauração ocorreu. Não pode liberar uma operação incerta sem
reconciliar o diário e o recibo da conta.

`instalar-parte-confirmada!` aplica a transformação registrada de treinador.
Ela normaliza XP e concatena PC na equipe. A prévia precisa validar e representar
exatamente esse resultado antes da confirmação, incluindo todas as referências
indexadas. Não basta comparar o JSON bruto com o atom transformado.

## Mudança proposta, sem DDL destrutivo

Usar módulos exclusivos na tabela estado_particionado existente:
- `pokemon-restauracao-backups`: partição por ID imprevisível, inserção IF NOT
  EXISTS, registro integral imutável com conta, identidade, operação e hashes.
- `pokemon-restauracao-operacoes`: partição por ID da operação, estados alterados
  por CAS, sem usar salvar! nem retries cegos.
- `pokemon-restauracao-bloqueios`: partição por grupo/jogador, reserva IF NOT
  EXISTS para impedir operações conflitantes após reinício.

A API de persistência precisa oferecer leituras SERIAL desses registros,
reserva imutável e atualização CAS explícita com resultados applied/conflict/unknown.
Essas capacidades serão internas e não permitirão nomes de tabelas arbitrários.
As tabelas legacy_* continuam exclusivamente SELECT.

Consultar/listar backups por índice durável do jogador, mantido por CAS em uma
partição própria. A operação deve conter o ID do backup antes de qualquer escrita
para permitir retomar a indexação sem duplicações. Nunca procurar backups por
varredura por comando. Limites de retenção e tamanho precisam ser explícitos;
nenhum backup poderá ser eliminado automaticamente nesta etapa.

## Sequência e reconciliação

1. Prévia: leituras sem gravação; token criptográfico aleatório vinculado a
   grupo/jogador/versão/hashes, validade de dez minutos. A retenção do token
   em memória pode exigir nova prévia após reinício; após confirmação, o diário
   durável é obrigatório. Nunca mostrar o token de outra pessoa.
2. Confirmar: adquirir fila de chat e reserva durável da conta, verificar ausência
   de combate, troca, enfermaria em processamento e operações Adventure pendentes.
   Revalidar origem e destino confirmados; rejeitar diferenças da conta-alvo.
3. Registrar intenção com ID do backup e conteúdo canônico. Criar backup por
   IF NOT EXISTS e ler SERIAL, validando integralmente conteúdo/hash/identidade.
   Não prosseguir com backup incerto ou divergente.
4. CAS do treinador: reler grupo confirmado; substituir somente jogador-alvo,
   preservando cada outro jogador literalmente. Conferir hash do alvo novamente.
   Registrar recibo permanente de operação na mesma conta/mesmo CAS da substituição.
5. Confirmar por leitura SERIAL: recibo e hash precisam coincidir. Persistir
   conclusão no diário; instalar cache/atom compatíveis; somente então liberar
   bloqueio e permitir resposta de sucesso.
6. Timeout: sem retry da substituição. Ler SERIAL; recibo coincidente prova CAS
   aplicado; conta antiga sem recibo e hash coincidente permite registrar não
   aplicado; qualquer outra situação permanece bloqueada para revisão.
7. Startup: carregar/reconciliar bloqueios e diários antes de liberar readiness,
   comandos e timers mutáveis. Não liberar apenas por expiração do token.
8. Desfazer: selecionar backup pertencente à conta, produzir nova prévia e exigir
   confirmação. Criar novo backup integral antes de substituir; executar o mesmo
   protocolo, sem caminho rápido que dispense CAS ou confirmação.

Duas confirmações iguais devem consultar a mesma operação; duas diferentes devem
conflitar pela reserva da conta. A coordenação precisa incluir os writers atuais,
e não apenas handlers de comandos restaurar. Uma implantação com dois writers
continua proibida.

## Integridade e fronteiras

Não assumir que moedas/inventário ou nível completo estão no treinador: o projeto
usa loja e XP derivado de múltiplas fontes. Restaurar somente campos que existem
na conta treinador não restaura o módulo loja nem o ranking. A prévia deve dizer
explicitamente quais módulos e componentes do nível permanecem atuais.

Preservar campos desconhecidos de perfil quando comprovadamente inertes. Bloquear
origens com campos operacionais desconhecidos; não apagar silenciosamente. Eventos
Adventure, recibos de entrega, transferência-professor e XP de raid pendente
exigem política separada: não reexecutar eventos antigos e não apagar recibos
atuais que previnem duplicação. Por padrão, bloquear operações pendentes e manter
recibos operacionais atuais; mostrar as exclusões na prévia. Backups conservam
integralmente o original, inclusive campos operacionais, mas seu desfazer também
não pode reproduzir eventos consumidos.

Validar UUIDs únicos e existentes em equipe, PC e enfermaria; não gerar novos.
Validar HP, atributos, tipos, golpes, níveis e referências ativas antes da
normalização. PC pode ser anexado preservando sequência; duplicação de UUID
bloqueia em vez de deduplicar silenciosamente. Validar referências de times,
ofertas de golpes e índices após transformação. Não consultar PokeAPI.

## Critério para habilitar

Implementar e testar o protocolo acima, incluindo reinício com estado durável,
resposta CAS perdida, falha de backup e writers concorrentes. A aprovação de
consultas/cards não valida restauração. Nenhum comando restaurar mutável deve
ser conectado antes de esses pré-requisitos passarem na suíte isolada.

## Implementação interna inicial (não habilitada)

`runtime/restore-protocol.cjs` contém o coordenador testável com armazenamento
injetado; `runtime/restore-store.cjs` contém primitivas preparadas de leitura
SERIAL, INSERT IF NOT EXISTS e CAS restritas aos módulos de restauração.
Não existe instância de produção nem export HTTP desses writers. O comando
`pk restaurar` retorna desabilitado antes de entrar na fila normal de jogo.

O protocolo registra operação antes do backup; confirma o backup por leitura;
faz somente um CAS do grupo, preserva demais jogadores, associa recibo ao alvo;
reconcilia resposta perdida sem repetir substituição. Journal separado nunca
é tratado como parte atômica do CAS da conta. Um lock liberado com falha após
conclusão do journal é revalidado por leitura e retomado, sem sucesso prematuro.

**Integração de persistência concluída nesta etapa:** `restore-store.cjs` agora
mantém registry fixo, índice de backups por hash de identidade e journal CAS.
Leituras são por chave; não há scan global nem ALLOW FILTERING nesse adaptador.
`pokemon-service/restauracao.cljs` conecta o store aos primitives do módulo
armazenamento e inicializa reconciliação antes de readiness/Adventure/timers.
As confirmações de produção continuam fechadas por gate constante false.
`pk restaurar backups` permite somente leitura com identidade autenticada;
prévia de backup e confirmação integrada existem como funções internas sem
export HTTP. Nenhum comando de confirmação/desfazer mutável foi disponibilizado.

A fila usada é a própria `filas-gravacao[treinador]`. CAS fora do writer exclusivo
é recusado. Cache, confirmados e atom são instalados somente pela função comum
de estado confirmado, com incremento das gerações. Um salvar! antigo enfileirado
antes da restauração não ultrapassa essa geração. Snapshots de uma partição
bloqueada que divergem do confirmado são recusados e o atom é reidratado.

Bloqueios sobrevivem em registry conhecido, lido SERIAL no startup. Operações
são indexadas antes do journal: uma resposta perdida não deixa journal invisível.
Journal ausente, corrupção ou ausência de recibo não libera automaticamente o
grupo. Locks são considerados mesmo se faltarem no índice de operações.
Só depois de concluir journal e liberar registry a entrada pendente é removida;
o journal permanece consultável pelo ID para replays. Sem registry confiável,
a barreira de readiness permanece fechada e timers não são iniciados.

**Limitação deliberada:** como o writer comum ainda persiste grupos inteiros,
o bloqueio é da partição/grupo, não só do jogador. Outros grupos continuam
independentes. Liberar apenas um jogador sem alterar o writer comum poderia
permitir perda de dados. Uma operação com journal preparado sem recibo permanece
bloqueada para revisão; não se assume que uma escrita incerta não ocorreu.

Testes: adaptadores ClojureScript exercitam fila/atom, snapshot antigo, bloqueio
contra batalha/captura, Cassandra indisponível e restart após CAS aplicado.
`test-restore-cassandra.cjs` usa Cassandra descartável 5.0.9, schema sintético,
SERIAL/LWT, backup imutável, indexação, resposta perdida, reinício e desfazer.
Não usa os exports/contas reais nem o Cassandra de produção.

A validação inicial bloqueia XP que requer normalização, UUID ausente/repetido,
referências inválidas e campos operacionais. Ainda falta uma transformação de
compatibilidade comprovada para todos os índices e propriedades operacionais;
esses casos não são corrigidos silenciosamente. São necessários testes de
integração em Cassandra descartável e de todos os writers reais antes de conectar
a aplicação, reconciliar no startup e oferecer os comandos completos.

### Resultado da integração

- Node.js: 72 testes aprovados.
- Domínio: 300 testes, 1.822 assertions, zero falhas/erros.
- Build release aprovado (avisos existentes de inferência/depreciação).
- Integração Cassandra 5.0.9 descartável aprovada com dados sintéticos; container
  exclusivo de testes encerrado após validação.

`confirmar-interno!` usa primeiro a fila real do chat e depois a fila de gravação
já existente. O writer protege a partição desde a entrada, antes da primeira
escrita de journal. Confirmar na instância de produção continua recusado pelo
controle constante false. Não houve DDL em produção, acesso a saves reais,
commit, push, merge ou deploy.

Riscos ainda impeditivos: política final de compatibilidade e campos operacionais,
UX completa de prévia/confirmação, limites de retenção/volume do índice global
fixo e revisão de operação bloqueada antes do CAS (não abortada automaticamente).
Uma segunda instância antiga que ignore os bloqueios não é suportada: continua
obrigatório o modelo operacional de um único writer ativo do Pokémon.

## Revisão final: compatibilidade e limite de liberação

A prévia possui versões fixas: `1` consulta a partição treinador do grupo;
`2` consulta a conta no estado completo. Não renumera versões por deduplicação.
Ambas requerem identidade autenticada exata; ausência de correspondência não
prova inexistência de save. Não há vinculação automática entre LID e telefone.

PC é anexado ao fim da equipe, sem modificar índices existentes, UUIDs,
atributos, golpes ou Pokémon da enfermaria. A transformação aparece na prévia.
O hash da origem bruta é distinto do hash do destino transformado, armazenado
no diário; journals anteriores sem targetHash mantêm a comparação antiga.
XP fora do intervalo normalizado é bloqueado: a função vigente do treinador
altera nível e atributos ao converter excesso. Não inventamos uma conversão
silenciosa. A chave legada vitorias-desde-nivel é removida somente quando já
existe XP válido, conforme a hidratação atual. Backups preservam o original.

Validação verifica duplicação/ausência de identidades, coleções, nível, HP,
atributos, tipos, formato de golpes, shiny, item, mapas de Pokédex/ginásios,
valores numéricos do perfil, prazo da enfermaria e índice ativo. Isso não é
uma certificação semântica de todas as estruturas antigas: referências em
outros módulos continuam pendentes. Não gera UUIDs ou atributos faltantes.
Contas atuais e origens com eventos/recibos Adventure ou outros campos
operacionais são bloqueadas. Nenhum recibo de entrega é apagado.

Comandos disponíveis: `restaurar 1 previa`, `2 previa`, `backups`,
`desfazer ID previa`. As duas formas de confirmar retornam bloqueio sem
escrita, não divulgam token e não chamam o protocolo mutável. O gate de
produção permanece fechado incondicionalmente. Não há variável que contorne
as garantias pendentes. As prévias em memória expiram em dez minutos e têm
limite de 1000; após reinício devem ser refeitas.

### Diagnóstico supervisionado sem desbloqueio

`RestoreProtocol.diagnose(operationId)` é interno, somente leitura SERIAL.
Retorna estado, prova de recibo/hash/backup e ação recomendada, sem identidades
ou payloads. Não aparece no WhatsApp, não tenta CAS e não libera bloqueios.
Recibo integralmente confirmado permite usar reconcile pelo writer autorizado.
Sem recibo, até um hash igual ao estado antigo não prova ausência de CAS futuro
ou gravação externa: preservar bloqueio e backup e exigir investigação privada.
Não existe comando de force-unlock. Journals e evidências não são apagados.

### Impedimentos essenciais antes da primeira restauração real

1. Auditar referências de batalhas, trocas, ginásios e timers em módulos externos.
2. Definir preservação de recibos operacionais atuais na substituição e no desfazer.
3. Definir escopo de moedas/inventário: persistem em loja, não no treinador.
   Restaurar integralmente vários módulos requer outro protocolo de consistência;
   esta etapa não promete atomicidade entre partições.
4. Validar semanticamente golpes e referências de todos os formatos legados.
5. Confirmar operacionalmente que existe um único writer Pokémon autorizado.

O bloqueio é conservador por grupo: todos os jogadores do grupo ficam impedidos
de mutar treinador durante operação pendente/incerta. Outros grupos continuam
operando. Um serviço antigo ou writer externo não cooperante não é impedido
pelo código local; exclusividade de implantação é requisito operacional.

## Auditoria de referências externas

| Referência | Criação/persistência | Consumo | Restauração |
|---|---|---|---|
| Combates PvP, ginásio e raide | estados ativos e filas dos módulos de batalha | encerramento, vitória ou expiração | não são cancelados; a prévia aguarda estado livre |
| Caçada | estado de caçada e fila do chat | captura, fuga ou expiração | não é encerrada automaticamente |
| Troca, transferência e ofertas de golpes | campos/propostas persistidas | confirmação, expiração ou conclusão | campos pendentes bloqueiam |
| Enfermaria | `enfermaria` na conta, com `pronto-em` | retorno pela Joy | registro e prazo são preservados |
| Adventure Sync | `adventure-eventos` e `adventure-entregas` | drenagem idempotente e recibo de nascimento | eventos pendentes bloqueiam; recibos confirmados são preservados |

Essas referências externas não são substituídas pelo protocolo de treinador.
Nenhuma batalha, raid, timer ou operação pendente é encerrada para permitir a
restauração. A fila compartilhada e o bloqueio persistido impedem novas escritas
cooperantes; uma versão antiga do serviço que ignore o bloqueio continua sendo
um impedimento operacional.

A origem histórica substitui apenas perfil, progresso, Pokédex, insígnias,
equipe, PC e enfermaria validados. O recibo atual `adventure-entregas` é
preservado a partir da conta ativa. `adventure-eventos`, transferências, ofertas,
combate, caçada e raid pendentes bloqueiam; não são copiados do save antigo.
Loja, ranking, moedas, mochila e inventário permanecem intocados. O mesmo
caminho é usado no desfazer, sempre com novo backup.

### Procedimento para primeira execução supervisionada (ainda não autorizada)

Não instalar ou habilitar agora. Primeiro concluir os cinco impedimentos,
reexecutar testes isolados e revisar o diff. Depois obter autorização explícita,
verificar writer único e saúde, conferir origem/destino por identidade exata,
revisar prévia e transformações com o jogador. Confirmar uma única operação,
verificar backup imutável e recibo por SERIAL, sincronização do atom e diário
completed antes de liberar o grupo. Em falha, suspender tentativas, preservar
evidências e executar diagnóstico pelo adaptador autorizado. Desfazer exige
nova prévia, novo código e novo backup; nunca substituir diretamente pelo JSON.

## Validação executada em 14/10/2026

- Node.js: 77 testes, 77 aprovados, zero falhas.
- Domínio ClojureScript: 301 testes, 1.826 verificações, zero falhas/erros.
- Release: `npm run build` terminou com código 0; warnings existentes de
  inferência/depreciação continuam presentes.
- Cassandra 5.0.9 descartável: integração SERIAL/LWT aprovada, incluindo
  resposta CAS perdida, reinício, indexação de backup, imutabilidade, desfazer
  e isolamento. Foi encerrado somente o container local de teste.
- `git diff --check`: sem problemas.

Node/npm estão disponíveis no host atual; Java não está. Suíte de domínio e
release foram executados na imagem local de ferramentas, montando fontes,
testes, runtime, assets e shadow-cljs.edn atuais. Nenhuma ferramenta instalada
no host. Nenhuma conexão a Cassandra/VMs de produção, commit ou publicação.
Os testes provam as garantias descritas para cenários sintéticos cobertos;
não resolvem os impedimentos de habilitação acima.
