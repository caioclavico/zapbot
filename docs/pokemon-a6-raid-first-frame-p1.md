# A6 P1 — Primeiro frame de combate das raids

**Status:** implementado em worktree local · **GO** para integrar na próxima RC (Explosion/caçadas)  
**Deploy / restart / migration / flags / CI:** **não realizados**

---

## Causas-raiz

### 1. Quadriculado branco/cinza no 1º frame

| Caminho | Problema |
| --- | --- |
| Raid **automática** (`raid-started`) | Outbox só tinha `texto` (+ `mentions`). O Node chamava `renderRaidBattleCard` com `chefe`/`atacante` vazios → `ensurePokemonSprite` sem espécie → `svgPlaceholder` **xadrez** composto na arena. |
| Fallback visual | `svgPlaceholder` era um padrão xadrez cinza claro (imitando transparência). Qualquer miss de download no 1º frame (cache frio) produzia o mesmo defeito visual. |
| Raid **manual** (`!pk raid iniciar`) | Usava `raid-summary` fino; se a URL faltasse, o mesmo placeholder xadrez aparecia. O 2º frame (ataque) costumava acertar porque o DTO/`getActiveRaid` + cache já estavam quentes. |

**Não** era “baixa resolução” do sprite oficial.

### 2. Emojis / dados de golpe ausentes no 1º frame

| Caminho | Problema |
| --- | --- |
| Auto | `format-started` (Clojure) listava `N. Nome (poder)` **sem** emoji de classe/tipo nem marca de efetividade. O Node entregava o texto pré-assado do outbox. |
| Manual | `formatRaidStart` → `formatRaidTurnBlock` já era o formatter certo, mas `raid-summary` **não** normalizava golpes (`golpe-normalizado`) nem raridade do atacante; golpes legados sem `classe` caíam em “indisponível” em `formatRaidMoveLines`. |
| 2º frame | `formatRaidAttack` + bloco de turno com DTO mais completo → emojis e efetividade corretos. |

---

## Diferenças entre renderizadores (antes)

| | 1º frame auto | 1º frame manual | 2º frame (ataque) |
| --- | --- | --- | --- |
| Texto | `format-started` (JVM outbox) | `formatRaidStart` | `formatRaidAttack` |
| Mídia | card com `{}` | card com `raid-summary` | card com `raid-summary` (+ fallback active) |
| Golpes | sem tipo/efetividade | select-keys cru | narrativa + menu |
| Placeholder | xadrez | xadrez se miss | cache quente |

---

## Correções

1. **`started-combat-from-raid`** (raid_store) — snapshot canônico anexado ao pending `raid-started` (sem migration).
2. **Bridge `/v1/events/pending`** — propaga `:raid`.
3. **Node `entregar-evento-jvm!`** — `formatRaidStart` + `renderRaidBattleCard` (mesmo pipeline do iniciar manual).
4. **`raid-summary` / `present-pokemon`** — golpes normalizados + raridade + sprites (paridade encounter-dto).
5. **`formatRaidStart`** — modo `auto` unificado com `formatRaidTurnBlock`.
6. **`formatRaidMoveLines` / `linhaGolpe`** — classe default + emoji de tipo (`TIPO_EMOJI`).
7. **`svgPlaceholder`** — silhueta indigo sólida (sem xadrez); `looksLikeCheckerboard` rejeita assets xadrez.
8. **Instrumentação** — `pokemon_sprite_diag` / `raid-started-render` (fase, fonte, alpha, cache, golpes/tipos) sem PII/imagem.

---

## Homologação visual

Gerados em `docs/previews/raid-first-frame/`:

| Arquivo | Conteúdo |
| --- | --- |
| `01-manual-start.txt` | 1º frame manual |
| `02-auto-start.txt` | 1º frame automático |
| `03-second-frame.txt` | 2º frame (ataque) |
| `frame1-raid-batalha.png` / `frame2-raid-batalha.png` | cards compostos |
| `04-silhouette-ok.png` | fallback legítimo |
| `05-checker-rejected.png` | xadrez detectado/rejeitado |
| `20-download-fail-fallback.png` | falha de download |

Com rede: sprites Magmar/Wartortle via official-artwork (`hasAlpha=true`, `placeholder=false`).

### Homologação pós-`npm install` (sharp) — `b477c34`

Script: `node scripts/homolog-raid-first-frame-b477c34.cjs`  
Artefatos: `docs/previews/raid-first-frame/homolog-b477c34/` · `REPORT.json`

| Check | Resultado |
| --- | --- |
| Texto manual (golpe+emoji tipo/classe+efetividade+raridade+HP) | **PASS** — pré-ataque sem narrativa de dano |
| Texto auto (`formatRaidStart` + mesmo turn block) | **PASS** |
| Texto frame2 (`formatRaidAttack` + menu) | **PASS** — dano + `Super efetivo!🔥` + menu idêntico |
| Pipeline compartilhado | **PASS** — `formatRaidTurnBlock` |
| Cache frio (miss) → silhueta sem xadrez | **PASS** |
| Cache aquecido (2º render) | **PASS** — `source=url`, sem placeholder |
| Cards frame1/frame2/auto 760×400 | **PASS** — `looksLikeCheckerboard=false`, checkerish ≤0,2% |
| Sprites individuais alpha (Magmar/Wartortle) | **PASS** — `hasAlpha=true`, milhares de px transparentes |
| Forma alternativa (deoxys-attack) | **PASS** — artwork URL |
| Artwork indisponível | **PASS** — silhueta indigo `?` |
| Detector xadrez (controle positivo) | **PASS** |
| Inspeção visual frame1 vs frame2 | **PASS** — artwork limpo sobre arena, sem xadrez |

Exemplo 1º frame (mensagem):

```
🔥 Combate iniciado! Chefe com 440 HP.
👾 *Magmar* · 🟡 Lendário
[██████████] 440/440
🐾 *Wartortle* · 🟢 Incomum
[████████░░] 120/150
1. 🔮 💧 *Water Gun* (Água, poder 40) 🔥 → `!pk raid atacar 1`
```

---

## Testes

```bash
node scripts/homolog-raid-first-frame-b477c34.cjs
node --test test/pokemon-a6-raid-first-frame.test.cjs \
  test/pokemon-a6-sprite-placeholder.test.cjs \
  test/pokemon-jvm-raid-fase12.test.cjs \
  test/pokemon-a6-raid-one-mon-ux.test.cjs \
  test/pokemon-jvm-raid-hotfix-fase1231.test.cjs
# Clojure: pokemon.tests.raid-opened-media-test
```

| Suite | Resultado |
| --- | --- |
| Homolog script | **12/12 PASS** |
| Node regressão raid (incl. first-frame) | **38/38 PASS** |
| JVM `raid-opened-media-test` | **19/19 PASS** |

Cobertura: 20 cenários pedidos (manual/auto, 2º frame, alpha, xadrez, cache, artwork, forma, ofensivo/status/legado Wartortle, SE/NVE/imune, emojis, raridade, HP, lobby→combate, download fail).

Commit `b477c34` permanece HEAD íntegro (working tree só com artefatos de homologação).

---

## Compatibilidade

- Raids em andamento: inalteradas (só apresentação + enrichment em leitura do outbox).
- Sem mudança de Species-HP, One-Mon, HP de chefes, balanceamento, flags, sessão WA.
- Caçada/ginásio: beneficiam do placeholder sem xadrez e da normalização de golpes no DTO compartilhado.

---

## GO / NO-GO

| Critério | |
| --- | --- |
| Homologação visual+textual `b477c34` | **PASS** |
| 1º frame = qualidade do 2º (texto + mídia) | **GO** |
| Sem xadrez no card / fallback | **GO** |
| Auto e manual unificados | **GO** |
| Sem regressão Node/JVM afetada | **GO** |
| Sem deploy neste lote | respeitado |
| Integrar na RC Explosion/caçadas | **GO** |
