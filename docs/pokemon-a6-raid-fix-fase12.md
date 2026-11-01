# A6 — Fase 12: Correção crítica das raides

**Data (UTC):** 2026-11-01  
**Branch:** `fix/a6-raid-fase12-turnos` (isolada; base `6ab23ae` / `release/a6-cutover-rc-7.3`)  
**Modo:** correção de código + testes — **sem** deploy, restart, migrate, escrita em saves live ou auto-heal de raides reais.  
**Raides automáticas:** permanecem **OFF**.

---

## Decisão: **READY para RC** · **NO-GO de implantação**

| Gate | Resultado |
| --- | --- |
| Causa raiz do bloqueio “não é sua vez” | **PASS** (identificada + corrigida) |
| Abertura direta `!pk raid abrir <n\|id\|nome>` | **PASS** |
| Lobby padrão 5 min + min-interval auto | **PASS** |
| Apresentação de turno (nome/golpes/comando) | **PASS** |
| Cartão visual com fallback textual | **PASS** |
| Testes Node + JVM fase 12 / lobby / auto | **PASS** |
| Deploy / recreate produção | **não executado** |
| Correção automática de raides travadas live | **não executada** (exige auditoria + GO) |

---

## 1. Causa raiz — todos bloqueados no ataque

### Sintoma (piloto)

Vários jogadores inscritos; combate iniciado; **todos** recebem `⏳ Não é a sua vez de atacar.`

### Cadeia

WhatsApp → Node (`executar-raid-jvm!` / `atacar`) → pré-check `raid.suaVez` → (nunca chega ao domínio se falhar).

### Bug

No DTO da bridge (`bridge_reads.clj`):

```clojure
:sua-vez? (= player-id (:vez raid))
```

O encoder JSON (`json.clj` `camel-key`) transformava `:sua-vez?` em **`suaVez?`** (o `?` ia junto).  
O Node lia **`suaVez`** (`.-suaVez` / `r.suaVez`) → sempre `undefined`/`false` → pré-check rejeitava **todos**, inclusive o jogador da vez.

Não era mismatch de telefone/`@lid` vs `@c.us` no incidente principal (embora o domínio continue exigindo identidade canônica exata). O erro de turno **mascarava** o contrato JSON quebrado.

### Correção

1. Keywords do DTO sem `?`: `:sua-vez`, `:inscrito`, `:voce` (+ campos de apresentação).  
2. `camel-key` remove sufixo `?` defensivamente (`:sua-vez?` → `suaVez`).  
3. Node aceita `suaVez` **e** `suaVez?`, e confere `playerId === vez` (igualdade literal).  
4. Domínio: `vez-valida?`; ataque com ponteiro fantasma → `:raid_turn_invalid` (não mascara como not_your_turn).  
5. Início de combate escolhe o primeiro participante **vivo** da ordem.

**Invariante:** em combate, `:vez` aponta a participante existente, na ordem, com HP > 0.  
**Sem** strip de sufixo `@lid`/`@c.us`. **Sem** auto-heal de raides live.

---

## 2. Abertura de raide

- `!pk raid abrir` → lista de ginásios.  
- `!pk raid abrir 1` / `pedra` / `Água` → abre direto (índice, id ou nome normalizado).  
- `!pk gin abrir …` encaminha ao mesmo fluxo.  
- Número solto `!pk 1` continua sendo **ataque** (sem menu de ginásio pendente) — documentado nos testes.

---

## 3. Lobby 5 minutos

| Antes | Depois |
| --- | --- |
| `duracao-inscricoes = 2 min` | **5 min** |
| `raid-auto/min-interval-ms` = lobby | permanece acoplado (= 5 min) |

Raides **já abertas** preservam `:expira` gravado (sem rewrite).  
Mensagens present/auto atualizadas para “5 minutos”.

---

## 4. Apresentação de turnos

Status / início / ataque passam a exibir:

- Nome do treinador da vez (nunca JID cru como rótulo)  
- Pokémon ativo + HP  
- Golpes 1–4 com comando exato; status/poder 0 → _indisponível_  
- HP do chefe  
- Próximo participante quando aplicável  

---

## 5. Imagem da batalha

`scripts/lib/pokemon-jvm-media.cjs` → `renderRaidBattleCard`:

- Ginásio, chefe, sprite do atacante (cache de sprites), barras de HP, treinador, rodada.  
- Falha de render → `null` → envelope só texto (sem re-TX).  
- Ligado em `iniciar` e `atacar` via `render-seguro!`.

---

## 6. Testes executados

```text
./scripts/test-raid-fase12.sh
  JVM pokemon.tests.raid-fase12-test     8 testes / 25 assert · 0 fail
  Node pokemon-jvm-raid-fase12 (+ lobby/raid)  16 pass

clojure -M:test-raid-lobby-fase2         23 / 76 · OK
clojure -M:test-raid-auto-fase3          19 / 76 · OK
```

Cobertura destacada:

- Incidente camelCase `suaVez?` vs `suaVez`  
- Só o player-id canônico da vez ataca; `@lid` ≠ `@c.us`  
- Vez inválida → `raid_turn_invalid`  
- Rotação, saída em combate, golpe status rejeitado  
- Lobby 5 min + min-interval auto  
- Abrir por índice/id/nome  
- Cartão PNG ou null  

---

## 7. Alterações principais

| Área | Arquivos |
| --- | --- |
| JSON camelCase | `modules/pokemon/infrastructure/.../http/json.clj` |
| DTO raid | `bridge_reads.clj`, `bridge_tx.clj` |
| Domínio | `domain/raid.clj`, `domain/raid_auto.clj` |
| HTTP erros | `infrastructure/http/bridge.clj` |
| Node fluxo | `src/zapbot/pokemon_http.cljs` |
| Present/UX | `scripts/lib/pokemon-jvm-present.cjs` |
| Mídia | `scripts/lib/pokemon-jvm-media.cjs` |
| Testes | `test/pokemon-jvm-raid-fase12.test.cjs`, `modules/.../raid_fase12_test.clj`, `scripts/test-raid-fase12.sh` |

---

## 8. Riscos e limites

| Risco | Mitigação |
| --- | --- |
| Raide live já travada com `:vez` fantasma | **Não** auto-corrigir; auditoria + GO separado; erro `raid_turn_invalid` |
| Cliente Node antigo sem rebuild | Encoder + DTO novos; Node também tolera `suaVez?` |
| Lobby 5 min vs schedules auto com interval=2 min | `normalize-interval` rejeita `< min-interval` (=5 min) |
| Imagem em cada ataque | Cache de sprites; fallback texto |

**Fora de escopo desta fase:** deploy, recreate, migrate, liberar grupos extras, ligar raid-auto, mutar volumes Datomic/A5.6.

---

## 9. Release candidato (preparação)

Sugestão de tag/artefato (autorização **separada**):

```text
Branch: fix/a6-raid-fase12-turnos
Conteúdo: Node + JVM (present/media + bridge/domain)
Validar em staging: abrir 1 → entrar → iniciar → atacar (jogador da vez)
Smoke: STRICT/allowlist piloto inalterados; POKEMON_RAID_AUTO_TICK=false
```

Implantação exige GO explícito (padrão Fase 11.4: recreate controlado, preferencialmente Node+JVM se a bridge JVM for atualizada).

---

## Status final

**READY para RC** — causa raiz do bloqueio universal de ataque corrigida; lobby 5 min; UX de turno + cartão; testes verdes.  
**NO-GO deploy** até autorização explícita.
