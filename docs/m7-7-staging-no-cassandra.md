# M7.7 — Boot de staging sem Cassandra

**Status:** caminho de boot staging comprovado sem cliente Cassandra.  
**Base:** `8ea3b30`  
**Produção inalterada.** Cassandra **não** aposentado.

## 1. Boot auditado

| Caminho | Produção / legado | Staging `PLATFORM=datomic` + `POKEMON=jvm` |
|---|---|---|
| `armazenamento/iniciar!` | Client + CQL + migrate | **skip** — sem Client, sem connect, sem CQL |
| 8 chaves platform `registrar!` | Cassandra | desligado (`when-not platform/enabled?`) |
| `pokemon_http` entregas | `salvar-confirmado!` C* | confirmação **só em memória** |
| `pokemon.core` / loja / ginásios | Cassandra (se NS carregado) | **não carregados** pelo build `:app` (só `pokemon-http`) |
| Bridge JVM | opcional | obrigatória (fail-closed) |
| Health Node `:3001` | WhatsApp state | sem checagem Cassandra |
| Compose `cassandra` | produção/dev legado | não necessário neste modo |

Flag: `config/cassandra-boot-enabled?` = `(not (and platform-datomic? pokemon-jvm?))`.  
Produção força adapters legado → boot Cassandra permanece.

## 2. Dependências Cassandra removidas em staging

1. Instanciação de `cassandra-driver` Client (lazy; só se boot ativo).
2. `connect`, `CREATE KEYSPACE/TABLE`, scan `estado_particionado`.
3. Confirmação de entregas HTTP Pokémon via C* (vira memória).
4. Nenhum healthcheck Node exige C*.

## 3. Prova negativa

```bash
./scripts/test-m7-7-no-cassandra.sh
```

- `CASSANDRA_CONTACT_POINTS=203.0.113.1` (TEST-NET): se alguém conectar, falha.
- CLJS: `staging-datomic-jvm-nao-constroi-cliente-cassandra` — `cliente-construido?` inalterado após `iniciar!`.
- Smoke JVM: nenhuma API C*; bridge bind `127.0.0.1`.

## 4. Funcionalidades testadas vs bloqueadas

| Testado (sintético) | Bloqueado (WA autenticado) |
|---|---|
| M7.0–M7.6 regressões | Envio real de lembrete |
| 8 chaves platform HTTP | Caça/batalha/Joy via WhatsApp |
| Claim/complete lembrete (simulado) | Quiz/enquete/rank por mensagem |
| Treinador Pokémon JVM | Sessão Chromium staging |
| Fail-closed bridge down | — |

## 5. Memória

Smoke reporta `jvm-rss-mb-before/after` (processo Clojure). Node: medir com bot real em staging autenticado (fora deste marco).

## 6. Segurança

| Check | Resultado |
|---|---|
| `APP_ENV=production` força Cassandra/legacy | sim (`config.cljs`) |
| Sem dual-write / fallback C* em staging | sim |
| Single-writer Datomic Local | FileLock + 1 bridge |
| Isolation poke vs platform DB | DBs distintos |
| Bridge loopback | `127.0.0.1` |
| Timers sem WA real neste smoke | claim/complete só HTTP |

## 7. Bloqueios restantes (staging autenticado)

- Validar WhatsApp staging com `STAGING_WHATSAPP_CONFIRM` + sessão não-prod.
- Exercitar caça/batalha/Joy e poller de lembretes com menções reais.
- Confirmar RSS Node sob carga.

## 8. Riscos cutover produção

- Pokémon Node legado ainda depende de C* se `POKEMON_ADAPTER≠jvm`.
- Dump/import real das 8 chaves ainda não feito em prod.
- Retirar Compose Cassandra só após cutover + janela de rollback.

## Próximo

Staging autenticado (M7.8 sugerido) ou ensaio de export/import real offline — **não** cutover.
