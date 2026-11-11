# Compatibilidade XP — simulações e dry-runs

## Escopos (não misturar)

| Escopo | O que é | Status |
|---|---|---|
| Sintético 759 | Registros gerados cobrindo 6 curvas / vitórias-only / L100 | Executado (cobertura de política) |
| Offline autorizado M5.3A | Dump `authorized-offline-copy` (histórico) | Executado — **119** Pokémon |
| Módulos ativos do mesmo export | Contas atuais no arquivo | Executado — **19** Pokémon |
| Cold live RC#66 (~759) | Restore descartável da produção atual | **Bloqueado** neste ambiente (backup cold tip não montado) |

Nenhuma escrita em volumes live. Nenhum backfill. Conversão apenas em memória (dry-run).

## Política final de conversão

1. Residual: `xp-desde-nivel` → `xp` → `vitorias-desde-nivel × 3` → 0
2. `extra = floor(R/9)` (cap 100−L); `rem = R mod 9`
3. `L' = L + extra`; `xp-total = total(L') + floor((rem/9)×span(L'))`
4. Auditoria: `:xp-legacy-residual` + `:xp-legacy-nivel` (imutável após 1ª gravação; attr `:pokemon/xp-legacy-nivel` na 037)
5. Nível nunca diminui

## Dry-run — snapshot histórico offline (119)

Fonte: `m8-1-private/pokemon/pokemon-export` · `snapshot-id=m53a-legacy-119-xp127-voltorb`
`authorized-offline-copy=true` · evidência histórica (não estado live atual).

```
quantidade                 119
niveis-reduzidos             0
niveis-aumentados            0
sem-progresso-conhecido      7
species-blank                0
sum-nivel-antes            297
sum-nivel-depois           297
sum-residual-legado        263
sum-xp-total             19004
curvas   erratic=1 fast=7 fluctuating=2 medium-fast=51 medium-slow=38 slow=20
xp-legacy-nivel preservado em 100% das conversões
```

Sem PII no repositório — só agregados.

## Dry-run — módulos ativos do export (19)

```
quantidade 19 · niveis-reduzidos 0 · niveis-aumentados 0
sum-nivel 31→31 · sum-xp-total 539
```

## Sintético 759 (política / fronteiras)

```
quantidade 759 · niveis-reduzidos 0 · subiram-residual≥9 63
sum-nivel 17185→17248 · sum-xp-total 59971033
```

Não representar como validação dos saves live.

## Cold live RC#66

Não disponível no workspace atual para restore descartável. Quando houver GO + artefato cold tip, repetir o mesmo dry-run e anexar fingerprints sem copiar dados pessoais.
