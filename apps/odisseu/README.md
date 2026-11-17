# Odisseu — composição JVM (A6)

Este diretório contém o ponto de composição do **monólito modular JVM**, incluindo a bridge HTTP e utilitários operacionais. Não é apenas um smoke M1.

## Onde cada responsabilidade vive

| Diretório | Responsabilidade |
|---|---|
| `apps/odisseu/src/odisseu/` | Entrada da aplicação JVM, bridge HTTP, composição e rotinas de operação |
| `modules/pokemon/domain/` | Regras puras do jogo Pokémon |
| `modules/pokemon/application/` | Casos de uso e coordenação de operações |
| `modules/pokemon/infrastructure/` | Persistência Datomic e integrações |
| `modules/platform/` | Domínio, aplicação e adaptadores da plataforma |
| `shared/` | Configuração, observabilidade e contratos de persistência |
| `src/zapbot/` | Adaptador WhatsApp/ClojureScript legado em **caminho ainda ativo**, até a migração de diretório ser testada |

## Regras

- O transporte WhatsApp interpreta comandos e apresenta retornos, sem duplicar o estado canônico do jogo.
- A JVM/Datomic é a fonte de verdade para saves, batalhas, trocas, recompensas, PI, PE e moedas A6.
- Não mover ou excluir `src/zapbot`, `pokemon-service`, migrações ou scripts operacionais somente por nome: verificar dependências em runtime, Docker, Compose e CI.
- Alterações de caminhos exigem atualização conjunta de `deps.edn`, `shadow-cljs.edn`, Dockerfiles, Compose, scripts e testes.
- Não usar este README para inferir que uma rotina antiga é executável na produção atual.

O plano de consolidação e os gates para remoção de legado estão em
[`docs/architecture/a6-consolidation-plan.md`](../../../docs/architecture/a6-consolidation-plan.md).
