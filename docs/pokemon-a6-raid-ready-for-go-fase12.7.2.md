# A6 — READY FOR GO definitivo · Fase 12.7.2

**Decisão prep:** **READY FOR GO** (aguardando GO explícito — **sem deploy**)  
**Escopo:** cadência dinâmica + limite 12/dia + anti-starvation especiais  
**Piloto:** `120363427684548487@g.us`  
**Preserva:** 12.7.1 (lobby/`iniciar`/ovos PNG) · 12.6.2 economia · recovery-v1 · fences · unknown-pending · leave ON · allowlist

---

## 1. Checklist dos 10 pontos

| # | Item | Resultado |
| --- | --- | --- |
| 1 | Limite inicial **12 raids/dia** (metas ≠ obrigação) | ✓ `daily-limit=12` |
| 2 | Prioriza raro/épico sem eliminar especiais | ✓ dia + reserva |
| 3 | Cap não starve lendário/mítico | ✓ reserve=2 + bypass 1 especial |
| 4 | Preserva especiais na migração 6h→1h | ✓ `safeCadence` |
| 5 | `intervalMs` sem spawn imediato/dup/retro | ✓ safe patch + teste |
| 6 | PNG só lendária/mítica na nova cadência | ✓ `reminders-allowed?` |
| 7 | Lembretes sem revelar chefe | ✓ format-reminder + asserts |
| 8 | `nextAt` / intent / receipts pós-restart | ✓ testes idemp. |
| 9 | Simulação ≥7 dias | ✓ 84 spawns · 12/dia |
| 10 | Msgs WA com limite 12 | ✓ **~30/dia** |

---

## 2. Simulação 7 dias (domínio puro)

Parâmetros: start 2026-11-03 08:00 BRT · `daily-limit=12` · `special-reserve=2` · interval 1h · slot sempre livre.

| Métrica | Valor |
| --- | ---: |
| Spawns totais | **84** |
| Por dia | **12, 12, 12, 12, 12, 12, 12** |
| Raro | 42 |
| Épico | 28 |
| Lendário | 7 |
| Mítico | 7 |
| Max/dia | 12 |
| Conflitos simultâneos | 0 (1 slot) |

### WhatsApp (estimado)

| | /dia | /semana |
| --- | ---: | ---: |
| opened | 12 | 84 |
| lembretes ovo (só especial) | 6 | 42 |
| started/cancelled | 12 | 84 |
| **Total** | **~30** | **~210** |

Fórmula: fáceis×2 + especiais×5.

---

## 3. Migração segura 6h → 1h (piloto)

```http
PUT /v1/raids/auto/schedule
{
  "chatId": "120363427684548487@g.us",
  "intervalMs": 3600000,
  "dailyLimit": 12,
  "specialReserve": 2,
  "safeCadence": true
}
```

`safeCadence` / `safe-cadence-patch`:

- **não** antecipa `nextAt` (ignora tentativa de puxar para agora/passado)
- **preserva** `next-categoria` especial já planejado + `reminders?`
- grava cadence receipt (`daily-limit` / reserve / intervals) sem migration

**Proibido no GO:** `nextAt: now` sem `safeCadence`.

---

## 4. Política resumida

```
tick: expire → remind(só especial|legado) → spawn
spawn: se spawns_hoje >= 12 → skip-daily-cap → nextAt = máx(advance, 08:00 BRT amanhã)
        exceto 1 especial due e 0 especiais hoje (anti-starvation)
pick:  dia → épico/raro; noite/overdue/reserva → lendário/mítico
```

---

## 5. Testes

```bash
clojure -M:test-raid-auto-cadence-fase1272
clojure -M:test-raid-auto-fase3
```

Inclui: cap/reserva, safe migrate, spoiler, sim 7d, restart/intent, regressão 12.7.1.

---

## 6. Commit / RC / backup / rollback

### Commit (local, após GO de commit — ver git log)

Mensagem sugerida:

```
feat(a6): cadência 12.7.2 com limite 12/dia e anti-starvation

Frequências por categoria como metas; lembretes ovo só em especiais;
migração segura 6h→1h sem spawn retroativo.
```

### Imagens RC (montar **após** commit, sem cutover)

```bash
# content-hash + tags a6-raid-auto-1272-<UTC>-<hash12>-amd64
# JVM + Node · labels fase=12.7.2 · git-sha · content-hash
# Export tar + checksums em odisseu-recovery/a6-fase12.7.2-rc-<UTC>/
```

Produção permanece em **12.7.1** até GO definitivo.

### Backup pré-cutover

- Snapshot Datomic / volume piloto  
- Export JSON agenda: `GET /v1/raids/auto/schedule?chatId=…`  
- Anotar `nextAt`, `revision`, intent receipt

### Deploy (só com GO)

1. Warm/cold conforme playbook 12.7.1  
2. `PUT` safeCadence (acima) **sem** alterar nextAt especial  
3. Observar 24h: spawns ≤12/dia · lembretes só especiais · logs `raid_auto_skip_cap`

### Rollback

1. `PUT intervalMs=21600000, safeCadence=true` (volta ~6h, preserva nextAt)  
2. Ou `enabled:false`  
3. Reverter imagens RC → 12.7.1 se necessário  
Receipts intent/cadence são leftover inofensivo.

---

## 7. Não fazer sem GO

- Deploy / restart produção  
- Migration schema  
- Alterar allowlist  
- Remarcar `nextAt` de especial já agendado  
- Abrir auto em outros grupos
