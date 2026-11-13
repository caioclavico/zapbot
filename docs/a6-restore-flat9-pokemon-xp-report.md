# Odisseu A6 — Relatório flat-9-v2 (PR #86)

**Estado:** implementação + testes + ensaio isolado concluídos. **Sem merge/deploy/backfill em produção.**
**Branch:** `feat/a6-restore-flat9-pokemon-xp`
**Base:** `5f3b716` (hotfix #85 presente). Exclui #84 (growth-v2) e #83.

## READY?

**READY para análise de autorização separada**, pois a reconciliação de **18/18** ambíguos foi ensaiada em restore isolado (0 níveis alterados; 0 UNRESOLVED). **Não** há autorização operacional de merge, deploy ou apply live; requer cold fresco e gates do cutover.

## Arquivos alterados

| Área | Arquivos |
|---|---|
| Domínio | `modules/pokemon/domain/pokemon/domain/growth.clj`, `xp.clj`, `collection.clj`, `egg_hatch.clj` |
| WhatsApp | `scripts/lib/pokemon-jvm-present.cjs` |
| Testes | `growth_curves_a6_test.clj`, `level_evolution_a6_test.clj`, `xp fixtures`, `gym_immediate_rewards_a6_test.clj`, `species_hp_a6_test.clj`, `collection_m31_test.clj`, `egg_fixes_a6_test.clj` |
| Ensaio | `scripts/a6_flat9_conversion_rehearsal.clj` |
| Docs | este relatório + plano atualizado |

## Fórmulas

- **Grant:** `residual' = residual + ganho`; enquanto `residual' ≥ 9` e `nível < 100`: `nível++`, `residual' -= 9`.
- **Vitória comum:** 3 XP flat; **derrota:** 1. Ajustes leves (ginásio 1.10, PvP 1.05, raid por categoria, ratio adversário 0.5–1.5) sobre pontos flat — **nunca** `(pts/9)×span` PokéAPI.
- **Cap grant:** 18 pontos flat.
- **L100:** descarta excedente.
- **`xp-total` sintético:** `(nível-1)*9 + residual` (compat; não é XP oficial).
- **Conversão ≠ level-up.**

### Conversores

| Origem | Regra |
|---|---|
| `flat-9-v2` | Normaliza residual 0..8 |
| `growth-v1` | `floor((resid/span)*9)` → 0..8; preserva nível |
| `growth-v2` | Residual &lt;9 preserva; senão fração segura → 0..8 |
| Legado 0..8 | Preserva residual; carimba `xp-legacy-*` |
| Ambíguo (sem versão, residual ≥9) | **Não converte** na leitura; grants e evolução falham fechados (sem reinicializar residual); exige reconciliação histórica antes de jogar com esse Pokémon |
| XP desconhecido | Leitura `conhecido?=false` |

## Persistência

- `:xp-progress-version` = `"flat-9-v2"`
- `:xp-desde-nivel` / `:xp` = residual 0..8
- `:nivel` 1..100
- Recibos/CAS idempotentes inalterados; PE/PI/economia intocados

## Ensaio isolado

| Item | Resultado |
|---|---|
| Cold integrity (sha256 archives) | OK (`cold-pr85-repair-20261113T185906Z`) |
| Restore descartável | `odisseu-a6-restore-flat9-20261113T193712Z-*` |
| Fonte canônica pós-reparo #85 | volume `odisseu-a6-repair-reh-20261113T184855Z-datomic` |
| Pokémon | 1080 |
| Players / saves / receipts / coins / inv / eggs | 28 / 79 / 4276 / 3691 / 137 / 7 |
| Por versão (ensaio histórico) | legacy-flat9 **999**, growth-v1 **62**, ambiguous **19** |
| Convertidos na simulação | 1061 |
| **Mudanças de nível na conversão** | **0** |
| Ambíguos preservados (ensaio histórico) | 19 (incl. Staryu L13 resid 478) |
| Reparados #85 | Blastoise **74**, Tyranitar **90**, Crobat **5**, Amaura **4** — inalterados pela conversão |

Evidências: `/tmp/a6-flat9-reh-20261113T193712Z/`

## CI

- Gate `a6-live-reconcile-validation` alinhado ao tip live **#85** (`5f3b716`).
- Scope esperado: `jvm-pokemon-growth` (altera `domain/growth`).

## Testes executados (host)


- `clojure -M:test-a6-pokemon-growth` → **60 tests, 1108 assertions, 0 fail**
- `clojure -M:test-a6-tm-xp-evolution` → **0 fail**
- egg + gym + collection-m31 + xp-idempotency → **39 tests, 227 assertions, 0 fail**
- Presenter Node (helpers): `XP n/9`, clamp de spans oficiais, L100, ambíguo → `XP ?`

## Riscos restantes

1. Ambíguos unversioned residual≥9: inventário atual **18** (não 19). Auditoria + ensaio de reconciliação concluídos — ver `docs/a6-flat9-ambiguous-xp-reconciliation-report.md`. Fail-closed permanece até apply autorizado em produção.
2. Cold archive `cold-pr85-repair` espelhou tip pré-reparo nos levels dos 4 afetados; cold fresco da reconciliação: `cold-flat9-ambig-20261113T215231Z`. **Gerar cold fresco** imediatamente antes de qualquer cutover autorizado.
3. Multiplicadores ginásio/raid/PvP/adversário permanecem leves sobre pontos flat — balanceamento documentado; caçada comum = 3.
4. Frota mista durante deploy: leitores antigos podem não entender `flat-9-v2` até o tip JVM/Node.

## Rollback

- Reverter tip para #85 (`5f3b716` / imagem pr85) sem backfill flat-9.
- Pokémon já gravados como `flat-9-v2` continuam legíveis via residual 0..8; não reexecutar reparos.
- **Proibido:** rollback destrutivo de volumes live; merge de #84.

## GO/NO-GO

| Gate | Status |
|---|---|
| Implementação flat-9-v2 | GO |
| Testes JVM focados | GO |
| Conversão sem salto de nível (ensaio) | GO |
| Reparados #85 preservados | GO |
| Ambíguos — auditoria + ensaio | GO (18/18 B; 0 UNRESOLVED no restore) |
| Merge/deploy produção | **NO-GO até GO explícito separado** |
| Backfill produção dos 18 | **NO-GO até GO explícito separado** |


## Revisão de segurança após GO do usuário

Identificado bug no primeiro grant: o caminho `ensure-flat9-v2-for-grant` inicializava residual 0 para unversioned com XP >=9, apagando implicitamente o progresso não reconciliado. Corrigido no PR: agora operação de grant ou evolução falha fechada antes de alterar registro; nada é redefinido.

**Atualização (reconciliação ambígua):** inventário live confirmou **18** registros (1 dos 19 anteriores: `d11cc9ad-…`, Salamence L3 residual 1, já `growth-v1`; nenhum novo ID). Todos classificados **B** com evidência; reconciler idempotente ensaiado em restore isolado com **0 Δ nível** e **0 UNRESOLVED**. Detalhes e fingerprints em `docs/a6-flat9-ambiguous-xp-reconciliation-report.md`. Nenhum merge/backfill/deploy executado.


## Ensaio mais recente — distinguir do snapshot histórico acima

A tabela de 1080 Pokémon / 19 ambíguos pertence ao **ensaio original**; não a substituir por números da nova janela. No cold `cold-flat9-ambig-20261113T215231Z`, o restore isolado continha **1082 Pokémon, 28 jogadores, 79 saves, 18 ambíguos pré-apply**. O reconciler registrou **18 applied**, depois **18 skipped/receipt-exists** em retry, **0 mudanças de nível**, **0 ambíguos pós-apply**, **0 UNRESOLVED** e moedas/itens/ovos estáveis; receipts 4296 → 4314 (+18 esperados). Os reparos Blastoise 74, Tyranitar 90, Crobat 5 e Amaura 4 permaneceram intactos. A evidência está em `docs/a6-flat9-ambiguous-xp-reconciliation-report.md`.

O Salamence `d11cc9ad-…` saiu da lista porque já está `growth-v1` (L3, residual 1), **não** por aplicação do reconciler em produção. O conjunto ativo ainda contém **18** ambíguos até autorização de apply e verificação de drift. O manifesto privado com identificadores completos fica fora do Git; executar novo inventário e comparar estados imediatamente antes do cutover.
