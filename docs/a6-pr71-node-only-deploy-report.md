# Relatório — deploy Node-only PR #71

## Veredito operacional

**Deploy Node-only: GO (executado com sucesso).**  
**Próxima janela de backup cold: NO-GO** (não autorizada nesta operação; requer prep/ordem específica).

## Identidade

| Campo | Valor |
|---|---|
| Host | `odisseu-core` (`x86_64`) |
| Janela UTC | `2026-11-11T00:31:00Z` → `2026-11-11T00:32:08Z` |
| Merge código | `66db8a3a0fe75249eec002f962b231cd12c1e736` |
| Image ID implantado | `sha256:250155bae3ba5236feb676c289d285967b9867d087fe3d9750adf5e7acd2f476` |
| Tar SHA-256 | `b81894e5cd37c92da38a8ec882a035fe5301b7982d80b818adaebbe6118bc204` |
| Rollback Node Image ID | `sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7` |
| Evidência host | `/home/caiohclavico/odisseu-recovery/a6-pr71-node-only-deploy-20261111T003028Z` |

## Preflight (PASS)

- Node live healthy + WhatsApp READY + Chromium true (`:3003`)
- JVM live healthy (`:18092`), RestartCount 0
- Disco ~20 G livres
- Imagem candidata verificada (tar SHA + Image ID) antes do load
- Sem operação concorrente de cutover detectada
- Intent de release preservado (não modificado); controle Announcer inexistente → criado `suspended` direto

## Suspensão

- `release-announcer-control.json`: `state=suspended`, `reason=node-only-deploy-pr71`, `0600`, uid/gid `1000`
- `publicationInFlight=false`
- Locks órfãos de publicação: não criados; LocalAuth `session-core` preservado

## Rollback preparado

- Imagem antiga `a87abb51…` presente no host
- Compose renderizado com `RELEASE_ANNOUNCER_ENABLED=false` comprovado para o serviço Node
- Se a flag false não puder ser garantida no futuro: manter Node parado

## Pós-deploy

| Check | Resultado |
|---|---|
| Node Image ID | `250155ba…` |
| Node health | `ok` / WhatsApp `READY` / Chromium `true` |
| Node RestartCount | 0 |
| JVM Image ID | `5cfca31b…` (inalterada) |
| JVM RestartCount | 0 (sem recreate) |
| Controle | `suspended` |
| Sinais de envio Announcer nos logs desde o cutover | nenhum |
| Memória Node | ~1.14 GiB / 2.5 GiB |

## Riscos

Aceitação temporária documentada em `docs/a6-pr71-residual-risk-acceptance.md` (revisão até 2026-12-11).

## Incidentes

Nenhum. JVM não reiniciada; Datomic/schema não tocados; sem migration 036; sem anúncios; sem backup cold.
