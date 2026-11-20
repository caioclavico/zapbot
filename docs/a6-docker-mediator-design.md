# Mediador Docker A6

## Matriz de operações

| Operação atual | Classe | Estado futuro |
| --- | --- | --- |
| `docker inspect`, health e imagem | Consulta Docker privilegiada | Mediador devolve somente estado, mounts, health, IDs e chaves de ambiente |
| `docker exec cat` de marcadores | Consulta Docker privilegiada | Somente quatro caminhos fixos por serviço |
| `docker exec ps` | Consulta Docker privilegiada | Somente JVM, formato fixo |
| Materialização de `env-file` em `/dev/shm` | Segredo e ambiente | Runner sem privilégio, tmpfs `0700` e limpeza por guardião |
| `stop`, `rename`, `update --restart=no`, `start` | Alteração de container | Identificador de serviço e nome de transição validados |
| `docker run` de substituição | Alteração de container | Contrato de recriação a implementar no mediador, sem argv do cliente |
| Backup | Operação administrativa | Helper root-owned existente, destino e janela validados |
| Hold e diário | Leitura e escrita não privilegiada | Permanecem no runner, com fsync e recuperação |

## Contrato de entrada

O mediador lê um documento JSON contendo `op` e os parâmetros da operação.
Ele reconstrói a chamada Docker e não aceita shell, lista de argumentos,
`--privileged`, namespaces host, Docker socket, mounts livres, prune ou remoção
de volumes. Imagens consultadas exigem tag A6 e digest SHA-256.

O usuário futuro `odisseu-ops` recebe apenas os entry points root-owned do
mediador e do helper de backup. Ele não pode pertencer a `google-sudoers`,
`docker` ou `lxd`. `caioclavico` permanece administrador de emergência.

## Recuperação antes da mudança de identidade

1. Criar uma segunda identidade administrativa independente.
2. Validar duas sessões SSH e recuperação GCE antes de remover qualquer grupo.
3. Instalar e ensaiar o mediador em VM descartável com o ciclo completo.
4. Migrar a recriação para contrato estruturado e testar SIGKILL após cada
   operação de estado.
5. Aplicar a identidade operacional apenas em janela administrativa separada.

Nenhuma dessas etapas é aplicada na VM de produção por esta alteração.
