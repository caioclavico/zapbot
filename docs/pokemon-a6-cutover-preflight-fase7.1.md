# A6 — Fase 7.1: Preflight final A5.6 → A6 (VM existente)

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Data:** 2026-11-01  
**Base:** Fase 7 (integridade) + Fase 6 (GCP readiness)  
**Modo:** documentação / análise RO — **sem** migrate live, deploy, restart de produção, alteração de grupos ou movimentação de saves.

---

## Decisão: **CONDITIONAL**

| Área | Veredito | Motivo |
| --- | --- | --- |
| Recompensas pendentes (5 saves) | **PASS** | Attr presente com EDN `"{}"` — **zero** qty positiva; sem risco de perda/duplicação no corte |
| Capacidade VM (A6 **substituindo** A5.6) | **PASS com monitoramento** | RAM da Core (~5.8 GiB) cabe **uma** stack Node≤2.5 GiB + JVM≤768 MiB; **proibido** colocalizar duas stacks |
| Segurança de grupos | **CONDITIONAL** | Compose atual força `ALLOW_ALL_GROUPS=true` — corte **exige** overlay explícito `false` + allowlist |
| Roteiro corte + rollback | **Documentado** | Pronto para GO; **não** executado |
| Corte agora | **NO-GO** até checklist §7 |

---

## 1. Recompensas pendentes

### 1.1 Identificação (cold Fase 7 — autoritativo)

Fonte: `~/odisseu-recovery/a6-fase7/a6-fase7-restore-check-20261101T132844Z/datomic`  
Atributo: `:save/recompensas-pendentes-edn` (5 entidades).

| # | player-hash | slot | ativo? | EDN bruto | qty positivas | coins no slot | livre mochila (aprox.) |
| --- | --- | --- | --- | --- | ---: | ---: | ---: |
| 1 | `8b3894bf04be` | `recovery-v1` | sim | `{}` | **0** | 110 | 23/50 |
| 2 | `9e91b990f5a2` | `recovery-v1` | sim | `{}` | **0** | 0 | 37/50 |
| 3 | `b61f6b3ba75e` | `a` | sim | `{}` | **0** | 0 | 30/50 |
| 4 | `e6e1377fbb0b` | `recovery-v1` | sim | `{}` | **0** | 20 | 35/50 |
| 5 | `f69e27cc4cfd` | `a` | sim | `{}` | **0** | 51 | 17/50 |

Fase 7 marcou `pending-rewards-present?` porque a string não é blank (`"{}"`). **Não há itens pendentes a conceder.**

### 1.2 Classificação

| Campo | Valor comum aos 5 |
| --- | --- |
| **Tipo** | Mapa de overflow de mochila (`item-id → qty`) no contrato A4.3.3 / bag-daily / missions |
| **Origem** | Paths que persistem `(pr-str (:pending allocated))` mesmo quando o mapa resultante é vazio (bag diário, claim missão, gift) |
| **Estado** | **Inerte / resolved-empty** — chaves conhecidas (`pokebola`, `grande-bola`, …) ausentes; sem `future-item` / chaves desconhecidas |
| **Ação nesta fase** | Nenhuma — **não** coletar, cancelar, limpar nem reescrever |

### 1.3 Compatibilidade A6 (preservar e recuperar)

| Capacidade A6 | Evidência |
| --- | --- |
| Schema preserva attr | `:save/recompensas-pendentes-edn` desde migrations ≤016; 029/030 **não** tocam o attr (Fase 7) |
| Leitura | `mission-economy` / `bag_store` / `mission_queries` leem EDN → `{}` |
| Recuperação jogável | `POST /v1/missions/pending/collect` → `collect-pending!` → `kind :pending`; com `{}` resulta `:empty true`, **sem** inventário alterado |
| Itens desconhecidos | Teste `full-bag-pending-recovery-and-unknown-pending-preserved`: qty conhecidas coletam; desconhecidas **permanecem** no mapa — sem drop silencioso |
| Risco perda/duplicação no corte | **Baixo / nulo** para estes 5 (nada a pagar). Duplicação só ocorreria se alguém **forçasse** collect + reescrevesse estado — **proibido** no preflight |

### 1.4 Critério de bloqueio (pendências)

| Condição | Status |
| --- | --- |
| Qty positiva sem contrato A6 | **não aplicável** (qty=0) |
| Chaves desconhecidas com qty>0 | **não** |
| EDN ilegível | **não** (`{}` parseia) |
| Plano de auto-clear no corte | **proibido** — se alguém propor, **BLOCK** |

→ **Não bloqueia** o corte por risco de recompensa.

**Gate T0:** repetir o dump no cold fresco; se aparecer qty>0, reclassificar antes do GO.

---

## 2. Capacidade da VM (substituição, não colocalização)

### 2.1 Estado atual (RO, 2026-11-01)

| Recurso | Evidência |
| --- | --- |
| Machine | `e2-custom-2-6144` · 2 vCPU · **5.8 GiB** · swap 2 GiB (~148 MiB usados) |
| Disco | ~19 G livres / 48 G |
| Node | ~1.37 / **2.5 GiB** cgroup · ~1.5 CPU |
| JVM | ~316 / **768 MiB** · `-Xmx384m` · 1 CPU |
| Available host | ~3.3 GiB |

### 2.2 A6 no lugar do A5.6

| Cenário | Decisão |
| --- | --- |
| **Parar A5.6 → subir A6** na mesma VM | **Viável** sob limites iguais/parecidos (Node ≤2560 MiB, JVM ≤512–768 MiB) |
| Duas stacks completas ao mesmo tempo | **NO-GO** (Fase 6) |
| JVM A6 + Node A5.6 (ou vice-versa) | **NO-GO** para corte |

Orçamento alvo (já no compose Core): Node+Chromium ≤2560 m · JVM ≤768 m · margem SO ~2–3 GiB.

### 2.3 Critérios de rollback por recurso / indisponibilidade

Abortar ou reverter se, após boot A6 (janela de observação ≥10 min):

| Sinal | Limiar |
| --- | --- |
| `MemAvailable` host | **< 400 MiB** sustentado |
| Swap used | **> 800 MiB** sustentado ou crescimento contínuo |
| OOMKill / exit 137 | qualquer container A6 |
| Health JVM `:18090` | falha > 3 checks consecutivos |
| Health Node (Docker) | `unhealthy` > 2 min |
| Latência bridge | timeouts sistemáticos em smoke RO |

Ação: executar §5 rollback para A5.6 (imagens + volume pré-corte).

---

## 3. Segurança de grupos

### 3.1 Estado atual (problema)

`docker-compose.release-battle-idle.yml` (overlay live) define:

```yaml
ALLOW_ALL_GROUPS: "true"
STAGING_STRICT_USER_ALLOWLIST: "0"
```

**Não herdar isso no corte A6.**

### 3.2 Configuração explícita obrigatória no primeiro boot A6

Modelo: `.env.a6-staging.example` + compose fail-closed.

| Variável | Valor no 1º boot |
| --- | --- |
| `ALLOW_ALL_GROUPS` | **`false`** (obrigatório no YAML de corte, não só no `.env`) |
| `STAGING_STRICT_USER_ALLOWLIST` | `1` |
| `DEV_GROUP_ID` | ID do(s) grupo(s) **confirmados** por escrito |
| `STAGING_GROUP_CONFIRMED` | `1` **somente** após confirmação humana do ID |
| `STAGING_ALLOWED_USERS` | allowlist LID/`@c.us` dos operadores/piloto |
| `STAGING_BOOTSTRAP_GROUP` | `0` |
| `STAGING_ALLOW_QR` | `0` (sessão já pareada; QR só sob GO separado) |
| `POKEMON_RAID_AUTO_TICK` | **`false`** |
| `ODISSEU_BRIDGE_RAID_AUTO_TICK` | **`false`** |
| `WHATSAPP_TRANSPORT` | `whatsapp` (ou equivalente prod) **só** após gates acima |

### 3.3 Preservar grupos existentes

- Comportamento nos grupos atuais **só** após: (1) lista explícita de IDs, (2) `STAGING_GROUP_CONFIRMED=1`, (3) smoke sem broadcast.
- Raides automáticas **desligadas** até runbook pós-corte.
- Sessão: continuar em `.wwebjs_auth_core` **ou** path A6 dedicado — decidir no GO; **não** copiar sessão entre identidades de clientId sem plano.

---

## 4. Roteiro operacional de corte (preparado — não executar)

### Pré-requisitos

- [ ] GO escrito Fase 7.1 + cold T0  
- [ ] Overlay compose A6 **sem** `ALLOW_ALL_GROUPS=true`  
- [ ] Imagens A6 amd64 buildadas e tags registradas  
- [ ] Volume destino preparado (recomendado: **volume novo** a partir do cold T0; live `a56-*` permanece intacto como fallback)  
- [ ] Janela comunicada; operadores na allowlist  

### Passos

1. **Pausar novas operações de jogo**  
   Aviso no grupo piloto; opcionalmente modo read-only / feature flags off (sem auto-raid).

2. **Aguardar ou registrar operações em andamento**  
   Listar batalhas ativas, raids, trades, hunts via bridge RO; esperar drenar ou registrar IDs para reconciliação pós-corte.

3. **Parar writers de forma controlada**  
   `docker compose … stop odisseu-jvm` (e Node se política exigir quiet). Grace ≥30 s JVM / ≥60 s Node.

4. **Gerar cold backup T0**  
   `FASE7_DO_COLD=1` / playbook `scripts/a6-fase7-cold-restore.sh` → `a6-cutover-t0-cold-<stamp>/`  
   Incluir datomic + journal + maint (+ meta hashes).

5. **Verificar integridade e restauração**  
   Restore-check filecount+sha256; abrir Datomic na cópia; gerar manifesto (`a6_fase7_integrity_manifest.clj`); comparar com baseline Fase 7 (drift só de gameplay real).  
   Dump pendências: esperar `{}` ou reclassificar.

6. **Aplicar migrations somente no destino A6 preparado**  
   Volume **novo** (ou rehearsal path) — **nunca** no mount live durante ensaio.  
   Schema 029/030 aditivos (já ensaiados). Subir JVM A6 apontando ao destino.

7. **Iniciar A6 com automáticos OFF**  
   Tick raid false; `ALLOW_ALL_GROUPS=false`; allowlist; sem bootstrap/QR.

8. **Conferir manifestos**  
   players/saves/pokemon/coins-sum + hashes canônicos vs T0.  
   Qualquer missing/extra/changed → **STOP** (§5).

9. **Smoke estritamente controlado**  
   Health JVM/Node; `/v1/saves` de 1–2 usuários allowlisted; **sem** collect-pending em massa; **sem** mensagens a grupos fora da allowlist; **sem** spawn de raide auto.

10. **Liberar operações**  
    Só após validação assinada; liberação gradual (comandos → depois features). Raides auto = GO separado.

---

## 5. Rollback (voltar ao A5.6 sem sobrescrever dados A6)

### Princípio

- Volume/imagens A5.6 pré-corte e cold T0 são a **fonte** do rollback.  
- Dados escritos **só no volume A6 pós-corte** **não** devem ser copiados por cima do A5.6.  
- Manter o volume A6 ** Intact** (renomear/desmontar) para forense/reconciliação.

### Procedimento

1. `stop` stack A6.  
2. **Não** `docker compose down -v`.  
3. Remontar compose A5.6 (tags pré-corte) nos volumes `odisseu-core-a56-*` **inalterados** *ou* restaurar cold T0 para volume de recuperação **novo** e cutover de volta.  
4. Health A5.6; manifesto RO vs T0.  
5. Registrar qualquer op-id / recibo criado **após** o boot A6 (journal A6) — esses eventos **não** existem no A5.6.

### Bloqueio de rollback automático

Se houver **qualquer escrita** confirmada no A6 após o corte (recibo, captura, trade, moeda, pending collect, raid):

| Ação | Regra |
| --- | --- |
| Rollback automático “só restart A5.6” | **BLOQUEADO** |
| Próximo passo | Reconciliar ops A6 (lista de operation-ids) → decidir: (a) aceitar perda das ops A6 e voltar A5.6; (b) cherry-pick/reaplicar; (c) permanecer em A6 |
| Sobrescrever A5.6 com volume A6 | **PROIBIDO** sem GO explícito de merge de dados |

---

## 6. Testes / evidências já disponíveis

| Evidência | Resultado |
| --- | --- |
| Fase 7 schema 029/030 em cópia | hashes jogador/Pokémon/moedas idênticos |
| Fase 7 restore + rollback drill | PASS |
| Dump pendências 7.1 | 5× `{}` |
| Homolog 5.4 / 5.5 | HTTP + scheduler lab OK (sintético) |
| Capacidade RO Core | 1 stack cabe; 2 stacks não |

**Não** reexecutar migrate/deploy nesta fase.

---

## 7. Checklist GO / NO-GO

### NO-GO imediato se

- [ ] Qty>0 em pendências ilegíveis ou sem contrato A6 no cold T0  
- [ ] Plano de limpar/coletar pendências automaticamente no corte  
- [ ] Overlay ainda com `ALLOW_ALL_GROUPS=true`  
- [ ] Intenção de subir A6 **junto** com A5.6 completo  
- [ ] Cold T0 / restore-check falhou  
- [ ] Manifesto T0 diverge com missing players/pokemon/coins  
- [ ] Escrita A6 já ocorreu e pede-se rollback automático sem reconciliação  

### GO condicional (todos obrigatórios)

- [ ] Pendências T0 revalidadas (esperadas `{}` ou plano manual documentado)  
- [ ] Compose A6 com `ALLOW_ALL_GROUPS=false` + `DEV_GROUP_ID` confirmado + allowlist  
- [ ] `POKEMON_RAID_AUTO_TICK=false` e `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`  
- [ ] Uma única stack na VM; limites mem documentados  
- [ ] Cold T0 + restore-check + manifesto OK  
- [ ] Volume A5.6 preservado como fallback  
- [ ] Rollback §5 ensaiado mentalmente / checklist impressa  
- [ ] **GO escrito** do responsável  

---

## 8. Entrega

| Artefato | Path |
| --- | --- |
| Este relatório | `docs/pokemon-a6-cutover-preflight-fase7.1.md` |
| Integridade (Fase 7) | `docs/pokemon-a6-save-integrity-fase7.md` |
| GCP / sizing | `docs/pokemon-a6-gcp-readiness-fase6.md` |
| Env fail-closed | `.env.a6-staging.example` |

**Classificação Fase 7.1: CONDITIONAL** — preflight de dados/capacidade ok para **substituição**; corte bloqueado até gates de grupo + GO T0.
