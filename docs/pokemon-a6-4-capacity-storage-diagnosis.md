# Diagnóstico — Armazenamento Pokémon cheio (A6)

**Data:** 2026-10-29  
**Escopo:** sinalização + regras `ocupação >= capacidade` no save ativo.  
**Sem GO:** código/testes locais; **não** deploy/migration em produção neste documento.

---

## Causa raiz

1. **Capacidade no save ativo já era calculada corretamente** (não usava slot `a` por engano).
2. **UI/API de status/coleção não expunham nem formatavam** `ocupação/capacidade/CHEIO` — só a loja/espaço tinham números crus, e `formatSpace` mostrava “0 vagas livres” sem “CHEIO / N acima”.
3. **`cabe-pokemon?` já bloqueava com `(< ocup cap)`** (= cheio quando `>=`), e a caça já marcava `somenteBatalha=true` no teu save — o bug era de **sinalização**, não de omissão total do bloqueio.
4. **Nível inconsistente:** `shop` usava `:trainer-level`; `cabe-pokemon?` usava só componentes XP (podia cair no nível 1). Unificado via `nivel-para-capacidade`.
5. **`!pk espaco comprar` comprava `mochila` (itens)** em vez de expansão de Pokémon.
6. **Import V1** lia `expansoes-pokemon-50` mas **não** o legado `expansoes-pc` / `capacidade-pokemon` (correção só no código de import futuro — **sem** reaplicar import nos 119).

### Capacidade efetiva do teu treinador (live, RO)

| Campo | Valor |
|-------|-------|
| Save ativo | `recovery-v1` |
| Nível | 10 (`trainerXp` 127) |
| Expansões Pokémon | **0** |
| Capacidade legado | **0** |
| **Capacidade efetiva** | **80** (= 30 + 50 marco nv.5) |
| Ocupação | **119** |
| Excesso | **39** |
| Hunt `somenteBatalha` | **true** (já bloqueava captura) |
| Mochila itens | 3/100 (separada) |

Mensagem alvo: `📦 Armazenamento Pokémon: 119/80 — CHEIO (39 acima do limite)`.

---

## Diff proposto (já no working tree)

| Área | Mudança |
|------|---------|
| `domain/capacity.clj` | `resumo-armazenamento-pokemon`, `colecao-cheia?` (`>=`), nível unificado |
| `bridge_reads` + `hunt` | DTOs com `capacidade/ocupacao/excesso/colecao-cheia?` em trainer, collection, shop, eligibilidade |
| `store` capture | erro `collection_full` com `capacidade`/`excesso` |
| `shop` + `shop_store` | item `espaco-pokemon` (+50); `mochila` continua só itens |
| `pokemon_http` | `espaco comprar` → `espaco-pokemon` |
| `pokemon-jvm-present` | linha CHEIO em status/time/espaço; erro captura; hint caça |
| import transform/inventory | mapeia `expansoes-pc` e `capacidade-pokemon` (futuros imports) |
| Testes | `capacity_storage_test.clj` + asserts present |

**Não altera** os 119 Pokémon, moedas, inventário nem saves em produção.

---

## Testes

```bash
clojure -M:test -e '(require … capacity-storage-test) (run-tests …)'
# 6 tests / 38 asserts OK
node --test test/pokemon-jvm-present.test.cjs
```

Cobertura: abaixo / igual / acima; nível+expansão; over-capacity preservado; switch `a`↔`b`; compra `espaco-pokemon`; bloqueio captura; present CHEIO.

---

## Plano de deploy (aguardar autorização)

1. Cold backup + restore check (padrão A6.4).  
2. Build JVM+Node com este diff; **sem** migration nova.  
3. Deploy JVM → health; depois Node.  
4. RO: confirmar shop/collection `119/80`, `colecaoCheia=true`, fence/contagens 158 intactas.  
5. WhatsApp: `!pk status` / `!pk time` / `!pk espaco` mostram CHEIO; captura falha com mensagem de armazenamento; caça ainda abre batalha.  
6. **Não** reimportar V1; se Cassandra tiver `expansoes-pc` histórico a reconciliar, GO separado de flags só (como bag).

Rollback: imagem JVM/Node anterior; dados de coleção inalterados por este patch.
