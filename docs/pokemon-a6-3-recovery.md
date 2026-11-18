# A6.3 — Recuperação seletiva V1/V2

Esta etapa adiciona descoberta e importação seletiva de snapshots históricos. O
Cassandra continua sendo somente leitura. Nenhum comando WhatsApp recebe
credenciais ou acesso direto ao Cassandra.

## Fluxo controlado

1. Um operador obtém uma cópia offline autorizada do dump Cassandra.
2. `scripts/a6-recovery-preview.clj` lista somente snapshots com `chat-id`,
   `player-id`, `snapshot-id` e versão explícita `v1`/`v2` coincidentes. O
   `sourceVersion` é metadado da versão, nunca a identidade do snapshot.
3. O jogador pode solicitar `!pk recuperar`, `!pk recuperar v1`, `!pk recuperar v2`
   ou `!pk recuperar ambos`. O adaptador apenas informa que a solicitação exige
   prévia/autorização administrativa; ele nunca afirma que importou um save.
4. Após conferência da prévia, um operador chama `pokemon.recovery/confirm!`
   com o token da prévia e os snapshots originais. A função transforma os
   snapshots e usa `pokemon.migration.import/import-selected-saves!`.

Os slots são determinísticos (`recovery-v1` e `recovery-v2`), não são ativados
automaticamente e não sobrescrevem slots existentes. O jogador continua com o
save ativo que já possuía. Cada operação usa recibo estável por lote, jogador,
versão e snapshot; repetir a mesma confirmação retorna `:duplicate`.

## Prévia offline

```bash
clojure -M -e '(load-file "scripts/a6_recovery_preview.clj")
 (a6-recovery-preview/-main
  "/path/to/authorized-offline-dump.edn" "<chat-id>" "<player-id>")'
```

O dump precisa conter `:meta {:authorized-offline-copy true}` (ou
`:offline-dump true`) e não pode conter marca de produção. O script não abre
conexões de rede e falha se a identidade não coincidir.

## Testes locais

```bash
clojure -M:test -e \
"(require 'clojure.test 'pokemon.tests.recovery-a63-test)
 (let [r (clojure.test/run-tests 'pokemon.tests.recovery-a63-test)]
   (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
```

As fixtures usam Datomic Local descartável e cobrem somente V1, somente V2,
ambos, nenhum snapshot, identidade de outro chat, versão incompatível,
confirmação inválida, importação seletiva e retry idempotente.

## Limitações antes da recuperação real

- Ainda é necessário um exportador operacional que produza a cópia offline
  autorizada a partir das leituras allowlisted do `save-slot-store.cjs`.
- Não há importação automática pelo WhatsApp. Solicitações devem ser registradas
  e executadas por processo administrativo autenticado.
- Dados incompletos ou sem versão explícita são omitidos da prévia e exigem
  análise manual; não são convertidos em save jogável.
- A cópia deve ser validada por checksums e mantida fora dos volumes oficiais.
