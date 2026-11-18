# Odisseu A6 — Runbook cutover growth-v1 / schema 037

**Escopo:** progressão oficial de XP dos Pokémon (curvas PokéAPI + migration 037) sobre live com PE/036 (RC #66).  
**Não executa** cutover sozinho — exige **GO explícito** de produção.

Homologação descartável (2026-11-11): tip `9780ecb` (PR #76), imagem
`odisseu-jvm:a6-growth037-20261111T155543Z-9780ecbed468-amd64`
(`sha256:237010e5d0f059c99d6772b72485a105f09fdf12963db3a44a2ecd6e03662222`),
cold `cold-rc66-a6-20261111T135754Z`, dry-run **1015** Pokémon, níveis↓ **0**.

## Pré-condições (preflight RO)

1. Live tip contém merge PR #76 (`9780ecb` ou sucessor).
2. Produção: schema **036** ativo; JVM RC #66; WhatsApp READY; Announcer `suspended`.
3. Deploy hold `true`; CI/CD `disabled_manually`.
4. Maintenance Node/JVM `open` (ou estado documentado); raids conforme flag live.
5. Backup cold **da janela** dos volumes `odisseu-a6-cutover-{datomic,journal,maint,data}`:
   - verify/checksums PASS
   - restore isolado PASS
   - Se saves mudaram desde o cold de homologação, capturar **novo** cold na janela (não reutilizar cegamente o de 13:58Z).
6. Imagem JVM candidata `linux/amd64` tip 037+growth (digest/tar registrados).
7. Imagem de rollback **compatível com 037 + growth-v1** (= mesma candidata ou rebuild do mesmo SHA).
   - Após a **primeira escrita** `growth-v1` / attrs 037 em Pokémon, a JVM RC #66 (`1e75d990`, 036-only) **não** é rollback seguro.
8. LocalAuth permanece no bind host; fora do cold de 4 volumes.
9. Node: presenter XP/barra do PR #76 **não** está no Node live PR #71. Cutover JVM-only entrega progressão canônica; barra WhatsApp completa pode exigir GO Node separado. Não atualizar Node sem autorização.

## Ordem canônica da janela

1. Preflight RO (digests, health, WA READY, Announcer suspended, hold/CI, volumes, disco, writers).
2. Baseline canônico RO dos saves (jogadores/saves/Pokémon/moedas/XP armazenado — separar derivados).
3. Node `enter` → `quiescent`.
4. JVM `enter` → `draining` → `quiescent` (contadores; ticks 503).
5. Freeze JVM → `frozen`.
6. Stop controlado Node → JVM (`docker stop`, sem `-v`, restart=`no`).
7. Cold backup atualizado da janela + verify.
8. Start JVM candidata growth-037 (ainda frozen/quiescent conforme marker).
9. Aplicar migration **037** de forma **explícita e idempotente**
   (`pokemon.infrastructure.datomic.schema/install!` no writer único).
   Confirmar marcador `pokemon/037-pokemon-growth-xp` e attrs:
   `:pokemon/xp-total`, `:pokemon/xp-progress-version`, `:pokemon/xp-legacy-residual`,
   `:pokemon/xp-legacy-nivel`, `:pokemon/growth-rate`.
   Confirmar **036** preservado (`pokemon/036-trainer-level-rewards`).
   **Sem backfill** — conversão lazy no primeiro grant/leitura de progresso.
10. Validar health bridge + schema; invariantes canônicos estáveis.
11. **Resume JVM → `open`**.
12. Start/restart Node (Node **não** sobe com JVM `frozen`).
13. Confirmar WhatsApp READY; Node pode ainda estar `quiescent`.
14. **Resume Node → `open`**.
15. Piloto XP (vitória/derrota/raid) + ficha `!pk`; PE treinador intacto; Announcer suspended.
16. Abortar com rollback 037-compatível se qualquer gate falhar após a migration.

## Critérios de aborto

- Drain timeout / writes tardios / writers externos.
- Freeze não persistente.
- Checksum/manifesto de backup inválido.
- Migration 037 não idempotente ou marcador ausente.
- Nível de Pokémon reduzido / perda de entidade / ledger inconsistente.
- Node ERROR por manutenção (JVM frozen) sem correção ordenada.
- Tentativa de rollback para JVM 036-only **após** writes growth-v1.

## Rollback

| Momento | Ação |
|---|---|
| Antes de instalar 037 | Voltar à JVM RC #66 (`1e75d990`) |
| Após 037 schema, **sem** writes growth-v1 em Pokémon | Preferir forward-fix com candidata; 036-only pode bootar mas **não** é suporte oficial |
| Após qualquer write `xp-progress-version=growth-v1` | Somente imagem tip 037+growth (candidata). Proibido RC #66 036-only |
| Cold restore sobre volumes live | Somente com GO destrutivo explícito — **não** é rollback automático |

## Retomada de emergência

1. Confirmar JVM healthy.
2. `POST /ops/maintenance/resume` na JVM.
3. `docker restart` do Node.
4. Aguardar READY.
5. Resume Node se ainda quiescent.

Ordem homologada: **Resume JVM → start/restart Node → resume Node.**

## Evidências de homologação (descartável)

| Item | Resultado |
|---|---|
| Cold | `…/cold-rc66-a6-20261111T135754Z` verify ok |
| Pokémon auditados | **1015** |
| Níveis reduzidos | **0** |
| Níveis aumentados (residual≥9) | **0** |
| HP/shiny/golpes/IVs alterados no dry-run | **0** |
| sum-nível | 1599 → 1599 |
| sum-xp-total (projetado) | 727797 |
| Curvas | mf 458 · ms 264 · slow 215 · fast 45 · erratic 18 · fluc 15 |
| 037 + 036 após install | PASS / idempotente |
| Drain open→quiescent→frozen→open | PASS |
| Persistência pós-restart | markers 036/037 + `:pokemon/xp-legacy-nivel` PASS |
| Produção mutada | **NÃO** |

## Não executado nesta preparação

- Deploy live / migration 037 live / backfill
- Stop de produção / novo cold da janela
- Atualização Node / Announcer / deploy hold / CI-CD
