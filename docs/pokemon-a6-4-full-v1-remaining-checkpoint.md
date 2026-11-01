# A6.4 — Checkpoint: recuperação V1 restante (ensaio, sem prod)

**Status:** preparação + ensaio concluídos · **importação em produção: NÃO autorizada.**  
**Quando:** 2026-10-29 · Artefatos privados: `~/odisseu-recovery/a64-reconcile/`  
**Checkpoint EDN:** `checkpoint-full-v1-remaining.edn`

---

## Totais

| Métrica | Valor |
|--------|------:|
| Identidades históricas V1 (`chat\|player`) | **23** |
| Jogadores V1 únicos | **22** |
| Pokémon V1 no dump | **1469** |
| Já recuperados (jogadores) | **13** |
| Identidades dos 13 no dump V1 | **14** (usuário tem 2 chats) |
| Pokémon V1 dos já recuperados | **162** |
| Ainda fora da importação (identidades) | **9** |
| Pokémon ainda fora | **1307** |
| Candidatos prontos (ensaio OK) | **6 / 942 Pokémon** |
| Hold humano (ambíguos) | **3 / 365 Pokémon** |

Identidade canônica: `chat|player`. Grupos diferentes não são misturados. Os 13 já recuperados foram excluídos do dump/ensaio.

---

## Progresso live após liberação geral

Probe RO em Core (`/v1/saves`, sem escrita):

- **13** recuperados ainda presentes.
- Usuário: `a=1` (starter de onboarding) + `recovery-v1=119` — progresso novo em `a` preservado; não sobrescrever.
- Dos **9** restantes: **8** ausentes no Datomic; **1** já onboardado (`c96b30db3100`) com `active=a` e `a/b` vazios.
- Nenhuma captura nova nos restantes (só slot vazio no onboardado).

Para o onboardado: importar só em `recovery-v1`; não tocar `a`/`b`.

---

## IDs / ambiguidades

Política aplicada no ensaio:

- Nativos preservados.
- `native-collision` com IDs distintos → **manter cada ID** (sem merge).
- Sem ID + núcleo único → sintético `a64sid:…`.
- Ambíguos → **hold humano** (sem associação automática).

### Hold (3)

| player-hash | Pokémon | ambiguous | grupos bloqueantes |
|-------------|--------:|----------:|-------------------:|
| `e49260a4dcfb` | 70 | 10 | 4 |
| `c9761f45d7d2` | 50 | 4 | 2 |
| `37391862b553` | 245 | 29 | 14 |

`37391862b553` continua o excluído histórico do lote dos 12.

### Candidatos (6) — ensaio 6/6

| player-hash | Pokémon | native-collision groups |
|-------------|--------:|------------------------:|
| `c96b30db3100` | 306 | 1 |
| `e6e1377fbb0b` | 272 | 19 |
| `d4cc1ead79c0` | 187 | 20 |
| `1b7f43d000c9` | 118 | 13 |
| `b61f6b3ba75e` | 38 | 1 |
| `9e91b990f5a2` | 21 | 0 (só sintéticos) |

---

## Economia

- **Não importada.** Loja/moedas/inventário omitidos do dump.
- Status dos candidatos: `:unknown` — **não** interpretar como saldo zero.
- Compras/saldos ficam em reconciliação separada.

---

## Ensaio descartável

Dir: `rehearsal-full-remaining-20261029T003953Z`  
Storage sob `~/odisseu-recovery/…/rehearsal…` · Cassandra env limpo · sem volume live.

| Check | Resultado |
|-------|-----------|
| Import 6/6 contagens | OK (942) |
| Slot `a` preservado | OK |
| Moedas `recovery-v1` = 0 (hold) | OK |
| Idempotência (mesmo batch → `:duplicate`) | OK |
| Token adulterado rejeitado | OK |
| Retomada após falha simulada | OK (`:duplicate`) |
| Escrita Cassandra / prod | nenhuma |

Correção de importador necessária para o caso 272 (Skeledirge `origin-edn` 5931B > limite Datomic Local 4096): compactação de `origin-edn` + recibo só após chunks. Testes `migration-m53` / `recovery-a64` passando.

Dump autorizado candidatos:  
`authorized-dump-full-remaining-candidates.edn`  
sha256 `4bafaa79b1b447ffa41ca7fe071ac015614ccddcf7b5551ea1c92e478bb7df92`

---

## Plano seguro para importação final (aguarda GO)

1. Backup frio do volume live (preservar backups/relatórios anteriores).  
2. Deploy JVM com compactação de `origin-edn` + recibo pós-chunks (**obrigatório** antes dos 942).  
3. Montar dump dos 6 candidatos RO; catalog recovery.  
4. Import admin **somente** os 6, batch estável `a64-full-remaining|<player-hash>|<chat-hash>`.  
5. **Não** modificar os 13; **não** ativar `recovery-*` automaticamente.  
6. Para `c96b30db3100`: só `recovery-v1`; deixar `a` ativo.  
7. **Não** importar economia; **não** alterar Cassandra/aliases/grupos.  
8. Verificar 942 em `recovery-v1`; `a`/`b` intactos; 13 intactos.  
9. Manter 3 ambíguos (365) em hold humano.  
10. Desmontar dump.

**Nenhuma importação em produção nesta etapa.**
