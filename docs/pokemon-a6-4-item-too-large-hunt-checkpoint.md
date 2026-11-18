# A6.4 — Checkpoint: falso `WRITER_FENCE_CONFLICT` em `!pk caçar`

**Data:** 2026-10-29  
**Escopo:** diagnóstico RO + correção local. **Sem deploy / sem escrita em produção.**

## Causa raiz (comprovada)

Não é corrida de fence nem CAS de revision falhando.

1. `start-hunt!` embute o Pokémon ativo completo (incl. `historico-golpes`) em `battle/state-edn` via `(pr-str state)`.
2. No ativo recovery-v1 do grupo *boot jogos*, o snapshot de batalha fica **~4351 B** (JSON aproximado; EDN equivalente).
3. Datomic Local rejeita valor de atributo com **`Item too large`** (~4096).
4. `transact-with-writer!` só faz retry se `conflict?`; `"Item too large"` **não** casa com o regex → `attempt: 0`.
5. Com fence ainda válido, o catch final relança **`:writer_fence_conflict`** — rótulo mentiroso.

### Evidência produção (RO)

| Campo | Valor |
|-------|--------|
| Deploy | `a64-fence-fix-20261029T1315-amd64` (JVM+Node) |
| Save / fence | `recovery-v1` / `2` |
| Coleção | 119 |
| HTTP | **409** `writer_fence_conflict` |
| `cause` | **`Item too large`** |
| `attempt` | **0** (nenhum retry CAS) |
| Batalha ativa | `null` (tx atômica abortou — sem encontro parcial) |
| Cooldown | `cooldownOk: true`, `cooldownRestanteS: 0` (última caçada **não** gravada) |
| Receipt `hunt-start` | não persistido (mesma TX) |

CAS de fence/revision **não chegou a falhar por concorrência** — a TX inteira morreu no tamanho de `battle/state-edn`.

## Correção (diff mínimo)

1. `pokemon.domain.battle/slim-pokemon-for-battle` — remove `historico-golpes`, ofertas/removidos, ivs, legado, origin (mantém `imagem*` para UI).
2. `create-wild-battle` e `encode-state-edn` — slim antes de persistir; guarda se ainda passar de 4000.
3. Todos os writers de `:battle/state-edn` usam `encode-state-edn`.
4. `transact-with-writer!` classifica `Item too large` como `:datomic_value_too_large` (HTTP 500), **sem** retries CAS.

Coleção / 119 Pokémon / fence / cooldowns / saves: **intocados**.

## Testes

- `battle-state-edn-slim-evita-item-too-large` — raw >4000, encode ≤4000; `start-hunt!` recovery-v1 + historico gordo OK.
- Classificação: `Item too large` → `:datomic_value_too_large`, 1 tentativa.
- Suite unit `hunt-m45` + `battle-idle` + `battle-leave`: verde.
- Smoke HTTP M4.5: `area_unavailable` (praia fora da rotação do dia) — pré-existente, fora do escopo.

## Plano de deploy (aguardando GO)

1. Tag imagens JVM (+ Node só se necessário; fix é JVM).
2. Cold backup Datomic + restore-check (mesmo playbook fence-fix).
3. Deploy JVM; health + `GET /v1/hunt/plan` (cooldownOk).
4. Usuário real: **um** `!pk caçar` no *boot jogos* — esperar 200, batalha ativa, sem `Item too large`.
5. RO: `activeBattleId` set, `ultima-cacada` avançou, receipt `hunt-start` presente, fence inalterado.
6. Rollback: imagem anterior `a64-fence-fix-20261029T1315-amd64`.

**Não executar sem nova autorização.**
