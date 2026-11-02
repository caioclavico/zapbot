# A6 — Fase 12.7.1: lembretes (ovo), lobby dinâmico e início antecipado

**Status:** IMPLEMENTADO · validado em ambiente descartável · **sem GO / sem deploy / sem migrate**  
**Base:** 12.7 (catálogo dinâmico + scheduler piloto)  
**Piloto alvo (após GO):** `120363427684548487@g.us`  
**Preserva:** recovery-v1 · fences · unknown-pending · leave ON · schema 030/031 · raids manuais · catálogo 571 elegíveis

---

## 0. Auditoria do comportamento atual (antes das mudanças)

| Área | Achado |
| --- | --- |
| Scheduler auto | `tick-raid-auto!`: expire lobby → reminders → spawn due; `nextAt` + `enabled` por chat; kill switches bridge/Node |
| Outbox WA | kinds `raid-opened` / `started` / `cancelled` + `raid-reminder-*`; Node poll + ack |
| Mídia | Comandos usam `MessageMedia`; outbox era texto; 12.7.1 adiciona PNG nos lembretes |
| Lobby 12.7 | Fixo 5 min · min 2 · max 5 (manual e auto) |
| `!pk raide iniciar` | Domínio `raid/iniciar`: se `:criador` definido, só ele; **auto tem `criador nil`** → qualquer inscrito já podia iniciar antecipado |
| Expire | `expirar-inscricoes`: &lt; min → cancelada; ≥ min → combate (timer automático) |
| Cheio (5) | `entrar` rejeita; **não** inicia combate sozinho |

Conclusão: **não criar comando novo** — reutilizar `iniciar`. Ajuste principal: lobby/min por categoria + anúncio + lembretes PNG.

---

## 1. Arquitetura dos lembretes

```
nextAt (persistido)
  ├─ T-30 / T-15 / T-5 → outbox raid-reminder-{30|15|5}
  │     id = raid-reminder:{chat}:{nextAt}:{offsetMs}
  │     Node: PNG local 1080² + caption (sem spoiler)
  └─ T-0 → spawn + outbox raid-opened (revela chefe + lobby dinâmico)
```

- Dedup: existência do outbox id (acked ou não)  
- `enabled:false` / `nextAt` mudou → suppress (ack sem WA)  
- Offset só se `fire-at` cair estritamente dentro do `interval-ms`  
- Allowlist: só chats com agenda enabled (piloto); Node `chat-autorizado?`

---

## 2. PNGs (estratégia e prévias)

| Arquivo | Texto nos pixels |
| --- | --- |
| `assets/raid-egg-30min.png` | FALTAM 30 MINUTOS |
| `assets/raid-egg-15min.png` | FALTAM 15 MINUTOS |
| `assets/raid-egg-5min.png` | FALTAM 5 MINUTOS |

- Resolução **1080×1080** · ~115 KB · ginásio + ovo + aura · sem silhueta do chefe  
- Geração offline: `node scripts/generate-raid-egg-reminders.cjs`  
- Runtime: `renderRaidEggReminder(kind)` — só filesystem local  
- Fallback: caption textual se PNG falhar (não bloqueia scheduler)

---

## 3. Lobby dinâmico (domínio centralizado)

`pokemon.domain.raid/lobby-por-categoria`:

| Categoria | Lobby | Mín | Máx |
| --- | ---: | ---: | ---: |
| raro | 5 min | 1 | 5 |
| epico | 10 min | 2 | 5 |
| lendario | 20 min | 2 | 5 |
| mitico | 30 min | 3 | 5 |

- Aplicado só em **spawn auto** (`plan-spawn` → `nova-raid` com `duracao-ms` / min / max)  
- Manual permanece 5 min / min 2  
- `max-duracao-inscricoes` = 30 min (validação `nova-raid`)  
- Cheio ≠ início; expire / `iniciar` respeitam `min-jogadores` da raid  
- `advance-next-at` usa duração real do lobby do spawn

---

## 4. Início antecipado (`!pk raide iniciar`)

| Caso | Regra |
| --- | --- |
| Manual | Só o `:criador` |
| Auto (`criador nil`) | Qualquer **inscrito** |
| Ambos | Exige ≥ `min-jogadores`; fase `inscricoes` aberta; idempotência via receipt `raid-start` |
| Após combate | Segunda chamada → erro (`raid_not_open`) |
| Timer | Expire usa `:expira` gravado; início antecipado avança fase → expire seguinte é noop |

---

## 5. Anúncio oficial (eclosão)

Exemplo (valores reais do chefe/categoria):

```
⚡ *UMA RAID LENDÁRIA APARECEU!*

👑 Chefe: *Rayquaza*
⭐⭐⭐⭐⭐ Dificuldade: *Lendária*
❤️ HP base: …
🏛️ … · liga …
👥 Participantes: 0/5 (mín. 2)
⏳ Lobby aberto por *20 minutos*.

*Comandos:*
`!pk raide entrar` / `entrar auto` / `entrar <n>` / `iniciar`
```

Menções 12.6.2 inalteradas (vitória etc.). Lembretes **não** revelam chefe.

---

## 6. Arquivos modificados

| Path | Papel |
| --- | --- |
| `modules/pokemon/domain/.../raid.clj` | `lobby-por-categoria`, max duração, docstring `iniciar` |
| `modules/pokemon/domain/.../raid_auto.clj` | reminders, `format-opened`, lobby no `plan-spawn` |
| `.../raid_store.clj` / `store.clj` | spawn com params + remind tick |
| `src/zapbot/pokemon_http.cljs` | entrega PNG + gate outbox sistema |
| `scripts/lib/pokemon-jvm-media.cjs` | `renderRaidEggReminder` |
| `scripts/lib/pokemon-jvm-present.cjs` | hint mínimo dinâmico |
| `assets/raid-egg-*.png` | arte 1080² |
| testes + `scripts/test-raid-auto-reminder-fase1271.sh` | cobertura |

**Migration:** nenhuma.

---

## 7. Testes executados

| Suite | Resultado |
| --- | --- |
| `./scripts/test-raid-auto-reminder-fase1271.sh` | **16 testes / 101 asserts + 6 Node PASS** |
| `./scripts/test-raid-auto-fase3.sh` | **19/79 PASS** (após ajuste next-at dinâmico) |
| `./scripts/test-raid-boss-fase127.sh` | **15/154 PASS** (boss + readiness) |

Cobertura: T-30/15/5 · spoiler · restart/dedup · cancel · lobby 5/10/20/30 · min/max · cheio sem auto-start · iniciar auto/manual · rejeição sem mínimo · encadeamento lembrete→opened · catálogo/anti-repeat regressão.

---

## 8. Compatibilidade 12.7

- Intervalo 6 h e grade BRT 00/06/12/18 preservados  
- Catálogo dinâmico + pesos 40/30/20/10 + anti-repeat 8 + fator ≤ 1,55  
- Economia captura/Frambo/recompensas/menções/leave — intocados  
- Piloto único via allowlist + schedule enabled  

---

## 9. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Raro min=1 (solo) | Intencional; épico+ exigem coop |
| Mythic lobby 30 min | `nextAt+6h` ainda &gt; lobby |
| Tick atrasado | Pode enfileirar 3 lembretes de uma vez (ids distintos) |
| Produção ainda 12.7 | Código local; **não deployado** |

---

## 10. Plano de deploy / rollback (após GO)

1. Backup warm/cold · registrar imagens 12.7  
2. Build/cutover JVM+Node mesma revisão 12.7.1  
3. Confirmar WA READY · ticks · schedule piloto  
4. Observar próximo `nextAt` (lembretes + eclosão) — sem force-tick  
5. Rollback: imagens 12.7 ou soft `enabled:false`  

## 11. Validação piloto (pós-GO)

- [ ] T-30/15/5 com PNG no grupo piloto  
- [ ] Sem spoiler nos lembretes  
- [ ] Eclosão com categoria/lobby corretos  
- [ ] `entrar` / `entrar auto` / reentrada 12.6.2  
- [ ] `iniciar` antecipado com ≥ mín.  
- [ ] Expire cancela/inicia conforme mín.  
- [ ] Demais grupos OFF  

**Aguardando GO definitivo — nenhuma alteração em produção nesta entrega.**
