# A6.4 — Relatório Core: SSH, volumes, fonte de verdade dos saves

Branch: `a6-save-slots` (preserva `32fcaf4` … `2102594` + este doc).
Atualizado: 2026-10-27. **Somente leitura** após o backup frio A5.6.
Sem import V1/V2, sem migration 025 em produção, sem deploy, sem restart
adicional nesta fase, sem escrita Cassandra/Datomic live.

---

## 1. SSH

```text
ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146
→ AUTH_OK (user=caiohclavico host=odisseu-core)
```

## 2. Volume Datomic live (A5.6)

| Item | Evidência |
|------|-----------|
| Imagem JVM | `odisseu-jvm:2cab639-amd64` |
| Compose | `odisseu-core.yml` + `release-2cab639.yml` (+ node `allow-all-groups`) |
| Env Node | `APP_ENV=staging`, `POKEMON_ADAPTER=jvm`, `PLATFORM_ADAPTER=datomic` |
| Cassandra no Core | `CASSANDRA_KEYSPACE=zapbot_core_unused` @ `127.0.0.1` (**não** é o Oracle histórico) |
| **Volume montado** | **`odisseu-core-a56-datomic` → `/data/datomic-local`** |
| Volumes **não** montados | `odisseu-core-datomic` / `journal` / `maint` (legado pré-A5.6) |

Com `APP_ENV=staging` + `POKEMON_ADAPTER=jvm`, o Node **pode** escrever Pokémon
no Datomic via bridge. O `core` vazio **não** é forçado por fallback “production→legacy”
neste host.

## 3. Backup frio A5.6 (já feito)

- Destino VM/Mac: `…/a64-preflight/a56-cold-20261027T213056Z/`
- JVM parada → cópia Alpine RO → JVM healthy de novo
- Migration 025 **só** em `a56-025-rehearsal` (descartável)

## 4. Diagnóstico: por que o Core A5.6 tem 0 treinadores?

### Veredito

**Esperado após o cutover de volume A5.6, não após uma migração completa de saves.**

O volume `odisseu-core-a56-datomic` foi criado/associado no release `2cab639` com
schema Pokémon (migrations 001–024) e platform populada, **sem** importar os
treinadores históricos do Cassandra nem copiar o conteúdo de `odisseu-core-datomic`.

Não tratar os ~151 `platform.participant` como treinadores Pokémon.

### Evidências por fonte (somente leitura)

#### A) Datomic `core` — volume live A5.6 (cold copy)

| Métrica | Valor |
|---------|-------|
| Migrations | 24 (até `024-gym-history`) |
| Players / saves / Pokémon | **0 / 0 / 0** |
| Txs | ~31 (quase só schema) |

#### B) Datomic `platform` — mesmo volume A5.6

| Métrica | Valor |
|---------|-------|
| Txs | ~11689 |
| `:platform.participant/key` | **151** (todos `@lid`) |
| `:platform.rank.player/key` | **2** |
| Chats distintos (sufixos) | 7 IDs numéricos/lid — **não** coincidem 1:1 com os `@g.us` do legado Pokémon |

#### C) Datomic `core` — volume **não montado** `odisseu-core-datomic`

Cópia RO: `~/odisseu-recovery/a64-preflight/legacy-core-datomic-ro-20261027/`

| Métrica | Valor |
|---------|-------|
| Migrations | 20 |
| Players / saves | **3 / 6** |
| Pokémon em coleção | **7** |
| Actives | todos `a` |
| Identidades (hash) | `6635c7f7bf15`, `e9b874a726fb` (+ sintético de teste) |
| Chat | sufixo `…663917673@g.us` |

Alinha-se ao backup a frio `a455` (git `6f62a67`): progresso **pequeno/de ensaio**,
não o catálogo histórico completo.

#### D) Cassandra Oracle `zapbot` — legado `legacy_*` (fonte histórica principal)

| Métrica | Valor |
|---------|-------|
| Partições V1 `treinador` | 9 (2 `@g.us`, 6 `@lid`, 1 `__migrado__`) |
| Players nos 2 grupos | **23** (19 `@lid`, 4 `@c.us`) |
| Com V1 / V2 / ambos | 23 / 21 / 21 |
| Chave V2 `legacy_estado` | inclui `treinador` (+ loja, rank, ginásios, …) |
| `estado` (operacional monolítico) | vazio nesta leitura |
| `estado_particionado` | módulos: `treinador`, `participantes`, `pokemon-http-*`, … |

#### E) Cassandra — `estado_particionado` / `treinador` (operacional)

8 partições (misto `@lid` / `@g.us`). Contagens de Pokémon **sem exportar contas**:

| Partição (sufixo) | Players | Equipe+PC+Enfermaria |
|-------------------|---------|----------------------|
| `…548487@g.us` | 12 | **61** |
| `…60473844195341@lid` | 2 | 7 |
| demais (várias) | 1–2 | 0–2 |

Campos típicos de conta: `equipe`, `pc`, `enfermaria`, `pokedex`, `times-liga`, …

Isso é progresso **operacional** (possivelmente HTTP/legado recente), **paralelo**
ao dump `legacy_*`, não idêntico.

### Cruzamento de identidades (hashes SHA-256 truncados)

| Comparação | Resultado |
|------------|-----------|
| Legacy Datomic ∩ Cassandra V1 | **2** hashes (`6635c7f7bf15`, `e9b874a726fb`) |
| Cassandra V1 ∩ Platform participants | **13** hashes (sobreposição parcial) |
| Cassandra V1 ∩ `estado_particionado` treinador | **7** hashes |
| Platform total (hashes únicos amostrados) | ~119–151 participantes ≠ 23 saves V1 |

**Conclusão:** a fonte de verdade dos **saves RPG recuperáveis em massa** é o
Cassandra histórico (`legacy_estado_particionado` + `legacy_estado`), com
complemento operacional em `estado_particionado`. O Datomic A5.6 `core` live
**não** contém esses saves. Platform é catálogo social/WhatsApp, não Pokédex.

---

## 5. Inventário consolidado (contagens)

| Fonte | Treinadores | Saves/slots | Pokémon (aprox.) | Notas |
|-------|-------------|-------------|------------------|-------|
| A5.6 Datomic `core` live | 0 | 0 | 0 | Schema only |
| A5.6 Datomic `platform` | n/a | n/a | n/a | 151 participants ≠ trainers |
| `odisseu-core-datomic` (desmontado) | 3 | 6 (`a`/`b`) | 7 | Ensaio pré-A5.6 |
| Cassandra V1 (2 grupos) | 23 | n/a (legado) | não exportado aqui | Probe versões only |
| Cassandra V2 `treinador` | ⊆ V1+extras | n/a | não exportado aqui | 21/23 com V2 nos grupos |
| Cassandra `estado_particionado` treinador | ≥12 num grupo principal | n/a | ≥61 nesse grupo | Operacional |

Artefatos privados (mode 600, fora do Git):

- `~/odisseu-recovery/a64-preflight/cassandra-inventory-ro.json`
- `~/odisseu-recovery/a64-preflight/list-players-*.json`
- `~/odisseu-recovery/a64-preflight/probe-summary-*.json`
- `~/odisseu-recovery/a64-preflight/a56-cold-*`
- `~/odisseu-recovery/a64-preflight/legacy-core-datomic-ro-*`

---

## 6. Divergências e riscos

1. **Volume A5.6 vazio vs expectativa de “produção com saves”** — risco de achar
   que o RPG “perdeu” dados no Datomic; na prática os dados históricos estão no Cassandra.
2. **Dois legados Cassandra** (`legacy_*` vs `estado_particionado`) podem divergir
   (ex.: 22 players V1 no grupo vs 12 no operacional do mesmo `@g.us`).
3. **`@lid` vs `@c.us`** — 4 players só `@c.us` no V1; WhatsApp atual pode ser `@lid`
   → exige `:identity-aliases` no dump autorizado.
4. **Mesmo player hash em vários chats/partições** — import deve ser por
   (chat-id, player-id) explícito; senão mistura progresso.
5. **Platform 151** — não usar como lista de importação Pokémon.
6. **Remontar `odisseu-core-datomic` no lugar de `a56`** — traria só 3 jogadores de
   ensaio e **perderia** o platform A5.6 atual se feito de forma destrutiva.
7. **Core Node não lê Oracle** (`zapbot_core_unused`) — recuperação exige export
   offline autorizado, não o caminho WhatsApp→Cassandra live.

---

## 7. Plano proposto de recuperação V1/V2 (ainda sem executar)

### Princípios

- Fonte primária: dumps `legacy_*` SELECT-only (A6.3.1).
- Complementar: inventariar `estado_particionado`/`treinador` para gaps, com política
  humana quando divergir do legacy.
- Destino: slots `recovery-v1` / `recovery-v2` no Datomic do **jogador atual**,
  sem ativar, sem sobrescrever save ativo (A6.3/A6.4).
- Idempotência: recibos `m50-import|…`; retry → `:duplicate`.
- Rollback: manter volumes `a56-*`; backups frios; não `down -v`.

### Etapas

1. **Fechar inventário por jogador** (hash → presença V1/V2/operacional/platform).
2. **Aliases** atuais WhatsApp ↔ histórico (especialmente `@lid`↔`@c.us`).
3. Export EDN autorizado por lote (`:authorized-offline-copy true`), checksums, mode 600.
4. Validar `transform`/`discover` em Datomic **descartável** (cópia do a56 cold).
5. Aplicar migration 025 **só** na cópia de ensaio populada (já ok em schema vazio;
   repetir após primeiro import sintético/real em cópia).
6. Release A6 na Core **preservando** `odisseu-core-a56-*`; carregar
   `ODISSEU_RECOVERY_DUMP_PATH` + import token.
7. Fluxo WA: `!pk recuperar` → confirmação jogador → **import admin** por requestId.
8. Pós-import: `!pk saves` / contagens; comparar hashes de conteúdo da prévia.

### Critérios de go/no-go

- Go: dump checksum ok; identidade resolvida sem ambiguidade; ensaio em cópia ok;
  backup frio recente; volumes a56 nomeados no compose de release.
- No-go: ambiguidade de dono; divergência legacy vs operacional sem decisão humana;
  tentativa de import no volume live sem cópia descartável prévia.

---

## 8. Restrições respeitadas nesta investigação

- Sem import V1/V2.
- Sem 025 no volume de produção.
- Sem escrita Cassandra / Datomic live.
- Sem deploy / restart (além do ciclo de backup frio já documentado).
- Sem alteração de volumes, chaves ou metadata da VM.
- Platform participants **não** tratados como treinadores.

## 9. Próxima ação recomendada

Autorizar **export SELECT-only** dos 23 players V1 (e operacionais divergentes)
para dumps privados + matriz de aliases — ainda sem import — e um ensaio de
`discover`/`confirm!` em cópia descartável do A5.6 cold com um jogador piloto
(hash já sobreposto: p.ex. `6635c7f7bf15`).
