# Checkpoint — Joy slim bed + imagens master (treinador/loja/joy/missões)

**Quando:** 2026-10-31 ~14:32–14:36 UTC  
**Host:** odisseu-core · `35.192.113.146`  
**Motivo:** Chandelure/Nidoqueen → `DATOMIC_VALUE_TOO_LARGE` em `!pk joy`; restaurar PNGs estáticos do legado.

## Escopo

| Incluído | Excluído |
|----------|----------|
| JVM: `joy/bed-write-attrs` + cascade encode (gzip) | Economy ledger wiring |
| JVM: `slim-golpe-for-battle` / `encode-value-edn` públicos | Migrations / reimport |
| JVM: `adventure_store` usa bed-write (não `pr-str` cru) | Volumes a56 (preservados) |
| Node: `resolveJoyTheme` + `renderStaticAsset` / `renderTrainerCard` | |
| Assets: Joy / hospital / Ash / loja / professor | |

## Regras de imagem (espelho master `tema-evento-da-resposta`)

| Comando | Tema |
|---------|------|
| Joy send (“A Enfermeira Joy recebeu”) | `enfermeira-joy.png` |
| Joy status (“Enfermeira Joy”) | `enfermeira-joy-tratando.png` |
| Joy saudável / hospital | `centro-pokemon.png` |
| Bloqueio “não pode enviar” | sem imagem |
| `!loja` catálogo | `loja-pokemon.png` |
| `!pk treinador` | Ash + sprite ativo |
| `!pk missoes` (lista) | `professor-carvalho.png` |

## Imagens

| | Tag |
|--|-----|
| JVM / Node | `a64-joy-slim-imgs-20261031T143209Z-amd64` |
| Rollback | `a64-pk-filtros-prof-20261031T140229Z-amd64` |

Compose bak: `docker-compose.release-battle-idle.yml.bak-pre-a64-joy-slim-imgs-20261031T143209Z-amd64`  
Meta: `~/odisseu-recovery/a64-joy-slim-a64-joy-slim-imgs-20261031T143209Z-amd64/`

## Testes locais pré-deploy

- Smoke bed Chandelure-like: raw **10116** → edn **2310** (≤4000)
- `pokemon.tests.adventure-m37-test`: **12 / 70** OK (incl. fat bed)

## Pós-deploy

- JVM / Node **healthy** na tag nova
- Container Node: 7 PNGs em `/app/assets/`
- Smoke temas: send→`joy`, status→`joy-tratando`, saudável→`hospital`
- JVM: `bed-write-attrs` + `encode-value-edn` presentes; adventure_store sem `pr-str (:pokemon bed)`

## Validação WA

Reenviar Chandelure / Nidoqueen com `!pk joy N` — deve aceitar sem `value too large`.  
Conferir imagens em `!pk treinador`, `!loja`, `!pk joy`, `!pk missoes`.
