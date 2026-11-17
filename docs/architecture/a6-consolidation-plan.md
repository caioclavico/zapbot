# Consolidação arquitetural do Odisseu A6

> Estado: plano de migração estrutural; **não autoriza exclusão, merge nem deploy automático**.
> Referência: `refactor/a6-runtime-cleanup`, 2026-11-17.

## Estado verificado

- A branch de refatoração diverge de `master` (457 commits exclusivos e 10 commits exclusivos da `master` no momento da auditoria).
- `deps.edn` aponta para `apps/odisseu/src`, `modules/pokemon/{domain,application,infrastructure,tests}`, `modules/platform/{domain,application,infrastructure,tests}` e `shared/{persistence,config,observability}/src`.
- O WhatsApp/Node ainda é compilado com `shadow-cljs`, e o ponto de entrada permanece separado do núcleo JVM.
- `README.md`, `apps/odisseu/README.md` e partes de `CLAUDE.md` contêm descrições históricas que não devem ser consideradas prova do runtime atual.
- Existiam 26 branches e 8 PRs abertos na auditoria.

## Contrato de pastas-alvo

```text
apps/
  whatsapp/             # futuro adaptador de transporte Node/CLJS
  odisseu/              # composição JVM, bridge HTTP, bootstrap
modules/
  pokemon/
    domain/             # regras puras: batalhas, captura, progressão, economia
    application/        # casos de uso e orquestração
    infrastructure/     # Datomic, APIs externas, adaptadores
    tests/              # testes do módulo
  platform/
    domain/
    application/
    infrastructure/
    tests/
shared/
  config/
  observability/
  persistence/
scripts/
  deploy/               # único fluxo de build, backup, deploy, rollback
docs/
  architecture/
  operations/
  archive/              # documentação histórica, não runtime
```

Esta é a direção arquitetural, **não** uma declaração de que essas pastas já existem nem um pedido de renomeação em massa. Manter caminhos atuais até ajustar `deps.edn`, `shadow-cljs.edn`, Dockerfiles, Compose, CI e testes em um mesmo PR.

## Regras de dependência

1. `domain` não importa HTTP, WhatsApp, Datomic, arquivos, ambiente ou bibliotecas de runtime.
2. `application` depende do `domain` e contratos/ports, nunca de detalhes de infraestrutura.
3. `infrastructure` implementa ports de persistência, PokeAPI e integrações.
4. `apps/odisseu` faz a composição JVM; o adaptador WhatsApp apenas interpreta comandos, chama endpoints e apresenta recibos.
5. Uma única autoridade para estado Pokémon/economia: transações canônicas JVM/Datomic. Sem segundo writer ou fallback silencioso para Cassandra.
6. Migrations, schemas, backups, imagens rollback e dados persistidos **não são legado descartável**.

## Inventário obrigatório antes de excluir

Para cada arquivo candidato:
- caminho, objetivo, último uso, imports e referências dinâmicas;
- referência em `deps.edn`, `shadow-cljs.edn`, scripts, Dockerfiles, Compose, CI e testes;
- classificação `active | compatibility | migration | dead | unknown`;
- prova objetiva de que nenhum runtime, CI, rollback, migração ou consumidor depende dele;
- teste de regressão que cobre o comportamento substituto.

Só apagar `dead` com evidência. `unknown` não é deletável. Remover/adaptar documentação Cassandra *após* validar que não governa outros comandos do bot fora do módulo Pokémon.

## Gates antes de integrar na master

1. Inventariar arquivos e branches; comparar PRs ainda abertos com a candidata.
2. Reconciliar os 10 commits exclusivos de `master` com a branch A6 sem sobrescrita.
3. Alterar pastas em lotes pequenos e atualizar referências; cada lote deve compilar e passar testes Node/CLJS/JVM.
4. Verificar invariantes de captura, raid, troca, ovos, PI, PE, XP, moedas, cooldown e idempotência.
5. Efetuar backup frio independente + inventário de saves e checksum; comprovar plano de rollback.
6. Executar CI, revisão, smoke em ambiente descartável e integração por PR revisado.
7. Só após a master integrar todo código necessário, arquivar por tags as pontas exclusivas e remover branches antigas sem PR ativo, sem valor único e sem proteção.
8. Fechar PRs antigos com justificativa quando substituídos, sem apagar o histórico dos PRs.
9. Deploy é procedimento separado, exigindo conferência de SHA, health, WhatsApp READY e integridade de saves.

## Política final de branches

- `master`: fonte canônica integrada, protegida.
- `refactor/a6-runtime-cleanup`: branch temporária ativa de refatoração.
- Nenhuma outra branch deve ser excluída até comparação de commits exclusivos, PRs e proteção.
- Jamais usar `push --force` em `master`, `git reset --hard` na VM ou `docker compose down -v` como parte da limpeza.

## Critério de conclusão

Apenas `master` e a branch de desenvolvimento ativas; nenhuma feature não integrada; CI verde; deploy e rollback reproduzíveis; saves preservados; documentação atualizada e ausência de caminhos de produção legados.
