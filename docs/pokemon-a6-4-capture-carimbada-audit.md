# Auditoria — captura selvagem · “chance de carimbada” (master × A6)

**Quando:** 2026-10-29  
**Status:** diagnóstico + patch · **GO deploy concluído** → `docs/pokemon-a6-4-capture-ux-deploy-checkpoint.md`  
**Arquitetura:** Node → JVM → Datomic (preservada)

---

## 1. Comparação master × A6

| Aspecto | Master (`src/zapbot/pokemon/core.cljs`) | A6 antes do patch | A6 após o patch |
|---------|----------------------------------------|-------------------|-----------------|
| Após KO | `preparar-captura-pos-batalha` → **menu de bolas** imediato | Vitória + texto `Chance-base carimbada: N%` + `!pk capturar` | Vitória + **menu de bolas** (paridade master) |
| Rolagem pré-bola | Nenhuma | Nenhuma (só carimbo determinístico) | Igual |
| Rolagem na bola | `rand-int 100 < chance` | Idem no JVM (`attempt-capture`) | Igual |
| Fórmula espécie / base / bola | Ver §3 | Paridade comprovada (`legacy_capture.edn`) | Igual |
| Persistência | Cassandra JSON | Datomic TX + recibo | Igual |
| Quem decide | Core CLJS | Domínio JVM | Domínio JVM |

**Conclusão de fórmula:** não há captura garantida por bug de HP=0 nem segunda roleta. O teto efetivo da Pokébola é **75%** (mesmo no master), o que explica sucessos frequentes em Magikarp/comuns com sequência.

---

## 2. O que é “chance de carimbada”

**Carimbada = valor congelado no encontro**, não uma loteria.

1. No KO, o JVM calcula uma vez `chance-base-pos-batalha` e grava `:chance-base-captura` no estado da batalha.
2. Cada `POST /v1/encounter/capture` reutiliza esse valor e aplica o multiplicador da bola.
3. A frase *"Chance-base carimbada"* foi rótulo de UI introduzido no present M4.5 (`ef92857`), **ausente no master**.

Introdução da string: `scripts/lib/pokemon-jvm-present.cjs` (formatBattleTurn).  
Stamp no domínio: `store.clj` (turno com `:aguardando-captura?`) + `pokemon.domain.capture/chance-base-pos-batalha`.

---

## 3. Fórmulas (idênticas master ↔ JVM)

```
chance_especie = clamp(round((70 - 0.15*(Σstats - 450)) * R[raridade]), 15, 90)

chance_base    = min(95, round(S[status] * chance_especie) + 25 + min(20, 2*seq))
                 // gravada uma vez no KO ("carimbo")

chance_bola    = min(L[ball], round(chance_base * M[ball]))
                 // pokébola ×1 cap75 · grande ×1.5 cap88 · ultra ×2 cap95

sucesso        = (roll ∈ [0,99]) < chance_bola     // única rolagem de captura
fuga_se_falha  = tentativas≥3 OR (fuga_roll < F[raridade])
```

O **+25** pós-batalha é regra de negócio do legado (não é HP restante estilo games oficiais).  
`poder-total` usa o atributo `:hp` máximo da espécie — HP de batalha 0 **não** infla a chance.

### Exemplos (fixtures)

| Caso | base | bola | chance efetiva |
|------|------|------|----------------|
| Magikarp comum seq0 | 95 | pokébola | **75** (cap) |
| Onix raro paralisado seq3 | 95 | ultra | **95** |
| Mewtwo mítico | 40 | grande | **60** |

Sucesso forçado: `roll=0`. Falha forçada: `roll=99` (e `fuga-roll=99` para permanecer no encontro).

---

## 4. Causa raiz da regressão percebida

1. **UX:** A6 mostrou a chance-base bruta com o jargão “carimbada” e exigiu um comando extra `!pk capturar` antes do menu — parecia etapa nova.
2. **Expectativa vs cap:** jogador via “95% carimbada” mas a Pokébola aplica cap 75% — sensação de inconsistência.
3. **Não era bug de RNG:** frequência alta em commons é compatível com 75% (≈3/4 das tentativas).

---

## 5. Diff da correção (local)

| Arquivo | Mudança |
|---------|---------|
| `scripts/lib/pokemon-jvm-present.cjs` | Vitória → menu de bolas direto; remove “carimbada” / “Agora você pode capturar” |
| `modules/.../bridge_tx.clj` | Turno com `aguardando-captura` inclui `inventory` + `tentativasCaptura` + `somenteBatalha` |
| Testes Node | battle-ui / capture / hunt — assert sem `carimbada`, menu presente |
| `capture_m33_test.clj` | carimbo sem RNG; caps/bolas; HP0 neutro; simulação 0..99 = chance publicada |

Nenhuma alteração nas fórmulas de `domain/capture.clj`.  
Sem migrations / sem mutação em saves históricos.

---

## 6. Testes

```bash
# Domínio + encontro
clojure -M:test -n pokemon.tests.capture-m33-test
clojure -M:test -n pokemon.tests.encounter-m44-test

# Present Node
node --test test/pokemon-jvm-capture.test.cjs test/pokemon-jvm-battle-ui.test.cjs test/pokemon-jvm-hunt.test.cjs
```

Cobertura nova: carimbo ≠ rolagem; falha com roll 99; fuga; caps 75/88/95; frequência exata em 100 rolls determinísticos; menu pós-KO sem “carimbada”; idempotência já em m44.

---

## 7. Plano de deploy seguro (aguardando GO)

1. Reconfirmar testes acima.  
2. Cold backup `a56-*` (padrão A6).  
3. Rebuild **somente** JVM + Node (tag `a64-capture-ux-…`).  
4. Compose sem `-v`; **sem** migrations / import.  
5. Smoke RO: health; caça cooldown 1800; idle 300; `ALLOW_ALL_GROUPS`.  
6. Smoke funcional em save descartável (slot `a`/`b` vazio) — **não** em recovery-v1 sem GO.  
7. Validar: KO → menu de bolas; falha com roll injetado em lab; 1 bola consumida; sem texto “carimbada”.

**Rollback:** imagens anteriores + compose bak. Sem rollback destrutivo automático.
