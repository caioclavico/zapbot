# Deploy manual do Odisseu A6

Execute na VM `odisseu-core` com um checkout deste repositório. Use o SHA
completo do commit aprovado da branch do A6.

```bash
bash -n scripts/deploy-odisseu-a6.sh
bash scripts/deploy-odisseu-a6.sh build <SHA>
sudo bash scripts/deploy-odisseu-a6.sh deploy <SHA>
bash scripts/deploy-odisseu-a6.sh status
```

O comando `build` compila Node e JVM para linux/amd64 e mantém os serviços ativos.
O comando `deploy` compara imagens, variáveis do Compose e montagens com os
containers atuais antes da parada. Ele precisa de sudo para ler o arquivo de
ambiente protegido. As verificações de manutenção usam os tokens dos containers.

O deploy para o Node primeiro, depois o JVM, e confirma que os volumes Docker
estão livres antes de gerar a cópia fria. O backup fica em
`/var/backups/odisseu-a6/`, com as referências anteriores e SHA-256 do arquivo.
Os arquivos do Compose anteriores permanecem no lugar. O script inclui um
override só para as imagens e usa os mesmos volumes ao recriar os containers.

A ordem de retomada é JVM saudável, JVM aberto, Node saudável e Node aberto.
O comando só imprime `DEPLOY_OK` após confirmar WhatsApp `READY`. Em caso de
falha durante a cópia fria, o script tenta religar os containers existentes. Se
a troca já começou, ele informa onde parou e mantém o backup para diagnóstico.

Teste `!pk ajuda` no WhatsApp após a conclusão.
