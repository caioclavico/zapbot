# A6 — Hotfix caçada: timeout, conflito Datomic, golpes irrecuperáveis, dano final

**Branch:** `fix/a6-hunt-timeout-conflict-moves`  
**Base RC:** `github/rc/a6-abc-p0-integrated` @ `1ce993a` (tip operacional pós-#62; executável live `2afd997`)  
**Commits:** `2295c71` vitória/TX · `f88d737` idle/CAS/notify · `fefe93f` golpes · `31223b4` dano final · (+ docs/testes neste tip)  
**Escopo:** correção de código + testes + plano RO. **Sem deploy / migrations / backfill / saves reais.**

---

## 1. Causas-raiz

### 1.1 `datoms-conflict` na vitória (comprovado)

Na TX de turno que concede XP de batalha, `apply-pokemon-xp-tx` recalculava `:hp-atual` no level-up enquanto `pokemon-hp-status-tx` escrevia o HP canônico do combate (incl. 0 Explosion). Dois datoms contraditórios **na mesma TX** → `:db.error/datoms-conflict`.

`err/conflict?` tratava isso como CAS transitório e o writer retryava até 12× a **mesma TX inválida**, surfacing como `WRITER_FENCE_CONFLICT` ao jogador. O ataque final falhava; a caçada podia permanecer inconsistente até idle/cooldown.

### 1.2 Timeout sem validação atômica do deadline (comprovado)

O sweeper de idle podia enxergar um `idle-expires-at` vencido, enquanto um turno válido renovava o prazo. Sem:

1. guard `expired?` no build da TX de expire; e  
2. CAS em `idle-expires-at` observado,

um scheduler atrasado (ou pull pós-renovação sem guard) encerrava batalha ainda ativa. O jogador não via mensagem clara e, ao tentar `!pk cacar`, recebia cooldown (~24 min = 30 − ~6 de inatividade/batalha).

### 1.3 Golpes `(irrecuperável)` (comprovado no domínio)

Slots com mapa vazio / sem nome+slug+tipo passam por `battle/golpe-normalizado` → `:golpe-irrecuperavel? true` e UI `(irrecuperável)`. Origem típica: legado de evolução/EDN truncado. Correção é recuperação determinística (não cosmético).

### 1.4 Dano do golpe final omitido (comprovado no present)

`formatBattleTurn` early-return em `aguardandoCaptura` montava só header de vitória + menu, **sem** `lastAction` / `formatCanonicalAttackLine`.

---

## 2. Linha do tempo do incidente (anonimizada)

| T | Evento |
|---|--------|
| T0 | `!pk cacar` — encontro ativo; cooldown 30 min inicia |
| T0+ε | Ações válidas renovam (ou deveriam renovar) idle |
| ~T0+5–6 min | Jogador inativo ≈ idle 300s; OU ataque final tenta gravar |
| Tf | Ataque final → loop `datoms-conflict` / fence conflict |
| Tf+δ | Estado: batalha não finalizada canonicamente **ou** idle encerra em paralelo |
| Tf+δ′ | `!pk cacar` → ~24 min de cooldown restante |
| — | Sem mensagem WhatsApp de encerramento por inatividade visível |

Cooldown pessoal do jogador omitida de propósito.

---

## 3. Estado final real do encontro (produção no momento do bug)

Hipótese consolidada pela correlação de código + sintomas:

- Vitória **não** commitada (TX estrutural inválida) **ou** race idle × turno.
- Cooldown de caçada **já** ativo desde o start (~6 min decorridos → ~24 restantes).
- Sem outbox de idle ackado (ou não emitido se a batalha nunca expirou canonicamente).

---

## 4. Cooldown ≈ 24 minutos

Cooldownamento **não** alterado. Cooldown de caçada = 30 min a partir do início.  
`30 − (~5–6 min de sessão/idle)` ≈ **24 min** restantes — compatível com o relato. Distinto do idle de batalha (300s).

---

## 5. Timeout deslizante

Política: `idle-expires-at = last-valid-action-at + idle-timeout` (default 300s).

- Renovado só por ações aceitas (ataque/defesa/esquiva/seleção válidas) via `touch-attrs` na TX do turno.
- Mensagens comuns / comandos inválidos / rejeitados **não** renovam.
- Persistido em Datomic (`:battle/last-activity-at`, `:battle/idle-expires-at`) — sobrevive a restart JVM.
- Cooldown 30 min intacto.

---

## 6. Concorrência e retries

| Mecanismo | Comportamento |
|-----------|---------------|
| `err/datoms-conflict?` | Fail-fast `:tx_datoms_conflict` — sem retry cego |
| `err/conflict?` | Exclui datoms-conflict; CAS/unique continua retentável |
| `apply-pokemon-xp-tx` + `:hp-atual-override` | HP de combate é autoridade na TX de vitória/captura/fuga |
| Expire | Guard `deadline-not-due` + CAS `idle-expires-at` + CAS `idle-expired?` |
| Expire vs renew | Scheduler antigo → `skipped`; batalha permanece ativa |
| Receipts | `battle-idle-expire\|id` + notify-id estáveis — idempotentes |
| `:force?` | Só admin/force-expire ignora prazo |

Retries de CAS releem estado; operação confirmada (receipt) devolve duplicate.

---

## 7. Mensagem de encerramento

Outbox Datomic (`battle-notify`, kind `battle-idle-timeout`) → `/v1/events/pending`.

Texto (exemplo):

```
⌛ Sua batalha terminou por inatividade. O Pokémon selvagem escapou.

Você poderá iniciar outra caçada quando seu cooldown terminar.
_(300s sem ação válida.)_
```

Garantias: uma notificação por jogador/encontro; só após TX de expire; ack idempotente; sem vitória; ataque pós-expire → `:battle_idle_expired`.

---

## 8. Recuperação de golpes

Módulo novo: `pokemon.domain.moves-recovery`.

Ordem: catálogo externo → histórico → ofertas → candidato evolução/STAB (`candidato-nivel` / `garantir-ataque-do-tipo`).

- Dry-run default (`plan-pokemon` / `audit-collection`).
- `recover-pokemon` com `:apply? true` só em slots `:recovered`.
- Determinístico, idempotente, não toca slots `:ok`.
- **Não** executado em saves reais nesta entrega.

---

## 9. Plano de reparo (saves reais) — RO

1. Exportar coleções afetadas (sem PII no relatório agregado).  
2. `audit-collection` → contar recuperáveis vs needs-approval.  
3. Dry-run individual com justificativa por slot.  
4. Aguardar autorização explícita antes de qualquer apply/backfill.  
5. Preferir candidato da evolução em que o slot se perdeu, quando `golpes-candidatos` / histórico existirem.

**Nesta etapa:** zero writes em produção.

---

## 10. Dano do golpe final

`formatBattleTurn` (Node present): em `aguardandoCaptura` / bolsa cheia, prefixa narrativa via `lastAction.mensagem` ou `formatCanonicalAttackLine` (campos do motor — sem recalcular dano).

Exemplo de shape:

```
⚔️ Wartortle causou 40 de dano!
Super efetivo!🔥
💀 Abra não resistiu aos ataques e desmaiou!
🏆 VITÓRIA NA CAÇADA!
…
Deseja capturar…
```

Explosion: preserva `autodesmaio` do atacante + HP 0 via override XP.

---

## 11. Testes e resultados

| Suite | Resultado |
|-------|-----------|
| `pokemon.tests.tx-datoms-conflict-a6-test` | PASS |
| `pokemon.tests.moves-recovery-a6-test` | PASS |
| `pokemon.tests.battle-idle-test` (incl. renew vs stale expire, pós-timeout) | PASS 24/24, 88 asserts |
| `node --test test/pokemon-jvm-hunt.test.cjs` (dano final + Explosion) | PASS 8/8 |

Regressões de política (Species-HP v1, raid 320/One-Mon, streak) cobertas por asserts dedicados nos testes de recovery; idle não altera cooldown 30 min.

---

## 12. Produção (RO `2026-11-08T23:34:47Z`)

| Campo | Valor |
|-------|-------|
| Node Image | `sha256:77d0d9a2…464d2b` · tag `…2afd9975ef69-amd64` · healthy · Restarts=0 |
| JVM Image | `sha256:9443b801…2198d1` · tag `…2afd9975ef69-amd64` · healthy · Restarts=0 |
| git-sha | `2afd9975ef695163a4d8f98eed632c644431c72f` |
| content-tree | `ad8a3cf41c4851237b535b15fad82e8e024b92f0` |
| WhatsApp | READY · chromium true |
| Bridge | up · maintenance open · activeRequests=0 |
| Schema | `035-species-hp-formula.edn` presente |
| Hold | `true` · CI `disabled_manually` |
| Deploy concorrente | nenhum `compose up`/cutover em andamento |

Cutover Explosion/Hunt/Raid anterior: **SUCCESS** (`20261108T222347Z`). Este hotfix **ainda não** está nas imagens live.

---

## 13. Arquivos principais

- `modules/pokemon/domain/pokemon/domain/errors.clj`
- `modules/pokemon/infrastructure/.../datomic/store.clj`
- `modules/pokemon/infrastructure/.../datomic/battle_idle_store.clj`
- `modules/pokemon/infrastructure/.../http/bridge.clj`
- `modules/pokemon/domain/pokemon/domain/moves_recovery.clj`
- `scripts/lib/pokemon-jvm-present.cjs`
- testes: `battle_idle_test`, `moves_recovery_a6_test`, `tx_datoms_conflict_a6_test`, `pokemon-jvm-hunt.test.cjs`

---

## 14. Riscos restantes

- Saves com golpes irrecuperáveis **ainda** na live até reparo autorizado.
- Hotfix não deployado — incidente pode se repetir na imagem `2afd997`.
- Capacidades `/health` sem string `encounter-release` (rota existe) — cosmético.
- Race ataque×expire sob carga extrema: coberta por CAS+guard; vale smoke pós-deploy.

---

## 15. GO / NO-GO

| Critério | Status |
|----------|--------|
| Vitória sem datoms-conflict estrutural | **GO** (código) |
| Timeout deslizante + CAS + guard | **GO** (código + testes) |
| Notificação idle idempotente | **GO** (código + testes) |
| Recovery determinístico dry-run | **GO** (código + testes; sem apply live) |
| Dano no golpe final | **GO** (present + testes) |
| Produção estável na RC anterior | **GO** (RO confirmado) |
| Deploy deste hotfix | **NO-GO** nesta tarefa (proibido) |
| Backfill golpes reais | **NO-GO** (falta autorização) |

### Veredito técnico

**GO para integrar/construir imagem de hotfix** a partir desta branch.  
**NO-GO deploy** até autorização explícita de cutover (hold/CI permanecem).
