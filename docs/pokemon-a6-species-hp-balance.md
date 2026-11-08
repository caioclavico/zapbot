# Pokémon A6 — HP realista por espécie (species-hp-v1)

**Atualizado:** 2026-11-08T13:40Z  
**RC integrada:** `rc/a6-abc-p0-integrated` @ `5ed39b0` (PR #45 ← `53a6b95`)  
**Escopo:** integração RC + build imagens amd64 + overlay pins (**sem** apply/prod).  
**Sem** deploy / migration live / backfill / saves reais / flags / WA / hold / CI.  
**Chefes de raid:** **não** alterados (Proposta A: 320 HP/jogador, var 85–100%).

---

## Fórmula final recomendada

```
fator        = clamp((base-hp / 80)^0.85, 0.60, 2.20)
hp-ref(nível)= round(80 × 1.03^(nível−1))
hp-max       = max(1, round(hp-ref(nível) × fator))
versão       = species-hp-v1
```

| Decisão | Veredito |
|---------|----------|
| Clamp `[0.60, 2.20]` | **Manter** (ver § Clamp) |
| Ataque/defesa | Inalterados (suavizar + 1.03) |
| Raridade → HP | Sem bônus direto |
| Raid boss HP | Fora (320×N×fator) |
| Fonte canônica | **JVM** (`species-hp` + `species-base`) |

---

## 1. Fórmula anterior

```
suavizar(v) = round(75 + 0.5 × (v − 75))
hp-max     = round(suavizar(base-hp) × 1.03^(nível−1))
```

---

## 2. Clamp extremo — avaliação quantitativa

### Espécies que saturam

| Espécie | base | fator livre | fator clamp | Satura? |
|---------|------|-------------|-------------|---------|
| shedinja | 1 | 0.024 | 0.60 | floor |
| magikarp / shuckle | 20 | 0.308 | 0.60 | floor |
| gengar | 60 | 0.783 | 0.783 | não |
| snorlax | 160 | 1.803 | 1.803 | não |
| wobbuffet | 189 | 2.077 | 2.077 | não |
| wailord | 169 | 1.888 | 1.888 | não |
| **chansey** | 250 | **2.634** | **2.20** | teto |
| **blissey** | 255 | **2.679** | **2.20** | teto |

### Chansey × Blissey (níveis 5–50)

| Espécie | Nv | legado | v1 clamp | v1 livre | Δ livre−clamp | def~suav | bulk_v1 (HP×def) |
|---------|-----|--------|----------|----------|---------------|----------|------------------|
| chansey | 5 | 183 | 198 | 237 | +39 | 40 | 7920 |
| chansey | 10 | 213 | 229 | 274 | +45 | 40 | 9160 |
| chansey | 20 | 286 | 308 | 369 | +61 | 40 | 12320 |
| chansey | 30 | 384 | 416 | 498 | +82 | 40 | 16640 |
| chansey | 40 | 516 | 557 | 666 | +109 | 40 | 22280 |
| chansey | 50 | 694 | 748 | 896 | +148 | 40 | 29920 |
| blissey | 5 | 186 | 198 | 241 | +43 | 43 | 8514 |
| blissey | 10 | 215 | 229 | 279 | +50 | 43 | 9847 |
| blissey | 20 | 289 | 308 | 375 | +67 | 43 | 13244 |
| blissey | 30 | 389 | 416 | 506 | +90 | 43 | 17888 |
| blissey | 40 | 523 | 557 | 678 | +121 | 43 | 23951 |
| blissey | 50 | 702 | 748 | 911 | +163 | 43 | 32164 |

**Conclusão clamp:**

- HP max Chansey = Blissey sob v1 (ambos no teto 2.20).
- **Defesa suavizada** ainda diferencia (~7% mais bulk Blissey: 32164 vs 29920 no nv50).
- Remover o teto acrescenta +148/+163 HP no nv50 e torna ambos desproporcionais em ginásio/PvP frente a Snorlax (bulk 42910 no nv50, mas com atk útil).
- Floor 0.60 é **necessário** para Shedinja/Magikarp jogáveis sem Wonder Guard A6.
- **Recomendação:** manter `[0.60, 2.20]`. Diferenciação Chansey/Blissey via defesa (e futuros atk/def) — não via HP.

### Simulação referência (legado × v1 clamp)

| Espécie | Nível | HP atual | HP novo | Variação |
|---|---|---|---|---|
| wartortle | 1 | 67 | 62 | -5 |
| wartortle | 5 | 75 | 69 | -6 |
| wartortle | 10 | 87 | 80 | -7 |
| wartortle | 20 | 117 | 108 | -9 |
| wartortle | 30 | 158 | 146 | -12 |
| wartortle | 40 | 212 | 195 | -17 |
| wartortle | 50 | 285 | 262 | -23 |
| charizard | 1 | 77 | 78 | +1 |
| charizard | 5 | 87 | 88 | +1 |
| charizard | 10 | 100 | 102 | +2 |
| charizard | 20 | 135 | 137 | +2 |
| charizard | 30 | 181 | 185 | +4 |
| charizard | 40 | 244 | 248 | +4 |
| charizard | 50 | 328 | 333 | +5 |
| snorlax | 1 | 118 | 144 | +26 |
| snorlax | 5 | 133 | 162 | +29 |
| snorlax | 10 | 154 | 187 | +33 |
| snorlax | 20 | 207 | 252 | +45 |
| snorlax | 30 | 278 | 341 | +63 |
| snorlax | 40 | 374 | 456 | +82 |
| snorlax | 50 | 502 | 613 | +111 |
| blissey | 5 | 186 | 198 | +12 |
| blissey | 20 | 289 | 308 | +19 |
| blissey | 50 | 702 | 748 | +46 |
| shuckle | 5 | 54 | 54 | +0 |
| shuckle | 20 | 84 | 84 | +0 |
| shuckle | 50 | 204 | 204 | +0 |
| gengar | 5 | 77 | 70 | -7 |
| gengar | 20 | 119 | 110 | -9 |
| gengar | 50 | 289 | 266 | -23 |
| dragonite | 5 | 93 | 100 | +7 |
| dragonite | 20 | 146 | 156 | +10 |
| dragonite | 50 | 353 | 379 | +26 |
| chansey | 5 | 183 | 198 | +15 |
| chansey | 20 | 286 | 308 | +22 |
| chansey | 50 | 694 | 748 | +54 |

---

## 3. Auditoria Node / JVM (risco fechado)

### Achado

`scripts/lib/pokemon-pokeapi.cjs` aplicava `suavizarStat` em **todos** os stats (incl. HP) ao montar candidatos de caça / time de ginásio / dados de evolução por pedra. A JVM aceitava esse HP e persistia na captura → **segunda fonte de verdade**.

### Correção

| Camada | Mudança |
|--------|---------|
| Node `pokemonDeDados` | Continua suavizando atk/def para poder-total; expõe `hpBase` / `hp-base` **oficial** |
| Node `dadosEvolucao` | Propaga `hp-base` |
| JVM `hunt/canonizar-hp-candidato` | Se há `hp-base`, recalcula HP via species-hp-v1 |
| JVM store `enrich-hp-base` | Completa base pelo catálogo se Node omitir; respeita `:species-hp-skip?` (fixtures) |
| JVM `start-hunt!` | Canoniza HP do selvagem **após** seleção (poder-alvo permanece no DTO suavizado) |
| JVM `scale-npc-team` | Canoniza HP no nível do ginásio |
| JVM `evolution/aplicar-evolucao` | `canonizar-dados-hp` sobrescreve HP do DTO Node quando base conhecida; política proporcional |
| JVM evolve TX | Persiste `hp-base` + `hp-formula-version` |

Seleção de selvagem / poder-alvo **não** mudam (ainda usam stats suavizados do Node). Só o HP de combate/coleção é canônico JVM.

### Legado

- Sem `hp-base` e espécie fora do catálogo → HP do DTO preservado (não inventa).
- CLJS monólito `core.cljs` continua legado e **fora** do caminho A6.

---

## 4. Código

Caminhos sob `modules/pokemon/domain/pokemon/domain/` (exceto schema/store/migrations/smoke).

| Módulo | Papel |
|--------|-------|
| `species_hp.clj` | fórmula, conversão, cura/dano |
| `species_base.clj` | base oficial (override / unsmooth catálogo) |
| `species_hp_migrate.clj` | plano idempotente (**não** roda em prod) |
| `hunt.clj` | `canonizar-hp-candidato` |
| `evolution.clj` | `canonizar-dados-hp` + proporcional |
| `level_evolution` / `trade_evolution` / `onboarding` / `xp` | HP v1 |
| `…/datomic/store.clj` | enrich + persistência |
| `scripts/lib/pokemon-pokeapi.cjs` | `hp-base` oficial |
| `migrations/pokemon/035-species-hp-formula.edn` | schema local |

Leitura de coleção **não** converte HP.

---

## 5. Compatibilidade com saves

| Situação | Comportamento |
|----------|---------------|
| Consulta/listagem | sem conversão |
| Novo starter JVM | nasce v1 |
| Caça A6 (Node+JVM) | selvagem e captura com HP v1 |
| Evolução nível/troca/pedra | max v1; proporção; faint=0 |
| Level-up com `hp-base`+v1 | recalcula; proporção |
| Level-up legado | `×1.03` |
| Cura / poção / Joy | clamped ao max persistido |
| Raid One-Mon | boss 320 intacto; party usa HP do save |
| Restart Node/JVM | atributos Datomic; sem conversão implícita |
| Batch migrate | `plano-registro` — **não executado** |

Exemplo: 40/100 → max 160 → 64/160.

---

## 6. Testes

| Suite | Resultado |
|-------|-----------|
| `:test-a6-species-hp` | **PASS** 19 / 1337 |
| `:test-a6-homolog-abc` | **PASS** 42 / 247 |
| `:test-a6-tm-xp-evolution` | **PASS** (incl. A5.4 reopen) |
| unit hunt M4.5 | **PASS** 18 / 127 |
| Node `pokemon-jvm-hunt` + `a2` | **PASS** 11 |

Cobertura extra pós-risco: DTO Node suavizado→JVM, evo pedra canonizada, clamp Chansey=Blissey + defesa, `hp-base` no pokeapi, reopen Datomic com `hp-formula-version`.

---

## 7. Plano de atualização idempotente (futuro GO)

1. Schema 035 (já versionado; só aplicar com GO).
2. Batch `plano-registro` por save; retry → `:noop` se já v1.
3. Sem escrever em read paths.
4. Imagens rollback Balance A / One-Mon.

**Não executar agora.**

---

## 8. Riscos restantes

| Risco | Severidade | Nota |
|-------|------------|------|
| Chansey=Blissey em HP max | Baixa | Defesa ainda diferencia; aceito |
| Floor Shedinja (48 HP nv1) | Baixa | Sem Wonder Guard A6 — intencional |
| Atk/def ainda suavizados | Médio | Fora desta etapa |
| CLJS monólito legado | N/A | Fora do caminho A6 |
| Migration live | Bloqueado | Exige GO separado |

---

## 9. Integração RC + imagens

| Campo | Valor |
|-------|-------|
| Feature commit | `53a6b9539a24d0063a1f532c7a38eb4587b0e15f` |
| PR | [#45](https://github.com/caioclavico/zapbot/pull/45) |
| Commit integrado | `5ed39b0306addeaf96d4cbb3ad8480268adb3ae0` |
| Content-tree | `80546bd3a04663b80a2e09750f48c5fa2a4cecf9` |
| Node tag | `odisseu-node:a6-species-hp-20261108T133538Z-5ed39b0306ad-amd64` |
| Node Image ID | `sha256:57a828ca6b6187b1415e0a5ea6f71c8b7ec12dd7ffc7fc63e5e6563c2aee5332` |
| JVM tag | `odisseu-jvm:a6-species-hp-20261108T133538Z-5ed39b0306ad-amd64` |
| JVM Image ID | `sha256:e1bb8ac5df0ab5ea81b1ab96ca689a602d47e14d41daf07fd0429fafd9df44a8` |
| Arch | **linux/amd64** |
| VERIFY images | **PASS** |
| Overlay pins | preparado em `docker-compose.a6-ai-drain-sandbox.overlay.yml` (**não** aplicado) |

### Regressões pós-merge

| Suite | Resultado |
|-------|-----------|
| `:test-a6-species-hp` | PASS 19 / 1337 |
| `:test-a6-homolog-abc` | PASS 42 / 247 |
| `:test-a6-tm-xp-evolution` | PASS |
| hunt unit + encounter | PASS |
| `:test-m4-raid` (+ gym) | PASS + smoke HTTP OK |
| `:test-m4-bridge` | PASS |
| Node hunt/a2/gym | PASS 18 |
| Smoke hunt HTTP `praia` | bloqueado por rotação diária (`area_unavailable`) — **não** regressão species-hp |

---

## 10. GO/NO-GO

| Item | Veredito |
|------|----------|
| Implementação + riscos residuais | **GO** |
| Integração RC (PR #45) | **GO** (feito) |
| Build imagens + overlay pins | **GO** (feito; overlay **não** aplicado em produção até cutover) |
| Prep operacional host (load/VERIFY/pin) | **GO** (feito — evid `a6-species-hp-prep-20261108T134333Z`) |
| **Cutover / deploy** | **NO-GO** até GO operacional (disco 93%) |
| Migration/backfill de saves | **NO-GO** (ver `pokemon-a6-species-hp-migration-plan.md`) |
| Alterar HP de chefes de raid | **NO-GO** |

### Critério de conclusão

**HP REALISTA POR ESPÉCIE INTEGRADO NA RC E HOMOLOGADO** — fórmula com clamp mantido, Node→JVM canônico, imagens amd64 pinned, saves legados protegidos na leitura, produção intocada.
