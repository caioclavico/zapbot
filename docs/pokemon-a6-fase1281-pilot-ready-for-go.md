# A6 — READY FOR PILOT GO · Etapa B · Festival dos Dragões

**Decisão prep:** **READY FOR PILOT GO** · **sem ativação / sem flip de flag em produção**  
**Pré-requisito:** Etapa A SUCCESS (`docs/pokemon-a6-fase1281-deploy.md`)  
**Grupo piloto:** `120363427684548487@g.us`  
**Evento:** `festival-dragoes` · **duração alvo:** 24h · **agenda recorrente:** OFF  
**Demais grupos:** eventos OFF (`enabled=false` / sem schedule)

---

## 0. Gate de código (obrigatório antes do GO definitivo)

Produção live ainda roda `dc6ba295` **sem** `durationHours`. No catálogo, categoria `especial` → **48h**.

| Opção | Ação |
| --- | --- |
| **A (recomendada)** | Micro-deploy do patch local (`force-duration-h` + cancel com anúncio) **antes** da ativação |
| **B** | Aceitar **48h** com imagem atual |
| **C** | Ativar 48h e `POST /cancel` em T+24h (anúncio de fim só se o patch de cancel estiver live) |

Patch preparado (ainda **não** commitado/implantado): `durationHours` no `PUT /v1/thematic-events/schedule` + cancel emergencial com outbox de fim.

---

## 1. Auditoria live (pós-Etapa A)

| Check | Resultado |
| --- | --- |
| Versão | 12.8.1 · `dc6ba295…` · content-hash `2e1c78445cb6…` |
| JVM/Node | healthy |
| WhatsApp | READY |
| Flag | `POKEMON_THEMATIC_EVENTS_ENABLED=false` |
| Migration 032 | aplicada (Etapa A) |
| Eventos ativos piloto | `[]` · `schedule=null` |
| History | `[]` |
| Outbox pending | `[]` |
| Outro chat | `events=[]` · `schedule=null` |
| Raid auto | rev **16** · `dailyLimit=12` · `specialReserve=2` intacto |

---

## 2. Isolamento da flag global

A flag é **process-wide**. Isolamento operacional:

1. Flag `true` habilita o *motor* (tick/caça/missões).  
2. Tick sem `chatId` só processa schedules com `enabled=true`.  
3. `ensure-schedule!` cria chat com `enabled=false`.  
4. Bias/missões só existem se houver ocorrência `active` **naquele** chat.

**Antes de ativar:** habilitar schedule **somente** no piloto; nunca `enabled=true` em outros chats.

---

## 3. Configuração exata da ativação (após GO + patch 24h)

### 3.1 Pré-checks

```bash
# Flag ainda false; schedule null; raids rev 16
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:18092/v1/thematic-events/active?chatId=120363427684548487@g.us"
```

### 3.2 Flip da flag (JVM+Node, compose `.env`)

```
POKEMON_THEMATIC_EVENTS_ENABLED=true
```

Recreate/restart JVM (e Node se env só no Node). Confirmar `printenv` = `true` nos dois.  
**Não** alterar `ALLOWED_GROUP_IDS` / `ALLOW_ALL_GROUPS`.

### 3.3 Agendar Festival 24h só no piloto

```http
PUT /v1/thematic-events/schedule
Authorization: Bearer <ODISSEU_BRIDGE_TOKEN>
Content-Type: application/json

{
  "chatId": "120363427684548487@g.us",
  "enabled": true,
  "catalogId": "festival-dragoes",
  "durationHours": 24,
  "nextAt": <epoch_ms_agora>
}
```

### 3.4 Tick (ativa + outbox início)

```http
POST /v1/thematic-events/tick
{"chatId":"120363427684548487@g.us"}
```

Node `consultar-eventos` entrega `thematic-event-started` → WhatsApp (allowlist).

### 3.5 Confirmações imediatas

| Check | Esperado |
| --- | --- |
| `GET .../active?chatId=piloto` | 1× Festival · janela 24h |
| `GET .../active?chatId=<outro>` | `events=[]` |
| `GET .../schedule?chatId=piloto` | `enabled=true` · policy limpa pós-force |
| Outbox / WA | 1 anúncio início |
| Raid schedule | revision 16 inalterada |
| Tick global `{}` | só processa piloto |

### 3.6 Encerramento

Automático no tick com `agora >= fim-ms` → status `ended` + `thematic-event-ended`.  
Após o fim: `PUT` com `"enabled": false` no piloto (bloqueia recorrência / Etapa C).

---

## 4. Comandos administrativos

| Ação | Método |
| --- | --- |
| Consultar ativo | `GET /v1/thematic-events/active?chatId=` |
| Consultar agenda/catálogo | `GET /v1/thematic-events/schedule?chatId=` |
| Histórico | `GET /v1/thematic-events/history?chatId=` |
| Agendar/forçar | `PUT /v1/thematic-events/schedule` |
| Tick | `POST /v1/thematic-events/tick` |
| Cancelar emergência | `POST /v1/thematic-events/cancel` `{"occurrenceId":"..."}` |
| Missões jogador | `!pk missoes` / `!pk missoes evento` |
| Resgate | `!pk missoes evento resgatar` |

---

## 5. Horários previstos

| Marco | Valor |
| --- | --- |
| Início | `T0` = instante do tick de ativação (UTC/BRT) |
| Término | `T0 + 24h` (com patch) · ou `T0 + 48h` (imagem live atual) |
| Anúncio início | outbox no tick de start |
| Anúncio fim | outbox no tick de end (ou no cancel emergencial com patch) |

Registrar `T0`, `occurrence-id` e `fim-ms` no meta do deploy da Etapa B.

---

## 6. Missões e recompensas definitivas

**Bias caça:** 20pp Dragão → depois clima 35pp → bioma 45pp (limite evento ≤35).  
**Outros tipos:** permanecem elegíveis. Raridades do domínio intactas.

| Missão | Meta | Ação | Itens | Doces |
| --- | --- | --- | --- | --- |
| `cap-dragao` | 3 | captura tipo dragon | fruta×2 (Frambo), ultra-bola×2 | dratini×2 |
| `evo-dragao` | 1 | evolução famílias dragão | fruta×1, cartao-presente×1 | bagon×3 |
| `raid-dragao` | 2 | raid-join | ultra-bola×1, fruta×1 | — |

Doces → `professor-cards-edn` (saldos existentes). Claim idempotente por `operation-id`.

---

## 7. Testes executados (descartável)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-fase1281` | **PASS** — 24 tests / 233 assertions |
| Novo: `piloto-24h-isolamento-e-desativacao-emergencial` | **PASS** |
| Script `scripts/a6_fase1281_pilot_festival_rehearsal.clj` | ensaio local 24h + isolamento + end |

Cobertura: ativação force, outbox início/fim, isolamento outro chat, tick global só enabled, cancel emergencial + anúncio, disable agenda, claim/doces (testes anteriores).

---

## 8. Evidência de isolamento por grupo

- Tick em `chat-outro` com `enabled=false` → sem occurrence.  
- Tick global → apenas chats `enabled=true`.  
- Live hoje: nenhum schedule temático em qualquer chat.  
- Allowlist WhatsApp inalterada (só piloto recebe anúncios mesmo se outbox vazasse).

---

## 9. Rollback / desativação

**Emergência (durante evento):**

1. `POST /v1/thematic-events/cancel` com `occurrenceId` do Festival.  
2. `PUT .../schedule` `{"chatId":piloto,"enabled":false}`.  
3. Opcional: `POKEMON_THEMATIC_EVENTS_ENABLED=false` + restart JVM (mata motor global).  
4. Confirmar `active=[]`, outbox fim (com patch), raids rev 16.

**Pós-fim natural:** só `enabled=false` no piloto; flag pode permanecer true se Etapa C ainda não for GO — preferível voltar flag `false` até C.

**Não** remover migration 032. **Não** tocar saves/`recovery-v1`/raids.

---

## 10. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Duração 48h na imagem live | Micro-deploy patch **ou** aceitar 48h no GO |
| Flag global | Só `enabled=true` no piloto; monitorar schedules |
| Cancel sem patch | Para bias, mas pode omitir anúncio de fim |
| Missão raid depende de spawns | Scheduler auto intacto (`dailyLimit=12`) |
| Claim pós-encerramento | Permitido se progresso completo (teste) |

---

## 11. Snapshot scheduler temático (pré-ativação)

```
piloto: schedule=null  active=[]  history=[]  enabled(flag)=false
outros: schedule=null  active=[]
raids: revision=16 dailyLimit=12 specialReserve=2
```

Plano pós-ativação: `enabled=true` só piloto · `force-catalog-id=festival-dragoes` · `force-duration-h=24` · após start policy `{}` · `next-at` = T0+72h (intervalo mínimo; **não** reativa sem enabled).

---

**NÃO ativar o Festival dos Dragões até GO definitivo.**  
**NÃO** flip de flag · **NÃO** PUT schedule em produção nesta etapa.
