# Checkpoint — GO `!pk professor` (enviar / confirmar / cancelar) · Odisseu Core

**Quando:** 2026-10-29 ~19:24–19:32 UTC  
**Autorização:** GO (chat) — referência `docs/pokemon-a6-4-professor-prepare.md`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| DTO professor plano (`token` no topo) + `formatProfessor*` | Migrations / reimport / Cassandra |
| Rebuild JVM + Node | `professor confirmar` em conta real |
| Caps `professor-send/confirm/cancel/cards/use` | Remoção artificial de Pokémon / cartões |
| Fluxo enviar → confirmar → cancelar (código 5 min) | Rollback destrutivo automático |
| Preservar CAS/fence, favorito, batalha, enfermaria | Expansão PokeAPI de famílias |

**Pós-deploy:** aguardar teste WhatsApp do usuário (transferência real).

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `professor-a64-test` + `profile-a551-test` | **15 / 54 OK** |
| present Node | **17 / 17 OK** |
| Famílias evolutivas | Catálogo local inalterado (`profile/family`; pidgey compartilhado; demais = species) |
| Startup auto-transfer | **não** — pendente só via `POST /v1/professor/confirm` |
| Cold | `~/odisseu-recovery/a64-professor-cold-20261029T192429Z/` |
| Restore | mom `eaafc4a05fac7dcf284edd0d71bbae5871b34cddd4f479041a53609c55cc6143` |

Imagens **antes:**
- JVM/Node `a64-battle-leave-20261029T1910-amd64`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-professor-20261029T1925-amd64` | `sha256:a8c3eaef16328f3742353adfc173c78495dc27aa3b76175579052c5588082523` |
| **Node** | `odisseu-node:a64-professor-20261029T1925-amd64` | `sha256:0f553aa9bc98c7bbee61a3cd8cd62626be863e1fdf08fbfa7d3cbb8e093ce94d` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-professor-a64-professor-20261029T1925-amd64`

Env JVM: `LEAVE=true` `HUNT=1800` `JOY=1800` `IDLE=300` `BRIDGE_RESET=false`  
Env Node: `ALLOW_ALL_GROUPS=true` `IDLE=300`  
Volumes: `odisseu-core-a56-{datomic,journal,maint,data}` preservados

---

## Contrato validado (sem mutação)

| Probe | Resultado |
|-------|-----------|
| Health caps | `professor-send` · `professor-confirm` · `professor-cancel` · `professor-cards` · `professor-use` |
| `GET /v1/professor/cards` (Caio / recovery-v1) | `{}` (RO) |
| Fixture `formatProfessor` (token topo) | mostra Pidgey nº 3 + `!pk professor confirmar 742` + 5 minutos |
| Fixture nested `summary.token` | ainda renderiza código (merge) |
| `formatProfessor` cancel / confirm | textos master |
| `POST professor/send` ou `confirm` em conta real | **não executado** |

Mensagem fixture (Node implantado):

```
⚠️ Enviar *Pidgey* nº 3 Nv.5 ✨ SHINY ao professor? …
Confirme em até 5 minutos: !pk professor confirmar 742
Cancelar: !pk professor cancelar
```

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** (JVM ~19:28Z · Node ~19:29Z) |
| WhatsApp | **READY** `2026-10-29T19:29:58.755Z` |
| Integridade | 12/12 · 39 · 119 · **158** · 13 treinadores |
| User | `recovery-v1` · fence **2** · **119** |
| Cooldowns | Hunt **1800** · Joy **1800** · Idle **300** |
| Groups | `ALLOW_ALL_GROUPS=true` |
| Mutação | **none** |

Arquivo: `~/odisseu-recovery/a64-professor-post-integrity.json`

---

## Limitação — famílias evolutivas

Equivalência vs master **parcial**: A6 usa catálogo local (`profile/family`). Cadeia Pidgey compartilhada; demais espécies = família = species (não PokeAPI evolution chain completa). O patch de UX **não** alterou essas regras.

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-professor-a64-professor-20261029T1925-amd64 \
   docker-compose.release-battle-idle.yml
# retag images para a64-battle-leave-20261029T1910-amd64 se necessário
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Não executar rollback destrutivo automaticamente.

---

## Próximo passo (humano)

Testar no WhatsApp: `!pk professor enviar <n>` → ver Pokémon + código → **só então** confirmar se desejar transferência real. Deploy aguarda esse teste; nenhuma confirmação foi feita daqui.
