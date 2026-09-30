# Medições do comando de treinador

`!pk treinador`, `!pokemon treinador` e o atalho `tre` geram logs
`[Desempenho]` com um identificador numérico por chamada. Não são registrados
nome, telefone, chat, mensagem, URL de sprite ou credenciais.

Há um registro `inicio` e um `fim` (ou `erro`). Se demorar mais de 30s,
um único registro `pendente_30s` informa as etapas ainda em execução; ele não
cancela, reinicia nem limita o comando. Exceções tratadas pelo fallback continuam
tratadas e aparecem em `falhas`. A medição não adiciona cache, fila ou concorrência.

| Campo em `etapas_ms` | O que mede |
|---|---|
| `historico` | Registro/fila do histórico antes do router |
| `processamento` | Router e preparação da resposta, incluindo as etapas da imagem |
| `dados_memoria` | Leitura do perfil e Pokémon ativo no estado em memória |
| `nome_whatsapp` | Resolução do nome usado na ficha |
| `imagem_total` | Preparação completa do PNG |
| `sprite_download` | Download do sprite, incluindo fallback entre hosts |
| `sprite_resize` | Redimensionamento e codificação do sprite do Pokémon |
| `ash_resize` | Leitura/redimensionamento do recurso local do treinador |
| `composicao_png` | Composição final com Sharp e codificação PNG |
| `envio` | Promessa de envio pelo WhatsApp, incluindo eventual fallback textual |

`total_ms` começa quando o Node recebe o evento e termina quando a promessa do
handler é concluída. Não inclui o tempo anterior ao evento nem comprova que o
celular já exibiu a imagem. Cronometre também pelo celular para comparar.
`processamento` contém `imagem_total`; etapas de sprites podem ocorrer em
paralelo. **Não some todas as etapas**, pois há sobreposição.

A leitura do perfil usa o estado já carregado em memória. Não foi criado um
cronômetro fictício de consulta Cassandra; não há consulta direta nessa etapa.
As medições usam relógio monotônico e contexto explícito por mensagem, mantido
em WeakMap e retirado ao terminar. O contexto explícito evita mistura entre
chamadas concorrentes no agendador de promises do projeto.

## Coleta após instalar a imagem nova

Aguardar READY antes de testar:

```bash
sudo docker exec zapbot node scripts/healthcheck.js
sudo docker logs --since 10m zapbot 2>&1 | grep '\[Desempenho\]'
```

Enviar três `!pk treinador`, um por vez, esperando cada resposta. Registrar a
primeira chamada separadamente das seguintes: aquecimento e swap podem afetá-la.
Durante os testes, consultar:

```bash
sudo docker stats --no-stream zapbot
vmstat 2 5
```

Usar as linhas de `vmstat` após a primeira (a primeira é média desde o boot).
Comparar `us/sy` (CPU executando), `wa` (espera por I/O), `st` (CPU indisponível
para a VM) e `si/so` (tráfego de swap). Não atribuir diferenças automaticamente
à GPU quando o tempo de CPU disponível também varia.

## GPU reversível

A imagem Docker passa a definir `CHROMIUM_DISABLE_GPU=true`. A aplicação então
acrescenta somente `--disable-gpu` às flags anteriores. O teste existente na VM
usava um wrapper temporário; após o deploy, esse wrapper não é mais necessário.
Não foi desativado SwiftShader nem removido qualquer processo Chromium.

Para voltar às flags anteriores, definir no `.env` da VM:

```dotenv
CHROMIUM_DISABLE_GPU=false
```

Depois é necessário recriar apenas o bot com o Compose da release e o override de
hostname. `docker restart` não recarrega as variáveis do `.env`. Não alterar o
bind de `.wwebjs_auth` nem limpar locks. Fora da imagem Docker, a opção é false
se a variável não estiver definida.

## Estado da comparação anterior

Teste manual com a mesma imagem e `--disable-gpu`: 11s, 23,40s e 6,20s;
referência anterior disponível: 36,81s. Houve restart e oscilação de CPU/swap,
portanto esses números não isolam causalmente o efeito da GPU. A instrumentação
foi criada para orientar a próxima otimização com evidência por etapa.
