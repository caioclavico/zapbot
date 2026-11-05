# A6 Pokédex — RC Node e homologação WhatsApp

**Branch RC:** `rc/a6-pokedex-node`  
**Commit final:** `77e347e73fdcbcdcfcd609fe4758bfe883f7003f`  
**Base produção:** `dc56a993cb3b` (announcer+drain)  
**Data:** 2026-11-05  
**Status:** CONDITIONAL (RC Node pronto; smoke WhatsApp real pendente — sem staging isolado)

## 1. Produção atual (fonte de verdade)

| Item | Valor |
| --- | --- |
| Tip git implantado | `dc56a993cb3b3e175d215a86bb70c5a3fe1e866b` |
| Node tag | `odisseu-node:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64` |
| Node Image ID | `sha256:1dcb24f7b2fcc3c692c07eba306f67e091b959f1f3959dc405ab2de48462800d` |
| JVM (inalterada) | `odisseu-jvm:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64` · `sha256:fa38d15c…` |
| Announcer | instalado · `RELEASE_ANNOUNCER_ENABLED=false` |
| Piloto Announcer | `120363427684548487@g.us` |

A feature `feature/a6-pokedex-species-frame` já era tip de produção + 7 commits Pokédex.  
RC = mesmo tip (`rc/a6-pokedex-node` @ `77e347e`).

## 2. Integração

Nenhum cherry-pick conflitante: a branch já contém exatamente:

1. drain / maintenance / Announcer (base `dc56a99`)
2. Pokédex espécie + moldura + testes + docs

Preservado: dispatcher, bridge JVM, sessão WA, raids auto, trocas A6, marker de maintenance, Announcer desligado.

**JVM / Datomic / saves / migrations:** sem alteração.

## 3. Testes (worktree RC)

| Suíte | Resultado |
| --- | --- |
| Pokédex species + integration + A4.2 | **25/25 pass** |
| `a6-maintenance` | **6/6 pass** |
| `pokemon-trade-ux` | **4/4 pass** |
| Bundle pokedex+maint+trade+a42 | **35/35 pass** |
| `pokemon-jvm-present` trade gate msg | **fail pré-existente** no tip `dc56a99` (não introduzido pela Pokédex) |
| `pokemon-jvm-commands` saves label | **fail pré-existente** no tip `dc56a99` |

Aliases: top-level `!pokedex`/`!dex`/`!pdx` → espécie; `!pk pokedex` → coleção pessoal.

## 4. Imagem RC

Pacote: `/Users/caioclavico/odisseu-recovery/a6-pokedex-rc-20261105T180125Z/` (`BUILD_OK`)

| Campo | Valor |
| --- | --- |
| Node tag | `odisseu-node:a6-pokedex-20261105T180125Z-77e347e73fdc-amd64` |
| Node Image ID | `sha256:bc6c73c117840405f635563189df2b3adb35e1c38dcfa83f1098f77cdd2c89ad` |
| Tar | `odisseu-node-amd64.tar` (~371 MiB) |
| Tar SHA-256 | `c95cd0a4c447b7cfb8f706d66af5466981f0c0bd3330b97f47df1b7f67cf3473` |
| Plataforma | `linux/amd64` |
| Labels | `fase=a6-pokedex-species-frame`, `git-tree=77e347e73fdc…` |
| Smoke isolado | `docker run --network none` → `SMOKE_OK` (lookup/render/drain presentes, Node v22.23.3) |
| JVM | **não reconstruída** (rollback JVM = imagem atual) |

### Rollback Node

```bash
# Em produção, somente com GO operacional + drain:
docker load -i odisseu-node-amd64.tar   # imagem anterior, se necessário
# recreate odisseu-a6-node com:
#   odisseu-node:a6-announcer-drain-20261105T164504Z-dc56a993cb3b-amd64
#   sha256:1dcb24f7b2fcc3c692c07eba306f67e091b959f1f3959dc405ab2de48462800d
# Mesmos volumes / sessão WA. Não reiniciar JVM.
```

### Config necessária

- Nenhuma env nova obrigatória para Pokédex.
- `RELEASE_ANNOUNCER_ENABLED=false` permanece.
- Sharp já na imagem (pipeline de mídia).
- `PUPPETEER_SKIP_DOWNLOAD=true` + Chromium do sistema (Dockerfile existente).

## 5. Homologação WhatsApp

### Staging isolado

**Não disponível nesta sessão** com sessão WhatsApp própria separada da produção.

Restrição respeitada: **não** conectar segunda instância à mesma sessão LocalAuth de produção; **não** enviar mensagens reais ao piloto/prod sem GO operacional.

### Smoke preparado (quando houver staging ou GO)

```
!pokedex pikachu
!dex 150
!pdx gengar
```

Checklist:

- [ ] Imagem enviada com moldura vermelha
- [ ] Caption equivalente à master
- [ ] Sem caixa preta
- [ ] Erro amigável para espécie inválida
- [ ] Sem duplicação de mensagens
- [ ] Raids / trocas / Announcer intactos

Evidências offline já inspecionadas: `docs/fixtures/a6-pokedex-visual/` + `docs/a6-pokedex-visual-validation.md`.

## 6. Classificação

**CONDITIONAL**

- Código + testes Pokédex + imagem RC Node: OK (após build).
- Smoke WhatsApp real: **não executado** (sem staging isolado / sem GO).
- Falhas de teste de trade/saves: pré-existentes no tip de produção.

Não declarar READY FOR PRODUCTION até smoke WA em ambiente isolado ou GO explícito de homologação no piloto.
