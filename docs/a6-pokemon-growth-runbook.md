# Runbook — Deploy growth-v1 (XP Pokémon / 037)

## Pré-requisitos

- Tip live com PR #75 / schema **036** e PE do treinador.
- Backup cold dos volumes cutover (datomic/journal/maint/data).
- Announcer permanece `suspended` (esta entrega não o altera).
- Drain JVM homologado.

## Deploy (GO separado)

1. Build imagem JVM amd64 do tip com 037 + growth.
2. Drain → freeze → stop JVM (Node inalterado se só JVM).
3. Subir JVM nova; `schema/install!` aplica **037** (aditiva).
4. Health: bridge up, raids conforme flag live, WhatsApp READY.
5. Smoke: `!pk` ficha mostra XP/barra; grant de combate sobe nível em mon de teste; PE treinador intacto.
6. **Não** rodar backfill dos 759 nesta janela — conversão lazy.

## Rollback

- 037 é aditiva (inclui `:pokemon/xp-legacy-nivel`). Rollback de **imagem** para tip 036-aware (PE) é seguro **antes** de writes growth-v1.
- Após writes `growth-v1`, não voltar a binário que ignore `xp-total` e reaplique limiar 9 sobre residual já convertido.
- Atributos 037 órfãos em rollback de imagem são inofensivos (aditivos).
- Forward-fix preferível; cold restore só com GO explícito.

## Não executado (produção)

- Deploy live / migration 037 live / backfill
- Freeze ou alteração de Node/JVM/Announcer/deploy-hold

## Regressão conhecida (fora de escopo)

`vitoria-preserva-recompensas-anteriores` falha no tip limpo pós-#75 (stash baseline). Não introduzida por growth-v1.

## Veredito desta entrega

**Código + testes + docs em branch/PR.** Sem deploy live, sem migration live, sem backfill.
