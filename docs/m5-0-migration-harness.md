# M5.0 — Harness seguro Cassandra → Datomic

**Branch:** `refactor/modular-monolith-datomic`  
**Base M4.12:** `fabc862`  
**Escopo:** pipeline sintético apenas. Sem produção, sem dual-write, sem alteração de deploy/flags.

> **M5 NÃO está concluído.** Esta etapa valida o *harness*. Importação efetiva de dados reais exige autorização explícita em marco posterior.

## Objetivo

Exportar → transformar → importar → conferir em Datomic Local descartável, com proveniência explícita dos dois históricos de save (atual × independente), sem mesclar coleções.

## Como executar

```bash
./scripts/test-m5-0-migration.sh
```

Alias Clojure: `-M:test-m5-0-migration` (heap até 512 MB).

Artefatos gerados:

| Path | Conteúdo |
| --- | --- |
| `fixtures/m5-0/cassandra-dump.edn` | Dump sintético (`:meta {:synthetic true}`) |
| `target/m5-0/export.edn` | Export normalizado |
| `target/m5-0/ir.edn` | IR intermediária auditável |
| `target/m5-0/report.edn` / `report.md` | Equivalência, proveniência, quarentena, backup |

Storage descartável: `.datomic-local/m50-*` (gitignored).

## Fixtures sintéticas

Conta `chat-m50-syn-001` × `player-ash-syn@c.us`:

| Save | Proveniência | Pokémon | Destaques |
| --- | --- | --- | --- |
| **A** | `:canonical-active` / `syn-active-atual-001` (módulo `treinador`) | **26** | Voltorb shiny, Gyarados shiny, moedas/itens/insígnias |
| **B** | `:historical-independent` / `syn-hist-independente-001` (envelope fixture) | **119** | Voltorb shiny + Dragonite shiny; inventário distinto |

Grupo: ocupação sintética de ginásio + descoberta global Voltorb.

Quarentena proposital: conta `loja` órfã (`player-loja-orfao@c.us`) sem `treinador`.

### Proveniência (não usar sourceVersion)

`sourceVersion` V1/V2 **não** identifica os dois históricos (ver M4.12). O harness:

1. Exige `:snapshot-id` + `:kind` explícitos.  
2. Se `sourceVersion` aparecer, registra quarentena `:sourceVersion-ignorado-como-identidade` e **ignora** como chave.  
3. Nunca concatena equipes nem soma inventários A+B.

## Pipeline

```
fixtures / EDN descartável
        │
        ▼
  migration.export     → modules + historical_snapshots
        │
        ▼
  migration.transform  → IR (saves, hashes, quarantine)
        │
        ▼
  migration.import     → Datomic Local (sem ensure-trainer!)
        │
        ▼
  migration.equivalence → campo a campo + contagens
        │
        ├── switch A→B→A (coleções intactas)
        ├── reimport mesmo batch → :duplicate
        └── backup dir → delete db → restore → re-ler
```

### Regras de importação

- **Proibido** chamar `ensure-trainer!` / bridge ensure nas identidades do import *antes* de importar (teste cobre rejeição).  
- Recibo `m50-import|{batch}|{save-key}` + payload-hash:  
  - mesmo hash → `:duplicate`  
  - hash diferente → erro explícito `import_receipt_payload_mismatch`  
  - save já populado sem recibo compatível → `import_save_not_empty`  
- Ambíguos → quarentena no IR/relatório; bloqueios duros abortam o import.

## Testes cobertos

1. Tamanhos 26 / 119 + shiny Voltorb + IDs disjuntos.  
2. Transform com proveniência e quarentena.  
3. Equivalência campo a campo + A→B→A sem merge.  
4. Reimport idempotente.  
5. Rejeição se ensure-trainer prévio.  
6. Rejeição se payload divergente no mesmo batch.  
7. Pipeline + backup/restore com equivalência pós-restauração.

## Rollback (procedimento)

1. **Harness / staging descartável:** restaurar snapshot do diretório Datomic (`migration.backup/restore-storage!`) ou apagar o `storage-dir`.  
2. **Produção (futuro, não nesta etapa):** manter `POKEMON_ADAPTER=legacy`; restore Cassandra pré-cutover; Datomic vira arquivo read-only. Sem dual-write.

## Riscos que permanecem para migração real

| Risco | Mitigação proposta (M5.x+) |
| --- | --- |
| Dump real sem `:synthetic` | Gate + autorização ops; export offline |
| IDs lid vs phone | Mesma normalização do bot atual no transform |
| Raid/ginásio mid-flight | Janela congelada; import só estados terminais ou mapeamento de fase |
| Missões/diário mid-flight | Quarentena ou zerar com aviso (módulos ainda BLOQ no JVM) |
| Snapshots Etapa 1 órfãos | Inventário por índice + revisão humana |
| Memória VM com Chromium+JVM | Medir no M5.3 staging; imagem ainda sem JRE |
| Dual-writer Cassandra×Datomic | **Proibido**; single-writer no cutover |
| ensure-trainer em massa pré-import | Checklist ops; harness falha fechado |

## Código

| Namespace | Papel |
| --- | --- |
| `pokemon.migration.fixtures` | Dump sintético |
| `pokemon.migration.export` | Fixture/arquivo → export |
| `pokemon.migration.transform` | Export → IR |
| `pokemon.migration.import` | IR → Datomic |
| `pokemon.migration.equivalence` | Diff campo a campo |
| `pokemon.migration.backup` | Cópia de storage |
| `pokemon.migration.report` | Relatório EDN/MD |
| `pokemon.migration.pipeline` | Orquestração |
| `pokemon.tests.migration-m50-test` | Suite |

## Critério de pronto *desta* etapa (M5.0)

- [x] Fixtures 26 + 119 com shiny/IDs/economia/grupo  
- [x] Proveniência explícita (sem identidade via sourceVersion)  
- [x] Export / IR / import / equivalência / A↔B / idempotência / backup  
- [x] Relatório + script + docs  
- [ ] ~~Importação de dados reais~~ → **fora** do M5.0  

Próximo marco sugerido (M4.12 §9): **M5.1** gaps WhatsApp MVP *ou* **M5.x** dry-run com dump real autenticado em base descartável.
)
