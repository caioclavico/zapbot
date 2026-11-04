# Odisseu A6 — preflight final do Release Announcer

**Data:** 2026-11-04 16:11 UTC

**Status:** **BLOCKED** — SSH foi recuperado pelo método histórico; faltam comprovação de backup atual íntegro e leitura direta do schema Datomic para confirmar platform/007 ausente. Nenhuma imagem foi exportada ou transferida.

## Escopo e revisão local

O acesso autorizado foi tentado somente para leitura. Artefatos conferidos no clone independente `/private/tmp/zapbot-announcer-a6-rc-final`:

| Item | Resultado local |
|---|---|
| Commit de código | `c948b29195a6c29ae6a12120b2ca378ab3e8ebf6` |
| Content-tree do código | `7da5d84749a6b09fa3cff31b75e8d4eaa03fab24` |
| Node | `sha256:69a7a9624ea190e6c2e2578d4a89a24b29884811b22f24762c2c92be71466dd1`, `linux/amd64`, label `git-tree=7da5d84749a6b09fa3cff31b75e8d4eaa03fab24` |
| JVM | `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb`, `linux/amd64`, label `git-tree=6e5604d425bc85ea33970e424c2ee55aefc7b620` |
| Build Node | A imagem foi construída do tree de código autorizado e passou smoke local isolado de `createClient().close()`; host ARM64 executou a imagem AMD64 por emulação. |

A imagem JVM não contém mudanças desde a revisão indicada pelo seu label. Não reconstruí nenhuma das imagens. Nenhum tarball foi criado; portanto, não há checksum de tarball a registrar.

## Resultado da primeira tentativa de acesso remoto

- `ssh -G odisseu-core` não encontrou configuração específica: `hostname` permaneceu `odisseu-core`, sem resolução para endereço conhecido.
- Comando read-only `ssh -o BatchMode=yes -o ConnectTimeout=8 odisseu-core 'hostname && uname -m'` falhou: `Could not resolve hostname odisseu-core`.
- `gcloud` está instalado, mas não inicializa: requer Python 3.10–3.15; o ambiente tem somente Python 3.9.6. Não alterei nem reinstalei a CLI.
- Logo, não há evidência atual sobre identidade/arquitetura da VM, Node/WhatsApp, JVM/Datomic, flags efetivas, migration instalada, backups, espaço livre, volumes/sessão ou imagens de rollback.

## Migration 007 — revisão local somente

`migrations/platform/007-release-announcements.edn` contém atributos aditivos `:platform.delivery/*`, sem campos de saves, economia ou Pokémon. A registry em `modules/platform/infrastructure/odisseu/platform/infrastructure/datomic/store.clj` lista `006-bugs-participantes` antes de `007-release-announcements`; o cabeçalho de 007 também registra que 006 já pertence à linhagem. A revisão local e os ensaios descartáveis anteriores indicam reaplicação idempotente. O histórico de produção disponível no clone não prova se 007 está ou não aplicada na VM; esse ponto continua desconhecido. Nenhuma migration foi executada.

## Transferência e plano

Transferência **não iniciada**: o preflight obrigatório da VM não passou, então não exportei com `docker save`, não usei SCP e não executei `docker load`. Produção e os containers permanecem sem alteração por esta tarefa.

Para retomar, primeiro restabelecer um método SSH/GCloud já autorizado que identifique a VM e permita leitura sanitizada. Confirmar os gates da solicitação, incluindo estado saudável, backups íntegros/restauráveis, espaço suficiente, migration, configuração e imagens de rollback. Só depois exportar os artefatos já verificados, conferir SHA-256 dos tarballs em ambos os lados, importar e revalidar Image IDs, arquitetura e labels. A próxima etapa operacional continua separada: sem recreate, deploy ou migration até GO específico.

Rollback futuro deve restaurar apenas as imagens anteriores compatíveis, mantendo o schema 007 se ele vier a ser aplicado. Não restaurar banco antigo sobre dados novos. Announcer deve permanecer desabilitado e sem intent até autorização própria.

**Decisão da primeira tentativa: BLOCKED.** Naquele momento, o gate era falta de conectividade autenticada/read-only à VM. A atualização abaixo registra a recuperação pelo comando SSH histórico. Não contornei controles de acesso.

## Atualização — preflight remoto histórico, 2026-11-04 16:20 UTC

### Acesso SSH

Os três relatórios pedidos foram consultados. `docs/pokemon-a6-dispatcher-critical-hotfix.md` registra o comando SSH de produção que já havia sido usado; os relatórios de trades corroboram o mesmo host operacional. Reutilizei o método histórico, sem alterar SSH ou GCloud:

```sh
ssh -o BatchMode=yes -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146 'hostname && uname -m'
```

Resultado: `odisseu-core`, `x86_64`. O acesso Docker read-only funciona diretamente para o usuário SSH; `sudo` interativo não está disponível e não foi necessário. O GCloud continuou inutilizável com o Python 3.9 instalado, mas não foi necessário para essa conexão.

### Estado observado na VM

| Gate | Evidência read-only | Resultado |
|---|---|---|
| Arquitetura | `hostname=odisseu-core`; `uname -m=x86_64`; Docker `linux/amd64` | PASS |
| Node | container ativo, `healthy`, imagem `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` | PASS |
| WhatsApp | healthcheck passivo: `READY`, Chromium `true` | PASS |
| JVM/bridge e Datomic | container `healthy`; `/health` retornou `status=up`, `platformEnabled=true` e capabilities operacionais | PASS para bridge saudável; sem consulta direta ao catálogo Datomic |
| Pokémon 033 | relatório de deploy confirma `pokemon/033-trade-public-id` aplicada; imagem operacional atual coincide com o Image ID do deploy reportado | PASS conforme evidência operacional existente; não alterei o banco |
| Trocas A6 | imagem A6 trades ativa e JVM healthy; nenhuma operação de jogo executada | PASS para preservação do serviço |
| Raides automáticas | `POKEMON_RAID_AUTO_TICK=false`; `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`; health da JVM reporta tick `false` | PASS |
| Grupos | `ALLOW_ALL_GROUPS=true`; função group-only da imagem ativa testada com JIDs sintéticos: `@g.us=true`, `@c.us=false`, `@lid` não classificado=false, `@lid` com metadado de grupo=true | PASS |
| Announcer | variável `RELEASE_ANNOUNCER_ENABLED` ausente; código bootstrap ausente na imagem Node atual; migration 007 ausente da imagem JVM ativa | Desabilitado/ausente no runtime atual |
| Montagens | bind de sessão WhatsApp e volumes de dados/Datomic, journal e maint presentes; sem alteração | PASS |
| Rollback de aplicação | imagem atual de trades permanece carregada; também existem tags históricas Node `a6-critical-5088b5b` e JVM `a6-fase12832` | PASS |
| Espaço | `/`: 48 GB total, 32 GB usados, 16 GB livres (67% usado) | PASS para tamanho esperado dos artefatos (~1,76 GB descompactados localmente) |

Os Image IDs ativos de rollback são Node `sha256:62414ffebb0c0ba55196617fafa334c51b8a72e48c39c1d12a73146ea429d4f6` e JVM `sha256:d07a379eb634e64ab7670252ff7e3a2a513e26c571c1c7028e3e2a5644408570`. Nenhum container foi parado, substituído ou recriado.

### Imagens autorizadas

Uma nova inspeção local confirmou:

| Imagem | Image ID completo | Plataforma | Label `git-tree` |
|---|---|---|---|
| Node | `sha256:69a7a9624ea190e6c2e2578d4a89a24b29884811b22f24762c2c92be71466dd1` | `linux/amd64` | `7da5d84749a6b09fa3cff31b75e8d4eaa03fab24` |
| JVM | `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb` | `linux/amd64` | `6e5604d425bc85ea33970e424c2ee55aefc7b620` |

O cliente Node contém o fix de cleanup do commit `c948b29195a6c29ae6a12120b2ca378ab3e8ebf6`. A JVM corresponde ao RC homologado, sem mudança JVM/schema desde a árvore registrada em seu label. Não reconstruí imagens.

### Gatilhos que mantêm BLOCKED

1. O backup frio mais recente encontrado é `a6-periodic-backup-trades-pre-20261104T142726Z`, feito antes do deploy que aplicou Pokémon 033. A pasta tem aproximadamente 18 MB, mas não contém os arquivos `files.sha256`/manifest esperados na própria localização; a tentativa de verificar o manifesto falhou porque ele não está presente ali. A cópia de sessão existente é antiga. Portanto, não consigo confirmar um backup consistente, íntegro e adequado ao estado operacional atual.
2. A imagem JVM ativa não contém `platform/007`, e o código atual não faz essa migration no boot. Isso é evidência de que o fluxo de boot atual não a aplicou, mas não prova a ausência dos atributos `:platform.delivery/*` no banco caso alguém os tenha transacionado manualmente. Não existe endpoint read-only de inspeção de schema nesta bridge; não abri um segundo cliente Datomic contra o storage ativo nem copiei volume vivo.

Por esses dois gates, não executei `docker save`, não criei tarballs/checksums, não transferi e não rodei `docker load`. O espaço e os demais gates verificados não substituem as comprovações faltantes. Permanecem sem alteração produção, sessão WhatsApp, configuração, volumes, Datomic e serviços.

### Migration 007 e plano de retomada

A revisão estática local confirma que `006-bugs-participantes` precede `007-release-announcements` na registry. 007 é aditiva, limitada a `:platform.delivery/*`, com identidades idempotentes; não toca atributos econômicos, Pokémon ou saves. Compatibilidade de código/schema foi validada em Datomic descartável nas fases anteriores. Estado live de atributos 007 permanece **não verificado**, não afirmar como “não aplicada”.

Para liberar apenas a transferência: obter um backup consistente e verificável conforme procedimento operacional já aprovado e um método de leitura autorizado para confirmar o schema live sem iniciar outro writer. Repetir os gates. Então exportar exatamente as imagens locais acima, gerar SHA-256 dos tarballs, transferir com o SSH histórico, verificar checksum, carregar e conferir IDs/arquitetura/labels. Ainda assim, não executar deploy nem migration sem GO específico.

**Status final desta tentativa: BLOCKED.** SSH foi restabelecido e os artefatos locais/estado geral foram verificados, mas backup atual verificável e prova direta de ausência de platform/007 não foram estabelecidos. Nenhuma transferência ou alteração de produção ocorreu.
