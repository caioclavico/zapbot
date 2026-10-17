# M1 — Scaffold + smoke Datomic Local

**Status:** executado em desenvolvimento (sem deploy)  
**Branch:** `refactor/modular-monolith-datomic`  
**Checkpoint anterior:** `d96dbb1` (`chore(pokemon): checkpoint before Datomic migration`)

## O que foi criado

- Árvore `apps/`, `modules/`, `shared/`, `migrations/`, `deploy/`, `tests/`
- `deps.edn` (Clojure 1.12.1 + `com.datomic/local` **1.0.301**)
- Smoke: `apps/odisseu/src/odisseu/smoke/datomic_local.clj`
- Scripts: `scripts/smoke-datomic-local.sh`, `scripts/bootstrap-clojure-cli.sh`
- Dados locais gitignored: `.datomic-local/`, `.cpcache/`, `.tools/`

## Como executar

```bash
# (opcional) CLI local no repo, se não houver `clojure` no PATH
./scripts/bootstrap-clojure-cli.sh

./scripts/smoke-datomic-local.sh
```

Requisitos: **Java 11+** (validado com OpenJDK 21). Linux e macOS usam o mesmo script (`ps` para RSS).

## Dependência e licença

| Item | Valor |
|---|---|
| Artefato | `com.datomic/local` |
| Versão | `1.0.301` (Maven Central) |
| Licença | Apache License 2.0 (redistribuível / embutível) |
| API | Client API (`datomic.client.api`) |
| Custo | Sem taxa de licença Datomic |

Fonte: [docs.datomic.com/datomic-local.html](https://docs.datomic.com/datomic-local.html)

## Configuração do smoke

| Parâmetro | Valor |
|---|---|
| JVM heap | `-Xms64m -Xmx256m` (`:smoke-datomic` em `deps.edn`) |
| Storage | `.datomic-local/smoke` (absoluto via system property) |
| System | `odisseu-m1-smoke` |
| DB | `treinador-ficticio` |
| Isolamento | Sem Node, Chromium ou Cassandra no mesmo processo |

## Compatibilidade Linux (deploy futuro)

- Datomic Local é JAR puro (Maven); JVM 11+ em Linux x86_64/arm64.
- Storage em filesystem local (volume Docker ou path absoluto).
- Não exige cluster Cassandra nem serviço Datomic separado.
- RSS via `ps` funciona em Linux; o smoke degrada graciosamente se `ps` falhar.

## Resultados observados

Execução local em 2026-10-17 (macOS aarch64, processo isolado):

| Métrica | Valor |
|---|---|
| Java | OpenJDK **21.0.12.1** (Homebrew) |
| Clojure CLI | 1.12.6.1673 (via `.tools/clojure`, gitignored) |
| Smoke | **OK** — create → write → update → reopen → assert |
| Treinador | `smoke-trainer-001` / Ash Ficticio / moedas 250 / nível 3 |
| Heap used / total / max | ~30–32 MB / 64 MB / **256 MB** (`-Xmx256m`) |
| RSS após escrita | ~**210 MB** (`ps`; inclui JVM + libs nativas) |
| Storage | `.datomic-local/smoke` (não versionado) |

Nota: RSS ≈ 210 MB é só a JVM do smoke. Em deploy futuro, somar Chromium/Node — **não** medir Cassandra+Chromium neste teste (restrito pelo plano M1).

## Fora de escopo (M1)

- Mover código existente, migrar Cassandra, alterar WhatsApp/deploy, flags, domínio Pokémon.
