# A6 — Fase 12.1: Release Candidate das correções de raides

**Data (UTC):** 2026-11-01  
**Branch:** `fix/a6-raid-fase12-turnos`  
**Artefatos:** `~/odisseu-recovery/a6-fase12.1-rc-20261101T152138Z/`  
**Modo:** build + testes + plano operacional — **sem** deploy, recreate, restart, migrate ou alteração em produção.

Base documental: `docs/pokemon-a6-raid-fix-fase12.md`

---

## Decisão: **READY**

RC Node+JVM imutável pronto para autorização de deploy coordenado.  
Produção **não** foi alterada nesta fase.

| Gate | Resultado |
| --- | --- |
| Diff exclusivo Fase 12 (+ teste integrado 12.1) | **PASS** |
| Contrato JSON `suaVez` (sem `suaVez?`) | **PASS** |
| Multi-grupo Fase 11.4 preservado no Node | **PASS** (`group-allowlist.cjs` na imagem) |
| Schemas/migrations intocados (30 arquivos) | **PASS** |
| Imagens amd64 imutáveis + tar SHA | **PASS** |
| Suite Node 26/26 · JVM fase12/lobby/auto | **PASS** |
| Ciclo integrado abrir→ataque→rotação + `@lid`/`@c.us` | **PASS** |
| Deploy / recreate | **não executado** |

---

## 1. Auditoria

### Git

| Campo | Valor |
| --- | --- |
| Commit de código (imagens) | `52baa9cc840511382381fdb71e54f8f7c259f487` |
| Commit de teste integrado | `50847ae6e93514209d91d5a6e41beea270dc1296` |
| Pai | `6ab23ae` (ALLOWED_GROUP_IDS / Fase 11.1–11.4) |
| Content hash (paths do RC) | `5b9a97e6022858f24a6449d9b9fb4a1eb83fc33f5761df44de9bb0d2697e0b41` |
| Short | `5b9a97e60228` |

Arquivos do commit de código: domínio/bridge/json raid, present/media, `pokemon_http`, testes lobby/auto/fase12, docs Fase 12.  
**Fora do RC:** docs Fases 7–11.4 untracked locais, `a6-periodic-backup-a6.sh`, `tmp-*.yml`.

### Contrato JSON JVM → Node

| Keyword Clojure | JSON camelCase | Node |
| --- | --- | --- |
| `:sua-vez` / `:sua-vez?` | `suaVez` | `.-suaVez` + fallback `suaVez?` + `playerId===vez` |
| `:inscrito` | `inscrito` | status |
| `:voce` | `voce` | participantes |
| `:vez-nome` | `vezNome` | apresentação |

Evidência: `tests/json-contract.txt` → `JSON_CONTRACT_OK` (sem `suaVez?` no payload).

### Multi-grupo 11.4

Smoke da imagem Node: `ALLOWLIST_OK` (`scripts/lib/group-allowlist.cjs` presente).  
Live (RO): `ALLOW_ALL_GROUPS=false`, `ALLOWED_GROUP_IDS=120363427684548487@g.us`, `STRICT_USER_ALLOWLIST=0`, raids auto OFF.

### Schemas

Nenhuma migration nova. Imagem JVM contém as 30 migrations existentes (`MIGRATIONS_PRESENT_OK`). Compatível com Datomic A6 já em `029`/`030`.

---

## 2. Imagens imutáveis

| | Node | JVM |
| --- | --- | --- |
| Tag | `odisseu-node:a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` | `odisseu-jvm:a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` |
| Image ID | `sha256:dbcb6c0213c266a5d8ab7923a8d4e5113da25fb8300fb415cb93822ed908a695` | `sha256:82e1a06cb00dc85d89eeaa885c75d7fec3e5e6f8dd9e040f5bebba00398394c3` |
| Platform | `linux/amd64` | `linux/amd64` |
| Tar | `images/odisseu-node-a6-raidfix.tar` (~371 MiB) | `images/odisseu-jvm-a6-raidfix.tar` (~249 MiB) |
| Tar SHA-256 | `29cf1a91976526b1807663f5c553134ebf8d41348eeb3b333fa2cbc6d860d1b2` | `5f4e777e06813977cb2136f9554176b0f2516a03ed3e50bd762ea852f608e50b` |
| Labels | `git-sha=52baa9c…` · `content-hash=5b9a97e6…` · `release=a6-raidfix-fase12.1` | idem |

**Sem tags mutáveis** (`latest` / `a6` soltas).

### Smoke imagem

Node: `ALLOWLIST_OK` · `PRESENT_OK` · `MEDIA_RAID_OK` · `SUAVEZ_PRESENT_OK` · `LOBBY5_OK` · `MAIN_OK`  
JVM: `RAID_DOMAIN_OK` · `LOBBY5_JVM_OK` · `SUAVEZ_DTO_OK` · `CAMEL_STRIP_OK` · `MIGRATIONS_PRESENT_OK` (30)

### Live atual (não alterado)

| Container | Tag live | Image ID |
| --- | --- | --- |
| `odisseu-a6-node` | `…a6-multigroup-…9c70a4738a37…` | `sha256:af7b2eb310f0…` |
| `odisseu-a6-jvm` | `…a6-cutover-…f0065de6e4b9…` | `sha256:714955b512ba…` |

---

## 3. Testes integrados

```text
node --test fase12 + integrated + lobby + raid + isolation  → 26 pass / 0 fail
./scripts/test-raid-fase12.sh (JVM 8/25 + Node 16)         → OK
clojure -M:test-raid-lobby-fase2                            → 23/76 OK
clojure -M:test-raid-auto-fase3                             → 19/76 OK
JSON camelCase contract                                     → JSON_CONTRACT_OK
```

Ciclo coberto em `test/pokemon-jvm-raid-fase12-integrated.test.cjs`:

1. `resolveRaidGym('1')` → `pedra`  
2. Dois jogadores (`111@lid`, `222@c.us`)  
3. Lobby = 5 min  
4. Start → vez = primeiro  
5. Nome/golpes/comando na apresentação  
6. Pré-check `suaVez` só para o da vez  
7. Após ataque → vez no próximo  
8. Cartão visual (PNG ou null)  
9. Reinício DTO: `@c.us` ≠ `@lid`  
10. Sem mutação de rewards/saves no harness  

---

## 4. Plano de deploy (GO futuro — **não executar agora**)

### Pré-condições no GO

1. Backup A6 gated (`A6_PERIODIC_BACKUP=1`, preferir cold/warm).  
2. RO: `GET /v1/raids/active` no chat piloto — **abortar** se houver combate/inscrições ativos (não auto-heal).  
3. Confirmar `ALLOW_ALL_GROUPS=false`, raids auto OFF, volumes/`a6-cutover-session-from-core` intactos.  
4. Carregar tars na VM (`docker load`).  
5. Snapshot rollback: compose + env + image IDs atuais (Node `af7b2eb3…`, JVM `714955b5…`).

### Janela coordenada (evitar incompatibilidade)

Ordem obrigatória (downtime curto de comandos Pokémon):

1. **Bloquear novos comandos** (feature flag / anúncio / pause do Node ou responder “manutenção”).  
2. Recreate **JVM** com `odisseu-jvm:a6-raidfix-…5b9a97e60228-amd64` (`--no-deps` se aplicável; **sem** `-v`; **sem** migrate).  
3. Aguardar health JVM `up` + `raidAutoTickEnabled=false`.  
4. Recreate **Node** com `odisseu-node:a6-raidfix-…5b9a97e60228-amd64` (mesma sessão WA bind).  
5. Aguardar WA READY (sem QR) + health Node.  
6. Reabilitar comandos.  
7. Smoke: `!pk raid abrir 1` → entrar×2 → iniciar → atacar (só o da vez).

**Não** atualizar só o Node ou só a JVM: o DTO `suaVez` corrigido está na JVM; o pré-check/UX/imagem estão no Node.

### Rollback

1. Bloquear comandos.  
2. Restaurar tags live atuais (Node multigroup + JVM cutover) **sem** tocar volumes/Datomic/sessão.  
3. Revalidar health + WA READY.  
4. Registrar incidente.  
5. **Não** rollback de saves / T0 / A5.6.

### Restrições permanentes pós-GO

- `ALLOW_ALL_GROUPS=false` · só grupo piloto  
- `POKEMON_RAID_AUTO_TICK=false` / `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`  
- Sem migrate · sem auto-fix de raides travadas  

---

## 5. Riscos residuais

| Risco | Tratamento |
| --- | --- |
| Raide live em combate no instante do GO | Abortar deploy até expirar/encerrar naturalmente |
| Ponteiro `:vez` fantasma em raide antiga | Erro `raid_turn_invalid`; auditoria manual (sem auto-heal) |
| Deploy parcial Node-only | Proibido neste RC — coordenar ambos |
| Lobby 5 min vs schedule auto com interval&lt;5min | Auto permanece OFF; `normalize-interval` rejeita &lt; lobby |

---

## Status final

**READY** — RC `a6-raidfix-20261101T152138Z-5b9a97e60228` (Node+JVM amd64) validado, multi-grupo preservado, schemas intactos, testes verdes.  
Aguardando **GO explícito** de implantação coordenada (Fase seguinte).
