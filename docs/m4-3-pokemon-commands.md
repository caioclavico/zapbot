# M4.3 — Comandos Pokémon e apresentação na bridge JVM

Integra os primeiros comandos reais `!pk` ao núcleo Datomic em **development**, preservando o envelope WhatsApp `{messages,effects}` no adaptador Node.

## Comandos que funcionam no modo JVM

Requer `APP_ENV=development` e `POKEMON_ADAPTER=jvm`.

| Comando | Aliases | Ação |
| --- | --- | --- |
| `!pk treinador` | `perfil`, `tre` | Perfil + save ativo + Pokémon ativo |
| `!pk time` | `meus`, `pokemons`, `colecao`, `equipe`, `tm` | Coleção com filtros/paginação (texto) |
| `!pk save` | `ativo` | Snapshot do save ativo / party |
| `!pk saves` | `saves-list` | Lista A/B |
| `!pk iniciar` | `criar` | Ensure treinador+saves |
| `!pk ativar a\|b` | — | Alterna save com fence esperado |
| `!pk recibo <id>` | `op` | Recibo persistido (autorizado ao jogador) |

### Exemplos de resposta (texto)

**Treinador**
```
🧢 *Treinador: Ash*
⭐ Nível 3 • ✨ PE 12 • próximo 2/9
…
⚡ Ativo: #1 *Bulbasaur* • Nv.12
```

**Coleção / time**
```
🎒 *Sua coleção Pokémon* • Página 1/2
🧑‍🎓 Nível de treinador: 2 • Save: a
👉 1. *Squirtle* Nv.8 • comum
   ████░░░░ 40/44
```

**Ativar**
```
✅ Save *b* ativo
Fence: 2 · moedas: 0
```

## Arquivos

- `scripts/lib/pokemon-jvm-present.cjs` — DTO → `{messages,effects}`
- `src/zapbot/pokemon_http.cljs` — roteamento JVM com aliases
- `bridge_reads.clj` — perfil/coleção enriquecidos
- `bridge_tx.clj` / bridge HTTP — recibo exige `chatId`+`playerId`
- testes: `test/pokemon-jvm-present.test.cjs`, `test/pokemon-jvm-commands.test.cjs`
- `scripts/test-m4-commands.sh`

## Recibos: persistido vs pending

| Código | Significado |
| --- | --- |
| `OPERATION_PENDING` | Em voo **neste processo** JVM (mapa local); ainda não é recibo Datomic |
| GET `/v1/operations/:id?chatId&playerId` | Recibo **persistido**; `persisted: true` |
| `OPERATION_FORBIDDEN` | Recibo de outro jogador |
| `OPERATION_NOT_FOUND` | Id inexistente / pré-commit |

## Diferenças de apresentação

1. Cartões JPEG do treinador/time legado **não** são gerados no JVM (só texto; nota no perfil).
2. Filtros de liga/`!pk time csv`/ficha com PokeAPI não portados.
3. Insígnias/títulos/missões do perfil legado ainda não vêm do Datomic completo.

## Ainda no legado (bloqueados no JVM, sem fallback)

`cacar`, `batalhar`, `capturar`, `gin*`, `raid*`, `adventure`/ovos, `loja`, `joy`, `evoluir`, trocas, mídia/eventos, bugs.

## Testes

```bash
./scripts/test-m4-commands.sh
```

Cobre: comandos simulados, formatação, A→B→A, isolamento de identidade, JVM down, writes bloqueadas, regressões M4.1/M4.2.
