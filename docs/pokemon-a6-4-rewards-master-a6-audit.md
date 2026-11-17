# Auditoria: recompensas moedas + XP (PE) — master × A6

**Escopo:** Node → JVM → Datomic (A6). Sem deploy, migration em produção ou backfill sem GO explícito.  
**Fontes master:** `pokemon-service/src/zapbot/pokemon/{core,loja,treinador,ajuda}.cljs`  
**Fontes A6:** `modules/pokemon/domain/**`, stores Datomic, bridge.

---

## 1. Matriz master × A6

| Atividade | Master (valor / fórmula) | A6 JVM atual | Diff |
|---|---|---|---|
| Vitória selvagem → captura OK | Pokémon XP = `xp-base-raridade + bonus-seq + bonus-1ª`; moedas = `2 + min(10, seq)` | Idem em `capture/recompensa-pos-captura` + store captura | **OK** |
| Vitória selvagem, bolsa cheia / só batalha | Pokémon XP = raridade + **1**; moedas **0**; seq **não** sobe | Finaliza batalha (`selvagem-liberado-bolsa-cheia?`) mas `reward-for-outcome :win` → **XP 0** | **GAP** |
| Captura falhou / fuga pós-vitória | Pokémon XP **1**; moedas 0; quebra seq | Idem | **OK** |
| Derrota / fuga em combate selvagem | Pokémon XP **1**; moedas 0 | Idem | **OK** |
| Idle-timeout / `!pk sair` | Sem XP/moedas | Idem | **OK** |
| Vitória PvP | **10** moedas; XP Pokémon = `1 + 2*min(3, kos)` | Idem (`pvp/moedas-por-vitoria`, `xp-por-nocautes`) | **OK** |
| Derrota / desistência PvP | Sem moedas; rank −1 na desistência | Idem (sem moedas) | **OK** |
| Ginásio 1ª vitória do dia (insígnia nova) | 100 moedas, **5** PE, pedra garantida; XP base 7 + kos | Idem (`gym/recompensa-vitoria`, `xp-ginasio-participante`) | **OK** |
| Ginásio revanche premiada (outro dia) | 25 moedas, **2** PE, pedra 25%; XP base 4 + kos | Idem | **OK** |
| Ginásio vitória já premiada no dia | Sem moedas/PE/pedra; XP base 2 + kos | Idem (`premio` nil) | **OK** |
| Ginásio derrota | **1** PE; XP part. 3 + kos | Idem | **OK** |
| Permanência >6h (ao ser derrubado) | **50** moedas 1× | Idem | **OK** |
| Raid vitória (dano) | **50** moedas, **6** PE, **6** XP Pokémon | Idem (`raid/recompensa-*`) | **OK** |
| Raid captura / participação sem vitória | Sem esse pacote (captura é fluxo separado) | Idem | **OK** |
| Evolução | Sem moedas/PE de evolução (só mecânica) | Idem | **OK** |
| Missões semanais (claim) | Moedas + bolas + catalisador + PE missões | `mission_catalog` / `mission_rewards` alinhados | **OK** (itens/moedas) |
| Conquistas / títulos (insígnias PE) | PE **derivado** dos marcos (3/6/12/24…) | `:save/xp-insignias` importado; **não recalcula** em gameplay | **GAP** |
| Bônus diário mochila | 3–7 bolas (+ fruta a cada 7 dias); **sem moedas** | Idem `bag_rewards` | **OK** |
| Kit inicial | 10 pokébolas | Idem | **OK** |
| Moedas selvagem (só vitória sem captura) | 0 | 0 | **OK** |
| Nível treinador (curva) | Custos 5,7,9,11…; PE = vitórias + insignias + missões + pe-gin + pe-raid | Idem `xp/progresso-treinador` | **OK** (se insignias corretas) |
| XP Pokémon / nível | 9 XP/nível, fator 1.03, max 100 | Idem | **OK** |
| Capacidade por nível | Marcos pokemon/mochila | Idem `capacity` / `shop` | **OK** |

### `xp-base-raridade` (master = A6)

| Raridade | XP base |
|---|---|
| comum | 2 |
| incomum | 3 |
| raro | 4 |
| epico | 5 |
| lendario | 6 |
| mitico | 7 |

### Bônus sequência captura (XP)

`min(2, max(0, sequencia-1))` → 1ª captura +0, 2ª +1, 3ª+ +2.  
Primeira tentativa bem-sucedida: **+1** XP extra.

### Insígnias PE (master `catalogo-insignias`)

| Marco | Métrica | PE |
|---|---|---|
| Doar 1 / 10 / 50 / 100 | doações | 3 / 6 / 12 / 24 |
| Vitórias 1 / 10 / 50 / 100 | vitórias treinador | 3 / 6 / 12 / 24 |
| Seq. captura 3 / 5 / 10 / 20 | maior sequência | 3 / 6 / 12 / 24 |

Soma = `xp-insignias` (derivada; não incrementa em escrita separada).

---

## 2. Economia histórica (`unknown-pending`)

### Estado atual

- Saves `recovery-*` / `origin=offline-import` / `legacy-version=unknown` → `economia-confiavel?` = false.
- Perfil: `coins-status = "unknown-pending"`; UI não trata `:save/coins` (muitas vezes 0 placeholder) como saldo confirmado.
- Loja: **só expansões** bloqueadas; itens normais ainda debitam `:save/coins`.
- Grants (PvP/ginásio/raid/captura/missões) CAS em `:save/coins` — misturam operacional com placeholder se o save for recovery.

### Separação exigida (ainda **não** modelada)

| Conceito | Representação proposta |
|---|---|
| Histórico não reconciliado | `:save/economy-status` = `:unknown-pending`; **não** somar em saldo gastável |
| Moedas novas pós-migração | `:save/coins-operational` (créditos A6) |
| Gastos A6 | debitam só `coins-operational` |
| Saldo comprovado gastável | `coins-operational` (e, se reconciliado, opcionalmente histórico liberado) |
| V1 / V2 / operacional | **nunca** auto-somar |

**Bloqueio:** compras sobre histórico inconclusivo. Operacional pós-migração pode ser gastável **após** schema + wiring (GO).

### Migration proposta (não aplicar sem GO)

Arquivo: `migrations/pokemon/028-economy-ledger.edn`

- `:save/economy-status` (keyword/string): `trusted` \| `unknown-pending` \| `reconciled`
- `:save/coins-operational` (long): ganhos−gastos A6 comprovados
- `:save/coins-historical-status` (string): `unknown` \| `declared` \| `reconciled` \| `rejected`
- `:save/coins-historical-amount` (long, opcional): só após reconciliação manual

Backfill (só com GO): trusted → `coins-operational = :save/coins`, status `trusted`; recovery → operacional 0, status `unknown-pending`, **não** zerar nem “corrigir” histórico.

Até lá: bloquear **toda** compra em economia não confiável; **não** adicionar novas fontes de moedas em recovery sem ledger.

---

## 3. Gaps priorizados (implementação local)

| # | Gap | Ação | Risco economia |
|---|---|---|---|
| G1 | Vitória só-batalha / bolsa cheia sem XP Pokémon | Restaurar fórmula master (raridade+1) | Só XP Pokémon — **seguro** |
| G2 | `xp-insignias` estático | Derivar dos marcos na leitura (como master) | Só PE — **seguro**; não reescreve histórico arbitrário |
| G3 | Loja gasta em unknown-pending | Bloquear qualquer compra se `!economia-confiavel?` | Proteção — **seguro** |
| G4 | Ledger operacional vs histórico | Schema 028 + wiring completo (local) | **Aguarda GO** só para migrate/deploy produção |

---

## 4. Proposta de arquitetura

```
[ação Node] → [application/bridge] → [domain puro: capture|gym|pvp|raid|missions|xp|economy]
                                      → [store: receipt + CAS fence + Datomic]
                                      → [Node apresenta resultado]
```

- Cálculo só no domínio Clojure.
- Persistência moedas/XP só Datomic (receipts + payload-hash + CAS).
- Node não recalcula quantidades.
- Idempotência: mesmo `operation-id` / receipt → sem segundo crédito.

---

## 5. Diff desta entrega (local, sem deploy)

| Item | Status |
|---|---|
| Este doc (matriz + proposta) | Feito |
| `capture/recompensa-vitoria-sem-captura` + turn bolsa cheia | Feito |
| `xp/xp-insignias-from-metrics` em `->save` | Feito |
| Loja bloqueia **qualquer** compra se `!economia-confiavel?` | Feito |
| `migrations/pokemon/028-economy-ledger.edn` | Final — ensaios locais OK; **não aplicar em prod sem GO** |
| `pokemon.domain.economy` + `economy-store` + wiring créditos/débitos | Feito |
| Testes ledger + shop + hunt + capture + capacity | Feito (unit OK) |
| Doc ledger | `docs/pokemon-a6-4-economy-ledger.md` |

**Aguarda GO:** deploy Core, migrate 028 em produção, backfill opcional trusted, Cassandra/aliases/grupos.

---

## 6. Plano de deploy e rollback (após GO)

1. Merge + imagem JVM+Node com tag `a64-rewards-…`.
2. **Só com GO:** aplicar migration 028 no Datomic Local; smoke 1 save trusted + 1 recovery.
3. Wiring: credits → `coins-operational`; shop lê saldo operacional; recovery sem operacional continua bloqueado para histórico.
4. Rollback: reverter imagem; attrs 028 são aditivos (não apagar dados); não reverter migration sem plano separado.
5. Sem backfill automático de moedas V1/V2.

---

## 7. Testes (cobertura alvo)

- Captura OK / falha / vitória sem captura (raridades).
- Sem reward em idle / abandon / ação inválida.
- Idempotência receipt (já existente nos stores — regressão).
- Economia unknown: compra bloqueada; coins-status.
- Troca save / fence / recovery-v1.
- Level-up treinador com insignias derivadas.
- Reinício JVM: estado só Datomic (sem side-effect em memória).
