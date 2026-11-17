# M4.6 — Mídia e apresentação da caça (adaptador JVM)

## Auditoria do legado

| Artefato | Origem | Dependência Cassandra? |
| --- | --- | --- |
| Arena por bioma (`svg-arena-cacada` + cenário) | `core.cljs` | Não — só cores/`id` do bioma |
| Sprites proporcionais + CDN jsDelivr | `core.cljs` / `imagens.cljs` | Não — URLs PokeAPI |
| Sobreposição de golpe/status/shiny | `core.cljs` | Não — texto + last-action |
| Cartão da Pokébola | `core.cljs` | Não |
| Cartão do treinador (Ash + sprite) | `core.cljs` | Parcial — asset local + sprite |
| Grade da coleção (12 por página) | `core.cljs` | Acoplada a filtros/layout legado |

## Separação

| Camada | Responsabilidade |
| --- | --- |
| **JVM** | DTOs com `area.{ceu,chao}`, sprites, HP, golpes, bioma no encontro/turno |
| **Node `pokemon-jvm-media.cjs`** | Download + sharp (arena, captura, sprite leve) |
| **Node `pokemon-jvm-present.cjs`** | Texto WhatsApp (pt-BR) |
| **`pokemon_http.cljs`** | TX JVM **antes** da mídia; falha de imagem → texto; `effects: []` |

Mídia **não** é efeito de rank nem recibo Datomic. Reenvio usa o mesmo `operationId` (idempotência JVM) + chave de entrega WhatsApp.

## Imagens preservadas

| Situação | Arquivo | Status |
| --- | --- | --- |
| Encontro selvagem | `batalha-selvagem.png` | Arena + bioma + sprites |
| Turno / vitória | idem / fuga | Arena + overlay de dano |
| Captura | `captura-*.png` | Pokébola |
| Treinador | `treinador-ativo.png` | Sprite do ativo (sem cartão Ash completo) |
| Coleção em grade | — | **Adiada** (layout 12 tiles legado) |

## Ainda falta

1. Grade visual de `!pk time` (12 sprites).
2. Cartão Ash completo do treinador.
3. Overlay rico de todos os tipos de golpe (parcial: fire/water/grass/electric + genérico).
4. PvP / ginásio / raid (fora do escopo).

## Testes

```bash
./scripts/test-m4-hunt.sh
# ou só mídia:
node --test test/pokemon-jvm-media.test.cjs
```
