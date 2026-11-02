# A6 — Readiness 12.7: ativação gradual de raids automáticas

**Status:** PILOT GO EXECUTADO — ver `docs/pokemon-a6-raid-deploy-fase12.7.md`  
**Escopo:** catálogo dinâmico 571 chefes + scheduler já existente (Fase 3/3.1)  
**Pós-GO:** imagens 12.7 + ticks ON + `enabled:true` **somente** no piloto; sem liberação global / sem migrate

Preservar 12.6.2: recovery-v1, fences, unknown-pending, cooldowns, battle leave ON, schema 031, piloto exclusivo.

---

## 1. Esclarecimento do `8/38 OK`

| Interpretação errada | Interpretação correta |
|---|---|
| “8 de 38 testes passaram” | **8 testes** contendo **38 asserts** — todos passaram |

Reexecução atual (catálogo + readiness):

| Suite | Testes | Asserts | Fail | Error | Skip |
|---|---:|---:|---:|---:|---:|
| `raid-boss-fase127-test` | 8 | 38 | 0 | 0 | **0** |
| `raid-auto-readiness-fase127-test` | 7 | 107 | 0 | 0 | **0** |
| **Combinado** `./scripts/test-raid-boss-fase127.sh` | **15** | **145** | **0** | **0** | **0** |
| Regressão `./scripts/test-raid-auto-fase3.sh` | 19 | 76 | 0 | 0 | 0 |

Não há `deftest` com `^:skip` / `testing` ignorado. O runner encerra com `ok true`.

---

## 2. Ciclo completo do scheduler (auditado)

```
due schedule
  → plan-spawn (catálogo dinâmico + anti-repeat)
  → TX atômica: receipt spawn-id + raid origem=auto + ponteiro + agenda + outbox raid-opened
  → WA: format-opened (“!pk raide entrar auto”, 5 min, sem @lid técnico)
  → joins (auto / ativo / índice) — lógica 12.6.2 preservada
  → expire lobby: <2 → cancelada + outbox; ≥2 → combate + outbox raid-started
  → ataques / vitória → rewards (50/6/6) + captura 30 min
  → next-at = max(due+interval, due+lobby+1)  // sem respawn no mesmo tick
```

Evidência: `ciclo-completo-spawn-lobby-combate-vitoria-captura` (spawn → join → combate → vitória → captura roll=0 → rewards-paid).

Kill switches intactos:

- Bridge `raidAutoTickEnabled` default **false**
- Node `POKEMON_RAID_AUTO_TICK` default **false**
- Schedule `enabled` default **false** por chat

---

## 3–4. Dificuldade e cooperação

| Evidência | Resultado |
|---|---|
| Fator médio raro < lendário < mítico | PASS |
| Fator ∈ `[0,80 ; 1,55]` (atributos, não só label) | PASS |
| Variação intra-categoria | PASS |
| Solo mon forte × raro fraco | **vitória** rápida |
| Solo mon médio × mítico/lendário extremo | **derrota** (coop necessária) |
| Solo mon elite × mítico extremo | vitória no limite (corrida de revide) |
| Coop 3 × lendário extremo | **vitória** |
| Coop 5 × mítico extremo (Arceus) | **vitória** |

**Leitura operacional:** raids raras são soloáveis com time razoável; lendários/míticos extremos empurram o grupo para 3–5 treinadores. HP escala com `n × fator`; defesa mistura stats do chefe.

Ajuste de segurança nesta fase: teto `fator-hp-max` **1,55** (antes 1,65) — evita chefe matematicamente impossível em lobby cheio, mantendo pressão alta.

---

## 5. Histórico dos 8 últimos chefes

- Fonte: últimas raids `origem=auto` do chat no Datomic (sem migration nova)
- Janela 8: nenhuma repetição dentro de qualquer janela deslizante de 8 spawns
- Após sair da janela, a espécie **pode voltar** (não é ban permanente)
- Evidência: `historico-oito-evita-repeticao-por-grupo`

---

## 6. Reinício, idempotência, anti-duplicata

Já coberto na Fase 3/3.1 e revalidado:

| Gate | Evidência |
|---|---|
| `spawn-id = raid-auto:{chat}:{due}` | duplicate sem 2ª raid |
| Restart recupera agenda + lobby + outbox | `reinicio-e-duplicate-preservam-uma-raid` |
| Tick concorrente → 1 raid / 1 outbox | `ticks-concorrentes-um-vencedor` |
| Manual nunca substituída | `manual-nunca-substituida-por-auto` |
| Ack outbox idempotente | `ack-outbox-idempotente…` |

---

## 7. WhatsApp, menções e comandos

| Mensagem | Conteúdo | IDs técnicos |
|---|---|---|
| `raid-opened` | `📣 Raide automática` + nome do chefe + liga + `!pk raide entrar auto` | ausentes |
| `raid-started` | HP chefe + ginásio | ausentes |
| `raid-cancelled` | poucos inscritos | ausentes |
| Vitória / recompensas | fluxo 12.6.2 (`formatRaidVictory` + `mentions`) | **inalterado** |

Participação: `!pk raide entrar` / `entrar auto` / `entrar <n>` — mesma semântica 12.6.2.

---

## 8. Parâmetros operacionais recomendados (piloto)

| Parâmetro | Valor recomendado | Motivo |
|---|---|---|
| Intervalo | **6 h** (`raid/intervalo-proxima`) | default legado; evita spam |
| Lobby | **5 min** | já em 12.x |
| Combate / captura | 30 min / 30 min | inalterados |
| Mín / máx jogadores | **2 / 5** | coop natural em lendário/mítico |
| Horário sugerido (BRT) | 12:00, 18:00, 00:00 | alinhável via `nextAt` inicial |
| Janela piloto | 48–72 h | observar outbox + queixas |
| Grupo | **somente** `ALLOWED_GROUP_IDS=120363427684548487@g.us` | piloto 12.6.2 |

Ativação gradual (ordem obrigatória):

1. Deploy imagens com 12.7 (**só após GO de deploy**) — schema permanece 031  
2. Confirmar envs: leave ON, `ALLOW_ALL_GROUPS=false`, recovery-v1  
3. Bridge `ODISSEU_BRIDGE_RAID_AUTO_TICK=true`  
4. Node `POKEMON_RAID_AUTO_TICK=true`  
5. `PUT /v1/raids/auto/schedule` **só no piloto** com `enabled: true`, `intervalMs: 21600000`, `nextAt` no próximo slot desejado  
6. Smoke: 1 spawn → join 2 → vitória/cancel → ack outbox  

**Não** ligar steps 3–5 em outros grupos.

---

## 9. Seleção não limitada a espécies fixas

| Evidência | Valor |
|---|---:|
| Catálogo | 1025 |
| Elegíveis | 571 (raro/épico/lendário/mítico, gens 1–9) |
| 10k sorteios | 571 espécies distintas |
| Top espécie | ~1% |
| Novas no snapshot | entram sem mudar código de seleção |
| Ases de ginásio (Onix/…) | **não** são a fonte do auto |

---

## 10. Rollback seguro (piloto)

Se qualquer gate falhar após ativação:

1. `enabled: false` no schedule do piloto (efeito imediato no próximo tick)  
2. `POKEMON_RAID_AUTO_TICK=false` + `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`  
3. Se regressão de código: restaurar imagens **12.6.2** (digests do deploy SUCCESS)  
4. Não reaplicar migrate; não tocar saves  
5. Preservar logs `raid_auto_*` + outbox pendente  

Rollback de schedule **não** precisa recreate do stack.

---

## Riscos residuais

- Duplicata **visual** WA se send OK e ack falhar (at-least-once)  
- Mítico extremo ainda é apertado mesmo a 5 (revide); times fracos vão falhar — esperado  
- Snapshot do catálogo precisa regenerar se o teto National Dex subir de 1025  
- Golpes do chefe são STAB sintéticos (HP/defesa dominam o combate atual)

---

## Decisão

**READY FOR PILOT GO** — implementação e testes OK; **não ativar** até GO definitivo explícito cobrindo deploy (se necessário) + flags + schedule só no piloto.
