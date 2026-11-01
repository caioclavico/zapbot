# A6 — Fase 7: Garantia de integridade dos saves A5.6 → A6

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-11-01  
**Prioridade:** preservação integral dos dados dos jogadores  

**Modo desta fase:** auditoria RO + backup/restore comprovado + ensaio de schema **somente em cópia isolada**.  
**Não feito:** corte, deploy A6 no Core, migrations no volume live `odisseu-core-a56-*`, remoção do A5.6, merge, WhatsApp.

---

## Decisão: **CONDITIONAL**

O ensaio de integridade A5.6 → schema A6 (029/030) **passou** em cópia isolada: nenhum jogador/slot/Pokémon/moeda divergiu.  
O **corte definitivo na VM existente** permanece **condicionado** a nova autorização explícita + cold fresco em T0 + checklist operacional (Fase 6 / segurança Core).

| Critério obrigatório | Resultado |
| --- | --- |
| Jogadores/slots desaparecidos | **PASS** (0 missing / 0 extra) |
| Pokémon perdidos, duplicados ou IDs alterados | **PASS** (ids-hash e state-hash idênticos) |
| Níveis/XP/HP/inventários injustificados | **PASS** |
| Moedas zeradas ou promovidas | **PASS** (`coins-sum` 181 → 181) |
| Identidades/referências ambíguas introduzidas | **PASS** |
| Recibos/pendências incompatíveis **criadas** pelo ensaio | **PASS** (pendências pré-existentes preservadas) |
| Restauração consultável | **PASS** (17=17 + Datomic aberto) |
| Teste de rollback | **PASS** (cold → drill ≡ manifesto origem) |
| Corte / migrate live | **não executado** |

---

## 1. Auditoria somente leitura

### 1.1 Fonte canônica

| Fonte | Papel |
| --- | --- |
| Volume live `odisseu-core-a56-datomic` | Produção A5.6 — **somente lido** via bridge HTTP e via cold |
| Cold `a6-fase7-cold-20261101T132844Z` | Snapshot frio (JVM parada) — base do ensaio |
| Restore-check / rehearsal / rollback-drill | Cópias sob `~/odisseu-recovery/a6-fase7/` — **nunca** montadas no compose live |

System/DB: `odisseu-m90-core` / `core`.

### 1.2 Inventário Datomic (autoritativo, pós-restore)

| Métrica | Valor |
| --- | --- |
| Jogadores (`:player/key`) | **20** |
| Saves / slots (`:save/key`) | **59** |
| Pokémon (`:pokemon/key` = `:pokemon/id-pokemon`) | **1072** |
| Soma moedas (`:save/coins`) | **181** |
| Itens de inventário | 24 |
| Recibos de operação | 544 |
| Batalhas (histórico) | 65 |
| Raides | 0 |
| Coin-ledger / schedules / outbox | 0 |
| Migrations | **28** (até `028-economy-ledger`) — **sem** 029/030 no live |

Ferramenta: `scripts/a6_fase7_integrity_manifest.clj`  
Artefato privado: `~/odisseu-recovery/a6-fase7/manifest-src-restore.edn`  
Resumo público: `docs/artifacts-fase7/fase7-summary.json`

Privacidade: `player-hash` / `chat-hash` = sha12; IDs de Pokémon só como hashes; sem telefone/nome/mensagem.

### 1.3 Manifesto por jogador/slot (síntese)

Todos os 20 jogadores compartilham o mesmo `chat-hash` anonimizado do grupo piloto/prod atual.  
19/20 têm slots `a`+`b`+`recovery-v1`; 1 tem só `a`+`b`.

| player-hash | slots | poke Σ | coins Σ | active | fence | pending-rewards? |
| --- | ---: | ---: | ---: | --- | ---: | --- |
| `1b7f43d000c9` | 3 | 118 | 0 | recovery-v1 | 2 | não |
| `1d4a0c2cbc93` | 3 | 6 | 0 | recovery-v1 | 3 | não |
| `3cf793a69b07` | 3 | 2 | 0 | recovery-v1 | 2 | não |
| `6e8ad515a7cb` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `7116331d2e99` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `724d1d63e716` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `74dfb2348c54` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `778f2dcd250c` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `8b3894bf04be` | 3 | 87 | 110 | recovery-v1 | 2 | **sim** (pré-existente) |
| `9a4322c7bd26` | 3 | 10 | 0 | recovery-v1 | 2 | não |
| `9e91b990f5a2` | 3 | 21 | 0 | recovery-v1 | 2 | **sim** |
| `b61f6b3ba75e` | 3 | 39 | 0 | a | 1 | **sim** |
| `bacdc2275203` | 3 | 8 | 0 | recovery-v1 | 2 | não |
| `bb1c8bf84402` | 3 | 5 | 0 | recovery-v1 | 2 | não |
| `c96b30db3100` | 3 | 302 | 0 | recovery-v1 | 2 | não |
| `d15aaa1f4eaa` | 3 | 2 | 0 | recovery-v1 | 2 | não |
| `d4cc1ead79c0` | 3 | 187 | 0 | a | 1 | não |
| `e6e1377fbb0b` | 3 | 270 | 20 | recovery-v1 | 2 | **sim** |
| `ee6231ab35de` | 3 | 1 | 0 | recovery-v1 | 2 | não |
| `f69e27cc4cfd` | 2 | 9 | 51 | a | 1 | **sim** |

Cada slot carrega `canonical-hash`, `pokemon-ids-hash`, `pokemon-state-hash`, digest de inventário e contagem de recibos.

### 1.4 Cruzamento HTTP live (subconjunto)

Script: `scripts/a6-fase7-live-integrity-ro.py`  
Resultado: **19** jogadores do roster conhecido · 57 slots · 1063 Pokémon listados · 130 moedas listadas · 0 erros API.

Diferença 19↔20 / 1063↔1072 é **esperada**: o roster HTTP não cobre 100% das entidades Datomic; a fonte de verdade para esta fase é o cold Datomic.

---

## 2. Backup e restauração (comprovados)

### 2.1 Cold backup (Core)

| Item | Valor |
| --- | --- |
| Stamp | `20261101T132844Z` |
| Path Core | `~/odisseu-recovery/a6-fase7-cold-20261101T132844Z/` |
| Volumes | `a56-datomic` + journal + maint (JVM **parada**) |
| Arquivos | **17** |
| Manifest-of-manifest | `4783f36ed941c3af35862ee975c3c4783c32cac66721df9c63dea693d7a02076` |
| Script | `scripts/a6-fase7-cold-restore.sh` |

JVM voltou **healthy** em `:18090` após o cold. Node permanece `healthy` no Docker Healthcheck; probe host `127.0.0.1:3001/health` observou RST intermitente (possível pré-existente — não investigado a fundo nesta fase; **sem** restart do Node). Volume live **não** foi substituído.

### 2.2 Restore-check

| Ambiente | Prova |
| --- | --- |
| Core | `a6-fase7-restore-check-20261101T132844Z` · **17 = 17** |
| Mac | cópia local · **17 = 17** · SHA256 dos arquivos **iguais** ao cold |
| Consulta Datomic | manifesto `manifest-src-restore.edn` gerado com sucesso |

**Não** basta o diretório existir: o banco abriu, listou 20 jogadores e emitiu hashes.

### 2.3 Rollback drill

`a6-fase7-rollback-drill-*` = cópia fresca do cold → manifesto ≡ origem (`block-migration? false`, sem diffs).

---

## 3. Ensaio de migração (só cópia)

### 3.1 O que foi aplicado

Em `a6-fase7-schema-rehearsal-*` (cópia independente):

1. `pokemon/029-raid-lobby` (schema aditivo)  
2. `pokemon/030-raid-auto` (schema aditivo; **sem** schedules/outbox rows)

Script: `scripts/a6_fase7_schema_rehearsal.clj`  
**Nenhuma** migration no volume live.

### 3.2 Comparação origem × destino

Artefato: `docs/artifacts-fase7/manifest-compare-a5.6-to-a6.edn`

```edn
{:missing-players ()
 :extra-players ()
 :changed-player-hashes []
 :count-diffs {}
 :intentional-schema
 [:applied-029-raid-lobby :applied-030-raid-auto :migration-count-delta]
 :unexpected-divergences false
 :block-migration? false}
```

| Campo | Origem (A5.6 cold) | Destino (ensaio A6) |
| --- | ---: | ---: |
| players | 20 | 20 |
| saves | 59 | 59 |
| pokemon | 1072 | 1072 |
| coins-sum | 181 | 181 |
| migrations | 28 | **30** |
| raids / outbox / schedules | 0 | 0 |
| player-canonical-hash (todos) | — | **iguais** |
| pokemon-ids/state hashes | — | **iguais** |

Alterações **intencionais**: apenas attrs/marcadores de schema 029/030.  
Divergências inesperadas: **nenhuma** (não houve correção silenciosa).

### 3.3 Pendências pré-existentes (não bloqueiam o ensaio de schema)

5 jogadores já tinham `recompensas-pendentes-edn` no A5.6. O ensaio **preservou** esses bytes; não os resolveu nem apagou. No corte, operador deve tratar pendências sob runbook próprio — não como “fix automático” da migration.

---

## 4. Critérios de bloqueio — avaliação

| Bloqueio se… | Avaliação no ensaio |
| --- | --- |
| Jogadores/slots sumiram | **não** |
| Pokémon perdidos/duplicados/IDs mudaram | **não** |
| Nível/XP/HP/inventário injustificado | **não** |
| Moedas zeradas/promovidas | **não** |
| Identidades ambíguas novas | **não** |
| Recibos/pendências incompatíveis **pelo** migrate | **não** |
| Restore/rollback falhou | **não** |

→ Ensaio de integridade de dados: **apto a solicitar autorização de corte**.

---

## 5. Plano de corte (documentado — **não executar**)

1. Congelar escritas: janela curta, avisar grupo, `ALLOW_ALL_GROUPS`/tick já sob política fail-closed desejada.  
2. Cold fresco T0 (`FASE7_DO_COLD=1 ./scripts/a6-fase7-cold-restore.sh`) + restore-check + manifesto.  
3. Comparar manifesto T0 com baseline desta fase (esperar drift só de gameplay real entre agora e T0).  
4. Em **volume novo** (recomendado) ou cópia isolada: aplicar 029/030 (ou subir imagem A6 que auto-instala schema) e revalidar hashes.  
5. Cutover controlado do compose para imagens A6 **apontando ao volume validado** — **nunca** apagar `a56-*` no mesmo passo.  
6. Smoke RO: contagens + amostra de saves ativos + health.  
7. Rollback: `stop` + remontar volume a partir do cold T0 (drill já provado).  
8. Só então liberar features A6 (raides/tick) sob runbook separado.

**Preservar:** volumes `odisseu-core-a56-*`, sessão WA Core, colds em `~/odisseu-recovery/`.

---

## 6. Riscos residuais (por isso CONDITIONAL)

| Risco | Mitigação |
| --- | --- |
| Drift live após este cold | Novo cold + manifesto em T0 |
| Colocar stack A6 na mesma VM (RAM) | Fase 6: VM exclusiva preferível; se mesma VM, só com GO de capacidade |
| `ALLOW_ALL_GROUPS=true` no Core hoje | Corrigir **antes** de expandir A6/WhatsApp |
| Pendências de recompensa em 5 saves | Revisar manualmente; não auto-clear |
| HTTP roster incompleto | Sempre auditar via Datomic cold, não só `/v1/saves` |
| Confundir ensaio com corte | Cópia sob `odisseu-recovery/a6-fase7/*rehearsal*`; live intocado |

---

## 7. Artefatos

| Artefato | Onde |
| --- | --- |
| Relatório | `docs/pokemon-a6-save-integrity-fase7.md` |
| Resumo JSON | `docs/artifacts-fase7/fase7-summary.json` |
| Compare edn | `docs/artifacts-fase7/manifest-compare-a5.6-to-a6.edn` |
| Scripts | `scripts/a6-fase7-live-integrity-ro.py`, `a6-fase7-cold-restore.sh`, `a6_fase7_integrity_manifest.clj`, `a6_fase7_schema_rehearsal.clj` |
| Privados (hashes/contagens) | `~/odisseu-recovery/a6-fase7/` (Core + Mac) |

---

## 8. Checklist de autorização para corte (próxima aprovação)

- [ ] Revisar este relatório e o compare `block-migration? false`  
- [ ] Aprovar cold fresco T0 + restore-check no dia do corte  
- [ ] Decidir destino: volume novo vs in-place (recomendado: **volume novo**)  
- [ ] Confirmar que A5.6/`a56-*` **não** serão removidos no corte  
- [ ] Tratar pendências de recompensa / economia hold se necessário  
- [ ] Alinhar com Fase 6 (sizing VM / isolamento)  
- [ ] Desligar ou restringir `ALLOW_ALL_GROUPS` conforme política  
- [ ] GO explícito por escrito para migrate/deploy

**Sem essa aprovação: não migrar.**
