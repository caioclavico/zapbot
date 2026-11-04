# Odisseu A6 — Ativação do Release Announcer (changelog automático)

**Status:** CONDITIONAL · harness físico agora passa sem shim; quatro falhas legadas da suíte ampla permanecem iguais à base limpa. Sem deploy / migration em produção / envio real.\
**Branch:** `rc/a6-release-announcer`\
**Worktree:** `/private/tmp/zapbot-announcer-a6-rc`\
**Base de produção (trocas A6):** código `f4e0972046d360f29081c36184beaa23cd590e09`, tree `a76b14aeb865e677ac4cf867ee6419acee287402`; tip documental posterior `197bf0c06806145aa491b73a9654e5bb22677b90`\
**Commit do código/imagens RC:** `fdf6c5932e2cd30fbef9682a589888ace712c615`, tree `6e5604d425bc85ea33970e424c2ee55aefc7b620`\
**Pacote de imagens:** `/Users/caioclavico/odisseu-recovery/a6-announcer-rc-20261104T144313Z/`

---

## 1. Diagnóstico — por que não há changelog automático hoje

| Fato | Evidência |
|------|-----------|
| Announcer **existe** (fases 12.8.x) | Clone `/private/tmp/zapbot-12.8.30-release-a` (`f70240e` opt-in bootstrap, `fcb8fb8` gates) |
| **Não** entrou nos deploys recentes de Pokémon/trocas | Imagens `odisseu-*-a6-trades-…` / produção atual sem rotas `/v1/platform/releases*` nem `release-bootstrap` no Node |
| Flag default **off** | `RELEASE_ANNOUNCER_ENABLED` default `false` — mesmo com código, READY sozinho não anuncia |
| Sem intent confirmado | Publicação exige `RELEASE_ANNOUNCEMENT_INTENT_PATH` com JSON `status=confirmed` + `rollbackComplete=true` |
| Colisão de migration | Announcer histórico usava `platform/006-release-announcements.edn`; a linhagem platform A6 reserva 006 para `bugs-participantes`, então o Announcer usa 007 |
| Platform store sem `migration-id` | `ensure-schema!` re-transaciona EDNs aditivos por arquivo; registry em `store.clj` é a fonte de verdade |

**Conclusão:** ausência de changelog = Announcer fora do artefato de trocas A6 + opt-in desligado + sem intent — **não** é falha de READY/WhatsApp.

**Proibição:** não reimplantar imagens antigas do Announcer 12.8.x sobre o Node/JVM de trocas (perderia UX de trocas / schema 033).

---

## 2. Estratégia de integração (RC atual)

Integração **cirúrgica** sobre o tip de trocas (`rc/a6-trades-ux` → branch nova `rc/a6-release-announcer`):

1. Extrair somente arquivos do Announcer do RC 12.8.30-release-a.
2. Renumerar migration para **`migrations/platform/007-release-announcements.edn`**.
3. Registrar `007` em `store.clj` **depois** de `006-bugs-participantes`.
4. Rotas HTTP `/v1/platform/releases*` em `handlers.clj` + métodos no `platform-jvm-client.cjs`.
5. Bootstrap opt-in em `core.cljs` / `config.cljs` (`RELEASE_ANNOUNCER_ENABLED=false` por default).
6. Allowlist **independente** via `RELEASE_PILOT_CHAT_ID` + `config/release-chat-autorizado?` — **não** usa `chat-autorizado?` / `ALLOW_ALL_GROUPS`.
7. JVM gate `release-pilot?` também só compara `RELEASE_PILOT_CHAT_ID`.
8. Zero alteração em saves, economia, trocas, raides ou sessão WhatsApp.

### Fluxo desejado (uma publicação por versão×grupo)

```
deploy nova versão
  → Node + JVM healthy
  → WhatsApp READY
  → migration/rollback gates OK
  → intent JSON confirmado (versão implantada = confirmada, rollbackComplete)
  → claim Datomic (lease/fence) + send + ACK_SERVER
  → complete (idempotencyKey = version:contentHash:chatId)
```

READY isolado → `no-release-intent` / `disabled` / `not-ready` — **sem envio**.

Reinício / recreate Node / evento duplicado com mesma chave → `already-completed` ou claim negado — **sem reenvio cego**.

Timeout / ACK incerto → `fail` com `transport_uncertain` — **não** completa; recuperação via reconcile, não resend automático cego.

Rollback / versão não confirmada → intent inválido ou `version-mismatch` — **não anuncia**.

### Changelog (texto ao treinador)

Derivado **somente** do intent/notas aprovadas (`highlights`, `fixes`, `newCommands`, `botName`).\
`render()` não inclui commits, hashes, JIDs, IPs ou infra. Fixture: `docs/fixtures/release-announcement-intent.example.json`.

### Política de releases antigas na ativação

- **Não** gerar intent retroativo para versões já em produção.
- Primeira ativação: `RELEASE_ANNOUNCER_ENABLED=true` **só** com intent da **próxima** release homologada, ou manter `false` até o GO da release seguinte.
- Entidades Datomic inexistentes ⇒ nada a reenviar.

### Expansão multi-grupo (futuro)

Hoje: um `chatId` no intent = piloto.\
Depois: lista explícita (ex. `RELEASE_ANNOUNCE_CHAT_IDS`) — **nunca** reutilizar `ALLOW_ALL_GROUPS`.

---

## 3. Migrations

| ID | Arquivo | Efeito |
|----|---------|--------|
| platform **007** | `007-release-announcements.edn` | Atributos aditivos `:platform.delivery/*` (type, release-version, content-hash, chat, idempotency-key, eligibility, status, claimant, lease, fence, message/ACK…) |

- **Pokémon 033:** intacto; Announcer não toca schema Pokémon.
- Aplicação: no boot JVM, `ensure-schema!` inclui `007` (idempotente se attrs já existirem).
- Homologação: preferir Datomic descartável / cópia antes de produção.
- Esta etapa: **não executar** migration em produção.

---

## 4. Testes (descartáveis) — executados no RC

```bash
cd /private/tmp/zapbot-announcer-a6-rc
node --test test/release-announcer.test.cjs \
  test/release-bootstrap.test.cjs \
  test/release-runtime.test.cjs \
  test/whatsapp-release-transport.test.cjs
# → 14/14 pass
```

| Cenário | Resultado esperado | Cobertura |
|---------|-------------------|-----------|
| Release nova confirmada | `completed` 1× | bootstrap + runtime |
| READY sem intent / disabled | sem envio | bootstrap |
| Gates JVM/WA/node/rollback falhos | `not-ready` / invalid | bootstrap/runtime |
| Grupo ≠ piloto | `group-not-authorized` | bootstrap + JVM `release-pilot?` |
| ACK atrasado / buffer | confirma após associate | transport |
| Timeout / uncertain | `fail` uncertain, sem complete | runtime/transport |
| Restart após associate | reusa correlação, não resend | transport |
| Fence obsoleto | rejeitado | transport |
| Duplicação de eventos | idempotency / already-completed | store + bootstrap |
| Compat trocas A6 + 033 | base = tip trocas; sem diffs em domínio Pokémon | integração RC |

Testes Clojure (`release_store_test`, `release_announcements_test`) copiados; rodar em CI/JVM de homologação com Datomic descartável.

Harness físico (sem prod): `scripts/test-release-physical-harness.cjs` — só em ambiente descartável.

---

## 5. Imagens AMD64

| Item | Valor |
|------|-------|
| Node tag | `odisseu-node:a6-announcer-20261104T144313Z-6e5604d425bc-amd64` |
| JVM tag | `odisseu-jvm:a6-announcer-20261104T144313Z-6e5604d425bc-amd64` |
| Labels | `fase=a6-release-announcer`, `git-tree=6e5604d4…` |
| Build | `…/a6-announcer-rc-20261104T144313Z/build-images.sh` |
| Tars / checksums | `odisseu-{node,jvm}-amd64.tar` + `checksums/IMAGES.sha256` |
| Image ID Node | `sha256:3d2961a15bc301ab023a44454fc0a50fe835bb2acfceced26dd246b1f8e6b91c` |
| Image ID JVM | `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb` |
| Tar Node SHA-256 | `f91827b6f345041e2a03a896631c3ec9a90a4a02dd7ae0fe28d2dc5aae3918bd` |
| Tar JVM SHA-256 | `d96b12b984a6c5fc670545ee02f6a86e57363205b7ed33a6d1c943593755c7cd` |
| BUILD_OK | sim · `/Users/caioclavico/odisseu-recovery/a6-announcer-rc-20261104T144313Z` |


**Não** substituir produção até GO de homologação. Imagens embutem código do Announcer com flag **default false**.

---

## 6. Plano de deploy (homologação → produção) — **não executar agora**

### Homologação

1. Commitar branch `rc/a6-release-announcer` (quando autorizado).
2. Confirmar `BUILD_OK` + checksums.
3. Subir stack descartável (Node+JVM+Datomic) com:
   - `RELEASE_ANNOUNCER_ENABLED=false` no smoke inicial;
   - schema Pokémon 033 + platform 007 via boot JVM;
   - sessão WA descartável ou transport mock.
4. Matriz: reboot sem intent; intent confirmado piloto; recreate Node; WA down; ACK delay; rollback intent; segundo grupo negado.
5. Só então `ENABLED=true` + intent de homolog + `RELEASE_PILOT_CHAT_ID` do grupo piloto de teste.

### Produção (GO futuro)

1. Manter `RELEASE_ANNOUNCER_ENABLED=false` no primeiro recreate (só carrega código + schema 007).
2. Ordem: JVM (007) → health → Node (preservar `.wwebjs_auth`, sem apagar Singleton*) → WA READY.
3. Validar trocas/raides-off/economia inalterados.
4. Na **próxima** release real: gravar intent aprovado no path montado → `ENABLED=true` → recreate Node (ou SIGHUP se aplicável) **sem** anunciar versões passadas.
5. Monitorar logs `[release-announcer]` e entidade Datomic `completed` + ACK.

Env relevantes (exemplos atualizados): `.env.example`, `.env.odisseu-core.example`, `docker-compose.odisseu-core.yml`.

---

## 7. Plano de rollback

| Situação | Ação |
|----------|------|
| Bug só no Announcer | `RELEASE_ANNOUNCER_ENABLED=false` (+ remover/renomear intent) — sem rollback de imagem de trocas |
| Regressão Node/JVM | Recreate com tags **trocas** anteriores (`a6-trades-…-f4e0972…` / hotfix ativo); 007 é aditivo e pode permanecer |
| Envio indevido | Desligar flag; **não** “corrigir” com reenvio; auditar chave idempotente |
| Migration 007 | Attrs aditivos — não exige down-migration; código antigo ignora attrs |

---

## 8. Checklist pré-GO

- [ ] 14 testes Node verdes no tip a empacotar\
- [ ] `BUILD_OK` + SHA-256 dos tars\
- [ ] Homolog: 007 aplicado; Pokémon 033 ok; trocas smoke\
- [ ] `ENABLED=false` até intent da release alvo\
- [ ] Piloto ≠ `ALLOW_ALL_GROUPS`\
- [ ] Sem intent retroativo\
- [ ] Rollback tags de trocas documentadas e tars acessíveis\

---

## 9. Arquivos tocados (RC)

**Novos:** `migrations/platform/007-release-announcements.edn`, domain/store release, testes CJS/CLJ, `scripts/lib/release-*.cjs`, `whatsapp-release-transport.cjs`, fixture intent, este doc.

**Alterados:** `store.clj` (registry), `handlers.clj` (rotas), `platform-jvm-client.cjs`, `config.cljs`, `core.cljs`, `platform_jvm.cljs`, envs, `docker-compose.odisseu-core.yml`.

**Não tocados:** domínio Pokémon/trocas/raides, schema 033, saves, economia, auth WhatsApp.

---

## 10. Homologação final e congelamento — 2026-11-04

### Correção do contrato de cleanup e E2E sem shim — 2026-11-04

O harness criava `platform-jvm-client` via `createClient()`. Esse cliente possui um recurso gerenciado: um `http.Agent` com `keepAlive`, que mantém sockets persistentes. Portanto o cleanup pertence ao cliente; não era correto remover a chamada de encerramento do harness nem simular `close()` com shim. `createClient()` agora expõe `close()` que chama `agent.destroy()`. Um teste de loopback abre uma requisição keep-alive, chama `close()` duas vezes e confirma o fechamento do socket.

O harness fecha o cliente em `finally`, fecha o cliente temporário se a inicialização/health check da JVM falhar e encerra processos filhos que ele próprio criou. Cada worker fecha seu cliente no próprio `finally`. Os cenários continuam usando JVM HTTP real, Datomic Local descartável, processos Node independentes e transporte simulado; nenhum mock substitui o bridge/store e não existe shim, monkey patch ou bypass de validação.

E2E físico sem shim: **PASS**, comando `node scripts/test-release-physical-harness.cjs`; porta loopback dinâmica `49559`, processos JVM observados `68931` e `68972`, diretório descartável `/var/folders/nk/g45_sr2x3dx4y0q84dwf9q4h0000gn/T/zapbot-release-physical-tJYc9I`. Evidências: READY sem intent → zero envios; intent confirmada → uma mensagem sintética ao piloto, `ACK_SERVER`, estado Datomic `completed` e repetição `already-completed`; timeout → `uncertain`, retry não reivindicado e sem segundo envio; morte Node antes de associate → `uncertain`, sem `messageId` e sem retry; morte após associate → restart recupera correlação/ACK e conclui sem novo envio; ACK antecipado conclui após correlação; dois workers disputam claim com um único vencedor; evento concluído sobrevive restart JVM/Node e não reenvia. Os processos criados pelo harness encerraram ao final. IDs e payloads são sintéticos.

O cleanup foi a única mudança em código runtime. O cliente `platform-jvm-client.cjs` é copiado para a imagem Node; portanto a imagem Node anterior **não** representa esta revisão. Uma nova imagem Node deve ser reconstruída após o commit/tree abaixo. O harness/teste/documentação não são copiados para imagem. JVM e migration/schema não mudaram e a imagem JVM existente continua correspondente ao código JVM congelado.

#### Regressões após a correção

| Verificação | Resultado |
|---|---|
| Testes direcionados (release, transporte, cliente HTTP, dispatcher, grupos, trocas) | PASS — 68/68 |
| `node scripts/test-release-physical-harness.cjs` sem shim | PASS — cenários acima |
| `npm run build` | PASS — 854 warnings conhecidos do compilador/dependências |
| `npm test` no RC | 236 testes / 1.407 asserções; 1 falha + 3 erros, mesmas quatro falhas da base limpa `197bf0c06806145aa491b73a9654e5bb22677b90` (3 `re-find must match against a string` em `pokemon_http_test.cljs:183,185,187`; expectativa `Cartões` vs `cartoes` em `pokemon_http_test.cljs:505`) |

Não alterei expectativas nem a implementação Pokémon para mascarar essas falhas. Elas já foram reproduzidas na base limpa em execução anterior documentada; nesta repetição continuam nas mesmas asserções e não há falha nova no circuito do Announcer. A saída integral das suítes Node/Shadow está disponível no histórico local desta homologação.

#### Estado de congelamento

Esta correção modifica `scripts/lib/platform-jvm-client.cjs`, por isso exige commit de código/tree novos e reconstrução apenas da imagem Node AMD64. O build ainda não foi registrado para essa nova árvore; o Image ID Node anterior (`sha256:3d2961a15bc301ab023a44454fc0a50fe835bb2acfceced26dd246b1f8e6b91c`) fica apenas como artefato histórico e não deve ser usado como imagem correspondente à correção. A imagem JVM permanece `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb`, sem alteração de código JVM.

O código e este relatório precisam ser congelados em commits separados após a revisão final do diff. Não houve deploy, migration ou envio real.

### Linhagem Git e produção

### Linhagem Git e produção

O código operacional das trocas implantado em `odisseu-core` é o commit `f4e0972046d360f29081c36184beaa23cd590e09`, tree `a76b14aeb865e677ac4cf867ee6419acee287402`, conforme o relatório do deploy de produção. O tip `197bf0c06806145aa491b73a9654e5bb22677b90` é posterior e contém documentação de pós-deploy e um script de ensaio; não altera os arquivos de runtime implantados. A migration Pokémon `033-trade-public-id` está aplicada. A migration econômica `033` não está neste RC.

O código do Announcer foi versionado em três commits temáticos:

| Commit | Conteúdo |
|---|---|
| `18b4994a20ded4d6c68c6e1f294caeabeb2cb6af` | Migration platform/007, domínio/store Datomic, registry e handlers HTTP JVM |
| `365236d4e336e94bc5fe138c7688f1ecc6b4434f` | Cliente Node, bootstrap opt-in, runtime/adapter WhatsApp e harness físico |
| `fdf6c5932e2cd30fbef9682a589888ace712c615` | Testes Node e Clojure de releases, store, gates, ACK e fences |

Commit do código congelado: `fdf6c5932e2cd30fbef9682a589888ace712c615`; content-tree: `6e5604d425bc85ea33970e424c2ee55aefc7b620`. Esta revisão documental/fixture será um commit posterior separado. Os Image IDs abaixo foram construídos da árvore `6e5604d…`; correspondem ao commit de código `fdf6c59…`, não ao tree posterior que incluirá a documentação final.

No clone `/private/tmp/zapbot-announcer-a6-rc`, a branch é `rc/a6-release-announcer`. Antes do commit documental, o conteúdo não rastreado era este relatório, `docs/fixtures/release-announcement-intent.example.json` e `node_modules/`. A pasta `node_modules/` é dependência local e será excluída do commit. A comparação `rc/a6-trades-ux..fdf6c59` contém 25 arquivos do Announcer (1.325 inserções, 4 remoções); não altera arquivos `modules/pokemon`, migrations Pokémon, saves, economia ou scheduler. O commit de produção `f4e0972` e o tip documental `197bf0c` foram verificados no histórico local; não foi feita nova inspeção remota nesta homologação.

### Imagens existentes

| Serviço | Tag | Image ID completo | Arquitetura | Label `git-tree` |
|---|---|---|---|---|
| Node | `odisseu-node:a6-announcer-20261104T144313Z-6e5604d425bc-amd64` | `sha256:3d2961a15bc301ab023a44454fc0a50fe835bb2acfceced26dd246b1f8e6b91c` | `linux/amd64` | `6e5604d425bc85ea33970e424c2ee55aefc7b620` |
| JVM | `odisseu-jvm:a6-announcer-20261104T144313Z-6e5604d425bc-amd64` | `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb` | `linux/amd64` | `6e5604d425bc85ea33970e424c2ee55aefc7b620` |

Inspeção Docker confirmou ambos os IDs, arquitetura e label tree iguais ao commit de código. A label `org.opencontainers.image.revision` não existe nestas imagens; a identidade rastreável é o label `git-tree` completo e a associação com o commit `fdf6c59` verificada no clone. Não reconstruí as imagens. Os checksums de tar dos artefatos existentes são `f91827b6f345041e2a03a896631c3ec9a90a4a02dd7ae0fe28d2dc5aae3918bd` (Node) e `d96b12b984a6c5fc670545ee02f6a86e57363205b7ed33a6d1c943593755c7cd` (JVM), conforme o manifesto de build arquivado.

### Migration platform/007

O histórico de migrations do domínio platform no clone é `001` a `006-bugs-participantes`; a registry da base operacional também registra 006 e o boot JVM com `ODISSEU_PLATFORM_ENABLED=true` chama `platform-store/make-store`, que instala o schema registrado. Os relatórios operacionais disponíveis confirmam platform ativo, mas não contêm uma leitura direta dos atributos 006 no Datomic depois do último deploy; portanto essa presença é comprovada pela linhagem/fluxo de boot, não por uma nova consulta ao banco live nesta fase. Não existe migration 007 anterior no histórico Git local. A migration 007 contém 20 definições novas, todas sob `:platform.delivery/*`; não adiciona atributo Pokémon, econômico ou de saves. `:platform.delivery/idempotency-key` e `:platform.delivery/message-id` têm identidade única. Lembretes legados não são alterados e as consultas do announcer usam o tipo release.

Ensaio descartável em Datomic Local: schema platform aplicado por `make-store` duas vezes; 131 idents antes e depois, sem alteração na segunda aplicação. Todas as 20 definições da migration foram verificadas como `platform.delivery/*`. Nenhum banco/volume de produção foi acessado ou migrado. A aplicação futura da migration ocorre no boot JVM e é aditiva; rollback de aplicação deve manter os atributos 007 no banco.

### Regressões executadas

| Suíte/ensaio | Resultado |
|---|---|
| Release Node (announcer/bootstrap/runtime/transport) | PASS — 50 testes, 0 falhas |
| Dispatcher, BATTLE_PENDING, cliente JVM, autorização e UX de trades | PASS — 49 testes, 0 falhas; `a64-gate-proof.mjs` PASS em 14 gates |
| Trade E2E sintético Node→HTTP JVM→Datomic | PASS — completa, retry idempotente, recusa, cancelamento, expiração e intruso rejeitados |
| Release Clojure domain/store | PASS — 8 testes, 42 asserções |
| Platform M7.6 bridge + regressões M7.0/M7.2–7.6 | PASS — 50 testes, 182 asserções; banco e identidade sintéticos |
| M3.6 raids/gym/concurrency e regressões | PASS — 82 testes, 482 asserções |
| Scheduler/raid-auto 12.8.2 e regressões 12.7.x | PASS — 66 testes, 361 asserções |
| `npm run build` Node/ClojureScript | PASS; 854 warnings preexistentes do compilador/dependências |
| `npm test` completo no RC | 236 testes, 1.407 asserções; 1 falha + 3 erros de apresentação |
| `npm test` completo na base limpa `197bf0c` | Mesmos 236 testes, 1.407 asserções e as mesmas 1 falha + 3 erros |

As falhas comuns à base são: três `TypeError: re-find must match against a string` em `comandos-de-missoes-preservam-o-filtro-de-apresentacao` (`pokemon_http_test.cljs:183,185,187`), e a expectativa `Cartões` em `perfil-professor-titulo-amizade-rota-evento-whatsapp` (`pokemon_http_test.cljs:505`) quando a saída existente oferece o alias `cartoes`. A comparação não encontrou falha nova introduzida pelo Announcer. Estas falhas permanecem como dívida da base de produção; não foram alteradas nem mascaradas.

Registro histórico: a execução inicial do harness falhou porque chamou `platform.close()` inexistente; uma execução intermediária usou shim temporária. Esse resultado foi substituído pela homologação sem shim documentada na seção “Correção do contrato de cleanup e E2E sem shim”.

### Política de anúncio verificada

- `RELEASE_ANNOUNCER_ENABLED` default `false` nos defaults Node/config/Compose; nenhum anúncio em boot comum.
- READY apenas avalia uma intent; sem intent confirmada retorna inerte. `deployedVersion` e `confirmedVersion` têm de coincidir e `rollbackComplete=true`.
- Releases com `announcementPolicy.enabled=false`, chat fora do `RELEASE_PILOT_CHAT_ID` ou não autorizado pelo gate JVM são negados. `ALLOW_ALL_GROUPS` não participa da allowlist do announcer.
- Não há varredura de releases históricas nem geração automática de intent retroativa. A ativação documentada usa somente intent aprovada da próxima release.
- Chave Datomic determinística por versão + content hash + chat; claim concorrente é protegido por CAS, lease e fence.
- ACK_SERVER (nível 1+) confirma aceitação pelo servidor WhatsApp apenas; não prova entrega ao aparelho nem leitura. ACK desconhecido/insuficiente não completa.
- Resultado incerto fica persistido e não é reenviado automaticamente. Reconciliação é manual; duplicação visual continua possível se o transporte aceitou envio mas a correlação/ACK se perdeu.
- Falhas do announcer são isoladas do fluxo do jogo. Migration Pokémon 033 e scheduler permanecem sem diff neste RC.

### Rollout/rollback futuro — não executado

1. Fazer backup consistente e verificar manifest/integridade; preflight da VM, versões, saúde, volumes, trocas em curso e espaço.
2. Transferir e verificar os dois artefatos AMD64 pelos checksums; confirmar IDs/tree e preservar as imagens atuais.
3. Com GO específico, aplicar `platform/007` no boot JVM; conferir health e atributos em leitura. Não aplicar migration econômica 033.
4. Atualizar JVM e confirmar saudável; atualizar Node preservando sessão WhatsApp; aguardar WhatsApp READY.
5. Manter Announcer disabled e sem intent durante o cutover. Testar bridge/endpoints internos com transporte simulado; validar trocas, scheduler, economia e saves por indicadores sanitizados.
6. Para rollback de aplicação, voltar às imagens de trocas anteriores e manter schema 007 aditivo (sem down-migration destrutiva). Se houver erro somente no announcer, manter `RELEASE_ANNOUNCER_ENABLED=false`. Nunca reverter banco restaurando snapshot antigo sobre dados novos.
7. Habilitar somente após GO futuro específico e intent da próxima release; manter o grupo piloto atual.

Nenhuma etapa de rollout foi realizada aqui: sem deploy, migration em produção, restart/recreate, alteração de saves/allowlist ou envio real.

### Decisão

**CONDITIONAL — E2E físico sem shim, build e gates direcionados aprovados, sem regressão nova; a suíte completa conserva quatro falhas idênticas à base limpa.** O Announcer permanece opt-in e desabilitado por padrão. Nenhuma operação de produção foi feita.

### Atualização de congelamento após correção do cleanup

- Commit de código final: `c948b29195a6c29ae6a12120b2ca378ab3e8ebf6`; content-tree: `7da5d84749a6b09fa3cff31b75e8d4eaa03fab24`.
- Commit documental anterior à atualização final: `103a193adee78b0028775a1a1063e4bc09778ccf`. Esta atualização deve ser registrada em commit documental separado; seu tree não deve ser apresentado como o tree do código.
- Imagem Node reconstruída somente para o novo código: tag `odisseu-node:a6-announcer-20261104T1558Z-7da5d84749a6-amd64`, Image ID completo `sha256:69a7a9624ea190e6c2e2578d4a89a24b29884811b22f24762c2c92be71466dd1`, `linux/amd64`, label `git-tree=7da5d84749a6b09fa3cff31b75e8d4eaa03fab24`. Foi feito smoke isolado `--network none` confirmando `createClient().close()`; Docker reportou host ARM64 e executou a imagem AMD64 por emulação. Nenhum container persistente foi iniciado.
- A imagem JVM não precisou ser reconstruída: `sha256:9bf811fc47488a534848bc0628c3b5b0f6bacdb7b1662774bd60c376339576bb`, `linux/amd64`, label tree `6e5604d425bc85ea33970e424c2ee55aefc7b620`; nenhuma fonte JVM/schema foi alterada.
- A imagem Node anterior (`sha256:3d2961a15bc301ab023a44454fc0a50fe835bb2acfceced26dd246b1f8e6b91c`) é histórica e não representa o cleanup corrigido. Não reutilizar esse ID para a nova árvore.
- `npm run build`: PASS, 854 warnings do compilador/dependências. Testes direcionados: 68/68 PASS. `npm test`: 236 testes/1.407 asserções, com 1 falha e 3 erros nas mesmas quatro asserções já reproduzidas na base limpa `197bf0c06806145aa491b73a9654e5bb22677b90`; sem falhas novas identificadas.
- A build da imagem concluiu e o smoke do método de cleanup passou. Não houve deploy, migration, restart live, ativação de announcer ou envio real.
