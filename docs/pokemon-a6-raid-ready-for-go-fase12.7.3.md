# Ready-for-GO — Fase 12.7.3 (intent antecipado)

**Não deployar sem GO explícito.** Produção = **12.7.2**.

| Gate | Status |
| --- | --- |
| Causa raiz documentada | ✓ intent só pós-spawn |
| Domínio + store + upsert | ✓ lead 40min · ensure antes dos ovos · clear em nextAt change |
| Testes obrigatórios 12.7.3 | ✓ |
| Regressão 12.7.2 / 12.7.1 | ✓ |
| Runner `clojure -M:test-raid-auto-intent-fase1273` | ✓ 47/262 |
| Sem migration / schema | ✓ receipt existente |
| Cap 12 · specialReserve 2 | ✓ |
| Spoiler ovo | ✓ |
| Piloto only | ✓ (deploy policy) |

Doc técnico: [`pokemon-a6-raid-auto-intent-fase12.7.3.md`](./pokemon-a6-raid-auto-intent-fase12.7.3.md)
