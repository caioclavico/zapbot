# M5.1 — Preparação da recuperação dos saves reais

**Branch:** `refactor/modular-monolith-datomic`  
**Base M5.0:** `12fda2f`  
**Escopo:** ferramentas + procedimento offline. **Sem** acesso a produção / Oracle / GCP / Cassandra real.

> M5 continua **não concluído**. Esta etapa só prepara descoberta e validação.

## Auditoria das fontes (código legado)

| Fonte | Módulo Cassandra | Papel | Leitura neste marco |
| --- | --- | --- | --- |
| Conta viva | `treinador` + `loja` | Save atual em gameplay | Inventário offline |
| Snapshots dorminhocos | `pokemon-save-slots` + `pokemon-save-slot-index` | Etapa 1 — envelopes imutáveis | Inventário offline |
| Recibos globais | `pokemon-save-global-receipts` | Anti-duplicação Adventure | Metadados / não decide identidade |
| Playable A/B | `pokemon-playable-save-*` + pointer | Ativação (flags fail-closed) | Fora do matching 26/119 |
| Restauração | `pokemon-restauracao-*` | Journals/backups de restore | Não copiar como save |
| Código | `pokemon-service/runtime/save-slot-store.cjs` | Import imutável + list/metadata | Referência de envelope |
| Código | `pokemon-service/runtime/restore-protocol.cjs` | Contagem `equipe+pc+enfermaria` | Critério de contagem |
| Docs | `docs/pokemon-adventure-sync/multiplos-saves-etapa1.md` | Contrato Etapa 1 | |
| Docs | `docs/pokemon-recovery/restauracao-segura-proposta.md` | Protocolo restore (não habilitado) | |

### sourceVersion **não** identifica os históricos

No legado, `sourceVersion` `"1"` / `"2"` significa apenas a tabela de origem (`legacy_estado_particionado` vs `legacy_estado`), **não** “save de 26” vs “save de 119”. O matching M5.1 **ignora** sourceVersion como identidade.

## Critérios objetivos: 26 vs 119

Definidos em `pokemon.migration.profiles/expected-profiles`:

| Perfil | Contagem | Shiny obrigatório | Id estável (fixture) |
| --- | ---: | --- | --- |
| `:save-atual-26` | **26** | espécie `Voltorb` | `act-voltorb-shiny-007` |
| `:historico-independente-119` | **119** | espécie `Voltorb` | prefixo `hist-voltorb-shiny` (`hist-voltorb-shiny-001`) |

Contagem = `equipe` + `pc` + `enfermaria` (igual `validateSlotAccount`).

Se **0** ou **>1** candidatos casarem com um perfil → status `:unmatched` / `:ambiguous` / `:blocked-insufficient`. **Nunca** auto-selecionar. Se evidência for insuficiente, **bloquear** sem completar valores por suposição.

## Fixture corrigida (Voltorb no histórico 119)

- Histórico: **100** na equipe + **19** no PC = **119**.
- Voltorb shiny com id **`hist-voltorb-shiny-001`**.
- Dump inclui envelope sintético em `pokemon-save-slots` + overlay `historical_snapshots`.

## Comandos (offline)

```bash
# Suite M5.1 (+ regressão M5.0)
./scripts/test-m5-1-recovery.sh

# Discovery sintético → target/m5-1/{inventory,match,checksums}
# (via testes / REPL)
# (pokemon.migration.recovery/synthetic-discovery! {:out-dir "target/m5-1"})
```

Inventário sobre dump EDN futuro (após autorização):

```clojure
(pokemon.migration.recovery/run-offline-discovery!
  {:dump-path "path/para/dump-offline.edn"  ;; meta :authorized-offline-copy true
   :out-dir "target/m5-1-real"})
```

Export IR **sem mutar origem** (exige matching resolvido por padrão):

```clojure
(pokemon.migration.recovery/export-ir-from-fixture-dump!
  {:dump-path "fixtures/m5-0/cassandra-dump.edn"
   :out-dir "target/m5-1"
   :require-match-resolved? true})
```

Import descartável só com gates:

```clojure
(pokemon.migration.import/import-ir!
  store ir {:import-batch-id "…"
            :require-match-resolved? true
            :match-report match-report})
```

## Procedimento para dados reais (futuro)

1. **Autorização expressa** (ver checklist abaixo).  
2. Backup consistente Cassandra (snapshot/nodetool ou `COPY` read-only) **fora** desta etapa.  
3. Transferir dump para máquina isolada; marcar `:meta {:authorized-offline-copy true}`.  
4. `checksum-manifest` + `verify-checksums!`.  
5. `run-offline-discovery!` → inventário + match.md.  
6. Se status ≠ `:resolved` → **parar**; revisão humana; não inventar mapping.  
7. Confirmação humana do `suggested-mapping` (assinada).  
8. `export-ir-from-fixture-dump!` / transform → IR.  
9. Import em Datomic Local **descartável** + equivalência M5.0.  
10. Rollback = apagar storage / restaurar backup Datomic; origem Cassandra intacta.

## Gates que impedem importação

`pokemon.migration.gates`:

- perda de campos (`id-pokemon`, `species`, `nivel`)
- colisão de `content-hash` entre saves
- proveniência ambígua / quarentena dura
- matching ≠ `:resolved` (quando exigido)
- `auto-selected?` true (proibido)

## Autorização expressa obrigatória

| Ação | Autorização |
| --- | --- |
| SSH VM Oracle/GCP | ops + ticket |
| Query Cassandra produção | ops + janela read-only |
| Export dump com IDs reais | LGPD/dono + storage seguro |
| Import descartável de dump real | mapping humano + checksums |
| Cutover / flags / deploy | go/no-go M5.3+ |
| Auto-escolher saves ambíguos | **proibido** |

Código: `pokemon.migration.recovery/authorization-checklist`.

## Testes

```bash
./scripts/test-m5-1-recovery.sh
```

Cobertura: Voltorb histórico; match resolvido sintético; ambiguidade sem auto-pick; gates; import com `require-match-resolved?`; checksums; sourceVersion ignorado.

## Namespaces novos

| NS | Função |
| --- | --- |
| `pokemon.migration.profiles` | Critérios 26/119 |
| `pokemon.migration.inventory` | Scan offline |
| `pokemon.migration.match` | Matching sem auto-seleção |
| `pokemon.migration.gates` | Bloqueio de import inseguro |
| `pokemon.migration.recovery` | Procedimento + checksums |

## O que **não** foi feito (de propósito)

- Conectar produção  
- Copiar dados pessoais reais  
- Escolher saves sob ambiguidade  
- Habilitar flags / deploy  
- Declarar M5 completo  
)
