# A6 Pokédex — validação visual e finalização

**Branch:** `feature/a6-pokedex-species-frame`  
**Base:** `dc56a99` (RC A6 announcer+drain)  
**Data:** 2026-11-05  
**Status:** READY (com ressalvas ambientais documentadas abaixo)

## 1. Commits da feature

| SHA | Mensagem |
| --- | --- |
| `b619d82` | feat(pokemon): portar Pokédex de espécie da master para o Node A6 |
| `f0f5f81` | feat(a6): moldura vermelha estilo Pokédex no pipeline de mídia |
| `f003e51` | feat(a6): ligar !pokedex/!dex/!pdx à ficha de espécie no adaptador JVM |
| `123cda2` | test(a6): cobrir Pokédex de espécie e moldura sem caixa preta |

Commits adicionais desta validação (não recriam os quatro acima):

- correção de escala fixa do sprite na moldura (Diglett/Wailord)
- evidências PNG + doc + testes de integração

## 2. Arquivos relevantes

| Papel | Arquivo |
| --- | --- |
| Lookup / legenda (PokeAPI) | `scripts/lib/pokemon-pokedex-species.cjs` |
| Moldura vermelha | `scripts/lib/pokemon-jvm-media.cjs` (`renderPokedexCard`) |
| Handler A6 JVM | `src/zapbot/pokemon_http.cljs` (`executar-especie-pokedex!`) |
| Legado (mesmo card) | `pokemon-service/src/zapbot/pokemon/pokedex.cljs` |
| Router aliases | `src/zapbot/router.cljs` → `!pokedex` / `!dex` / `!pdx` |
| Testes | `test/pokemon-pokedex-species.test.cjs`, `test/pokemon-pokedex-integration.test.cjs` |
| Evidências | `docs/fixtures/a6-pokedex-visual/` |

## 3. Arquitetura

```
!pokedex|!dex|!pdx
    → router → pokemon_http/executar
        → (JVM) executar-especie-pokedex!
            → pokemon-pokedex-species.lookup (PokeAPI + tradução)
            → pokemon-jvm-media.renderPokedexCard (Sharp/SVG)
            → envelope imagem+caption → reply WhatsApp
!pk pokedex
    → JVM getPokedex / coleção pessoal (inalterado)
```

- Domínio de espécie **não** usa Puppeteer (só Sharp).
- Sem Datomic / dual-write / saves.
- Falha de mídia: `render-seguro!` → `nil` → envelope só texto.
- Cache de espécie: memória no processo Node (master usava Cassandra `pokedex-cache`).

## 4. Comparação com a master

| Aspecto | Master | A6 |
| --- | --- | --- |
| Comandos | `!pokedex` `!dex` `!pdx` | iguais (router) |
| `!pk pokedex` | coleção pessoal | coleção pessoal (JVM) |
| Nome / `#NNN` | sim | sim |
| Tipos pt-BR | sim | sim |
| Altura / peso | sim | sim |
| Stats base crus | sim | sim (não suavizados) |
| Habilidades + flavor | traduzidos | traduzidos (Google Translate) |
| Evolução | cadeia + pedras | mesma lógica portada |
| Erro inexistente | `❓ Não encontrei…` | idêntico |
| Erro API | `❌ … PokeAPI fora do ar?` | idêntico |
| Imagem | URL raw / MessageMedia | moldura vermelha + artwork |
| Sorteio sem args | 1..1025 | igual |
| Cache | Cassandra | memória |

Nenhuma mecânica nova de gameplay foi adicionada.

## 5. Evidências visuais

Geradas pelo pipeline real (`lookup` + `renderPokedexCard`), inspecionadas visualmente:

| Arquivo | Observação |
| --- | --- |
| `docs/fixtures/a6-pokedex-visual/pikachu-pokedex.png` | Moldura OK, tela clara, sem caixa preta |
| `docs/fixtures/a6-pokedex-visual/charizard-pokedex.png` | Idem; asas/proporção OK |
| `docs/fixtures/a6-pokedex-visual/gengar-pokedex.png` | Idem |
| `docs/fixtures/a6-pokedex-visual/bulbasaur-pokedex.png` | Idem |
| `docs/fixtures/a6-pokedex-visual/wailord-pokedex.png` | Proporção larga; cabível na tela |
| `docs/fixtures/a6-pokedex-visual/diglett-pokedex.png` | Após correção de escala fixa: legível |
| `docs/fixtures/a6-pokedex-visual/number-150-pokedex.png` | Consulta por número (#150 Mewtwo) |
| `docs/fixtures/a6-pokedex-visual/*-caption.txt` | Legend as master |
| `docs/fixtures/a6-pokedex-visual/manifest.json` | Metadados da geração |

Critérios visuais atendidos: corpo vermelho, LED/botões, tela clara, sprite centralizado, alpha preservado, sem texto sobre a arte, sem caixa preta.

### Correção aplicada na validação

A primeira geração usava `spriteProporcional` (escala por altura de batalha). Diglett ficava pequeno demais na tela.  
`renderPokedexCard` passou a usar **tamanho fixo** (`spriteFixoPokedex`), alinhado ao contain ~360 da master. Evidências regeneradas.

## 6. Testes

```bash
npm run test:pokedex-species
# = node --test species + integration + a42
```

Resultado nesta validação: **pass** (unitários + integração + regressão A4.2 coleção).

Cobertura:

1. Nome / número  
2. Inexistente (404)  
3. Falha PokeAPI (rede)  
4. Imagem indisponível → placeholder  
5. Envelope imagem+caption (sem WA real)  
6. Aliases `!pk pokedex` intactos  
7. Exports raid/trade presentes  

**Não** foram enviadas mensagens a grupos de produção.

Smoke live (PokeAPI real): Pikachu, Charizard, Gengar, Bulbasaur, Wailord, Diglett, Mewtwo — OK.

## 7. Diagnóstico `npm ci` / Puppeteer

### Sintoma
`npm ci` no worktree falhou em `puppeteer` postinstall:

`Failed to set up chrome-headless-shell v146.0.7680.31`  
causa: `Cannot find package 'extract-zip'` a partir de `@puppeteer/browsers`.

### Classificação
- **Não** é falta de disco (133 GiB livres).
- **Não** é versão alterada por nós.
- É falha de **download/extração no postinstall** com árvore incompleta / resolução de `extract-zip` durante o install do Chromium.
- Chromium **já existe** no cache local:  
  `~/.cache/puppeteer/chrome/mac_arm-146.0.7680.31/`  
  `~/.cache/puppeteer/chrome-headless-shell/mac_arm-146.0.7680.31/`

### Relevância para a Pokédex
**Nenhuma.** Renderização usa **Sharp**, não Puppeteer. Puppeteer entra só via `whatsapp-web.js` no bot WhatsApp.

### Mitigação local
- Testes da feature usaram `node_modules` do worktree irmão (já instalado).
- Para install isolado futuro: `PUPPETEER_SKIP_DOWNLOAD=1 npm ci` **depois** de confirmar cache/Chromium, sem mudar `package.json`/lock.
- Não foi feito upgrade indiscriminado de dependências.

## 8. Revisão arquitetural (checklist)

- [x] Domínio espécie sem Puppeteer  
- [x] Visual isolado em `pokemon-jvm-media`  
- [x] Sem dual-write / Datomic  
- [x] Contratos dispatcher A6 (`executar` top-level vs `!pk`)  
- [x] Falha visual → texto  
- [x] Sem refatoração ampla  

## 9. Pendências / riscos

| Item | Severidade | Nota |
| --- | --- | --- |
| Cache Cassandra da master não portado | baixa | só memória; reinício esfria cache |
| Fallback Gemini na tradução ausente | baixa | Google Translate only no módulo Node |
| Smoke WhatsApp real em grupo | ambiente | não feito de propósito (sem prod) |
| `npm ci` limpo no worktree | ambiente | Puppeteer postinstall; irrelevante ao render |

## 10. Prontidão para deploy

**READY** para merge/homologação da feature.

Deploy de produção **não** faz parte desta etapa. Recomendação: incluir no próximo RC Node A6 após review; validar um `!pokedex pikachu` em staging/WhatsApp de teste antes de GO prod.
