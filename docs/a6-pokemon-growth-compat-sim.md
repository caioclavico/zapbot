# Simulação de compatibilidade — baseline 759 Pokémon

Baseline de referência (não imutável): 18 jogadores · 54 saves · 759 Pokémon · 333 moedas.

## Política aplicada (dry-run lógico)

Para cada Pokémon legado:

1. Resolver residual: `xp-desde-nivel` | `xp` | `vitorias×3` | 0.
2. Resolver curva pela espécie-base.
3. `extra = floor(R/9)` (cap 100−L); `rem = R mod 9`.
4. `L' = L + extra`; `xp-total' = total(L') + floor((rem/9)×span(L'))`.
5. Nível nunca diminui; pode subir se R≥9 (espelha normalização cljs).
6. IVs, golpes, HP, shiny, dono, apelido intactos na conversão de campos XP.

### Amostra sintética (759 registros, sem PII)

Fingerprints da execução local (conversão pura, sem Datomic):

```
quantidade              759
niveis-reduzidos          0
subiram-residual>=9      63
sum-nivel-antes       17185
sum-nivel-depois      17248
sum-xp-total       59971033
```

Restore cold de produção: **não executado** nesta correção (sem GO). Script:
`scripts/a6_growth_compat_dry_run.clj`.

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
