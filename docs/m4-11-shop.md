# M4.11 — Loja e itens (Datomic)

## Escopo

Catálogo, compra transacional, inventário por save e uso seguro de **curas de status** e **poções** no adaptador Node → JVM → Datomic.

**Fora desta etapa:** kit/diário/resgatar, presentes, MT, equipar, missões, PvP, Adventure auto-grant.

## Catálogo à venda (preços preservados)

| item-id | Preço |
| --- | ---: |
| cartao-presente | 20 |
| atadura / antidoto / paralisia / despertar / degelo / persim | 15 |
| fruta | 10 |
| pocao | 20 |
| pocao-maxima | 60 |
| restos / banda / oculos / faixa-foco | 45 / 40 / 40 / 55 |
| mt / mochila | 200 |

Não à venda: bolas, pedras, reviver, catalisador, fruta-dourada.

## Comandos habilitados

| Comando | Ação |
| --- | --- |
| `!loja` | Catálogo + saldo |
| `!loja comprar <item>` | Compra 1 (fence + operationId) |
| `!loja detalhes <item>` | Descrição |
| `!mochila` | Inventário do save ativo |
| `!pk curar [n]` | Cura status (escolhe item pelo status) |
| `!pk pocao` / `pocao-maxima` `[n]` | Cura HP |

## Bloqueados

- `!mochila kit|diario|resgatar`
- `!presente`, MT, equipar, `espaco comprar`
- Uso de cartão-presente / MT / equipáveis (podem ser comprados, uso bloqueado)

## Divergências

1. Expansão `mochila`: +50 via `expansoes-mochila-50` (pokemon-service / `capacity.clj`), não +25 direto do `src` legado antigo.
2. Ginásio `pocao`/`fruta` motivação continua no fluxo `!pk gin` (já M4.7), não neste `use-item`.
3. Sem Cassandra no gameplay JVM.

## HTTP

| Método | Rota |
| --- | --- |
| GET | `/v1/shop` · `/v1/inventory` |
| POST | `/v1/shop/buy` · `/v1/items/use` |

## Testes

```bash
./scripts/test-m4-shop.sh
```
