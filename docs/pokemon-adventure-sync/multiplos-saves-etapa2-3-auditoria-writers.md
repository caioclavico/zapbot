# Etapa 2.3 — auditoria de writers de ginásios e batalhas

## Resultado

Esta etapa **não está apta para liberar alternância de saves**. A auditoria encontrou writers atuais que não participam de fencing. Um lock durável de treinador não os impede de gravar, e a confirmação de cobertura foi fixada como `false` no código para que a variável `SAVE_TRANSITIONS_ENABLED` sozinha não habilite uma transição.

Não foi implementada a retirada administrativa de defensores: sem exclusão compatível entre o writer do ginásio e os callbacks de batalha, apagar ou substituir uma ocupação poderia ser revertido por um snapshot atrasado. Também não se concedeu recompensa nem se alterou estado de jogo.

## Inventário encontrado

| Caminho | Persistência atual | Corrida/bypass encontrado | Situação para transição |
| --- | --- | --- | --- |
| Conta/coleção do treinador | `treinador`, partição por grupo em `estado_particionado`; CAS no adapter legado ou linha por jogador no store canônico opcional | O adapter de CAS consulta lock em outra linha e depois aplica LWT na partição; não é atômico com o lock. `CanonicalTrainerStore.saveGroup` usa geração/hash, mas não verifica a reserva de transição na mesma mutação. | Bloquear. O CAS protege contra snapshot obsoleto da mesma linha; não prova exclusão com o coordenador. |
| Ocupações e times defensores | `ginasios`, snapshot por grupo, via `salvar!` incondicional | Batalhas guardam cópia do líder e podem chamar `ocupar!` ao finalizar. Escrita completa do snapshot pode reintroduzir/remover defensores e sobrescrever outro resultado. | Bypass confirmado. |
| Permanência, defesas e histórico | `ginasios-estatisticas`, snapshot por grupo, via `salvar!` incondicional | `registrar-permanencia!` e `registrar-resultado!` alteram o atom; a gravação não usa versão, CAS ou fence. | Bypass confirmado; não deve ser reescrito pela limpeza de uma ocupação. |
| Batalhas PvP/ginásio | Atom `jogos`; `add-watch` grava `pokemon-batalhas-ativas` por chat | Turnos/callbacks continuam após a leitura inicial; timers de expiração também alteram o atom. Não há geração de operação verificada no ponto Cassandra. | Bypass confirmado. |
| Caçadas/capturas | Atom `cacadas-selvagens`; `add-watch` grava `pokemon-cacadas-selvagens` por chat; resultados atualizam treinador/loja/eventos | Callback de batalha/captura pode terminar depois de uma reserva e persistir usando o caminho normal. | Bypass confirmado. |
| Raids manuais e resultados | `raids`, snapshot por partição, via `salvar!`; recompensas também alteram treinador e loja | O estado do raid e as recompensas estão em módulos/partições separados; não há protocolo transacional comum. | Bypass confirmado para a coordenação de transição. |
| Loja, moedas e inventário | `loja`, snapshot por grupo, via `salvar!` | Recompensas de batalha/ginasio e operações de compra podem gravar snapshots sem fence de conta. | Bypass confirmado para operações vinculadas ao save. |
| Adventure/ovos | Estado e recibos próprios do módulo Adventure; callbacks entregam efeitos ao treinador | Recibos são duráveis/idempotentes dentro do módulo, mas o efeito no treinador é outra escrita. | Requer fence comum ou outbox reconciliável antes da troca. |
| Timers e callbacks | Watches e timers em `core.cljs`; timer periódico Adventure | O callback pode acordar com estado capturado antes de outra operação. Timers não são, por si, uma barreira Cassandra. | Bypass confirmado até cada efeito entrar no mesmo protocolo. |

O bot WhatsApp encaminha comandos Pokémon ao serviço independente; os writers de domínio encontrados estão em `pokemon-service`. Isso não prova que uma instância antiga do próprio serviço esteja ausente. A exclusividade operacional de processos em VMs diferentes não pode ser verificada sem consultar os ambientes, o que não foi feito.

## Por que o lock atual não é fencing efetivo

O coordenador registra locks/journal com LWT, mas seus `writeFenced` primeiro lê o lock e depois escreve a linha-alvo em outra partição. O lock pode mudar entre essas operações. De modo semelhante, `trainer-state-store.saveGroup` lê o lock e então executa CAS na linha `treinador`; a condição LWT protege o valor anterior da partição, não o proprietário/generation do lock. Um writer legado ainda faz upsert incondicional. Portanto não existe uma única condição Cassandra que ordene a aquisição da reserva contra todas as mutações atuais.

Os testes de integração anteriores provam que um upsert legado pode gravar com o lock adquirido. Esse comportamento permanece um impedimento observado, não um cenário resolvido. Adicionar checagem local ou `SELECT` prévio aos writers não fecharia TOCTOU nem protegeria versões antigas.

## Barreira fail-closed adicionada

`SaveTransitionCoordinator` agora exige também `writerProtocolCoverageVerified`. A configuração de runtime passa um valor literal `false` em código, não controlado por `.env`. Com isso, `SAVE_TRANSITIONS_ENABLED=true` não é suficiente para iniciar `begin`, `reserve`, `writeFenced` ou `release`. Testes unitários podem injetar a certificação para exercitar o protocolo isoladamente; isso não certifica os writers do domínio.

As flags de Adventure e transições permanecem no padrão documentado existente. Nenhuma flag de produção foi lida ou alterada.

## Próximas condições para retomar

1. Mover ocupações, estatísticas e demais estados associados para armazenamento canônico versionado, com credenciais sem escrita legada para o novo estado.
2. Fazer batalha, caçada, raid, loja e callbacks persistirem efeitos com versão/fence válido na operação efetiva; rejeitar callbacks com geração antiga.
3. Criar protocolo de operação que serialize mutações por jogador e por ginásio e reconcilie efeitos entre partições sem afirmar atomicidade Cassandra.
4. Só então implementar a retirada administrativa idempotente, sem recompensa, mantendo histórico e com plano de recuperação.
5. Testar as versões de deploy/rollback e impedir que uma versão sem suporte volte a ser writer. Um lock local ou apenas variável de ambiente não atende isso.
6. Executar Cassandra descartável com cenários de callback atrasado, timeout/LWT ambíguo e reinício em cada fase.

Até essas condições passarem, qualquer restauração/alternância que substitua o treinador ou mexa em ocupações precisa continuar bloqueada.
