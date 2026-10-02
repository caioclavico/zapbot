# Medições do comando de treinador

## Diagnóstico de fila Pokémon

Todos os comandos `!pk` e `!pokemon` agora recebem contexto de medição. O campo
`comando` contém apenas um rótulo fixo (`pokemon` ou `pk treinador`), sem os
argumentos enviados pelo jogador. As raides automáticas também são medidas,
mas só geram logs em caso de erro ou duração de pelo menos 30 segundos.

`fila_pokemon` significa espera pela rodada anterior do mesmo chat;
`aguardando.id` aponta para o identificador da operação anterior. Ela pode ser
outro comando ou `raid_automatica`. `rodada_pokemon` significa que a operação
já adquiriu sua vez. As etapas internas incluem `recolher_curados`,
`xp_raid_evolucao`, `comando_pokemon`, `turno_lider`, `persistencia_combate` e
`persistencia_modulos`. Desafios de ginásio detalham `ginasio_nome`,
`ginasio_adversarios` e `ginasio_imagem_envio`. Raides detalham `raid_pokemon`,
`raid_golpes`, `raid_imagem` e `raid_envio`.

Para consultar as operações pendentes **sem enviar novos comandos ao WhatsApp**:

```bash
sudo docker exec zapbot node -e 'const http=require("node:http"); const r=http.get("http://127.0.0.1:3001/diagnostics",s=>s.pipe(process.stdout)); r.setTimeout(5000,()=>r.destroy(new Error("timeout"))); r.on("error",e=>{console.error(e.message);process.exitCode=1})'
```

Esse endpoint usa o servidor interno existente, sem publicar portas. Não acessa
Chromium, Cassandra ou credenciais: consulta apenas os contextos em memória.
Eles são retirados quando terminam; os identificadores recomeçam a cada processo.
O `/health` mantém sua semântica de conexão e não garante que a fila esteja livre.

O diagnóstico **não destrava nem cancela operações**, não executa jogadas fora
de ordem e não muda as regras do jogo. Esta versão permite localizar a espera;
não constitui uma correção confirmada para o incidente. Um deploy reinicia o
processo e perde as filas em memória; não reenvie desafios antigos em massa.

Para rollback, use a imagem anterior com o mesmo Compose, `.env`, volumes e
override de hostname descritos em `estabilidade-whatsapp.md`. Não remova a sessão.

Se o deploy detectar parada/reinício inesperado e fizer rollback, os últimos
300 registros do container ficam em `releases/<commit>/deploy-failure.*`,
com permissão `0600`, antes da recriação. Esse arquivo pode conter mensagens
ou QR Code: consulte na VM e não publique seu conteúdo integral. O timeout
de inicialização continua preservando o container, sem rollback por demora.

## Tempos de treinador

### Investigação de mídia — 2 de outubro de 2026

Teste real de `!pk time`: 44.196 ms no Node, sendo 13.998 ms de processamento
e 29.695 ms de envio. Espera na fila Pokémon: 7 ms. O teste seguinte em texto
terminou em 1.505 ms, com 163 ms de envio. São amostras individuais, com carga
variável na VM; não representam uma comparação controlada de capacidade.

Conversão isolada de buffers sintéticos na mesma VM: 1 MiB levou de 1,158 a
1,367 ms; 4 MiB, de 4,718 a 6,658 ms. Uma amostra de 256 KiB levou 90,783 ms
(as outras duas ficaram abaixo de 1 ms). A transferência de uma string base64
para o Chromium já aberto, retornando só seu comprimento, levou 906 ms para
256 KiB de entrada e 2.595 ms para 1 MiB. Não houve envio de mensagem nesse teste.
Esses tempos não incluem preparação da mídia nem upload pelo WhatsApp.

A mídia de saída registrada no WhatsApp às 17:37:50 UTC tinha 171.000 bytes.
Esse é o tamanho após processamento no WhatsApp, não o tamanho confirmado do
PNG original. A conversão Node para base64 não parece explicar sozinha a demora;
ainda falta separar o processamento de mídia dentro do navegador do upload.

O cartão da coleção passa a medir `time_sprites` (download e resize juntos),
`time_svg`, `time_png` (composição e codificação) e `imagem_base64` separadamente.
O campo `midias` registra somente `bytes` do PNG original e `base64_chars`,
sem conteúdo, nomes, destinatários ou credenciais. Tempos por etapa são
arredondados em milissegundos; zero pode representar duração menor que 0,5 ms.
O envio de uma imagem agora distingue `envio_midia`, `envio_texto_extra`
(quando a legenda é longa) e `envio_texto_fallback` (após erro da mídia).
`envio_midia` ainda inclui o trabalho interno do WhatsApp e o upload.

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
