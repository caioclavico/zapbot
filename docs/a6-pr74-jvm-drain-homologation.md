# Homologação administrativa — PR #74 (drain JVM completo)

**Data:** 2026-11-11T01:50Z  
**HEAD homologado:** `94f4fa2b3031645383a78a9f4190a97ee6442f7b`  
**Base:** `a6/live-baseline-8ef41c8` @ `a243ce518e4be72fdf6e3df778bdb38927d4cbec`  
**PR:** https://github.com/caioclavico/zapbot/pull/74

## Controles GitHub

- Branch protection em `a6/live-baseline-8ef41c8`: **ausente** (404).
- Rulesets: nenhum.
- Reviews obrigatórias: nenhuma configurada; reviews no PR: 0.
- Esta homologação é **administrativa baseada em evidências**, sem fingir revisão humana independente.

## CI

Workflow `a6-live-reconcile-validation.yml` corrigido neste PR:

- Tip esperada atualizada para `a243ce5…`.
- Escopo `node` vs `jvm`; proíbe caminhos 036/PE.
- Job JVM: unit drain, smoke quiescence/freeze, cold fingerprint, Node maintenance.

Resultado no HEAD `94f4fa2`:

- Classify scope: **SUCCESS**
- JVM drain / freeze gate: **SUCCESS**
- Node integration: **SKIPPED** (escopo jvm)

## Testes locais (reconfirmados)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-a6-maintenance-drain` | 7 testes / 45 asserts PASS |
| `clojure -M:smoke-a6-maintenance-quiescence` | PASS |
| `clojure -M:smoke-a6-drain-cold-rehearsal` | PASS (fingerprint estável) |
| `node --test test/a6-maintenance.test.cjs` | 7/7 PASS |
| `git diff --check` | PASS |

## Auditoria de writers

Writers cobertos pelo gate: rotas autenticadas da bridge (`acquire-for!`), idle sweeper (`acquire-write!`), ticks raid/thematic (POST = mutação), audit agregada (`acquire-read!`).

Writers **fora** do gate (pré-flight operacional, não regressão deste PR): scripts/cutover/migrations com `datomic.client.api` direto; segundo processo após liberação do writer-lock. Documentados no runbook.

## Compatibilidade produção

- Diff vs live-baseline: apenas drain/CI/docs/deps aliases — **sem** 036, sem PE/#66.
- Imagem candidata contém migrations até `035-species-hp-formula.edn`; `NO_036`.
- Contratos Node: 503 em mutações sob manutenção; leituras até `freeze`.
- Rollback marker: JVM antiga mapeia `:frozen` → `:quiescent` (fail-closed). **PASS** no ensaio semântico.

## Imagem JVM-only candidata (não implantada)

| Campo | Valor |
| --- | --- |
| Tag | `odisseu-jvm:a6-drain-94f4fa2b3031-amd64` |
| Image ID / manifest digest | `sha256:39070def6a073824c43d867770baad30a39caa571cd5fbc77cf4f069863272ad` |
| Commit | `94f4fa2b3031645383a78a9f4190a97ee6442f7b` |
| Tree | `c69807ec1c461194a33283fbde22d43824365c31` |
| Tar SHA-256 | `b214bd17b014e916fa9156813ef5d5e5f1a8aae01f4c9998fd2b3b68024dd69a` |
| Base | `eclipse-temurin:21-jdk-jammy` / Java 21 / linux/amd64 |
| Rollback live | `sha256:5cfca31b18c4cbf652280a01df39deaa2744cf0a6c93cff2cdb292d24aaa94f2` |

Artefato local: `/private/tmp/a6-jvm-drain-image-20261111T014858Z/`

## Decisão de integração

**GO para merge** em `a6/live-baseline-8ef41c8` com esta homologação administrativa, CI verde e ausência de proteção/review obrigatória.

**Não** autoriza deploy live, freeze live nem cold backup.
