# Fase 12.8.3 — PE/XP por participação em raids derrotadas — READY FOR GO

**Status:** código + testes locais prontos. **Sem deploy / sem migration / sem mutação de produção.**  
**Versão-alvo:** 12.8.3 (sobre 12.8.2).  
**Ativação:** somente após GO explícito (deploy JVM+Node).

---

## 1. Valores atuais de PE e XP na vitória (inalterados)

| Recurso | Vitória |
|---------|---------|
| Moedas | **50** |
| PE treinador (`:save/pe-raids`) | **6** |
| XP Pokémon | **6** |
| Captura / Frambo / Doces | fluxo 12.6 intacto |

Constantes: `pokemon.domain.raid/recompensa-{moedas,pe,xp-pokemon}`.

---

## 2. Fórmula proposta para derrota

| Recurso | Derrota |
|---------|---------|
| Moedas | **0** |
| PE treinador | `quot(recompensa-pe, 2)` → **3** |
| XP Pokémon | `quot(recompensa-xp-pokemon, 2)` → **3** |
| Itens / Frambo / Doces / captura | **nenhum** |

Arredondamento: **`quot` (floor)** — determinístico. Documentado em `raid/recompensas` e nas defs `recompensa-*-derrota`.

API canônica: `(raid/recompensas :vitoria|:derrota)`.

---

## 3. Critério de participação efetiva

Reusa `raid/elegivel-recompensa?` → **`(:dano participante) > 0`**.

| Situação | PE/XP? |
|----------|--------|
| Lobby only / cancelada | Não |
| Entrou, zero dano | Não |
| Lutou e foi nocauteado | **Sim** (mon ainda na coleção) |
| Abandono (`!pk sair` → `abandonada`) | Não |
| Idle-timeout | Não |
| Wipe → `derrota` com dano > 0 | **Sim** |

Pokémon elegível = o selado na inscrição, via `id-pokemon` na coleção (mesmo com HP 0 no snapshot da raid).

---

## 4. Fluxo de persistência

1. `raid/atacar` → `:derrota? true`, fase `"derrota"`.
2. `raid-store/build-attack-tx`:
   - `pay-rewards?` se `(or venceu? derrota?)` e `(not rewards-paid?)`.
   - Bundles só para `participantes-elegiveis`.
   - PE via `[:db/add … :save/pe-raids …]` (mesmo canal da vitória).
   - XP via `apply-pokemon-xp-tx` → `xp/aplicar-xp-pokemon` (nível/stats).
   - Moedas: **omitidas** quando delta=0 (sem ledger).
3. `:raid/rewards-paid? true` na mesma TX do ataque final.
4. DTO `:rewards` no bridge → WhatsApp usa **somente** esses valores.

---

## 5. Garantias de idempotência

| Camada | Mecanismo |
|--------|-----------|
| Ataque | `operation-receipt/id` do `raid-attack` |
| Recompensa | `raid-reward\|{raid-id}\|{player-id}` (único por raid+jogador) |
| Raid | `:raid/rewards-paid?` + CAS `:raid/revision` |
| Retry | mesmo `operation-id` → `duplicate` |
| Concorrência | um TX vence; o outro falha (stale turn / fase) |
| Restart | flags/recibos no Datomic — sem reaplicar |

Payload hash: vitória `kind=raid-victory` (legado); derrota `kind=raid-defeat`.

---

## 6. Exemplo de mensagem WhatsApp

Valores vindos do DTO persistido (ilustrativo com 3/3/0):

```
💀 *RAID ENCERRADA — DERROTA*
🐉 *Onix* resistiu aos ataques!

Mesmo sem vencer, seus Pokémon ganharam experiência pela batalha.

🎖️ *Recompensas de participação*
@Ash
👤 Treinador: +3 PE
⚔️ Pokémon participante: +3 XP
🪙 Moedas: 0
@Misty
👤 Treinador: +3 PE
⚔️ Pokémon participante: +3 XP
🪙 Moedas: 0

🎯 Continue treinando e tente novamente!
```

Multiplayer: uma seção por participante (mentions canônicas). Sem bloco de captura.

---

## 7. Testes executados

| # | Caso | Onde |
|---|------|------|
| 1 | Derrota solo | `raid-defeat-rewards-fase1283-test` |
| 2 | Derrota multiplayer | idem |
| 3 | Nocauteado + XP | solo (HP snapshot 0) |
| 4 | Vitória 50/6/6 | regressão no mesmo ns |
| 5 | Abandono sem reward | `leave-battle!` → abandonada |
| 6 | Lobby sem combate | cancel |
| 7 | Retry finalização | duplicate |
| 8 | Dois processos finais | futures |
| 9 | Restart após derrota | novo store + mesmo conn |
| 10 | Pokémon sobe nível | xp 7+3 |
| 11 | Treinador sobe nível | pe 4+3 |
| 12 | Missão participação | `event-missions` unit |
| 13 | Missão vitória | unit (join ≠ win) |
| 14 | `recovery-v1` isolado | idem |
| 15 | `unknown-pending` | pe ok, coins hist intactos |
| 16 | Timeout idle | sem reward |
| 17 | WhatsApp valores reais | `pokemon-jvm-raid-fase12.test.cjs` |

Comando:

```bash
clojure -M:test-raid-defeat-rewards-fase1283
node --test test/pokemon-jvm-raid-fase12.test.cjs
# regressões sugeridas pós-GO local:
clojure -M:test-raid-capture-rewards-fase126
clojure -M:test-raid-auto-cadence-fase1282
```

---

## 8. Arquivos modificados

- `modules/pokemon/domain/pokemon/domain/raid.clj` — tabela derrota + `recompensas`
- `modules/pokemon/domain/pokemon/domain/ops.clj` — payload `raid-defeat`
- `modules/pokemon/infrastructure/.../raid_store.clj` — pay on defeat
- `modules/pokemon/application/.../bridge_tx.clj` — outcome no DTO
- `scripts/lib/pokemon-jvm-present.cjs` — `formatRaidDefeat`
- `modules/pokemon/tests/.../raid_defeat_rewards_fase1283_test.clj` — novo
- `apps/odisseu/src/odisseu/smoke/raid_defeat_rewards_fase1283.clj` — smoke
- `deps.edn` — alias `:test-raid-defeat-rewards-fase1283`
- `test/pokemon-jvm-raid-fase12.test.cjs` — copy derrota
- `docs/pokemon-a6-fase1283-ready-for-go.md` — este doc

---

## 9. Necessidade de migration

**Não.** Reusa attrs existentes (`:save/pe-raids`, `:raid/rewards-paid?`, `operation-receipt`, XP de Pokémon). Sem schema novo. Migration 032 e demais **intocadas**.

---

## 10. Plano de deploy e rollback

**Deploy (após GO):**
1. Backup A6 periódico.
2. Build/RC JVM + Node (present).
3. Cutover JVM-first → Node (padrão 12.8.2-b).
4. Smoke sintético: abrir raid frágil → derrota → conferir PE=3, XP=3, moedas=0, mensagem.
5. Smoke vitória: 50/6/6 + captura.

**Rollback:** reverter imagens JVM+Node para 12.8.2-b. Estado já creditado em derrota **permanece** (PE/XP); não há ledger de moedas a desfazer. Não reprocessar raids antigas.

**Não alterar:** probs captura, dificuldade chefes, `dailyLimit=12`, `specialReserve=2`, `distribution=uniform`, Festival, Frambo, doces, Gemini/Groq.

---

## 11. Missões e eventos

| Ação missão | Evento | Derrota com combate |
|-------------|--------|---------------------|
| `raid-participou` / `raid-join` | `:raid-joined` (no join) e `:raid-won` | Join já conta; derrota **não** emite win |
| `raid-venceu` / `raid-win` | só `:raid-won` | **Não** conta |

Festival dos Dragões e catálogo temático: sem mudança de bias/recompensas de missão.

---

## 12. Relatório READY FOR GO

| Item | Status |
|------|--------|
| Auditoria vitória/derrota | ✅ |
| 50% PE/XP derrota (`quot`) | ✅ |
| Sem moedas/itens/captura | ✅ |
| Elegibilidade `dano>0` | ✅ |
| Idempotência recibo+flag | ✅ |
| WhatsApp valores reais | ✅ |
| Missões participar ≠ vencer | ✅ |
| Testes 1–17 | ✅ (locais) |
| Migration | ❌ não necessária |
| Deploy / prod | ⛔ aguarda GO explícito |

**Veredito:** **READY FOR GO** — implementação e testes locais concluídos; produção bloqueada até autorização.
