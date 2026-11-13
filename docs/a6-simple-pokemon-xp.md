# Odisseu A6 — XP simples dos Pokémon (growth-v2)

Base live: `a6/live-baseline-8ef41c8` @ `8b05886` (PR #80 MTs).
Branch: `feat/a6-simple-pokemon-xp`. **Sem** PR #83 / incubadora.

## Auditoria do modelo antigo (growth-v1)

| Conceito | Significado pré-v2 |
|---|---|
| Curva | Uma das 6 taxas PokéAPI (`medium-fast`, `medium-slow`, …) por espécie |
| `:xp-total` | XP acumulado oficial na tabela da curva |
| `:xp-desde-nivel` / `:xp` | Residual = `xp-total − total_oficial(nível)` |
| Span do nível | `total(n+1) − total(n)` (milhares nos níveis altos) |
| Legado flat-9 | Sem `xp-progress-version`: residual em “vitórias” ×9 → `ensure-growth-v1` |
| XP desconhecido | Sem campos de XP → `conhecido?=false`; UI mostra `XP ?` |
| Schema | 037 (`:pokemon/xp-total`, `:xp-desde-nivel`, `:xp-progress-version`, …) |

## Fórmula nova (growth-v2)

```
xpNecessario(nivel) = 10 + 2 * (nivel - 1)   ; nil no nível 100
xpTotalMinimo(nivel) = (nivel - 1) * (nivel + 8)  ; só interno/conversão
```

Nível máximo: 100.

Ao ganhar XP: consumir a meta do nível atual, preservar excedente, repetir até
faltar XP ou atingir L100. Excedente em L100 é descartado.

## Modelo persistido (schema 037, sem migration nova)

| Campo | Significado growth-v2 |
|---|---|
| `:pokemon/xp-progress-version` | `"growth-v2"` |
| `:pokemon/xp-desde-nivel` | residual no nível atual |
| `:pokemon/xp-total` | `xpTotalMinimo(nivel) + residual` |
| `:pokemon/growth-rate` | `:simple` (sentinela; curvas PokéAPI não se aplicam) |
| `:pokemon/xp-legacy-*` | auditoria da conversão legado → v1 (inalterada) |

Campos históricos growth-v1 (`growth-rate` oficial, totais PokéAPI) continuam
legíveis. Transações Datomic antigas **não** são apagadas.

## Conversão growth-v1 → growth-v2

1. Preservar nível.
2. `frac = residual_v1 / span_oficial(nível)` (clamp `[0, 1)`).
3. `residual_v2 = floor(frac * xpNecessario(nível))`.
4. **Não** aplicar level-up nesta etapa.
5. Idempotente se já `"growth-v2"`.

XP desconhecido (sem campos de XP): permanece `conhecido?=false` na leitura,
em evolução, em conversão explícita e em grants de zero XP. O **primeiro grant
positivo** é uma operação canônica de gameplay que inicializa a base no começo
do nível persistido (residual 0) e soma os pontos ganhos; esse marco não presume
progresso anterior e não é executado por consulta. No nível 100, não inicializar
XP desconhecido. A operação usa os recibos transacionais de gameplay.

## Quando grava

- **Escrita**: grants positivos convertem v1→v2 proporcionalmente; registros
  desconhecidos são inicializados somente no primeiro grant positivo.
- **Evolução**: converte XP conhecido, mantém desconhecido sem criar XP 0.
- **Hatch**: Pokémon novo recebe o modelo v2 conforme as regras de nascimento.
- **Leitura** (`pokemon-progress` / DTO): cálculo in-memory para a barra;
  **não** grava conversão só por consultar.

## Balanceamento

Ganhos de atividade seguem `(pts/9) × span-simples` com os mesmos
multiplicadores (ginásio 1.10, PvP 1.05, raids por categoria) e cap 60% do
span. Ritmo ≈ 3 vitórias comuns por nível — **sem alterar PE do treinador nem PI**.

## Migração live / rollback

**Nesta etapa: sem merge deploy, sem backfill, sem GO de conversão em massa.**

Antes da liberação (GO futuro):

1. Inventário por `xp-progress-version` + contagem conhecido/desconhecido.
2. Simulação proporcional (níveis estáveis) em restore isolado.
3. Cold backup dos 4 volumes + fingerprints pré/pós.
4. GO explícito para o cutover **e para conversões lazy durante gameplay**:
   não há backfill em massa, mas a primeira escrita pode persistir v2.
5. Ensaiar em restore isolado uma frota mista (v1, v2, legado e XP desconhecido)
   e comparar níveis, IDs, barras, XP, histórico e fingerprints; prever casos
   de falha durante primeira escrita e reprocessamento idempotente.
6. Rollback de **código**: a imagem anterior NÃO é segura para editar registros
   já v2. Não reiniciar escrita com imagem antiga após qualquer grant v2.
   Em erro, isolar escritores; voltar apenas com versão compatível com v2.
7. Rollback de **dados**: restauração de cold pré-cutover exige análise de todas
   as escritas posteriores e reconciliação, jamais sobrescrever saves ativos.

## WhatsApp

Presenter mostra apenas nível, XP no nível / meta, barra e próximo nível.
L100: `🏆 NÍVEL MÁXIMO` (sem meta fantasma). Sem curvas PokéAPI, sem XP
acumulado histórico na UI.
