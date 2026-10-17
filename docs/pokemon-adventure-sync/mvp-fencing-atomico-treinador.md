# Fencing atômico do treinador — MVP interno

Status: implementação local, transições e certificações continuam desabilitadas.
Nenhum DDL ou dado foi aplicado em produção. A integração Cassandra desta
mudança ainda precisa ser executada em uma tarefa separada.

## Registro e barreira

O protocolo canônico do treinador passa de 1 para 2. A linha de cada jogador
em `canonical_trainer_accounts` possui, além da versão de conteúdo e hash:

| Campo | Semântica |
| --- | --- |
| `fence_generation bigint` | Época monotônica; avança em cada reserva, inclusive uma reserva abortada. |
| `active_save_id text` | Identidade do slot jogável a que pertence o estado. |
| `transition_owner text` | ID da operação que reservou a linha; string vazia significa liberada. |

`reserveTransition` condiciona o LWT à versão, hash, protocolo, época, save e
dono anteriores; no mesmo LWT avança a versão e a época, define o dono e grava
um recibo da reserva. Esse LWT é o ponto em que a barreira se estabelece.
O lock do coordenador em outra partição continua sendo uma reserva de recursos,
mas isoladamente não é uma barreira para o treinador. O caminho de ativação
exige o adaptador canônico e só prossegue depois desse LWT.

Uma escrita concorrente tem duas possibilidades: confirma antes da reserva,
fazendo a reserva baseada no snapshot anterior falhar, ou perde seu LWT depois
da reserva. A verificação prévia do lock não participa desta prova.

## Writers e callbacks

`compareAndSet` e `applyOnce` exigem contexto de identidade, época e save.
Seus LWTs incluem esses valores e o dono da reserva. Uma escrita normal não
pode usar um dono de transição. A ativação usa `applyOnce` com contexto reservado
e muda `active_save_id` junto com o treinador e o recibo do efeito.

O runtime captura o contexto antes do domínio, no ingresso HTTP e na fila de
comandos internos. `AsyncLocalStorage` preserva esse contexto em promises e
timers; uma chamada aninhada não o renova. Mesmo uma leitura nova da conta
não permite a um callback herdado adotar outra época. `saveGroup` sem contexto
é recusado, e verifica também o hash exato da base antes do CAS individual.
Novas contas no modo canônico ainda exigem uma entrada de provisionamento
certificada; `saveGroup` não as cria implicitamente.

Writers/timers fora de um contexto certificado falham fechados. Callbacks
reconstruídos após reinício não recebem permissão para reaplicar snapshots
antigos só porque o serviço pode ler a época nova. Journals novos de HP
carregam o contexto no payload persistido; journals antigos sem ele bloqueiam
novas mutações e precisam de revisão, sem inferência automática.

## Respostas perdidas e retomada

Reservas e liberações têm recibos distintos na própria conta. Após timeout,
uma leitura SERIAL precisa comprovar recibo, época e dono esperados; falha ou
ambiguidade mantém o bloqueio. O journal de ativação conserva a base da reserva.
O save de origem é salvo antes do efeito canônico, inclusive ao retomar uma
operação interrompida logo após reservar. A liberação só ocorre depois da
publicação confirmada do ponteiro e conserva a época avançada.

## Compatibilidade e pendências

O schema é aditivo. Linhas antigas sem os novos campos e a capability do
protocolo 1 são recusadas; não existe inicialização automática dos campos nem
fallback para `estado_particionado`. Uma migração offline supervisionada e
verificada é indispensável antes de publicar a capability 2. LWTs antigos
condicionados a `protocol_version=1` não alteram as linhas do protocolo 2.

Isso não impede um cliente com permissões amplas de emitir CQL arbitrário.
A revogação de credenciais incompatíveis, exclusividade dos writers, proteção
de rollback e corte operacional continuam obrigatórios. Nenhuma certificação
foi habilitada. O coordenador genérico ainda oferece locks para recursos
legados; ele não certifica seus writers pela mera aquisição desses locks.

A validação opt-in `npm run test:adventure-fence:cassandra` exercita incubação
com `save-fence` durável, reinício, entrega única, rejeição sob reserva,
isolamento A→B, LWT perdido e recibos globais em Cassandra 5.0.9 descartável.
Não habilita flags nem certificações.

Retomada após A→B→A (`runtime/adventure-hatch-resume.cjs`): o fence de
incubação permanece evidência imutável de `originSaveId` e não é reescrito por
callbacks antigos. A retomada é uma operação durável/auditável que só captura
a geração atual depois de provar origem, estado `delivery_pending`, ausência de
recibo global e ausência de reserva. B não pode reivindicar entrega de A.
Recaptura silenciosa da geração vigente pelo tick antigo continua proibida.

O path CLJS `adventure.cljs` registra o claim em toda entrega canônica
(`registrar-nascimento-pendente!`), entrega com o fence de incubação e, se a
geração mudou, chama `recuperar-nascimento!`/`retomar-nascimento!` só quando
`origem-save-ativa?`. Ovos sem `save-fence` no modo canônico ficam adiados
(origem improvável). Flags e certificações seguem desligadas.

A validação opt-in `npm run test:adventure-birth:cassandra` (após
`npm run build`) exercita o fluxo real `domain.cjs` com incubação em A,
pendência após A→B, retomada em B→A, reinício entre claim e entrega, recibos
globais e isolamento de outro jogador do grupo. A ativação A↔B usa o adaptador
Node `PlayableSaveActivation` (ainda não exportado no domínio HTTP); o domínio
recarrega os atoms após cada troca. Não habilita flags nem certificações.

Na corrida concorrente de ativação jogável, `duplicateFenced=false` com
`concurrentSingleWinner=true` significa que o perdedor reconheceu o alvo já
ativo (`idempotent_already_active` via `finishWithoutActivation`), não que o
fencing tenha falhado. Rejeições por reserva, journal pendente ou fence são
classificadas em `duplicateOutcome` sem forçar `duplicateFenced=true`.

Continuam pendentes a auditoria completa dos timers reconstruídos, a
hidratação dos caches na futura ativação real, provisionamento de novos
treinadores e a coordenação plena Adventure global vs coleção por save.
`save-transition-writer-coverage-verified?` e a certificação das batalhas
canônicas permanecem `false`.
