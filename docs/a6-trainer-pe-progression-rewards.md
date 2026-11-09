# Odisseu A6 — PE progressivo e prêmios de nível

## Estado

Implementação isolada na branch `feature/trainer-pe-progressive-rewards`, baseada no commit live `8ef41c8da1c28bfb69d57bade81a5a41e9225d44`. Nenhuma alteração foi aplicada à produção ou ao workspace original. A migration `pokemon/036` é aditiva e **não foi aplicada**.

Decisão: **GO para integração e build de homologação; NO-GO para deploy até existir imagem compatível de rollback pinada**. A suíte direcionada de progressão e Datomic passou após o ensaio upgrade/rollback compatível/re-upgrade. As falhas amplas conhecidas continuam reproduzidas na base limpa. Esta homologação encontrou e corrigiu duas regressões: datoms conflitantes de inventário e reinterpretação do PE acumulado pela curva antiga após progresso 036 persistido.

## Implementação

- Curva: PE para o próximo nível é `10 + 5 × (nível atual - 1)`.
- Os níveis existentes são preservados. Saves sem progresso novo inicializam o PE residual pela curva histórica 5, 7, 9…; saves com `trainer-xp-level-progress` persistido usam esse residual como fonte autoritativa.
- PE excedente é carregado entre níveis e pode gerar múltiplos níveis na mesma transação.
- O transator JVM enriquece a transação original. Nível, residual, pacote, inventário, pendências por mochila cheia, registro idempotente por nível e resumo do recibo são gravados atomicamente no Datomic.
- Um registro pequeno e individual por nível mantém o histórico abaixo do limite de valor do Datomic Local. A chave de identidade é `save-key|level-reward|level`.
- O sorteio é executado na JVM e usa SHA-256 de `operation-id + nível` como semente. Retentativas reproduzem o mesmo pacote; o ledger impede concessão duplicada.
- A notificação é anexada ao resultado da operação e apresentada pelo Node. Resultados duplicados não repetem a mensagem. Em operações com vários participantes, o enriquecimento ocorre por save.
- Operações que concedem item e PE na mesma transação consolidam as quantidades por item antes do commit. Isso evita dois valores `:inventory-item/qty` para a mesma entidade quando, por exemplo, uma missão paga Poké Bolas e também gera level-up.
- O resultado recuperado do receipt repete o resumo de level-up sem expor `save-key` ou `operation-id` internos.
- Após `:save/trainer-xp-level-progress` existir, a JVM usa `:save/trainer-level` e o resíduo persistido como par canônico. A curva antiga só inicializa saves sem resíduo progressivo; ela não pode elevar o nível de um save já migrado.
- A alocação respeita a capacidade existente da mochila. Itens que não cabem ficam em `:save/recompensas-pendentes-edn`; não são descartados.
- Marcos de 5 níveis reutilizam a regra de expansão existente: +50 espaços de Pokémon nos níveis 5, 15, 25… e +50 de mochila nos níveis 10, 20, 30… O pacote normal continua sendo concedido.
- Níveis múltiplos de 10 recebem o pacote especial adicional. Nível 50 recebe o pacote comemorativo em lugar do pacote especial comum, além do pacote normal.

## Catálogo e balanceamento

O catálogo foi verificado contra os itens reais do jogo. O cartão-presente existe no catálogo, pode ser comprado por 20 moedas, aparece em recompensas de missões/eventos e possui envio canônico JVM: o remetente consome um cartão, o destinatário recebe itens via alocador de capacidade, e a transação usa CAS de revisão e receipt único. A descrição antiga da loja que dizia que o fluxo ainda estava bloqueado está desatualizada: o comando `sendGift` está ligado à bridge e à implementação Datomic. O pacote de nível pode conceder **no máximo 1 cartão por nível**, nunca concede moedas, e usa o mesmo ledger de nível idempotente. A mochila cheia mantém o cartão como pendente, sem descartar ou transferir automaticamente.

Pesos interpolam linearmente entre nível 1 e 50. Cada pacote normal escolhe 2 ou 3 tipos distintos sem reposição; quantidades de Poké Bolas e Grandes Bolas aumentam suavemente nos níveis altos.

| Item | Peso nível 1 | Peso nível 50 | Ocorrência simulada em pacote normal |
| --- | ---: | ---: | ---: |
| Poké Bola | 44,5 | 21 | 66,39% |
| Grande Bola | 15 | 20 | 45,29% |
| Ultra Bola | 2 | 12 | 19,39% |
| Poção | 17 | 13 | 40,18% |
| Poção Máxima | 2 | 12 | 19,33% |
| Frambo | 12 | 8 | 28,44% |
| Frambo Dourada | 1 | 5 | 8,81% |
| Cartão-presente | 0,5 | 1 | 2,31% |
| Reviver | 6 | 8 | 19,78% |

Simulação de 10.000 pacotes normais, 200 em cada nível de 1 a 50: média de 2,499 tipos por pacote. Frequência observada de cartão-presente: 231/10.000 (2,31%); máximo de um por pacote. Unidades normais: 16.165 Poké Bolas, 6.946 Grandes Bolas, 1.939 Ultra Bolas, 4.018 Poções, 1.933 Poções Máximas, 2.844 Frambos, 881 Frambos Douradas, 231 cartões-presente e 1.978 Reviver. Sem moedas.

Do nível 1 ao 50, a curva requer 6.370 PE acumulados. Uma simulação reprodutível dos pacotes de nível 1 a 50 gerou 198 unidades incluindo especiais (2 cartões), abaixo da capacidade de mochila existente de 300 no nível 50, partindo de mochila vazia. Jogadores legados mantêm o nível salvo e o PE residual derivado da curva antiga quando ainda não persistido. Expansões compradas permanecem somadas aos marcos; capacidades legadas mantêm o limite já maior via a regra existente de `max`. Inventário cheio foi testado separadamente e mantém excedentes pendentes.

### Fontes e impacto dos consumíveis

- **Poção Máxima:** comprável por 60 moedas; também consta no alocador comum e pode chegar por recompensas existentes. A nova chance é cerca de 19,3% por pacote normal, limitada a uma unidade por nível.
- **Reviver:** não é vendável; já é sorteado em 20% por missão diária resgatável e aparece em recompensas de eventos/raids. A nova incidência é cerca de 19,8% por pacote, e a frequência de level-up é muito inferior à de resgates diários.
- **Frambo:** comprável por 10 moedas e também aparece em missões, eventos e raids. A nova incidência é cerca de 28,4% por pacote, uma unidade por nível.
- **Cartão-presente:** custa 20 moedas e também vem de missões/eventos; 2,31% dos pacotes na simulação, no máximo um por nível. Seu uso converte o cartão em itens para outro jogador pelo fluxo transacional existente; não cria moedas.

Essas frequências são ocorrências por pacote normal; níveis 10/20/30/40 e 50 têm bônus especiais separados. Itens no pacote são distintos, quantidades normais são uma unidade exceto pelas quantidades progressivas das bolas.

## Arquivos

- `xp.clj`: curva progressiva, carry-over e compatibilidade de perfil legado.
- `trainer_rewards.clj`: catálogo real, pesos, quantidades e pacotes especiais determinísticos.
- `trainer_progression.clj`: integração atômica com o transator, capacidade, ledger e recibos.
- `migrations/pokemon/036-trainer-level-rewards.edn`: progresso residual e ledger por nível.
- `store.clj`, stores de perfil/eventos/batalha: integração dos caminhos JVM que concedem PE.
- `mission_rewards.clj`: a ordem global de alocação de missões foi preservada; apenas o alocador dos prêmios de nível inclui Poções.
- `pokemon-jvm-present.cjs` e `pokemon_http.cljs`: apresentação consolidada do level-up.

## Validação

- Build Node/ClojureScript: **PASS**, compilação concluída (106 arquivos; avisos de inferência/depreciação existentes no projeto).
- Regressões finais JVM/Datomic de progressão: **16 testes, 1.380 asserções, PASS**. Incluem ciclo upgrade → PE e múltiplos níveis → reconstrução usando código compatível → novo PE → retry; migration 036 antes/depois com save legado; preservação de nível, resíduo inicializado, capacidades, itens e ledger.
- Migration 036: instalada em Datomic descartável após as migrations 001–035. Snapshot de nível 20, 437 PE, expansões, capacidades legadas e inventário permaneceu igual; a migration não preencheu resíduo nem alterou os registros. Primeiro PE posterior avançou segundo a curva nova, preservando nível e capacidades.
- Prova de downgrade: com 50 PE, a aplicação nova persiste nível 4 e resíduo 5; a função da curva antiga interpreta 50 como nível 6. A versão anterior é portanto incompatível após a primeira escrita progressiva.
- Regressões amplas após a correção: **37 testes/334 asserções, 11 falhas conhecidas, 0 erros** com loopback. São as mesmas 10 expectativas históricas de `missions-a433-test` e a falha conhecida de saldo `nil`/`0` no profile; comparações anteriores na base limpa reproduziram esses casos.
- Testes de concorrência de colisão de receipt: **3/3 PASS**; antes da correção falhavam por dois datoms conflitantes de `:inventory-item/qty` para a mesma entidade. O conflito foi reproduzido, localizado e eliminado.
- Contrato HTTP e reconciliação de receipt: **12/12 PASS** após restart/retry; a resposta duplicada conserva o level-up e não expõe identificadores internos.
- Cartão-presente canônico: **5 testes, 38 asserções, PASS** para envio/retry, concorrência, mochila cheia, seleção de save e troca concorrente do destinatário.
- Presenter Node (level-up + regressões do presenter): **24/24 PASS**.
- `npm run test:http`: **287/287 PASS** em executor com loopback.
- `npm test`: recursos **87/87 PASS**; suíte CLJS **236 testes/1.407 asserções**, com 1 falha e 3 erros de apresentação. Comparação na base limpa `8ef41c8` reproduziu exatamente os mesmos quatro casos (`pokemon_http_test.cljs`, filtros de missões e label “Cartões”). Nenhum deles é nova regressão deste branch.
- `trainer-profile-a64-test`: **7 testes/49 asserções**, uma falha preexistente sobre saldo desconhecido (espera `nil`, recebe `0`), reproduzida com o mesmo resultado na base limpa.
- `missions-a433-test`: **10 falhas/208 asserções**, mesmas dez na base limpa; são expectativas históricas de recompensas divergentes. Os testes direcionados de colisão e HTTP dessa suíte foram executados isoladamente e passaram após a correção.
- Build Node/ClojureScript e `git diff --check`: aprovados no commit inicial; as alterações de balanceamento são somente JVM e passaram no conjunto direcionado.

O executor com loopback permitiu executar todas as suítes HTTP solicitadas. A suíte CLJS rodou até o fim; suas quatro falhas foram verificadas na base limpa. A falha do profile também foi comparada diretamente entre candidato e base limpa.

## Gates e riscos

1. Antes da primeira escrita progressiva, rollback apenas de aplicação para o binário anterior é possível; manter 036 instalada. Depois da primeira escrita, o binário pre-036 fica proibido: ele ignora o resíduo progressivo e pode exibir/aplicar um nível maior.
2. Revisar/aceitar as quatro falhas ClojureScript preexistentes, a falha de perfil conhecida e as dez expectativas históricas de missões antes de integração ampla.
3. Garantir que qualquer ambiente de homologação instale `pokemon/036` antes de iniciar código que grava o novo progresso/ledger.
4. Nenhuma migration, backfill, deploy ou alteração de save real ocorreu nesta tarefa.

**Resultado: GO para integração e build de homologação; NO-GO para deploy até o preflight final das imagens e pin do runbook.** Nenhuma migration, backfill, deploy ou alteração de save real ocorreu.

### Rollback da migration e do código

`036` somente adiciona atributos e não altera entidades existentes. Não criar migration reversa, não retrair schema/ledger e não restaurar backup antigo. Antes do primeiro write progressivo, o binário anterior pode ser usado com 036 ainda presente. Depois do primeiro write, só é permitido voltar para uma imagem de rollback que mantenha a leitura do nível/resíduo 036, a curva progressiva, o ledger por nível, itens pendentes e recibos. O binário pre-036 não pode servir como rollback. Esta revisão constrói o alvo JVM migration-aware a partir do commit registrado abaixo. Ele é um rollback operacional para uma falha de versão posterior, não um downgrade para o comportamento antigo. Se a própria progressão precisar ser desativada após writes, parar a admissão mutável e fazer forward-fix; não reintroduzir a curva anterior.

### Plano da migration 036

1. Fazer backup consistente e registrar nível, `trainer-xp`, expansões/capacidades e itens agregados antes da janela.
2. Aplicar 036 (schema aditivo e idempotente) antes de iniciar qualquer writer do novo código; não executar backfill.
3. Conferir atributos e tipos, e comparar a fotografia legada após a instalação do schema.
4. Implantar a JVM e Node da mesma árvore congelada, validar progresso e ledger em smoke controlado.
5. Em rollback pré-primeiro-write, reverter somente a aplicação e manter 036. Em rollback pós-write, usar exclusivamente a imagem migration-aware pinada. Nunca restaurar backup antigo sobre progresso novo.

### Artefato de compatibilidade

- Commit: `aacc6534642cbb4270bb2f5861ccf5e369ea4f9e`.
- Git tree: `8a6856263394e4d8bc6cdc7ae82420b903ac8864`.
- JVM AMD64 local, construída de `Dockerfile.jvm`: `sha256:4fb14d7e7b46f030dfedd7d2fa1a806ac308c770aafc378b92101e75b9e20f48`.
- Node AMD64 local, bundle construído sem erro, Node 22.23.4/Chromium 156: `sha256:fe04c51e2bada9eec8375749cfa6e81bafa848ae2ad31ef7f402ee8a89e463d0`.
- Smokes descartáveis sem rede/volumes: namespaces JVM carregaram e `node --check target/main.js` passou. As imagens foram construídas do mesmo código de runtime antes do commit documental final; nenhuma foi carregada em produção.
- Estas imagens são anchors de rollback compatíveis que preservam a feature e os dados; não revertem a nova curva. Fixar os dois digests na configuração de uma janela aprovada, sem voltar ao binário pre-036.

### Colisão transacional encontrada na homologação

Quando uma transação original e o pacote de level-up alteravam o mesmo item, ambos escreviam quantidade absoluta diferente em `:inventory-item/qty`. Datomic rejeitou a transação (`tx_datoms_conflict`) e uma colisão concorrente de receipt impediu ambos os claims. A correção calcula o delta já presente na transação original, agrega a recompensa e emite um único valor final para cada chave de inventário tocada. Teste Datomic reproduz o caso com quantidade preexistente, concessão original e pacote de nível; teste de concorrência e retry HTTP também passaram. Nenhuma exceção foi ocultada e não foi adicionado retry genérico.
