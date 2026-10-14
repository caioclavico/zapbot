# Auditoria de writer único

Esta auditoria é somente de código e de procedimento. Nenhuma VM ou Cassandra
de produção foi acessada nesta etapa.

## Resultado do código

O único writer atual da partição `treinador` é o domínio do
`pokemon-service`. Todas as mutações do módulo passam por `treinador/persistir!`
(`armazenamento/salvar!`) ou por `armazenamento/alterar-treinador-confirmado!`
para operações que exigem CAS, como entrega de nascimento. O serviço principal
do WhatsApp usa `src/zapbot/pokemon_http.cljs`: ele encaminha comandos e eventos
por HTTP e não registra o módulo `treinador` nem abre um cliente Cassandra para
essa tabela.

As filas são por chat no Pokémon e a persistência é serializada por módulo.
Durante restauração, `com-writer-restauracao!` compartilha a fila do treinador,
o bloqueio durável é gravado no registro de controle e `salvar!` rejeita
snapshots que não correspondam ao estado confirmado. Uma callback atrasada não
pode substituir a conta restaurada com o snapshot antigo.

O container Pokémon usa `tini`, um único `node runtime/main.cjs`, usuário
`node`, `restart` controlado pelo deploy e readiness dependente da reconciliação.
O deploy para o container anterior antes de iniciar o substituto e verifica
health/readiness estáveis; rollback restaura a imagem anterior pelo mesmo
executor. O compose do Odisseu principal não carrega o domínio ClojureScript
Pokémon e o workflow constrói/publica os serviços separadamente.

## O que não pode ser provado pelo repositório

Não é possível provar por código que uma VM antiga ou um processo iniciado fora
do compose não esteja gravando Cassandra. O bloqueio é cooperante e não é um
lock distribuído de processo. A restauração deve permanecer desabilitada até a
verificação operacional abaixo ser executada nas duas VMs.

## Diagnóstico somente leitura

Executar como administrador, sem imprimir `.env`, tokens ou valores de
`CASSANDRA_*`:

```sh
# Em cada VM: containers, imagem, revisão, reinícios e mounts.
docker ps --no-trunc --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker inspect --format 'name={{.Name}} image={{.Image}} status={{.State.Status}} restarts={{.RestartCount}} pid={{.State.Pid}}' zapbot zapbot-pokemon 2>/dev/null
docker inspect --format '{{range .Mounts}}{{.Type}} {{.Destination}} rw={{.RW}}{{"\n"}}{{end}}' zapbot zapbot-pokemon 2>/dev/null

# Processos fora de containers; não mostra argumentos completos.
pgrep -a -f 'node|pm2|pokemon|zapbot' | sed -E 's/(TOKEN|KEY|SECRET|PASSWORD)=([^ ]+)/\1=<redacted>/g'
systemctl list-units --type=service --state=running --no-legend | grep -Ei 'zapbot|pokemon|pm2|docker'

# Oracle: procurar apenas indícios de clientes Cassandra em fontes instaladas.
docker exec zapbot sh -c 'find /app/src /app/scripts -type f 2>/dev/null | grep -E "pokemon|cassandra" | head -50'
# Google: confirmar o processo único dentro do container.
docker exec zapbot-pokemon sh -c 'pgrep -a node; test "$(pgrep -cx node)" = 1'
```

No Odisseu, espera-se ausência de `node`/PM2 executando o domínio Pokémon ou
de um cliente Cassandra para `treinador`. Na VM Pokémon, espera-se exatamente
um processo Node no container autorizado e nenhum segundo container/serviço
com `estado_particionado` ou código de persistência. Qualquer resultado
ambíguo bloqueia a habilitação.

## Checklist antes da primeira restauração

1. Registrar nomes, IDs completos, imagens e revisões de todos os containers
   nas duas VMs.
2. Confirmar que existe somente um `zapbot-pokemon` ativo e que o Odisseu só
   usa o cliente HTTP.
3. Confirmar que não há serviço systemd/PM2 antigo, cron ou container parado
   sendo reiniciado automaticamente com o domínio antigo.
4. Conferir que a revisão ativa do Pokémon é a esperada e que o healthcheck e
   `/ready` estão aprovados após reconciliação.
5. Durante deploy, observar que o container antigo termina antes de o novo
   receber tráfego; em rollback, verificar a mesma propriedade.
6. Só depois revisar uma prévia sintética e habilitar uma operação supervisionada
   em ambiente isolado. A confirmação de produção permanece fechada até todos
   os itens serem comprovados.

Não executar `docker stop`, `docker rm`, `docker compose`, `systemctl restart`,
DDL Cassandra ou comandos de escrita como parte deste diagnóstico.

## Barreira de rollback após habilitação

As imagens agora carregam o label imutável
`io.zapbot.restore-protocol=1`. O executor de deploy verifica, antes de
qualquer start de deploy ou rollback, o marcador root-owned
`/etc/zapbot-deploy/pokemon/restore-protocol-required`. Enquanto o marcador não
existe, a política de rollback normal não muda. Depois de uma habilitação
supervisionada, o operador deve criar o marcador com conteúdo exatamente `1` e
modo `0600`; imagens sem o label são rejeitadas antes de iniciar o container.
Falha, ausência ou conteúdo inválido do marcador também bloqueia a operação.

Procedimento controlado, somente após aprovação explícita e backup do estado do
deploy:

```sh
install -o root -g root -m 0600 /dev/null /etc/zapbot-deploy/pokemon/restore-protocol-required
printf '%s\n' 1 > /etc/zapbot-deploy/pokemon/restore-protocol-required
```

Esse arquivo não deve ser criado agora. A verificação é externa ao container,
não depende de uma flag que uma imagem antiga possa ignorar e sobrevive a
reinicialização. Remover ou alterar o marcador exige revisão administrativa;
rollback de imagem sem suporte não é uma recuperação segura.
