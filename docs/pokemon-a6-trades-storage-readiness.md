# Odisseu A6 — storage readiness · trocas Pokémon

**Status:** CONDITIONAL  
**Gerado:** 2026-11-04T14:10Z (UTC)  
**Host:** `odisseu-core` (`x86_64`)  
**Escopo:** desbloquear capacidade de disco para o RC de trocas **sem** apagar dados de jogo.  
**Nesta etapa:** somente auditoria — **nenhuma exclusão**, deploy, migration, recreate ou restart.

---

## 1. Espaço atual

| Métrica | Valor |
| --- | --- |
| Filesystem `/` | ext4 **48 G** · usados **44 G** · livres **4.0 G** · **92%** |
| Inodes `/` | 8% usados — OK |
| `docker system df` Images | 10.94 GB total · reclaimable reportado **3.377 GB (30%)** |
| Containers | 2.7 MB (irrelevante) |
| Volumes locais | **31.98 MB** total · Datomic cutover **17.78 MB** |
| Build cache | **7.111 GB** (Docker marca só ~229 MB reclaimable; tratar prune como *indeterminado*) |
| `$HOME/odisseu-recovery` | **~17 G** (principal concentração de artefatos) |

### Ativos (não tocar)

| Recurso | Papel |
| --- | --- |
| Containers `odisseu-a6-node` / `odisseu-a6-jvm` | produção live |
| Images `508021e8…` (Node) / `e18c20cb…` (JVM) | implantadas |
| Volume `odisseu-a6-cutover-datomic` | Datomic jogo (~18 MB) |
| `a6-cutover-session-from-core/` | sessão Chromium/WA bindada |
| `a6-cutover-runtime-20261101T141408Z/` | compose operacional |
| `a6-whatsapp-session-backup-20261104T004608Z.tar.gz` | backup único recente de sessão (**1.4 G**) |
| `a6-fase12832-deploy-authorized/` + periodics `12832-*` | rollback/backup JVM atual |
| `odisseu-node-hotfix-5088b5b-amd64.tar` | tar da Node ativa |

Dados Datomic e saves **não** são o problema de espaço (volume ~18 MB).

---

## 2. Espaço necessário (medido, não arbitrário)

### Entradas medidas

| Item | Tamanho |
| --- | --- |
| Tar RC Node | **371 MiB** (`odisseu-node-amd64.tar`) |
| Tar RC JVM | **249 MiB** (`odisseu-jvm-amd64.tar`) |
| Soma tars | **620 MiB** |
| Imagem Node virtual (família atual) | ~**1.5 GB** (layers majoritariamente **compartilhadas**) |
| Imagem JVM virtual | ~**848 MB** (idem) |
| Unique tipico entre tags Node irmãs | ~**18 MB** |
| Unique tipico entre tags JVM irmãs | ~**5 MB** |
| Backup Datomic cold/warm | ~**18 MB** (volume atual) |
| Migration 033 | desprezível (attrs schema) |

### Cenários de pico em `/`

Assumindo preservação das imagens atuais (rollback) já residentes.

| Cenário | Pico estimado | Cabe em 4.0 G? |
| --- | --- | --- |
| **A — otimista** (layers compartilhadas): tars 620 MiB + unique ~23 MiB + backup 18 MiB + margem 1 GiB | ≈ **1.7 GiB** | Sim |
| **B — sequencial** (load JVM→apagar tar JVM→load Node→apagar tar Node) + unique pior caso parcial + margem 1 GiB | ≈ **2.5–3.0 GiB** | Sim, apertado |
| **C — pessimista simultâneo** (tars + unique full 1.5 G+848 M sem share + margem 1 GiB) | ≈ **4.0 GiB** | **Não** (margem zero) |

**Conclusão de necessidade:** para deploy **com margem e rollback seguros**, almejar **≥ 6 GiB livres** antes do `docker load`, **ou** executar limpeza comprovada + carga sequencial. Os **4.0 GiB atuais não comprovam folga** no cenário C.

Espaço “consumido ao mesmo tempo” por tar **e** imagem carregada: até +620 MiB enquanto os tars permanecerem após o load — por isso a estratégia sequencial + remoção dos tars RC **após** verificar Image IDs.

---

## 3. Classificação de limpeza (proposta — **não executada**)

### 3.1 Ativos / essenciais (proibido remover)

- Containers e volumes live; sessão WA; compose runtime.  
- Backup de sessão `…20261104T004608Z.tar.gz`.  
- Imagens Docker atualmente em uso.  
- Tar/hotfix `5088b5b` e pacote `a6-fase12832-deploy-authorized` + periodics `12832-*`.

### 3.2 Necessários para rollback imediato (preservar)

| Artefato | Motivo |
| --- | --- |
| Docker Node `a6-critical-5088b5b` | produção atual |
| Docker JVM `a6-fase12832-…-b99fb7863975` | produção atual |
| Opcional: Node `a6-node-battle-pending-9757e31` (`611718d0`) | rollback imediatamente anterior ao hotfix |
| Periodics cold/warm 12832 | último backup Datomic alinhado à JVM |

### 3.3 Temporários / RC históricos — **descartáveis comprováveis** (~**12.6 GiB**, 49 entradas)

Diretórios/tars sob `/home/caiohclavico/odisseu-recovery/` já substituídos por deploys posteriores, **sem** ser a imagem/compose ativos. Exemplos (maiores):

```
/home/caiohclavico/odisseu-recovery/a6-fase1282b-saves-rc-20261103T024704Z     (~622M)
/home/caiohclavico/odisseu-recovery/a6-fase1281-rc-20261102T204022Z            (~622M)
/home/caiohclavico/odisseu-recovery/a6-fase128-rc-20261102T194643Z             (~622M)
/home/caiohclavico/odisseu-recovery/a6-fase12.7.3-rc-20261102T044514Z          (~622M)
… (lista completa: ~17 dirs ~620M + tars avulsos 9757e31, jvm-1273fa4, a5-6-2-*, builds)
```

Também candidatos menores em `/tmp`:

```
/tmp/a6-trades-datomic-ro-20261104T134520Z     (~17M)  # cópia RO da auditoria
/tmp/a6-trades-033-rehearsal-20261104T134520Z  (~18M)  # ensaio 033 em cópia
/tmp/a6-127-src.tgz /tmp/a6-127-build-ctx      (~43M)
```

**Estimativa recuperável só com RC/tars históricos:** ≈ **12.6 GiB** (13 547 838 668 bytes; 49 paths).  
Lista fechada: `docs/assets/a6-trades-disposable-paths.txt`.  
Após isso, livres projetados: **4.0 + 12.6 ≈ 16.6 GiB** (antes de qualquer prune Docker).

### 3.4 Indeterminados (não remover sem revisão)

| Item | Nota |
| --- | --- |
| Periodics antigos (~554 MiB agregados) | backups; preferir retenção N últimos |
| Build cache Docker 7.1 GB | `docker builder prune` **não** é `-a` de imagens, mas efeito em rebuilds futuros; só com GO explícito |
| Imagens Docker unused (3.377 GB reclaimable) | remover **por tag/ID explícito**, nunca `docker system prune -a` / `volume prune` |
| Containers exited `odisseu-core-node/jvm` | legados; remoção de container exited não apaga volumes nomeados |

### 3.5 Explicitamente **fora** de qualquer limpeza

- Volumes Datomic / journal / maint.  
- Saves / storage de jogo.  
- Sessão WhatsApp live.  
- `docker system prune -a`, `docker volume prune`, `docker builder prune -af` sem autorização.

---

## 4. Decisão / estratégia segura

### Caminho recomendado (sem expansão de disco)

1. **Autorizar limpeza seletiva** dos 49 artefatos descartáveis em `odisseu-recovery` (~12.6 GiB) — lista fechada, `rm -rf` só dos paths nomeados.  
2. Confirmar `df -h` ≥ **15 G** livres (folha).  
3. Só então: transferir RC → `docker load` sequencial → verificar Image IDs → apagar tars RC da VM se desejado → (etapa futura) deploy.

### Alternativa sem limpeza ampla

- Carga **sequencial** + apagar cada tar após load verificado.  
- Cabe no papel no cenário B, mas **sem folga** para surpresas → permanece CONDITIONAL.

### Se limpeza for recusada e cenário C for exigido

- **Expandir disco** do root (≥ +8–10 G) antes do GO.

Não há necessidade de apagar Datomic, sessão ou imagens ativas para caber o RC.

---

## 5. Próxima operação recomendada

**Operação seguinte (ainda sem deploy):**  
pedido explícito de **GO DE LIMPEZA** dos paths descartáveis listados na §3.3 (ou subconjunto prioritário dos ~620 MiB×N até liberar ≥6 GiB).

Depois da limpeza verificada: retomar `docs/pokemon-a6-trades-deployment-readiness.md` e solicitar GO de implantação.

---

## 6. Riscos

| Risco | Mitigação |
| --- | --- |
| Apagar tar/imagem ainda necessária ao rollback | Manter §3.1–3.2; checklist pré-rm |
| `df` 92% durante load simultâneo | Evitar cenário C; sequencial ou limpar antes |
| Confundir session-backup com RC | Nunca incluir `*whatsapp-session*` na limpeza |
| Prune agressivo Docker | Proibido nesta linha |
| Liberar pouco e seguir ao deploy | Abortar se livres &lt; 6 GiB no pré-GO (meta do readiness) |

---

## 7. Status

**CONDITIONAL**

- Espaço **recuperável comprovado** (~**12.6 GiB** em RCs/tars históricos) torna o deploy viável **após limpeza autorizada**.  
- Com **4.0 GiB** livres hoje, o pico pessimista simultâneo **não** deixa margem segura.  
- Sem bloqueio estrutural de dados de jogo; **não** é necessário expandir disco se a limpeza seletiva for aprovada.

**Nenhuma exclusão ou mudança live foi executada nesta auditoria.**
