# Odisseu A6 — Release conjunto XP v3 + raid auto (UM deploy)

**Release candidate**: `release/a6-xp-v3-raid-auto-single-cutover`
**Fonte XP v3**: PR #134, SHA `1e0690f764d7776bc632d861a59a3d5853b903c8`
**Fonte raid auto**: PR #135, SHA `b29565b912cd9dac67f6e6ccb777fe6a80924646`
**Autorização do proprietário**: auditoria, reconciliação controlada, merge e um deploy combinado (23/11/2026).
**Estado inicial**: `AUDIT_BLOCKED / MERGE_NO_GO / DEPLOY_NO_GO` até todos os gates PASS.

## Objetivo

Entregar **ambas** as funcionalidades numa única revisão da master e numa **única janela com um cutover** dos containers Node e JVM. Não fazer deploy do #134 ou #135 isoladamente; não emitir um deploy após cada merge.

- XP: 9→30 XP por nível Pokémon, sequência 2/3/4/6/8/10 XP independente do nível; flag `POKEMON_PROGRESSIVE_XP_V3=true` somente depois de auditoria e reconciliação efetiva; não alterar PE/PI/moedas.
- Raid: `!pk raid entrar auto` seleciona counter elegível com base no dano canônico contra os tipos do chefe e na sobrevivência ao revide real; index/ativo/participação isolados e intactos.
- `ADVENTURE_AUTO_HATCH=true` e demais flags de produção preexistentes preservadas.
- `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` durante a operação e ao final.

## Fonte do bloqueio XP v3

Cold T0 anterior `v3-t0-20261123T225039Z`, manifesto `b85948c5…`:
39 players, 102 saves, 1.336 Pokémon, 43 ovos, 2.979 moedas; 1.335 conversões puras PASS e 1 bloqueio.

Registro: Charmander ID `abb8955f-7ebe-4084-a634-fd74cfa9bf62`; nível **6**; `xp-desde-nivel=21`; unversioned; `:flat9-ambiguous-legacy-xp`; registro histórico de criação `onboard-starter` sem origem comprovada para XP 21.

**NÃO inventar a origem histórica do valor 21, NÃO usar `21/9` para aumentar níveis e NÃO zerar o registro silenciosamente.**

### Política de reconciliação excepcional (se a origem continuar indeterminada)

Como resolução **administrativa/compensatória**, e **não** alegação de conversão histórica:
1. Preservar o nível **6**, HP, IVs, stats, espécie, shiny, golpes, vínculo ao save e valor bruto **21** em campos de proveniência/histórico e recibo imutável.
2. Para não prejudicar o jogador, conceder no máximo **8/9 XP residual flat-9** (crédito conservador pró-jogador dentro do nível, sem promoção); documentar como compensação manual, não como XP historicamente comprovado.
3. Usar exatamente **um** manifesto privado por ID com CAS de `level=6`, `raw-xp=21`, presença da versão legada e fingerprint do registro; aplicar somente se todas as precondições coincidirem.
4. Validar isoladamente a idempotência do receipt e da operação, preservar 1.336 IDs, verificar **zero drift** de níveis, atributos, economia, incubadoras e ovos; diferenciais permitidos: apenas XP residual, metadados de versão/proveniência e um receipt controlado.
5. Se houver qualquer informação nova que contradiga as precondições, **abortar** e investigar, não cair em fallback.
6. Aplicação no live requer o **writer único autorizado** ou protocolo de manutenção provado; **nunca** rodar `scripts/a6_flat9_ambiguous_reconcile_apply.clj` como segundo writer diretamente no volume de produção. Se não houver mecanismo writer-exclusive auditado, **DEPLOY_NO_GO**.

Esse crédito compensa incerteza; ele não reconstrói os 21 XP originais. O valor original permanece rastreável. O proprietário autorizou as medidas necessárias, mas a integridade tem precedência sobre o prazo.


## Correção na release: writer único e CAS atômico

O script histórico scripts/a6_flat9_ambiguous_reconcile_apply.clj abre outro cliente Datomic. **Ele permanece limitado ao restore isolado e NÃO deve ser usado em produção.**

A release inclui o novo módulo pokemon.infrastructure.datomic.xp-reconciliation, acessível **somente pela JVM writer vigente**:

- GET /ops/maintenance/a6-v3-xp-preview?pokemonId=... retorna fingerprint SHA-256 sem modificar dados.
- POST /ops/maintenance/a6-v3-xp-reconcile recebe um objeto JSON com somente pokemonId e snapshotSha256. Faz CAS do nível, XP bruto, XP residual, versão, XP total e atributos escalares; grava original 21 como legado e recibo exclusivo na mesma transação. A compensação não sobe de nível.
- Usa a conexão do store JVM; não cria segundo client Datomic. Requer token X-A6-Maintenance-Token, maintenance em fase frozen, active-writes=0 e lock do gate para impedir resume durante transação.
- OFF por padrão. Habilitação explícita apenas na JVM supervisionada: A6_V3_RECONCILIATION_ENABLED=true e A6_V3_RECONCILIATION_ALLOWED_ID=abb8955f-7ebe-4084-a634-fd74cfa9bf62.
- Só aceita Charmander nível 6, XP bruto e residual 21, versão ausente e alvo 8/9. Se o T0 atual divergir (inclusive o campo pokemon/xp), **NO_GO**, sem ajustar valores cegamente.

### Ensaio obrigatório antes do merge

1. Novo cold backup e restore isolado (não reutilizar o snapshot anterior como evidência atual).
2. Executar a nova JVM no restore isolado com maintenance frozen e flag de compensação habilitada; obter SHA da prévia, executar POST, verificar CAS, duplicata idempotente e manifesto completo.
3. Preparar e ensaiar imagem JVM alternativa **v3-aware** com a flag POKEMON_PROGRESSIVE_XP_V3=false. Provar que lê Pokémon já salvos em v3, preserva XP residual e continua gameplay seguro. A imagem a4a7807 não é rollback permitido depois da primeira escrita v3.
4. Demonstrar exclusividade real do writer e do volume; nenhuma conexão paralela ou mutação pelo script antigo.
5. Se e somente se tudo PASS, mergear apenas PR #136, construir Node/JVM do mesmo SHA e fazer **um único cutover supervisionado**.

### Aplicação live na única janela

- Preflight WA READY/healthy, hold true, scheduler isolado; colocar Node em quiescent e JVM em frozen, validar backup frio T0.
- Substituir Node/JVM uma vez pelas imagens combinadas, sem abrir gameplay.
- Confirmar que a nova JVM permaneceu frozen, que existe um único writer e que o registro mantém as precondições auditadas.
- Obter preview pelo endpoint autenticado da JVM, aplicar o POST uma vez e provar a resposta duplicate na segunda consulta, sem duplicação de recibo ou XP.
- Verificar nível 6, original 21 preservado e 8/9 flat-9; revalidar contagens, níveis, saves, ovos, moedas, atributos e histórico.
- Apenas depois liberar tráfego, preservar auto-hatch e XP v3, restaurar hold true. Manter rota administrativa inacessível fora de frozen e remover a habilitação em manutenção futura, não forçando outro deploy agora.
- Registrar manifestos, hashes de imagens, T0, recibo, roteiro de rollback e health; se qualquer gate falhar, DEPLOY_NO_GO e não restaurar backup antigo sobre alterações legítimas.

Os testes CI são em base descartável; **não equivalem** à auditoria real, à prova de writer único na VM ou ao rollback ensaiado.

## Gates PRE-MERGE (no release candidate)

- [ ] CI completa na árvore combinada, incluindo `clojure -M:test-raid-counter-selection-a6`, XP v3, JVM/raids, Node e scripts operacionais.
- [ ] Auditoria atual (novo cold T0; não assumir que os números do backup anterior ainda são atuais).
- [ ] Reconciliação excepcional ensaiada em **restore isolado**: `AUDIT_PASS` em **100%** dos registros, `blocked=0`.
- [ ] Fingerprint e manifesto T0 de saves, jogadores, Pokémon, ovos/incubadoras, coins, XP, atributos, HP, itens e recibos conferidos.
- [ ] Exercício de idempotência/concorrência e rollback **v3-aware** (a imagem antiga `a4a7807` é insegura após qualquer escrita v3).
- [ ] Mecanismo exclusivo e idempotente para aplicar a única reconciliação no live, sem abrir segundo writer.
- [ ] Review final do PR de release; head SHA e base SHA validados.

## GO / um único deploy

1. Enquanto a `master` permanecer no último tip estável e `HOLD=true`, **não** mergear PRs #134 e #135 isoladamente. Após os gates anteriores PASS, mergear **apenas este PR de release** em master.
2. Build **uma única** dupla de imagens Node/JVM a partir do **mesmo merge SHA** da master; validar hashes, manifests e pin imutável. Não mudar a imagem de produção antes do cutover.
3. Abrir uma **única** janela operacional via scripts oficiais. Fazer preflight WA READY + Node/JVM healthy + maintenance open + scheduler, depois Node quiescent → JVM drain/freeze, backup cold e restore isolado verificados.
4. Conferir novamente registro-alvo/fingerprint/manifesto e que não houve drift desde o ensaio. Se houve, **parar** e refazer auditoria isolada.
5. Executar a reconciliação via caminho de manutenção do **writer único** (ou fase controlada do novo writer com Node/JVM quiescent), sob recibo/CAS, e comprovar `blocked=0` antes de liberar tráfego de gameplay.
6. Fazer **um único cutover** para as imagens combinadas, aplicando `POKEMON_PROGRESSIVE_XP_V3=true` e preservando `ADVENTURE_AUTO_HATCH=true`; evitar um deploy v3 OFF seguido de outro v3 ON. O cutover deve ser transacional/seguro; se não houver como garantir exclusão de escrita e inicialização segura, **NO_GO**.
7. Conferir 100% da integridade, writer único, JVM+Node healthy, WA READY, maintenance `open`, scheduler, monitoramento de grants (nível somente por XP novo), entrada auto contra chefes de tipos variados e mensagens. Nenhum smoke mutante em live sem escopo e receipt; preferir homologação isolada.
8. Restaurar HOLD=true, guardar relatório com T0, SHAs, imagens, recibos, fingerprints e `DEPLOY_OK` só após invariantes PASS.

## Rollback / falha

- Se falhar **antes da primeira escrita v3**, rollback aos serviços antigos pode ser possível após confirmar compatibilidade e ausência de progresso legítimo novo.
- Após **qualquer** gravação `progressive-xp-v3`, **NÃO** inicializar JVM `a4a7807`; usar binário v3-aware com a flag OFF, preservando registros já v3.
- Nunca restaurar um T0 antigo sobre progresso legítimo posterior; nunca recriar/excluir volumes sem análise.
- Em qualquer gate falhado: `AUDIT_BLOCKED` ou `DEPLOY_NO_GO`, manter os jogos na revisão atual e preservar saves.

## Resultado esperado

**Um PR de release + uma janela operacional + um cutover de Node/JVM.** Os PRs #134 e #135 ficam como origem/revisão e podem ser fechados como incorporados pelo PR de release **somente depois** de a master receber a revisão e de o deploy ser confirmado.

Este documento não declara que a auditoria live, a reconciliação ou o deploy tenham sido executados.