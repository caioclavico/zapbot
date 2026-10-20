# M9.6.1 — Imagem dos três Pokémon iniciais

**Branch:** `refactor/modular-monolith-datomic`  
**Escopo:** cartão visual único (Bulbasaur + Charmander + Squirtle) em `!pk iniciar` / `!pk inicial`  
**Sem:** alteração de regras de negócio, kit, Datomic, TX ou deploy

---

## Problema

M9.6 enviava só o sprite do Bulbasaur; Charmander e Squirtle ficavam só no texto.

## Solução

`renderStartersCard` em `scripts/lib/pokemon-jvm-media.cjs`:

- PNG horizontal 960×540 com 3 painéis lado a lado.
- Número (1–3), sprite (PokeAPI/CDN + cache LRU em memória), nome e tipo em PT (Planta/Veneno, Fogo, Água).
- Sprite ausente → placeholder SVG (mesmo mecanismo da arena).
- Falha de render → `null` → mensagem de texto segue (`render-seguro!`).

Node (`pokemon_http.cljs`) chama o cartão após `startJourney` quando `awaiting_starter`, e também em `!pk inicial` sem argumento.

## Arquivos

- `scripts/lib/pokemon-jvm-media.cjs` — `renderStartersCard`, cache de download, `tiposPt`
- `src/zapbot/pokemon_http.cljs` — wire iniciar/inicial
- `scripts/lib/pokemon-jvm-present.cjs` — labels PT no menu textual
- `test/pokemon-jvm-media.test.cjs` / `test/pokemon-jvm-present.test.cjs`
- este relatório

## Testes

```bash
node --test test/pokemon-jvm-media.test.cjs test/pokemon-jvm-present.test.cjs
```

Cobertura: layout PNG, fallback sem download, tipos PT, menu textual, escolha inalterada (sem TX de mídia).

## Restrições

Gym WIP preservado; `cutover-ready?=false`; sem commit/push/deploy neste marco até autorização.
