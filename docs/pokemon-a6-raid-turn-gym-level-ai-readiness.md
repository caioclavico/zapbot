# Readiness — IA Gemini/Groq · 1º turno auto · nível livre · ginásio por tipo

**Status:** implementado e testado em ambiente descartável · **AGUARDA GO**  
**Base:** hotfix lobby+solo (`5e8375d`) implantado no piloto  
**Deploy / migrate / backfill / produção:** **não realizados**

---

## 1. Diagnóstico Gemini / Groq (sem credenciais)

### Causa raiz

| Camada | Achado |
| --- | --- |
| Código | Sem regressão. `disponivel?` = Gemini **ou** Groq não-vazio; fallback Gemini→Groq em `scripts/lib/ai-client.cjs` intacto (38 testes Node OK). |
| Compose A6 | Node usa só `env_file`; **não** injeta `GEMINI_*` / `GROQ_*` em `environment:`. |
| Runtime piloto | Arquivo `.env.a6-cutover`: **`GEMINI_API_KEY` MISSING** · **`GROQ_API_KEY` MISSING**. |
| Container Node | Ambas **UNSET** (presença verificada sem ler valores). |

Mensagem ao usuário (`!pergunta` / `!resuma`):  
`configure GEMINI_API_KEY ou GROQ_API_KEY no .env` — esperada com chaves ausentes.

### O que precisa de intervenção operacional (no GO de deploy)

1. Definir **`GEMINI_API_KEY`** no env_file do Node (ex.: `.env.a6-cutover`) — valor **não** deve ser colado em chat/PR.
2. Opcional mas recomendado: **`GROQ_API_KEY`** como fallback.
3. Opcional: `GEMINI_MODEL` / `GROQ_MODEL` (defaults já sensatos).
4. Recriar **somente o container Node** após gravar o env (JVM não usa essas chaves).
5. Confirmar boot log `[IA] gemini=configured … | groq=… | disponivel=true` (novo — sem valores).

Não há chave inventada/rotacionada neste trabalho. Pokémon permanece independente da IA.

### Mudanças de código (IA)

- `zapbot.gemini/status-provedores` + `log-status-boot!` (presença/formato only).
- Exemplos `.env.a6-staging.example` / `.env.odisseu-core.example` documentam Gemini+Groq.

---

## 2. Causa raiz — 1º turno ausente no início automático

**Não era falha de combate.** `expirar-inscricoes` já inicializava `:fase "combate"`, `:vez`, HP e golpes.

O outbox `raid-started` usava `format-started` mínimo:

```
⚔️ Raide iniciada automaticamente em …
Chefe HP: x/y
```

O caminho manual (`!pk raide iniciar`) usa `formatRaidStart` + `formatRaidTurnBlock`. O Node entregava o texto pré-assado do outbox sem reconstruir o bloco de turno.

### Correção

- `format-started` (Clojure) agora emite header + vez + golpes numerados + `!pk raide atacar N` (degrada se sem `:vez`).
- Pending events anexam `:mentions [vez]` para `raid-started` (sem migration).
- Node `entregar-evento-jvm!` respeita `mentions` do evento.

Estado de combate / idempotência do outbox (`raid-started:{raid-id}:{lobby-expira}`) inalterados.

---

## 3. Evidência reprodução → correção

| Antes | Depois (teste) |
| --- | --- |
| Texto só “iniciada automaticamente” + HP | `RAID INICIADA AUTOMATICAMENTE` + `Vez de *…*` + `raide atacar 1` |
| Sem mentions | `:mentions` = player da vez |
| Ataque após auto-start | PASS — turno avança ao próximo |

Suite: `pokemon.tests.raid-turn-gym-level-hotfix-test/auto-expire-anuncia-turno-e-golpes`.

---

## 4. Sem bloqueio de nível mínimo

Removido o gate de faixa de liga em:

- `assert-combatente-valido!`
- `build-join-tx`
- `raid-apto?` / `raid-inapto-reason` / `raid-eligible-pokemon`
- `pokemon-elegivel-liga?` → sempre true se há Pokémon (liga = flavor)

**Mantido:** HP > 0, golpe ofensivo, locks de combate, fences/saves.

Nv.1 entra em raid “diamante”; desmaiado continua bloqueado. Dificuldade do chefe inalterada.

---

## 5. Ginásio temático por tipo do chefe

- Novo `pokemon.domain.raid-gym-theme` — 18 tipos → nome/emoji/cores.
- `plan-spawn`: após sorteio do chefe, `nome-ginasio` = tema do **tipo primário**; `nivel-ginasio`/`liga` do cursor round-robin **preservados** (dificuldade).
- `opened-media-from-raid` envia `tipo` / `tipos`.
- `renderRaidOpenedReveal` usa `TYPE_REVEAL_THEMES` (gradientes por tipo).
- Lembretes ovo T−30/15/5: **inalterados** (sem spoiler).

### Prévias

| Arquivo | Tema |
| --- | --- |
| `docs/previews/raid-revelada-moltres-fogo.png` | Fogo |
| `docs/previews/raid-revelada-kyogre-agua.png` | Água |
| `docs/previews/raid-revelada-mewtwo-psiquico.png` | Psíquico |
| `docs/previews/raid-revelada-rayquaza-dragao.png` | Dragão |
| `docs/previews/raid-revelada-tyranitar-pedra.png` | Pedra |

---

## 6. Arquivos modificados

| Área | Paths |
| --- | --- |
| Domínio | `raid_auto.clj`, `raid.clj`, **`raid_gym_theme.clj` (novo)** |
| Infra | `raid_store.clj`, `store.clj`, `bridge.clj`, `bridge_tx.clj`, `bridge_reads.clj` |
| Node | `pokemon-jvm-present.cjs`, `pokemon-jvm-media.cjs`, `pokemon_http.cljs`, `gemini.cljs`, `core.cljs` |
| Env docs | `.env.a6-staging.example`, `.env.odisseu-core.example` |
| Testes | `raid_turn_gym_level_hotfix_test.clj` (novo), runners/deps, ajustes m49 / readiness / capture, egg-reminder Node |
| Docs | este readiness + prévias PNG |

---

## 7. Testes executados

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-turn-gym-level-hotfix` (4 suites) | **PASS** 0 fail |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** |
| `node --test` ai-client + egg-reminder + fase12 | **PASS** 38 |

Cobertura relevante: auto-start 1º turno + ataque, nv.1 join, KO bloqueado, 18 tipos, tematização no plan-spawn, regressões lobby/solo/readiness/m49/capture.

---

## 8. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Chaves IA ainda ausentes em prod até GO ops | Documentado; Pokémon não depende delas |
| Magikarp/catálogo injeta golpes ofensivos | Inapto por HP/golpe explícito; nível não é mais o gate |
| Ginásio “id” interno ainda do cursor (pedra/água…) | Só **nome** visual muda; batalha/nível intactos |
| Menção WA depende de JID válido em `:vez` | Mesmo padrão de outros eventos; nome nunca é `@lid` no texto |
| Cartão de batalha no auto-start ainda só texto | Paridade de **informação** com manual; PNG battle card pode ser fase 2 |

---

## 9. Plano de deploy e rollback (para o GO futuro)

### Deploy (piloto only)

1. Warm/cold backup.
2. Build JVM+Node da mesma revisão (content-hash).
3. **Antes do recreate Node:** gravar `GEMINI_API_KEY` (+ opcional `GROQ_API_KEY`) no env_file — fora do git.
4. Cutover tags compose (sem migrate / sem force tick).
5. Smoke: WA READY · `[IA] disponivel=true` · spawn/abrir · auto-expire com 1 jogador mostra turno · nv.1 entra · revelação temática · ovo sem spoiler.
6. Allowlist piloto inalterada.

### Rollback

- Imagens anteriores (lobby-join-hotfix `5e8375d` / digests atuais do piloto).
- Remover chaves IA do env **não** é necessário no rollback de código.
- Dados de raid/saves preservados (sem migration).

---

## 10. Decisão

**READY FOR GO** — aguardando autorização explícita. Nenhuma alteração aplicada em produção nesta etapa.
