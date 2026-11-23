# A6 gameplay hotfix

Branch: `feature/a6-gameplay-hotfix-unified` (base `master` `180a16e`).

**DEPLOY_NO_GO.** Este documento não autoriza merge nem deploy.

## Diagnóstico

### Biomas e climas

- `!pk cacar <nome>` usava um único erro quando o texto não era bioma e quando o bioma existia mas estava fora do horário.
- Agora: `:area_unknown` (nome fora do catálogo) e `:area_unavailable` (bioma canônico fechado neste período).
- Aliases só apontam para os 6 ids existentes (`verde`→floresta, `azul`→praia, `rochosa`→caverna, `pokemon`→cidade, `encantado`→lago, `rubro`→vulcão). Não há bioma novo.
- Lookup aceita acento, caixa, hífen/underscore e espaços internos.
- Fora do período Dia, o sorteio `ensolarado` vira `ceu-limpo`. `obter-clima` de "ensolarado" continua Ensolarado. Período (Madrugada/Dia/Entardecer/Noite) e clima são linhas diferentes na apresentação.

### Ovos

- `!pk chocar` passa por `shouldRenderHatchReveal` + `renderHatchRevealCard`, no mesmo caminho do auto-hatch. Coleção cheia não gera PNG.
- Ovo efetivamente concedido (não adiado) anuncia a dica de incubar em `formatConfirmedPiLines` e pede PNG via `renderEggGrantCard` (egg-cards). Inventário cheio não renderiza o ovo.

### Raid `!pk 1` / `!pk atk 1`

- Sem escopo, `resolveScopedCommand` ainda classifica `1` e `atk` como `atacar` com `scope` null.
- Com raid elegível, o handler de ataque **não** chama `raidAttack` nem `confirmBattleTurn`. A resposta é a mensagem educacional (`formatRaidBareAttackBlocked` / o mesmo texto em `pokemon_http.cljs`): usar `` !pk raid atk 1 ``. Nenhuma ação é executada.
- `` !pk raid atk 1 `` continua no escopo `raid`.

### Paridade de chat

- Rotação de bioma e clima é global no fuso `America/Sao_Paulo`. `mundo.clj` e `hunt.clj` não ramificam gameplay por `chatId`.
- Eventos temáticos podem carregar `chat-id` na ocorrência. Isso não altera o catálogo de biomas.

## Catálogo

### Biomas (6)

| id | nome | alias |
| --- | --- | --- |
| floresta | Floresta Verde | verde |
| praia | Praia Azul | azul |
| caverna | Caverna Rochosa | rochosa |
| cidade | Cidade Pokémon | pokemon |
| lago | Lago Encantado | encantado |
| vulcao | Vulcão Rubro | rubro |

### Climas (6 + céu limpo)

| id | nome | nota |
| --- | --- | --- |
| ensolarado | Ensolarado | só no período Dia |
| chuvoso | Chuvoso | |
| nublado | Nublado | |
| ventania | Ventania | |
| neve | Neve | |
| nevoeiro | Nevoeiro | |
| ceu-limpo | Céu limpo | substitui ensolarado fora do Dia; não entra na rotação de 6 |

## Gates

| Gate | Estado |
| --- | --- |
| DEPLOY_NO_GO | **ativo** — sem merge, sem deploy |
| DEPLOY_GO | não |
| `:area_unknown` ≠ `:area_unavailable` | teste JVM |
| aliases dos 6 biomas (acento/caixa/espaço/composto) | teste JVM |
| noite ensolarado → céu limpo; Dia permanece Ensolarado | teste JVM |
| período ≠ clima na apresentação | teste Node |
| `!pk chocar` PNG como o auto-hatch | `pokemon_http.cljs` |
| ovo concedido: PNG + dica de incubar | present + media |
| `!pk 1` / `!pk atk 1` na raid não atacam | mensagem educacional; teste Node |
| biomas sem fork de `chatId` | auditoria no teste JVM |

Não promover este branch a deploy enquanto o gate estiver `DEPLOY_NO_GO`.
