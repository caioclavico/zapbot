# Ativação controlada da proteção de rollback

O deploy mantém a política fora do container, em `/etc/zapbot-deploy/pokemon/`.
Antes da ativação, a ausência dos dois registros (`restore-protocol-state.json` e
`restore-protocol-required`) preserva o comportamento legado. Depois da ativação,
ambos precisam existir, ser arquivos regulares privados do usuário do instalador e
conter exatamente os valores esperados. A perda, corrupção, troca de proprietário,
permissão excessiva ou symlink de qualquer registro bloqueia deploy e rollback.

## Instalação e ativação supervisionada

1. Instale a versão revisada de `deploy-service.py` no caminho root-owned usado pelo
   wrapper da VM e valide proprietário/permissões.
2. Confirme, somente por leitura, que o container Pokémon saudável anuncia a label
   `io.zapbot.restore-protocol=1` e que não há `transaction.json` pendente.
3. Em uma janela supervisionada, chame `Deployment.activate_restore_protocol` com a
   imagem ativa já validada. O método grava primeiro o registro JSON e depois o
   marcador, usando `fsync` e `rename` atômico; uma interrupção entre as etapas deixa
   o estado inconsistente e bloqueia conservadoramente o próximo comando.
4. Leia novamente os dois registros com o mesmo usuário root e execute o teste de
   compatibilidade antes de qualquer deploy.

Este procedimento não habilita `pk restaurar confirmar` e não altera Cassandra,
contêineres, volumes ou saves. A confirmação real permanece bloqueada no serviço.

Um administrador root que remova deliberadamente os dois registros ainda pode
desativar a âncora local; esta proteção não substitui controle de acesso, auditoria
externa e backups da VM.
