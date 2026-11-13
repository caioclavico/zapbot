# Odisseu A6 — Ensaio isolado growth-v1 → growth-v2

**Timestamp (UTC):** 2026-11-13T01:00Z–01:16Z  
**Host:** `odisseu-core`  
**Commit ensaiado:** `f4f0144c004079a3384afcc312af64faf4b0836f` (PR #84)  
**Base live:** `8b05886f696441fabdce06823a2513c7d8997446`  
**CI referência:** https://github.com/caioclavico/zapbot/actions/runs/43340020790 (`success`)  
**Evidência privada (host):** `/home/caiohclavico/odisseu-recovery/a6-gv2-rehearsal-20261113T010003Z`  
**Produção mutada:** **NÃO**

---

## Autorização respeitada

| Ação | Status |
|---|---|
| Merge PR #84 | **não** |
| Deploy / backfill live | **não** |
| Escrita em volumes `odisseu-a6-cutover-*` | **não** |
| WhatsApp / raids / Announcer no ensaio | **não** (`--network none`) |
| Deploy automático / Announcer | **não reativados** |

Live ao final: JVM/Node nos volumes cutover; WhatsApp `READY`; Announcer não tocado.

---

## Backup e restore

| Item | Valor |
|---|---|
| Cold | `…/a6-cold-live-private/cold-pr80-a6-20261112T231100Z` |
| Criado | `2026-11-12T23:11:10Z` (janela cutover PR #80) |
| Verify | **PASS** (`logical_bytes=41471233`, 4 volumes) |
| Restore conversão | `odisseu-a6-restore-gv2-20261113T0100-*` |
| Restore baseline RO | `odisseu-a6-restore-gv2base-20261113T0113-*` |
| Rede do ensaio | `none` (sem WA / cron / writer live) |
| Imagem runtime | `odisseu-jvm:a6-pr80-…-8b05886…` + **overlay RO** de `modules/`/`apps/`/`shared/` do tip `f4f0144` |

Fingerprint HTTP do cold (cutover PR #80, pós-restore live na época):  
`93b769e70ddba53b9b43e125598f6ffd9e4ee277bc46ec162ce09a40674e89bd`  
(agregado ativo: 18 jogadores / 54 saves / 808 Pokémon / 437 moedas / 1 ovo / 18 incubadoras).

> Nota: hash `find\|sha256sum` do volume restaurado ≠ `tree_sha256` do manifesto (algoritmos distintos). Integridade do cold validada via `a6_cutover_backup.py verify`.

---

## Inventário pré-migração (cold consistente)

Fonte: restore baseline + pull Datomic (sem PII; owners = SHA-12).  
Relatório privado: `…/live-ro/01-inventory-private.edn` (somente no host).

| Agregado | Valor |
|---|---|
| Pokémon (entidades) | **1046** |
| Owners distintos (hash) | **29** |
| Saves (entidades) | **79** |
| Σ nível | **1665** |
| Σ HP / HP atual | 69141 / 67215 |
| Shiny | 1 |
| Nível 100 | 0 |
| XP inconsistente (total≠base+residual v1) | **0** |
| XP desconhecido | **0** |
| `growth-v1` | **49** |
| Legado sem versão | **997** |
| Moedas `:save/coins` | **2632** |
| Moedas operacionais | **3985** |
| Ovos | **4** |
| Receipts | **4012** |

Distribuição de nível (topo): L1=922, L2=38, L3=28, L4=20, …, L85=1.

Escopo HTTP ativo (18/54/808) ⊂ entidades Datomic (inativos/históricos inclusos).

---

## Conversão no restore

| Métrica | Antes | Depois |
|---|---|---|
| Pokémon | 1046 | 1046 |
| Σ nível | 1665 | 1665 |
| Versões | v1=49, legado=997 | **growth-v2=1046** |
| Níveis alterados | — | **0** |
| Não idempotentes | — | **0** |
| Reconvert nível mudou | — | **0** |
| IDs / espécie / HP / shiny / golpes / IVs | — | **idênticos** |

- Convertidos: **1046**  
- Skip unknown: **0** (nenhum no cold)  
- `max-abs-pct-delta` (pares com pct antes/depois): **≈0.079** (7,9 pp)

### Progresso proporcional

- **growth-v1 → v2:** `residual' = floor(frac × span_simples)`; diferença além de 1/span vem de renormalização `ensure-growth-v1` antes da fração.
- **legado flat-9 → v1 → v2:** residual pequeno (ex.: 3/9) pode colapsar para **0** no span simples após o mapeamento oficial — **nível preservado**, progresso intramível pode zerar. Política documentada; aceitável só com GO explícito dessa quantização.

### XP desconhecido

Cold: **0** registros. Suite sintética no mesmo restore validou grant em fixture sem XP prévio; `adicionar-pokemon!` materializa campos — não substitui frota real unknown. Gate G = **PASS com cobertura limitada**.

---

## Gameplay isolado (IDs de teste)

| Caso | Resultado |
|---|---|
| +XP sem level-up | PASS (L10 residual 5→7) |
| Sobe 1 nível | PASS (→11) |
| Multi-level + excedente | PASS (20→22, residual 7) |
| Lazy v1→v2 no grant | PASS (nível 15 preservado) |
| L100 descarta excedente | PASS |
| Retry grant 0 | PASS |
| Evolução / MT / ginásio / raid E2E | **não exercidos neste ensaio** (domínio XP + idempotência cobertos; MT suite CI no PR) |
| Restart JVM imagem | N/A (one-shot); reconvert idempotente simula reentrada |
| Concorrência hard | **não** (pendência) |

---

## Frota mista

No restore convertido: 100% `growth-v2` após backfill de ensaio.  
Fixtures sintéticas no mesmo DB: v2 / v1→v2 / legado / L100.  
Operação em um Pokémon de teste **não** reescreveu a frota já convertida além dos seeds adicionados (+6 Pokémon de ensaio → 1052 entidades no volume de conversão).

---

## Rollback

| Cenário | Validação |
|---|---|
| Antes da 1ª escrita v2 | Imagem live `8b05886` permanece writer; cold verify PASS; restore descartável independente |
| Depois da 1ª escrita v2 | Volume `…-gv2-20261113T0100` contém v2 — **não** montar imagem pré-v2 como writer sobre ele |
| Retorno de dados | Recriar restore a partir do cold PR80 (demonstrado com `gv2base-…`); gravações **após** o cold exigem reconciliar ou novo cold de janela |
| Freeze/open | Marker `:frozen` do cold aberto **somente** no restore (`:open` + `maintenance-control?`) |

Rollback que preserve progresso pós-cold: exige cold **da janela** imediatamente antes do cutover, não reutilizar cegamente o de 23:11Z se a live avançou.

---

## Gates A–M

| Gate | Critério | Resultado |
|---|---|---|
| A | Inventário completo | **PASS** |
| B | Backup cold íntegro | **PASS** |
| C | Restore isolado válido | **PASS** |
| D | Identidade canônica preservada | **PASS** |
| E | Níveis 100% preservados | **PASS** |
| F | Progresso proporcional | **PASS*** (caveat legado→0 residual) |
| G | XP desconhecido preservado | **PASS*** (0 no cold; cobertura sintética limitada) |
| H | Idempotência | **PASS** |
| I | Gameplay pós-conversão | **PASS** (XP core) |
| J | MTs e evolução | **PARTIAL** (CI PR #84; não E2E no restore) |
| K | Integridade econômica | **PASS** (coins 2632 / receipts 4012 / eggs 4 estáveis no baseline) |
| L | Rollback após escrita v2 | **PASS** (procedimento; restore a partir do cold) |
| M | Prontidão cutover | **NO-GO** (aguardando GO; caveats F/G/J; cold da janela) |

---

## Veredito

**Ensaio isolado: PASS (com ressalvas).**  
**Cutover / merge / deploy / backfill live: NO-GO** até GO explícito.

Pendências antes de GO de produção:

1. Aceitar (ou ajustar) quantização de residual legado pequeno → 0 no v2.  
2. Cold **fresco da janela** se a live divergiu do cold PR80.  
3. E2E MT + evolução + ginásio/raid no restore com imagem JVM buildada do tip.  
4. Teste de concorrência / restart com bridge completo.  
5. Inventário live RO autenticado na janela (agregado HTTP + cold).

---

## Artefatos no repositório

- `scripts/a6_growth_v2_rehearsal.clj` — inventário / conversão / gameplay isolado  
- `scripts/a6_gv2_economy_ro.clj` — contagens econômicas  
- `scripts/a6_gv2_rehearsal_host.sh` — orquestração no host  

PII e dumps privados permanecem apenas no host (`01-inventory-private.edn`).


## Correção posterior ao ensaio (requer novo ensaio)

Após este ensaio, o PR #84 recebeu uma correção para converter o legado flat-9
**diretamente** para growth-v2, preservando a fração `residual/9` sem usar
curvas growth-v1 intermediárias. O inventário do script de ensaio também
passa a computar a porcentagem antiga do legado como `residual/9`.

**Atenção:** os números e os gates deste relatório referem-se exclusivamente
à execução anterior e **não demonstram** o resultado da nova conversão.
Um novo restore isolado, nova auditoria de residual fora de 0..8,
novo E2E e novo relatório de diferenças são necessários antes do GO.
