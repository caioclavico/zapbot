# Hotfix — entrada no lobby + mínimo real = 1

**Status:** **SUCCESS em produção (piloto)** — cutover `2026-11-02T05:55:45Z`  
**Produção atual:** hotfix lobby+solo (`5e8375d`) · relatório `docs/pokemon-a6-raid-deploy-lobby-join-hotfix.md`  
**Rollback:** imagens de revelação (`c413d14` / digests `34a1b5d7…` / `79a6ae27…`)

---

## 1. Causa raiz — `!pk raid entrar` → “nenhuma raide”

O sweeper de battle-idle (`expire-idle-battles!`) tratava raids em **`inscricoes` (lobby)** como combate aberto:

1. `find-open-raid-ids` incluía `inscricoes` + `combate`
2. `backfill-idle-window!` / `find-combat-raids-missing-idle` carimbavam `idle-expires-at = agora + 5 min` no lobby
3. ~5 min depois, `find-expired-raid-ids` + `expire-raid!` marcavam a raid como `idle-timeout` e **limpavam o ponteiro** `:chat-active-raid`
4. O anúncio WhatsApp (`raid-opened`) continuava no histórico → jogador via “lobby aberto”
5. `join-raid!` resolve só pelo ponteiro → `RAID_NOT_FOUND` / “❓ Nenhuma raide ativa neste chat.”

**Não era** filtro auto vs manual, nem alias `raid`/`raide`. Join já usava o mesmo ponteiro para ambos.

Evidência produção (piloto, pós-spawn mítico 05:00Z, lobby 30 min):  
`[PokemonJVM] Falha: RAID_NOT_FOUND 400 raid_not_found {"raidId":null}` ~05:22Z — dentro da janela de lobby, após idle de 5 min ter matado o ponteiro.

---

## 2. Reprodução (teste)

`idle-sweeper-nao-mata-lobby-auto` em `raid_lobby_join_hotfix_test.clj`:

1. Spawn auto (mítico, lobby 30 min)
2. Simula o bug legado: `raid-touch-tx` no lobby
3. Avança relógio além dos 5 min de idle
4. **Antes do fix:** sweeper expirava e `active-raid` → nil  
5. **Depois do fix:** ponteiro intacto · `entrar` funciona

---

## 3. Correção

| Área | Mudança |
| --- | --- |
| `battle_idle_store.clj` | `find-combat-raid-ids` só `combate`; backfill/expire idle **ignoram lobby** |
| `build-expire-raid-tx` | no-op se fase=`inscricoes`; limpa ponteiro só com retract do attr (não `retractEntity`) |
| Join / aliases | inalterados — já corretos (`raid`/`raide` → mesmo fluxo) |

---

## 4. Política — mínimo real = 1

| Categoria | Recomendados (UX) | Mínimo real | Máximo |
| --- | ---: | ---: | ---: |
| Rara | 1 | **1** | 5 |
| Épica | 2 | **1** | 5 |
| Lendária | 2 | **1** | 5 |
| Mítica | 3 | **1** | 5 |

- `iniciar` / expire-lobby com ≥1 inscrito → combate  
- 0 inscritos → cancelada  
- HP/dificuldade/captura/recompensas **inalterados** (sem buff solo)  
- Manual: só criador inicia antecipado · Auto: qualquer inscrito  

---

## 5. Exemplos de mensagem WhatsApp

```
⚡ *UMA RAID MÍTICA APARECEU!*

👑 Chefe: *Mewtwo*
⭐⭐⭐⭐⭐⭐ Dificuldade: *Mítica*
❤️ HP base: 4000
🏛️ Ginásio Central · liga diamante
👥 Jogadores recomendados: 3
⚔️ Participantes: 1/5
💪 Você pode tentar sozinho, mas esta raid será muito difícil!
⏳ Lobby aberto por *30 minutos*.

*Comandos:*
`!pk raide entrar` — Pokémon ativo
`!pk raide entrar auto` — melhor elegível
`!pk raide entrar <n>` — índice da coleção
`!pk raide iniciar` — começar agora (1+ inscrito)
```

---

## 6. Arquivos modificados

- `modules/pokemon/infrastructure/.../battle_idle_store.clj`
- `modules/pokemon/domain/pokemon/domain/raid.clj`
- `modules/pokemon/domain/pokemon/domain/raid_auto.clj`
- `modules/pokemon/infrastructure/.../store.clj` (announce recomendados)
- `modules/pokemon/infrastructure/.../raid_store.clj`
- `scripts/lib/pokemon-jvm-present.cjs`
- testes: `raid_lobby_join_hotfix_test.clj`, `raid_auto_lobby_iniciar_fase1271_test.clj`, `raid_lobby_fase2_test.clj`
- runner: `apps/odisseu/src/odisseu/smoke/raid_lobby_join_hotfix.clj` · alias `:test-raid-lobby-join-hotfix`

---

## 7. Testes executados

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-lobby-join-hotfix` | **39 tests · 188 asserts · 0 fail** (hotfix + 12.7.1 lobby + fase2) |
| `node --test test/pokemon-jvm-raid-egg-reminder.test.cjs` | **7/7** |
| `node --test test/pokemon-jvm-raid-lobby.test.cjs` | **4/4** |

---

## 8. Compatibilidade

- Scheduler / `nextAt` / cap 12 / `specialReserve=2` — sem mudança  
- Cartões PNG de revelação / ovos — sem mudança de pipeline  
- Lobby dinâmico 5/10/20/30 min — preservado  
- Leave ON · allowlist piloto · migration 031 — intactos  

---

## 9. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Raids em lobby já com `idle-expires-at` legado | Novo expire ignora `inscricoes`; attr órfão é inofensivo |
| Solo em mítica muito difícil | Copy de aviso; stats do chefe intactos |
| Testes antigos que assumiam min>1 | Atualizados nas suites desta entrega |

---

## 10. Plano de implantação / rollback (após GO)

1. Build RC JVM+Node da mesma revisão · digests · backups warm/cold  
2. Cutover só tags (sem migrate / sem force tick / sem alterar agenda)  
3. Smoke: spawn auto → `entrar` imediato; solo `iniciar`; T−30 ovo; revelação PNG  
4. Rollback: imagens anteriores (reveal ou 12.7.3 conforme baseline no momento do GO)

**Não executar cutover até GO definitivo.**
