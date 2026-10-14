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

## Instalação do executor e ativação posterior

Na VM Pokémon, como `root`, instale os arquivos pelo instalador versionado:

```sh
sudo bash scripts/install-deploy.sh pokemon /caminho/absoluto/do/repositorio caioclavico
```

Depois que a imagem compatível e os dois registros root-owned forem validados,
a operação explícita é:

```sh
ssh -i "$KEY" -o IdentitiesOnly=yes zapbot-deploy@HOST activate-restore
```

O comando é aceito somente pelo wrapper do serviço `pokemon`; o wrapper do Odisseu
recusa a mesma operação. Não execute esse comando como parte de um deploy comum.

## Liberação global da confirmação

O serviço lê `POKEMON_RESTORE_ENABLED` na inicialização. O padrão do projeto é
`false`; somente `true` habilita explicitamente a confirmação para jogadores
autenticados. Qualquer outro valor é inválido e impede a inicialização. A variável não pode ser
alterada por mensagem, endpoint ou comando WhatsApp. Após alterar a configuração,
é necessário reiniciar o serviço de forma controlada e validar a saúde antes de
aceitar confirmações. Para desativar em incidente, defina `false` e reinicie; isso
não desfaz operações já confirmadas, que devem ser reconciliadas pelo journal.

Um administrador root que remova deliberadamente os dois registros ainda pode
desativar a âncora local; esta proteção não substitui controle de acesso, auditoria
externa e backups da VM.
