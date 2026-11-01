# A6.4 — Matriz de reconciliação (anonimizada)

Branch: `a6-save-slots`. Atualizado: 2026-10-28 (ensaio em lote controlado).
**Sem produção.** Sem deploy, 025 live, escrita Cassandra, import Core live ou
alteração de volumes produtivos. Pending (9) **não** decididos automaticamente.

Privado: `~/odisseu-recovery/a64-reconcile/`  
(`batch-audit-gates.edn`, `batch-rehearsal-report-anonymized.edn`,
`rehearsal-batch-*/`, `*.pre.tgz`).

---

## 1. Políticas canônicas (aprovadas)

| Tema | Decisão |
|------|---------|
| Identidade | `chat\|player` — sem alias `@lid`↔`@c.us` automático |
| Economia | `known` / `divergent` / `unknown` — unknown ≠ 0; sem soma entre fontes |
| IDs | `a64-stable-v1` só com unicidade+estabilidade comprovadas |
| Pending | 9 permanecem await-human |

---

## 2. Auditoria — 151 native-collision + 43 ambiguous (V1)

Classificação por **grupos de núcleo estável** (não por decisão humana de pending):

| Classe de grupo | Qtd grupos | Interpretação |
|-----------------|------------|---------------|
| `native-collision-distinct-ids` | **61** | Mesmo núcleo estável, **ids nativos distintos** → exemplares tratados como colisão de fingerprint (stats iguais); **bloqueiam** síntese/`import-ready` na conta |
| `blocking-ambiguous-or-mixed` | **20** | Núcleo compartilhado com ausência de id ou mistura → **ambiguous**; impedem recuperação automática |
| Pokémon `:native-collision` | **151** | Contagem ao nível de linha (soma nas contas) |
| Pokémon `:ambiguous` | **43** | Idem |
| Contas V1 que bloqueiam | **9** | Contêm algum grupo acima |
| Contas V1 `import-ready?` | **14** | Sem ambiguous/native-collision |

**Duplicação legítima entre snapshots (V1↔V2):** não fundida automaticamente; ensaio
usou só snapshot **V1** por candidato aprovado. Cross-borrow continua proposta
1:1, não aplicada em lote.

**Quantos impedem recuperação automática:** as **9** contas com grupos bloqueantes
+ qualquer candidato com `:ambiguous` / ids não únicos (ver exclusões §3).

---

## 3. Gates dos elegíveis → liberados para ensaio

Candidatos resolved com V1: **13** → após gates: **12 liberados**, **1 excluído**.

### Excluído

| player-hash | Motivos |
|-------------|---------|
| `37391862b553` | `:ids-not-ready`, `:ambiguous-pokemon` (29), `:proposed-ids-not-unique` |

### Liberados (12) — todos com ids estáveis sob reordenação e únicos

| player-hash | kind | Pokémon | native | synthesizable | economia* |
|-------------|------|---------|--------|---------------|-----------|
| `1d4a0c2cbc93` | lid | 6 | 0 | 6 | unknown |
| `bacdc2275203` | lid | 8 | 0 | 8 | unknown |
| `d15aaa1f4eaa` | c.us | 2 | 0 | 2 | unknown |
| `6e8ad515a7cb` | lid | 1 | 0 | 1 | unknown |
| `3cf793a69b07` | lid | 2 | 0 | 2 | unknown |
| `ee6231ab35de` | c.us | 1 | 0 | 1 | unknown |
| `9a4322c7bd26` | lid | 10 | 9 | 1 | unknown |
| `bb1c8bf84402` | lid | 5 | 0 | 5 | unknown |
| `724d1d63e716` | lid | 1 | 0 | 1 | unknown |
| `74dfb2348c54` | c.us | 1 | 0 | 1 | unknown |
| `778f2dcd250c` | lid | 1 | 1 | 0 | unknown |
| `7116331d2e99` | c.us | 1 | 0 | 1 | unknown |

\*Neste ensaio a economia do dump treinador permanece **unknown** (módulo `loja`
não mesclado ao snapshot); política: **não** gravar saldo 0.

Re-gate determinístico: **ok**. Multi-chat nos 12: **nenhum**.

---

## 4. Isolamento e backup

| Checagem | Resultado |
|----------|-----------|
| Storage | `~/odisseu-recovery/a64-reconcile/rehearsal-batch-20261028T204413Z` |
| Contém `rehearsal` / sob `odisseu-recovery` | sim |
| Volume live `odisseu-core-a56-datomic` | **não** compartilhado |
| Env Cassandra unset no processo | sim |
| Origem | cópia de `a56-025-rehearsal` (025 já aplicada, 0 trainers) |
| Backup pré-ensaio | `rehearsal-batch-….pre.tgz` |
| Restore check | 11 arquivos origem = 11 restaurados; dir `core` presente |
| Escrita Cassandra / Datomic live | `[]` |

---

## 5. Resultado do ensaio (12/12)

Fluxo por treinador: `discover` → `register` → `player-confirm!` → `admin-import!`
→ retry `confirm!` (mesmo batch) → simulação de falha (token adulterado) + resume.

| Métrica | Valor |
|---------|-------|
| Sucesso | **12 / 12** |
| Pokémon em `recovery-v1` (soma) | **39** (bate esperado por trainer) |
| Slots após | `a`+`b`+`recovery-v1` em todos |
| Coleção do slot ativo `a` | **0** (antes e depois) |
| Active preservado | **sim** (`a`) em 12/12 |
| Retry | **`:duplicate`** em 12/12 |
| Economia escrita | **não** (unknown retained) |
| Pending 9 | intocados |

### Por treinador (antes → depois)

Todos: active `a` com 0 Pokémon; após: `recovery-v1` com N = esperado; retry
sem novo slot.

### Idempotência e falha parcial

| Teste | Resultado |
|-------|-----------|
| Reexecução mesmo batch | `:duplicate`; contagens iguais |
| Token adulterado | rejeitado; estado inalterado |
| Resume com token/batch corretos | `:duplicate`; active `a` |

IDs sintéticos aplicados **somente** no dump descartável
(`authorized-dump-stable-ids-disposable.edn`), não em Cassandra/produção.

---

## 6. Critérios Go / No-go para produção

### Go (produção) — ainda **não** autorizado; requer nova aprovação

1. Decisão humana nos **9** pending (+ multi-chat `8b3894bf04be`).
2. Política para **9** contas com colisão/ambiguidade (61+20 grupos).
3. Economia: importar `loja` known; hold divergent/unknown — nunca zero implícito.
4. Ensaio descartável de lote (este) verde + backup/restore ok.
5. Release A6 com dump autorizado + token admin; slots `recovery-*` sem ativar.
6. Aliases só com evidência WA/admin.

### No-go

- Import/025/deploy no live sem cópia descartável prévia e aprovação explícita.
- Auto-alias `@lid`/`@c.us`; auto-decidir pending; fundir chats.
- Aplicar sintéticos em contas com ambiguous/native-collision.
- Tratar unknown como 0; somar saldos.

### Rollback produção (quando houver)

Cold A5.6; descartar ensaio; recibos `m50-import|…`; sem `down -v` nos volumes nomeados.

---

## 7. Preparação de produção (sem execução)

Ensaio descartável **12/12** aprovado tecnicamente.  
Runbook operacional (inventário, 025, backup, manifesto imutável, rollback,
Go/No-go): [`docs/pokemon-a6-4-production-runbook.md`](pokemon-a6-4-production-runbook.md).

Manifesto selado: `manifest-id` `a64-prod-prep-12` · `content-hash`
`2acc4689f5460f425702e9b77511a08be7ceb32c6a8b9693b8420e04428df812`
(privado: `~/odisseu-recovery/a64-reconcile/manifest-immutable-12.edn`).

**Execução em produção ainda não autorizada** — aguardar Go explícito do operador.
