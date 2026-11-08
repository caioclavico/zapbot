# Pokémon A6 — Auditoria P0: raids, ginásios e captura

**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`  
**Atualizado:** 2026-11-08T06:25Z  
**Branch RC:** `rc/a6-abc-p0-integrated` (`9d2a345` P0 + `c5ab41d` ops)  
**Restrições:** sem deploy, migration, backfill, flags, sessão WA ou alteração de produção.

---

## Relatório IMPLEMENTADO / TESTADO / PENDENTE

| Item | IMPLEMENTADO | TESTADO | PENDENTE |
|------|--------------|---------|----------|
| Preservar `:raridade` slim→encode/decode→KO→DTO | Sim (`battle` keep + `assegurar-raridade`) | P0 + flow Datomic | Cutover live |
| Sem fallback silencioso → `comum` na **chance** | Sim (`resolver-raridade`: snapshot / classificado / desconhecida→**mitico**) | P0 domínio | Cutover live |
| Taxas raro/épico/lendário/mítico pós-reopen | Sim (carimbo + reopen store) | `capture_rarity_flow_a6_test` | Cutover JVM live |
| UI `Raridade: ?` | Sim (`formatCaptureMenu` canônico / indisponível) | Node P0 + lote-c + imagem `289a0407` | Cutover Node live |
| Ginásio NPC sorteado (pool + RNG) | Sim (`gym/sortear` + `/v1/gyms/npc-roll`) | P0 + JVM RC `7bc8f341` | Cutover Node+JVM |
| Raid man/auto mesmo motor (faint/eff/dano/UI) | Sim (já A+B+C + UI turno) | faint + P0 + homolog | JVM live ainda `03a3dc7e` |
| Lotes A+B+C preservados | Sim | smokes PASS | — |
| Imagens RC Node+JVM | Sim (thin AMD64) | sandbox + Datomic descartável | Janela autorizada |

### RC necessária

| Camada | Precisa RC nova? | Motivo | Image ID candidata |
|--------|------------------|--------|--------------------|
| **JVM** | **Sim** | Domínio captura/raridade, ginásio pool, raid party/eff | `sha256:7bc8f341…` |
| **Node** | **Sim** | present/HTTP npc-roll + Chromium 156 + listeners | `sha256:289a0407…` |
| Só Node | Não resolve | Chance/faint/sorteio exigem JVM | — |
| Só JVM | Parcial | UI `?`/raid texto/npc-roll ficam velhos | — |

**Produção hoje (RO):** Node `05b36c5a` · JVM `ad0b7c1b` (`source-base=03a3dc7e`) — JVM **sem** A+B+C nem este P0.

---

## 0. Identidade produção vs RC

| Camada | Live | RC candidata (`c5ab41d` / tree `8428d03b`) |
|--------|------|-------------------------------------------|
| Node | `05b36c5a…` | `289a0407…` · tag `a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64` |
| JVM | `ad0b7c1b…` / `03a3dc7e` | `7bc8f341…` · mesma stamp/tag curto |

---

## 1. Matriz raid manual × automática

Após lobby, **mesmo** `pokemon.domain.raid/atacar` + `raid_store` + `formatRaidAttack`.

| Mecânica | Manual | Automática |
|----------|--------|------------|
| Entrada/party | join sela ≤3 | **idem** |
| Ativo / desmaio | `avancar-party` | **idem** |
| Dano / efetividade | `calcular-dano` × tipos | **idem** |
| Vitória/derrota | chefe 0 / nenhum vivo | **idem** |
| Mensagem turno | Node `formatRaidAttack` | **idem** |
| Persistência | Datomic party v2 | **idem** (+ schedule/outbox no spawn) |

---

## 2. Causas-raiz

### Raids (UI / desmaio / dano 27)

- JVM live sem party e sem × tipos → desmaio único encerra; Água→Fogo sem mult; `floor(15+24×0.5)=27` coerente **sem** efetividade.
- RC `ca06cff` já corrigia motor; este P0 reforça UI (turno/tipo/continuidade).

### Raridade `?` + chance “comum”

1. Slim dropava `:raridade`.
2. `raridade-de` caía em `"comum"` → UI `?` e taxas infladas no KO.
3. **Correção:** keep + `resolver-raridade` (snapshot → classificar espécie → desconhecida/**mitico** para chance).

### Ginásio NPC fixo

`:time` estático. **Correção:** `:pool` + sorteio na criação do desafio; ocupado por jogador intocado.

---

## 3. Política de raridade (canônica)

| Fonte | Quando | Chance |
|-------|--------|--------|
| `:snapshot` | `:raridade` válida no mon | Faixa da raridade |
| `:classificado` | Legado sem campo; há taxa/stats/flags | `classificar-raridade` |
| `:desconhecida` | Sem sinais | Faixa **`mitico`** (nunca comum) |

XP com slug ausente continua `comum` (não infla recompensa). UI nunca imprime `?`.

---

## 4. Exemplos de mensagem

```
⚔️ *RAID — TURNO 4*
💧 *Blastoise* usou *Jato d'Água*!
💥 É super efetivo!
❤️ Chefe: 142 → 115 (-27 HP)
💀 *Seu Blastoise desmaiou!*
🔄 *Gyarados entrou na batalha!*
⚔️ *A raid continua!*
```

```
⭐ Raridade: Raro
```

---

## 5. Evidências de testes

| Suite | Resultado |
|-------|-----------|
| `raid-gym-capture-p0-a6-test` | **11 / 64** PASS |
| `capture-rarity-flow-a6-test` (Datomic) | **1 / 11** PASS |
| `clojure -M:test-a6-lote-a` | PASS |
| `clojure -M:test-a6-lote-b` | PASS (inclui P0+flow) |
| `clojure -M:test-a6-lote-c` | PASS |
| `clojure -M:test-a6-homolog-abc` | **32 tests / 213 asserts** PASS |
| Node `pokemon-a6-raid-gym-capture-p0` + lote-c present | **4/4** PASS |
| Sandbox AMD64 + HEALTH_BIND + ACL | PASS em `odisseu-core` |
| JVM descartável temáticos `true` + restart | PASS (88 caps, sem dup) |
| Contrato `npc-roll` / present / raridade | PASS |

---

## 6. Saves / migrations / riscos residuais

| Item | Impacto |
|------|---------|
| Migrations / backfill | **Não** |
| Raids live com party 1 | Expiram sozinhas; joins novos na JVM RC selam ≤3 |
| Snapshots legados sem raridade | Recuperação por classificação; senão chance mítica |
| Ginásios ocupados / batalhas ativas | Não sorteados / não alterados |
| Hold/CI/flags/WA | Intocados |

**Risco residual:** produção só melhora após **cutover JVM+Node** com as Image IDs acima. Node live atual já tem WA listeners, mas **não** este present/ginásio roll.

---

## 7. GO / NO-GO

| Pedido | Veredito |
|--------|----------|
| Código P0 + A+B+C no worktree | **GO** |
| Imagens Node+JVM homologadas juntas | **GO** |
| Deploy agora | **NO-GO** |
| Solicitar janela **JVM + Node** | **GO** |
| RC só Node ou só JVM | **NO-GO** para fechar o P0 completo |
