# Simulação de compatibilidade — baseline 759 Pokémon

Baseline de referência (não imutável): 18 jogadores · 54 saves · 759 Pokémon · 333 moedas.

## Política aplicada (dry-run lógico)

Para cada Pokémon legado `{nível L, xp-desde-nivel R}`:

1. Resolver curva pela espécie-base.
2. `fração = min(1, R/9)`.
3. `xp-total' = total_curva(L) + floor(fração × span_curva(L))`.
4. Nível permanece `L` (nunca diminui).
5. IVs, golpes, HP, shiny, dono, apelido intactos.

## Propriedades verificadas em testes

- Conversão idempotente (`growth-v1` não reconverte).
- Tabelas L1–L100 monotônicas nas 6 curvas.
- Totais L100 oficiais (medium-fast 1e6, slow 1.25e6, fast 8e5, medium-slow 1059860, erratic 6e5, fluctuating 1.64e6).
- Multi-level grant + teto 100 sem XP excedente.
- Evolução não reduz nível.
- Migration 037 instalável após 036.
- Persistência Datomic de `xp-total` / versão / curva.
- Presenter usa intervalo JVM (sem reinventar curva).

## Backfill live

**Não executado.** Contagens/fingerprints de produção ficam para GO de cutover com dry-run + manifesto.

## Exemplo WhatsApp (valores reais medium-fast L24)

Após conversão de residual legado 4/9:

```
⚡ *Pikachu* — Nv. 24
✨ XP: <residual mapeado> / <span oficial L24>
████░░░░░░ <pct>%
⬆️ Próximo nível: 25
```
