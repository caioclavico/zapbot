# A6 — Fase 4: Homologação de raides

**Branch:** `feature/a6-raid-homolog-fase4`  
**Base:** `feature/a6-raid-auto-fase3.1`  
**Worktrees:** `a6-save-slots` (`/Users/caioclavico/repos/zapbot`) preservado  
**Isolamento:** Datomic Local em `.datomic-local/raid-homolog-f4` (system `odisseu-raid-homolog-f4`)  
**Não feito:** deploy, migrate, backfill, merge, produção; **staging externo não foi tocado** (sem autorização de isolamento)

## 1. Auditoria prévia

| Item | Evidência |
| --- | --- |
| Fase 2 (lobby) | `029-raid-lobby.edn` aditivo; suíte `test-raid-lobby-fase2` |
| Fase 3 (auto) | `030-raid-auto.edn` aditivo; default `enabled=false` |
| Fase 3.1 (hardening) | intervalo ≥ lobby; `next-at` determinístico; CAS/receipt; logs |
| Schema 007 | identidade raid, participantes, captura, `chat-active-raid` |
| Schema 029 | `criada-em`, min/max jogadores |
| Schema 030 | agenda + outbox + `origem` |
| Cadeia A6 | `schema/migration-files`: 001–025 → 029 → 030 (gaps 026–028 intencionais neste branch) |
| Saves reais | **não usados** — fixtures sintéticas (`boot!` + seed ultra-bola) |
| Staging | **não modificado** — homologação local apenas |

Teste: `schemas-007-029-030-e-cadeia-a6`.

## 2. Ciclo integrado (fixtures)

Coberto por `ciclo-completo-auto-ate-proxima-aparicao`:

1. Configuração da agenda (`enabled=true`, interval ≥ 2 min)  
2. Abertura automática (`tick` → spawn-id estável)  
3. Anúncio outbox (`raid-opened` + ack)  
4. Inscrição de 2 jogadores  
5. Encerramento do lobby no prazo  
6. Início automático do combate + outbox `raid-started`  
7. Ataques até derrota do chefe  
8. Recompensas idempotentes (`rewards-paid?`, retry `operation-id`)  
9. Captura com roll fixo + duplicate  
10. Próxima aparição no `next-at` da agenda  

## 3. Cenários de falha

| Cenário | Teste | Resultado esperado |
| --- | --- | --- |
| Reinício no lobby | `reinicio-durante-lobby-preserva-estado` | fase/agenda/outbox recuperados; expire → combate |
| Reinício no combate | `reinicio-durante-combate-preserva-turno` | turn-index/HP preservados; vitória pós-reopen |
| Datomic indisponível | `datomic-indisponivel-nao-avanca-agenda` | exception; `next-at` intacto; sem receipt |
| Falha envio WA | `falha-envio-whatsapp-mantem-outbox-pendente` | pendente até ack; log `delivery_failed`; replay ≠ novo spawn |
| Ack duplicado | `ack-duplicado-nao-reabre-evento` | `:duplicate true`; lista vazia |
| Ticks simultâneos | `dois-ticks-simultaneos-um-spawn` | 1 outbox + 1 receipt |
| Manual ativa | `raide-manual-ativa-nunca-substituida` | `skip-busy` + log |
| Agenda off no lobby | `agenda-desligada-durante-inscricoes-preserva-lobby` | combate inicia; sem novo spawn |

## 4. Validação operacional

### Kill switches

1. **Global bridge:** `raid-auto-tick-enabled?` / tick com `global-enabled?=false` → no-op.  
2. **Node:** `POKEMON_RAID_AUTO_TICK=false` (default) — poll não efetiva tick.  
3. **Por grupo:** `PUT schedule` com `enabled: false`.

Teste: `kill-switches-global-e-por-agenda`.

### Pausar novas aparições sem interromper raides

```text
1. enabled=false na agenda do chat  (ou kill switch global)
2. Lobbies/combates já abertos seguem até expire/vitória/cancelamento
3. Novos spawns não ocorrem
```

Teste: `pausar-novas-aparicoes-sem-interromper-combate`.

### Rollback de configuração

Reverter `enabled`/`interval-ms`/`next-at` via upsert; agenda normaliza e rejeita interval < 2 min (`:raid_auto_interval_invalid`).

### Logs estruturados

Eventos: `raid_auto_spawn`, `raid_auto_skip`, `raid_auto_lobby_expired`, `raid_auto_started`, `raid_auto_cancelled`, `raid_auto_delivery_failed`.  
Allowlist de campos: `chat-id`, `raid-id`, `spawn-id`, `due-at`, `next-at`, `origem`, `result`, `event-id`, `active-raid-id`, `active-fase`.

## 5. Como rodar

```bash
./scripts/test-raid-homolog-fase4.sh
./scripts/test-raid-auto-fase3.sh
./scripts/test-raid-lobby-fase2.sh
./scripts/test-m4-raid.sh
npm run build
```

## 6. Checklist de liberação (staging/prod — **não executado**)

- [ ] Confirmar URI/storage Datomic do alvo **isolado** do prod e autorização explícita  
- [ ] Backup/snapshot do keyspace alvo (se aplicável)  
- [ ] Aplicar migrations `029` + `030` **somente** no alvo autorizado  
- [ ] Bridge com `raid-auto-tick-enabled?` ainda **false** no boot  
- [ ] Node com `POKEMON_RAID_AUTO_TICK=false`  
- [ ] Smoke sintético (chat de teste) com agenda `enabled=true` + tick manual controlado  
- [ ] Verificar logs `raid_auto_*` e outbox ack  
- [ ] Pausar (`enabled=false`) e confirmar combate em andamento intacto  
- [ ] Só então considerar canário com kill switches prontos para reverter  

## 7. Riscos residuais

- Duplicação **visual** no WhatsApp se falhar entre send e ack (at-least-once).  
- Staging real **não homologado** nesta entrega — só Datomic local.  
- Gaps 026–028: se outro branch exigir attrs, reconciliar antes do migrate alvo.  
- Combate/economia herdados de M4.9; homologação não altera fórmulas.
