# Runbook — implantação JVM-only do drain completo (futuro)

**Não executar agora.** Este documento prepara a janela após merge do PR #74 e build da imagem candidata.

## Pré-condições

- PR #74 mergeado em `a6/live-baseline-8ef41c8`.
- Imagem JVM candidata pinada por digest (não tag mutável).
- Schema **035** apenas; 036 / RC #66 ausentes.
- Node live healthy; WhatsApp READY; Announcer `suspended`.
- Deploy hold `true`; CI/CD de deploy desabilitado; operador exclusivo.
- Volumes ativos: `odisseu-a6-cutover-{datomic,journal,maint,data}`.
- Rollback image: live atual `sha256:5cfca31b18c4cbf652280a01df39deaa2744cf0a6c93cff2cdb292d24aaa94f2`.

## Rollback e marcadores

- JVM nova grava fases `open|draining|quiescent|frozen`.
- JVM antiga (`5cfca31b…`) **não** conhece `:frozen`; ao ler o marker, trata fase desconhecida como `:quiescent` (fail-closed).
- Antes de rollback: preferir `POST /ops/maintenance/resume` na JVM nova (voltar a `open`) **ou** aceitar que a JVM antiga subirá em `quiescent` até resume explícito.
- Não apagar `bridge-maintenance.edn` manualmente.

## Sequência (resumo)

1. Preflight RO: health Node/JVM, digests, hold, Announcer, disco, mounts cutover.
2. Fechar admissão Node (`a6-maintenance` → quiescent).
3. Opcional: `enter` na JVM antiga se for drenar writers antes da troca.
4. Stop controlado JVM (containers preservados / recreate só o serviço JVM).
5. Atualizar **somente** a imagem JVM para o digest candidato; volumes intactos.
6. Start JVM; health; schema 035; maintenance status.
7. Se marker não-`open`, decidir resume explícito após checks.
8. Confirmar `raid-auto` tick só quando `open`; Node ainda quiescent até GO.
9. Retomar Node; verificar saves/bridge; Announcer permanece suspended.
10. Abortar e rollback se health/schema/marker/divergência de saves.

## Critérios de aborto

- Digest divergente do manifesto.
- Schema ≠ 035 ou presença de 036.
- Volumes `odisseu-core-*` montados por engano.
- Drain timeout / writers externos.
- Health ou WhatsApp não READY após retomada do Node.
- Qualquer necessidade de mutar saves/recibos/announcements.

## Relação com cold backup

Implantar o drain **não** autoriza cold backup. Cold exige GO separado após drain/freeze comprovados na live com a JVM nova.
