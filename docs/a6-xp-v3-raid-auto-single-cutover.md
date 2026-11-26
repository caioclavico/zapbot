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

## Integração com runner oficial (PR #136, atualização pós-bloqueio)

**O runner antigo não pode mais liberar esta release sem o protocolo v3.** Foram alterados:

- `scripts/a6_supervised_deploy.py`: etapa `v3_release_gate` obrigatória antes de `resume_jvm` e `resume_node`, registrada no diário; sem evidência não libera gameplay.
- `scripts/a6_runtime_materialize.py`: adiciona apenas à JVM nova `POKEMON_PROGRESSIVE_XP_V3=true`, `A6_V3_RECONCILIATION_ENABLED=true` e `A6_V3_RECONCILIATION_ALLOWED_ID=abb8955f-7ebe-4084-a634-fd74cfa9bf62`. Preserva todas as demais variáveis originais e não altera Node.
- `scripts/a6_v3_release_gate.py`: exige ensaio isolado documentado, verifica flags realmente presentes na JVM, congela a JVM nova, faz auditoria completa pela **conexão do writer JVM**, exige exatamente o Charmander ambíguo, faz preview SHA-256, CAS/receipt, verifica idempotência e reaudita tudo antes de permitir `resume`.
- `xp_reconciliation.clj` e `bridge.clj`: nova rota read-only `GET /ops/maintenance/a6-v3-xp-audit?pokemonId=...`, acessível exclusivamente com token, flag opt-in e gate frozen. Retorna contagens, bloqueios e fingerprint dos atributos protegidos.
- `scripts/a6-operational-window.sh`: recusa a execução sem `A6_V3_SINGLE_CUTOVER=a6-v3-raid-auto-136` e caminho do relatório de ensaio.

### Prova de ensaio externo (antes da janela)

O runner exige `A6_V3_REHEARSAL_REPORT=/caminho/absoluto/relatorio.json`, arquivo privado `0600`, produzido pelo Codex **após ensaio real do restore isolado**, não escrito a partir de suposições nem copiado de testes sintéticos.

Formato esperado (valores ilustrativos; **substituir somente por resultados medidos**):

```json
{
  "result": "PASS",
  "releaseSha": "SHA_DE_MERGE_40_HEX",
  "jvmDigest": "sha256:DIGEST_JVM_VERIFICADO",
  "nodeDigest": "sha256:DIGEST_NODE_VERIFICADO",
  "restoreIsolated": true,
  "casReceiptAtomic": true,
  "rollbackV3Aware": true,
  "auditBlocked": 0,
  "levelDrift": 0,
  "onlyOneWriter": true,
  "manifestSha256": "HASH_REAL_64_HEX",
  "pokemonCount": 1336
}
```

**Nota:** `pokemonCount` acima é exemplo histórico. O ensaio deve usar a contagem do restore auditado; o runner exige contagem positiva e valida integralmente o estado live antes e depois. O relatório é vinculado ao SHA e aos digests exatos das duas imagens **após o merge**, por isso o ensaio precisa ser revalidado se os binários ou o merge SHA mudarem.

Não considerar esse arquivo uma prova criptográfica independente: o operador deve comprovar a sua procedência mediante logs, hashes e execução documentada no restore isolado. O novo T0 realizado durante a janela continua obrigatório. Um relatório de uma snapshot anterior **não prova a integridade do T0 mais recente**.

### Sequência real antes do resume

1. Runner confere o arquivo de ensaio com SHA/digests e somente então admite a janela.
2. Backup frio e restore isolado do T0 são verificados pelos helpers oficiais; nenhum save live é sobrescrito.
3. Instala uma vez a dupla de imagens do mesmo merge SHA com Node/JVM ainda quiescent.
4. JVM nova confirma as flags v3, mantém Node quiescent e entra em `frozen`.
5. Auditoria read-only na JVM deve mostrar **somente** o Charmander L6/xp21 bloqueado; qualquer segundo problema faz `NO_GO`.
6. Preview, CAS/receipt e retry idempotente no writer da própria JVM.
7. Reauditoria read-only deve indicar `blocked=0`, mesmo número de Pokémon, fingerprint dos atributos protegido igual, receipt confirmado, Charmander L6 e residual 8. Se qualquer dado falhar, manter frozen e bloquear o resume.
8. Somente então restaurar hold e fazer `resume_jvm`/ `resume_node`, terminando em WA READY/healthy. Um resultado pendente/ambíguo no diário impede retomada automática.

**ATENÇÃO:** a implementação de CI testa os contratos em Datomic e Docker descartáveis; é necessário ainda validar a máquina real, o restore mais atual, a exclusividade e o rollback real antes de autorizar merge. Não utilizar a API nova para escrever em live antes disso.

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

## Preparação para atualizações posteriores ao XP v3

O gate desta primeira ativação é específico da release #136: ele exige o
registro ainda bloqueado e não deve ser reutilizado para uma atualização
pós-v3. O Pokémon pode ter evoluído ou recebido XP legítimo depois da
compensação; nível 6 e residual 8 não são invariantes permanentes.

A rota autenticada e restrita a `frozen`
`GET /ops/maintenance/a6-v3-xp-post-audit?pokemonId=...` prepara uma auditoria
sem transações pela conexão do writer. Ela verifica a proveniência do
Pokémon, o conteúdo e o histórico imutável do recibo, a consistência dos
registros v3 e calcula `fullSha256` sobre os datoms atuais, incluindo o
recibo e os campos de XP, exceto `:db/txInstant`. Ausência ou divergência
resulta em `BLOCKED`; essa consulta não reaplica a compensação.

O seletor `A6_V3_PROTOCOL` distingue `INITIAL_V3_ACTIVATION` de
`POST_V3_UPGRADE`. O primeiro mantém todas as exigências da release #136.
O segundo é recusado com `post_v3_t0_audit_not_integrated` antes de liberar o
hold; não é ainda um caminho operacional implementado.

O helper de backup verifica que o manifesto descreve exatamente todos os
arquivos do backup e do restore, sem duplicatas, caminhos externos ou links.
Ele devolve o SHA-256 do manifesto atestado pelo root. Quando presente, o
runner registra esse hash com janela, revisão e digests no diário e o confere
novamente ao retomar com a JVM substituída. Uma cópia preexistente sem etapa
de backup da mesma janela é recusada. Helpers antigos sem o novo hash continuam
compatíveis com o protocolo inicial, mas **não são evidência para pós-v3**.
Instalar o helper atualizado requer provisionamento administrativo revisado;
nenhuma instalação é realizada por este PR ou pela CI.

O teste `scripts/test-a6-post-v3-restore.py` gera um Datomic descartável,
encerra a JVM, copia o diretório e compara a auditoria em outra JVM. Isso
comprova a comparação no cenário sintético testado, não a origem de um T0
de produção. `scripts/a6_post_v3_restore_audit.clj` é uma ferramenta de
ensaio sobre cópias isoladas, não uma autorização para abrir o volume live.
Ela não cria banco nem executa transações; o próprio Datomic Local pode
escrever arquivos internos na cópia, portanto não apontá-la ao backup ou
ao restore original que o manifesto precisa preservar.

O workflow de build isolado usa o HEAD exato do PR, sem segredos de produção
nem publicação de imagens. Seus IDs são digests de configuração locais, não
digests de manifesto de registry nem prova de imagem instalada na VM.
O Node fixa os três pacotes Chromium Debian Security na mesma versão; o ensaio
em CI abre o navegador com sandbox em um perfil descartável e sem rede.
Instanciar `LocalAuth` não autentica uma conta: WhatsApp READY exige uma conta
de teste isolada e homologação separada, sem usar a sessão de produção.

**A rota não libera o runner.** Ainda falta produzir e autenticar a mesma
auditoria sobre o restore isolado do T0 **desta** janela e comparar os
fingerprints antes e depois do cutover, além de integrar a recuperação
idempotente de uma janela pós-v3 e validar rollback. Um relatório antigo ou
uma contagem de entidades não substitui essa comparação. Até que esses
gates sejam implementados e comprovados, deploys posteriores continuam
`DEPLOY_NO_GO`; não contornar o gate #136 nem preencher evidências manualmente.

## Recuperação após CAS Datomic e falha do diário (PR #136)

A operação v3_release_reconcile é crítica: o Datomic pode ter confirmado
CAS e recibo mesmo se o runner morrer antes de journal.done.
**Não repetir o CAS ou uma compensação cegamente.**

O fluxo agora persiste a auditoria BLOCKED inicial e o fingerprint SHA-256 do
preview na intenção durável *antes* de qualquer escrita Datomic.
Em caso de queda, a mesma janela, SHA e digests precisa provar:

1. As imagens corretas, writer único, Node quiescent e JVM frozen.
2. Flag v3 e reconciliação ativas na JVM.
3. Consulta **somente leitura** do recibo pelo writer JVM: resposta duplicate,
   ID do Charmander, nível 6, residual 8 e receiptId esperado.
4. Reauditoria integral e comparação com baseline persistido: blocked=0,
   mesmas quantidades de Pokémon, saves, jogadores, ovos/incubadoras, itens e
   moedas, fingerprint protegido idêntico e exatamente um recibo novo.
5. Registro **atômico** da evidência do recibo e da etapa v3_release_gate no
   diário, ainda sem repetir CAS.
6. Nova verificação read-only antes de permitir resume_jvm e resume_node.

Recibo ausente/conflitante, contagens diferentes, writer extra, fases incorretas,
falha do diário ou quaisquer divergências implicam DEPLOY_NO_GO, manutenção
fechada e nenhum retry mutante automático.

**Ensaio obrigatório em restore isolado:** interromper o runner após o
d/transact REAL e antes da conclusão durável no diário, retomar a mesma janela,
confirmar que não houve segunda transação e que Node/JVM reabriram somente
depois das verificações. Repetir cenário sem commit, fingerprint ou recibo
incorretos e falha de escrita no diário. A suíte CI Python simula a janela,
mas não substitui esse ensaio JVM+Datomic isolado na VM.

