# Odisseu A6 — preflight final do Release Announcer

**Data:** 2026-11-04 16:11 UTC

**Status:** **BLOCKED** — preflight remoto não concluído; nenhuma imagem foi exportada ou transferida.

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

## Resultado do acesso remoto

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

**Decisão: BLOCKED.** O gate bloqueador é falta de conectividade autenticada/read-only à VM e, consequentemente, impossibilidade de verificar estado, backups e espaço antes de transferir. Não contornei essa restrição.
