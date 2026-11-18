# Pokémon A6 — Auditoria Combate / Golpes / Eventos / UX

**Atualizado:** 2026-11-08T16:50Z  
**Escopo:** investigação + implementação local + testes. **Sem deploy / cutover / migrations / backfill / prod.**  
**Produção (RO):** Species-HP Final live — Node `17520a1e…` / JVM `c13140fa…` @ `72bffdb` · **intacta**.
**RC candidata:** Combat UX+Eventos @ `3bc5076` · imagens buildadas · **não** aplicadas.

---

## 1. Causa-raiz do golpe Wartortle (sem nome/poder)

Não é learnset específico do Wartortle.

**Causa:** golpes legados com chaves string (`"nome"` / `"poder"`, frequentemente **sem `slug`**) passavam por caminhos de persistência/evolução (`moves/chave`, `golpe->registro`, `registro->golpe`) que **ignoravam `"nome"`**. Em snapshots antigos / slim pré-P0, `select-keys` em keywords gerava mapas vazios → UI `? — Poder 0`.

Wartortle aparece como exemplar porque Squirtle→Wartortle preserva golpes via esse round-trip.

### Correção canônica (esta entrega)

| Camada | Mudança |
|--------|---------|
| `moves/chave` + `golpe->registro` + `registro->golpe` | Lê `"nome"`/`:nome`; deriva slug; preserva `autodesmaio` |
| `battle/golpe-normalizado` | Deriva slug a partir do nome quando ausente |
| `moves-audit` | Auditoria reutilizável agregada (sem PII) |
| Node `nomeGolpeUi` / slim P0 | Já existiam; mantidos |

**Não considerar corrigido só porque a UI parou de mostrar `?`:** asserts exigem nome + poder no slim e round-trip legado.

---

## 2. Outros Pokémon potencialmente afetados

Qualquer mon com golpes no shape Cassandra/legado:

- Import M5 / fixtures `"golpes" [{"nome" …}]`
- Evoluções pós-legado (Squirtle linha, etc.)
- Parties de raid **seladas antes** do normalize (precisam leave/rejoin)

Auditoria: `pokemon.domain.moves-audit/aggregate` sobre export RO anônimo.

---

## 3. Correção canônica dos golpes

- Normalização única no JVM (`battle/golpe-normalizado` → slim).
- Persistência string-keys canônica via `moves/golpe->registro`.
- Status com poder 0 (Protect/Growl) ≠ dado quebrado.
- Golpe irrecuperável marca `:golpe-irrecuperavel?` — **não** inventa poder.

Testes: `pokemon.tests.moves-audit-a6-test` (incluído em `:test-a6-lote-b`).

---

## 4. Explosion e autodesmaio

Já no motor (`battle/apply-player-turn`, `raid/atacar`): após hit com dano, HP do usuário → 0; imunidade/dano 0 **não** autodesmaia.

Esta entrega:

- Preserva `autodesmaio` no registro de golpes.
- UI: descrição obrigatória  
  `💥 Explosion — causa dano devastador ao adversário, mas faz o próprio Pokémon desmaiar após o uso.`  
  + aviso se o alvo for imune (sem desmaio).

Testes: `explosion-autodesmaio-a6-test` + lote B.

---

## 5. Formatação de dano e revide

`formatCanonicalAttackLine` / `formatRaidAttack`:

- Com revide: `⚔️ Nome causou X de dano e recebeu Y de revide!`
- Sem revide: `⚔️ Nome causou X de dano!`
- Imunidade: “não teve efeito”
- **Removida** repetição `HP antes→depois` na narrativa (barras de estado permanecem)

Números vêm só do DTO canônico.

---

## 6. Mensagens de desmaio

- Autodesmaio: `não resistiu ao próprio golpe e desmaiou`
- KO normal: `não resistiu aos ataques e desmaiou`
- Derrota de raid (chefe venceu): mantém “chefe resistiu” (resultado efetivo = players perderam)

---

## 7. Raridade e HP

- Caça/ginásio: raridade do **jogador** e do adversário acima das respectivas barras (`🐾 Wartortle · ⭐ Raro`).
- Raid turn block: idem (`·` separator).
- Sempre 2 barras (chefe/adv + ativo). Sem fallback de raridade do inimigo no mon do jogador.

---

## 8. Renderização da primeira imagem

**Causa:** `renderRaidBattleCard` não chamava `ensurePokemonSprite` (caça sim). Snapshots slim sem URL → 1º frame vazio/placeholder; 2º ataque às vezes já tinha cache.

**Correção:** rehidrata chefe + atacante antes do composite.

**Extra:** outbox `raid-started` agora tenta cartão PNG (fallback texto).

---

## 9. Timeout e notificações

- Idle canônico: `battle-idle` · default 300s (`POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS`).
- Mensagem Node:  
  `⏳ Tempo limite atingido! A batalha foi encerrada por inatividade (Ns…). Sem recompensas de vitória.`
- Sweep/idempotência já no store; jobs respeitam maintenance (não alterado).

---

## 10. Traduções do clima

- `TIPOS_PT` / `tipoLabelPt`: ground → **Terra** (unificado; removido “Terrestre”).
- `formatHuntStart` e `formatWorldInfo` usam labels PT.
- IDs internos permanecem em inglês.

---

## 11. Balanceamento dos eventos (aprovado nesta RC)

| Parâmetro | Antes | Nesta RC | Status |
|-----------|-------|----------|--------|
| `hunt/chance-surto` | 50 | **25** | **APLICADO** |
| Surto efetivo (sim 10k) | ~50% | ~25% | assert `0.22–0.28` |
| Demais 75% | — | seleção normal (clima/bioma) | preservado |
| Eevee | nos **4** surtos | inalterado | diversificar pools = GO futuro |
| Frequência/calendário global | 6h | inalterado | **sem** pausas nesta RC |

Candidato estrutural (doc only): rotação/intervalos entre surtos — proposta separada.

Não alteramos recompensas, cooldown global nem captura.

---

## 12. Padronização de comandos / lobby

`formatRaidOpened` unificado:

```
⚔️ RAID POKÉMON
🥚 Um desafio está prestes a começar em …
👥 Treinadores: n/max
⏳ prazo
Como participar / Outros comandos (!pk raid entrar|sair|status|iniciar|cancelar)
```

Respeita `chefeOculto` quando o DTO pedir.

---

## 13. Testes e asserts

| Suite | Resultado |
|-------|-----------|
| `:test-a6-lote-b` (+ moves-audit + event-sim) | **PASS** |
| Node present UX (`a6-combat-ux-present-test.cjs`) | **PASS** |
| Explosion / One-Mon / Species-HP asserts nos testes | intactos |

---

## 14. Impacto em saves existentes

- **Aditivo / forward-compatible.** Leitura normaliza legado; escrita nova grava slug+nome-exibicao+autodesmaio.
- Parties raid pré-fix: leave/rejoin recomendado.
- Sem migration/backfill (proibido nesta janela).

---

## 15. Riscos residuais

- Eevee permanece nos 4 pools sob 25% — diversificação = GO futuro.
- Imagem `raid-started` depende de payload com chefe/vezPokemon no outbox — se ausente, fallback texto.
- Golpes 100% irrecuperáveis (mapa vazio) ainda precisam reaprender / STAB filler.
- PvP: formatação alinhada via `formatBattleState`/`formatCanonicalAttackLine`; validar smoke PvP em RC.
- Produção ainda em Species-HP Final (`72bffdb` / `17520a1e…`/`c13140fa…`) até cutover operacional separado.

---

## 16. GO/NO-GO integração em nova RC

| Item | Veredito |
|------|----------|
| Integrar correções de golpes + Explosion UI + UX combate + sprite 1º frame + clima PT + lobby + moedas 🪙 | **GO** (este commit) |
| `chance-surto` 50→25 | **GO** (este commit) |
| Deploy/cutover produção | **NO-GO** (não autorizado nesta janela) |
| Species-HP / One-Mon / raid boss 320 | **preservados** |
| Migrations / backfill / hold / CI | **intocados** |

---

## Arquivos principais tocados

- `modules/pokemon/domain/pokemon/domain/moves.clj`
- `modules/pokemon/domain/pokemon/domain/battle.clj`
- `modules/pokemon/domain/pokemon/domain/moves_audit.clj` *(novo)*
- `modules/pokemon/tests/pokemon/tests/moves_audit_a6_test.clj` *(novo)*
- `modules/pokemon/tests/pokemon/tests/event_weight_sim_a6_test.clj` *(novo)*
- `scripts/lib/pokemon-jvm-present.cjs`
- `scripts/lib/pokemon-jvm-media.cjs`
- `src/zapbot/pokemon_http.cljs`
- `apps/odisseu/src/odisseu/smoke/a6_lote_b_mechanics.clj`

## 17. Identidade da RC Combat UX + Eventos

| Campo | Valor |
|-------|-------|
| Commit integrado | `3bc507679a4511aa850e5dd015e6bde56e38fca3` |
| Content-tree | `452ff59358a9530a2c358239eba3d1ac8510c2d6` |
| Branch | `rc/a6-combat-ux-events` / `rc/a6-abc-p0-integrated` |
| PRs | #51 · #52 · #53 |
| Node | `odisseu-node:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` |
| JVM | `odisseu-jvm:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` |
| Stamp | `20261108T164301Z` |
| VERIFY | PASS |
| Deploy | **NO-GO** (prep only) |

### Simulação surtos (10k)

- `chance-surto=25` → pct efetivo **0.25** (assert 0.22–0.28)
- Eevee permanece nos 4 pools — diversificação = GO futuro
- Sem mudança de frequência global / calendário

## 18. Prep operacional host (2026-11-08T16:54Z)

- Inbox: `/home/caiohclavico/odisseu-recovery/rc-inbox/a6-combat-ux-events-3bc5076/`
- Evidência: `/home/caiohclavico/odisseu-recovery/a6-combat-ux-events-prep-20261108T165411Z`
- SHA256 Node/JVM: `83796ce1…537f` / `48cb0e87…e22d` (local=host)
- Image IDs host confirmados; VERIFY PASS; overlay pinado **sem** apply
- Live Final intacta (WA READY)
- Auditoria snapshot: 035=true · v1=984 · legacy-unset=5 · invalid=0 · battles/raids=0
- Homolog candidata sem volumes: PASS
- **GO** solicitar janela · **NO-GO** cutover até autorização
