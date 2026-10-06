# Recursos do Chromium e Node no Odisseu

O coletor agora também exige `PERFORMANCE_METRICS=true`. O padrão global é
`false`; veja [controle de métricas](performance-metrics.md). A flag local
`ODISSEU_RESOURCE_METRICS_ENABLED` continua disponível.

## Escopo e resultado da revisão

Esta revisão prepara diagnóstico para o Odisseu em Linux/Docker, considerando
a VM informada com aproximadamente 2 vCPUs, 1 GB de RAM e 4 GB de swap. Foram
analisados o código local e as dependências instaladas. Não houve acesso à VM,
deploy, alteração de processos ou sessão de produção, nem medição de produção.

Não há redução de CPU/RAM comprovada. O ganho concreto é a instrumentação para
separar consumo do Node, árvore do Chromium, atraso do event loop e latência dos
comandos. O modo conservador explicita três opções que o Puppeteer instalado já
usa. A configuração não altera gameplay, sessão, comandos ou limites Docker.

## Configuração conservadora e reversão

| Variável | Default | Efeito |
|---|---|---|
| `CHROMIUM_LOW_RESOURCE_MODE` | `true` | Acrescenta as três flags explícitas abaixo |
| `ODISSEU_RESOURCE_METRICS_ENABLED` | `true` | Liga o coletor passivo do Odisseu |
| `ODISSEU_RESOURCE_METRICS_INTERVAL_MS` | `60000` | Intervalo entre coletas; limitado entre `15000` e `3600000` ms |

As flags explícitas são `--disable-extensions`, `--disable-default-apps` e
`--no-first-run`: desativam extensões, instalação de apps padrão e assistentes
iniciais do navegador. Seus significados estão no
[guia de flags do Chrome para ferramentas](https://github.com/GoogleChrome/chrome-launcher/blob/main/docs/chrome-flags-for-tools.md).

O [ChromeLauncher do Puppeteer 24.38.0](https://github.com/puppeteer/puppeteer/blob/puppeteer-v24.38.0/packages/puppeteer-core/src/node/ChromeLauncher.ts)
já inclui as três opções por padrão. Portanto, `CHROMIUM_LOW_RESOURCE_MODE=false`
remove somente as três entradas explícitas da aplicação: os defaults do
Puppeteer continuam ativos. Nesta versão, esse toggle não cria um experimento
com flags efetivas diferentes nem fundamenta uma promessa de economia.

`CHROMIUM_DISABLE_GPU` mantém seu valor operacional independente. Executável,
`LocalAuth`, perfil persistente, opções de QUIC e `protocolTimeout=300000`
permanecem conforme a configuração existente. Não há limitação nova de
renderers, descarte de serviços do navegador ou mudança de heap, swap e volumes.

Para uma reversão operacional futura, os valores relevantes são:

```dotenv
CHROMIUM_LOW_RESOURCE_MODE=false
ODISSEU_RESOURCE_METRICS_ENABLED=false
```

Essa configuração exige autorização para ser aplicada à produção. Os valores
são lidos ao iniciar o processo; editar `.env` não altera o Env de um container
em execução. A rotina de deploy preserva o Env efetivo, portanto o operador
precisa planejar a recriação autorizada com a configuração desejada, preservando
sessão, montagens e recursos. Não é necessário alterar o Pokémon ou Cassandra.

## Ciclo de vida e riscos identificados

[core.cljs](../src/zapbot/core.cljs) guarda uma única construção de Client e
tentativa de `initialize()` por processo, inclusive após erro. Os avisos de
startup são diagnósticos; não criam navegadores. A aplicação não adiciona um
ciclo de reconexão. Em SIGTERM/SIGINT, para o coletor e o polling Pokémon,
fecha o servidor local e chama `Client.destroy()`, com limite de 45 segundos.

Há uma diferença preexistente entre sinais: o Puppeteer também instala handlers
por padrão. Seu [launcher em SIGINT](https://github.com/puppeteer/puppeteer/blob/puppeteer-v24.38.0/packages/browsers/src/launch.ts)
mata o browser e chama `process.exit(130)`, podendo interromper a limpeza
assíncrona da aplicação após Ctrl+C. O limite de 45 segundos da aplicação não
garante essa janela em SIGINT. O launcher trata SIGTERM chamando `close()`;
essa revisão não alterou handlers nem testou sinais em produção.

A análise do
[Client fixado no lockfile](https://raw.githubusercontent.com/wwebjs/whatsapp-web.js/b0a4b6c6c10868fad4881fb484b97895ce898b5d/src/Client.js)
identificou possibilidades que exigem observação, sem comprovar vazamento:

| Caminho | Consequência possível |
|---|---|
| Chromium lançado antes de atribuir `this.pupBrowser` | Uma falha intermediária em configuração da página pode deixar um browser sem referência no Client |
| `initialize()` rejeita após o browser existir | O catch da aplicação registra ERROR e mantém Node/servidor vivos; não fecha o browser automaticamente |
| `destroy()` encontra `isConnected()` falso | A biblioteca pula `browser.close()`; desconexão CDP não comprova término do processo Linux |
| Biblioteca reinjeta conteúdo após navegação | Isso reutiliza a página e não demonstra, por si só, lançamento de outro browser |

`destroy()` e logout têm efeitos diferentes. A rotina da aplicação preserva a
autenticação; [LocalAuth.logout() remove o diretório da sessão](https://docs.wwebjs.dev/authStrategies_LocalAuth.js.html).
Uma revogação real pelo WhatsApp segue o comportamento da biblioteca.

O [verificador de lembretes](../src/zapbot/lembretes.cljs) tem guarda para um
único intervalo, mas continua após desconexão/falha de autenticação. Um lembrete
vencido cujo envio falha pode ser tentado novamente a cada segundo. Isso pode
gerar trabalho/logs durante uma indisponibilidade; seu ciclo não foi modificado
nesta revisão. O polling Pokémon já é interrompido nesses eventos.

O Chromium utiliza vários processos por arquitetura; quantidade de processos
isoladamente não demonstra duplicação de Client ou leak. Compare PIDs,
parentesco, tempo de início e evolução entre amostras. Consulte a
[arquitetura multiprocesso do Chromium](https://www.chromium.org/developers/design-documents/multi-process-architecture/).

## O que o coletor registra

[recursos.cljs](../src/zapbot/recursos.cljs) inicia um coletor por processo,
mantém a coleta durante desconexões para observar resíduos e o encerra antes
de destruir o Client na parada. O helper
[odisseu-resource-monitor.cjs](../scripts/lib/odisseu-resource-monitor.cjs) usa
leituras assíncronas de `/proc`; não executa shell, CDP, reconexão ou sinais.
Os timers não mantêm o processo vivo por conta própria. O custo do coletor é
adicional e ainda não foi quantificado na VM.

`collection_ms` mede a duração total assíncrona, incluindo espera de I/O;
não é uma medida isolada de CPU gasta pelo coletor. A próxima coleta é agendada
após terminar a atual, portanto `window_ms` pode superar `interval_ms`.

As linhas `[RecursosOdisseu]` contêm JSON agregado. `/diagnostics`, em
`127.0.0.1:3001` dentro do container, expõe o último snapshot em `recursos`;
consultar esse endpoint não inicia outra varredura. Antes da primeira coleta
ou com o coletor desabilitado, essa chave pode estar ausente.

| Campos | Interpretação |
|---|---|
| `timestamp`, `interval_ms`, `window_ms`, `collection_ms` | Horário, intervalo configurado, janela efetiva e duração da coleta |
| `node.rss_bytes`, `heap_used_bytes`, `heap_total_bytes`, `external_bytes`, `array_buffers_bytes` | Memória do processo Node; heap é somente V8 e `array_buffers_bytes` já integra `external_bytes` |
| `node.cpu_percent` | CPU user+system na janela; `100%` equivale a um núcleo, podendo superar 100% |
| `event_loop.*_ms`, `samples`, `resolution_ms` | Histograma de atraso com resolução de 100 ms; valores brutos incluem o intervalo de amostragem |
| `event_loop.lag_p95_ms` | Aproximação `max(0, p95_ms - 100)` do atraso adicional; não mede tempo de CPU |
| `command_latency.count`, `mean_ms`, `max_ms` | Latências de operações com prefixo concluídas na janela; inclui processamento e envio, sem texto, chat ou identificador do comando |
| `chromium.process_count`, `processes` | Processos encontrados na árvore do browser, com PID/PPID, nome, estado e tempo de início |
| `chromium.cpu_percent`, `cpu_status`, `cpu_incomplete`, `previous_processes_not_observed` | Soma de CPU dos processos com deltas válidos, cobertura e identidades anteriores não observadas nesta coleta |
| `chromium.rss_bytes`, `rss_measured_processes`, `rss_incomplete` | Soma de VmRSS dos processos observados e sua cobertura; páginas compartilhadas podem ser contadas mais de uma vez |
| `chromium.*_candidates`, `scan` | Candidatos a órfãos/zumbis/fora da árvore e contadores de erros, processos desaparecidos e truncamento |

As definições de memória e CPU são as APIs oficiais de
[process do Node](https://nodejs.org/docs/latest-v22.x/api/process.html#processcpuusagepreviousvalue).
RSS do Node inclui memória nativa; heap estável com RSS crescente não basta
para provar leak, pois alocação nativa e fragmentação também podem influenciar.

O [histograma de event loop do Node](https://nodejs.org/download/release/v22.22.0/docs/api/perf_hooks.html#perf_hooksmonitoreventloopdelayoptions)
é baseado em timers. Leia também `max_ms`: pausas raras podem ter pouco efeito
no p95/p99. Não confunda esse histograma com latência de mensagem ou CPU.

CPU é `null` antes de existir um delta válido, quando um PID é novo/reutilizado
ou quando a frequência de ticks não pôde ser obtida. `null` não significa zero.
CPU de um worker que saiu entre coletas não é recuperada: a soma dos
sobreviventes pode ser `partial`. Processos que nascem e terminam inteiramente
entre duas amostras sequer são observados. RSS somado não é PSS/memória exclusiva
nem memória total do cgroup; use também `docker stats` para o container.
Sem comandos concluídos, média/máximo de latência também são `null`; operações
travadas ainda não concluídas não entram nessa média. Use saúde e diagnósticos
de pendências em conjunto.

A varredura padrão considera até 256 PIDs, com até quatro leituras concorrentes,
no namespace de PIDs atual. Não observa outros containers ou a VM inteira.
Erros/truncamento invalidam conclusões de cobertura completa. Candidatos a
órfão por PPID 0/1 podem ser helpers legítimos; estado `Z` indica processo já
terminado aguardando coleta. Nenhum desses contadores autoriza matar processos
ou limpar arquivos de sessão.

## Validação local

Os testes passaram sem rede ou acesso à produção:

- ClojureScript: 204 testes e 1304 assertions, sem falhas ou erros, em Docker
  limitado a 1 CPU e 512 MiB.
- Node/Linux: 61 testes passaram, incluindo 13 do coletor e 48 de HTTP/serviço.
- Build da aplicação: 101 arquivos, com 138 warnings preexistentes; nenhum no
  namespace de recursos. A verificação das alterações de mídia também passou.

Um smoke test lançou Chromium real na imagem local `zapbot:cicd-validated`,
sem rede e sem healthcheck automático, com perfil temporário privado criado
para o teste. Não usou sessão WhatsApp persistente nem executou gameplay.
Os browsers criados localmente foram encerrados ao final.

O ambiente tinha Node 22.23.3 e Chrome 154.0.8037.92, limitado a 1 CPU e 768 MiB,
com AMD64 emulado no Mac ARM. Os modos `CHROMIUM_LOW_RESOURCE_MODE=false` e
`true` renderizaram texto/canvas, observaram dez processos cada e tiveram flags
efetivas idênticas, confirmando os defaults do Puppeteer. A leitura de
`AT_CLKTCK` retornou 100 e o coletor obteve CPU com status `measured`.

Quatro coletas duraram 31,32 / 33,70 / 104,40 / 13,59 ms. São durações totais
assíncronas nesse ambiente emulado, incluindo I/O, e não um benchmark de
produção ou uma medição isolada de overhead de CPU. Esses checks validam a
instrumentação e o lançamento local; o consumo na VM e o comportamento com
uma sessão WhatsApp autenticada ainda precisam de comparação autorizada.

## Leituras seguras para uma comparação futura

Os comandos abaixo são somente leitura e devem ser executados pelo operador
na VM autorizada. Mostram contadores e nomes de processos, sem abrir `.env`,
perfil WhatsApp, argumentos completos ou conteúdo de mensagens.

```sh
sudo docker stats --no-stream zapbot
sudo docker top zapbot -eo pid,ppid,comm,stat,rss,pcpu
sudo docker inspect zapbot --format 'image={{.Config.Image}} restart={{.RestartCount}} memory={{.HostConfig.Memory}} memorySwap={{.HostConfig.MemorySwap}} shm={{.HostConfig.ShmSize}}'
sudo docker exec zapbot node scripts/healthcheck.js
vmstat 1 61
awk '/^(MemTotal|MemAvailable|SwapTotal|SwapFree):/ {print}' /proc/meminfo
head -n 1 /proc/stat
```

`docker top` e `docker stats` abrangem o container. `ps %CPU` é uma medida de
vida do processo, diferente do delta por janela do coletor. A primeira linha
de dados de `vmstat` resume desde o boot; compare as linhas subsequentes.

Para consultar somente os recursos já coletados do Node principal:

```sh
sudo docker exec zapbot node -e '(async()=>{const r=await fetch("http://127.0.0.1:3001/diagnostics",{signal:AbortSignal.timeout(5000)});if(!r.ok)throw Error();const d=await r.json();console.log(JSON.stringify(d.recursos??{status:"sem_amostra"},null,2));})().catch(()=>{console.error("Diagnóstico indisponível");process.exitCode=1;})'
sudo docker logs --since 30m zapbot 2>&1 | rg '^\[RecursosOdisseu\] '
```

Executar `process.memoryUsage()` em um novo `docker exec node` mede o processo
da sonda, não o bot. O endpoint acima devolve a amostra do processo principal.
Versões anteriores sem coletor podem retornar `sem_amostra`.

## Antes/depois sem inventar economia

Não foram coletados valores de produção nesta etapa:

| Medida comparável | Antes | Depois |
|---|---|---|
| CPU Node/Chromium por janela | Não coletado | Não coletado |
| RSS Node e RSS estimado Chromium | Não coletado | Não coletado |
| Event loop p95/p99/máximo | Não coletado | Não coletado |
| Latência: quantidade/média/máximo | Não coletado | Não coletado |
| Processo raiz/árvore, candidatos e erros de scan | Não coletado | Não coletado |
| MemAvailable, swap-in/out, CPU steal e I/O wait | Não coletado | Não coletado |

Após autorização específica para implantação/comparação, use períodos
equivalentes, mesma VM, limites, versões, sessão e perfil de tráfego autorizado.
Separe startup/sincronização, ociosidade e comandos; compare várias janelas de
60 segundos com volume semelhante e saúde READY. Para isolar outro ajuste,
mantenha a instrumentação ligada nos dois lados e inclua `collection_ms`.
Uma amostra isolada ou média com poucos comandos não demonstra melhora.

Em `vmstat`, `st` alto sugere tempo de CPU retirado pelo hypervisor; `wa` e
swap-in/out sustentados ajudam a investigar pressão de I/O/memória. A
[documentação do kernel sobre /proc/stat](https://docs.kernel.org/filesystems/proc.html)
ressalta limitações do próprio iowait. Esses sinais são da VM e incluem outros
trabalhos; não identificam sozinhos Chromium ou Cassandra como causa. Flags
de navegador não corrigem contenção do host, limites do provedor ou disco lento.

Investigue crescimento persistente sob carga estável correlacionando heap,
RSS, processos, saúde e duração das operações. Trocas de PID são diferentes
de crescimento de uma mesma árvore; caches aquecidos também mudam a memória.
Qualquer ajuste posterior ou recuperação operacional precisa preservar uma
única instância com a sessão e seguir o
[guia de deploy e rollback](deploy-github-actions.md).
