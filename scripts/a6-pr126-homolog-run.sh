#!/usr/bin/env bash
# Homologação isolada PR #126 — sem produção/WhatsApp live.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
OUT="${A6_PR126_HOMOLOG_OUT:-/tmp/a6-pr126-homolog}"
mkdir -p "$OUT"
export A6_PR126_HOMOLOG_OUT="$OUT"
export CLJ_CONFIG="${CLJ_CONFIG:-$ROOT/.tools/clj-config}"
export GITLIBS="${GITLIBS:-$ROOT/.tools/gitlibs}"

echo "==> isolated core scenarios + egg/hotfix/hp/auto suites"
clojure -M -i scripts/a6-pr126-homolog-isolated.clj | tee "$OUT/core.log"

echo "==> raid defeat rewards (processo isolado)"
clojure -M -e "(require 'clojure.test 'pokemon.tests.raid-defeat-rewards-fase1283-test) (let [r (clojure.test/run-tests 'pokemon.tests.raid-defeat-rewards-fase1283-test)] (spit \"$OUT/raid-defeat.edn\" (pr-str r)) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))" \
  | tee "$OUT/raid-defeat.log"

echo "==> raid homolog fase4 auto/manual (processo isolado)"
clojure -M -e "(require 'clojure.test 'pokemon.tests.raid-homolog-fase4-test) (let [r (clojure.test/run-tests 'pokemon.tests.raid-homolog-fase4-test)] (spit \"$OUT/raid-homolog-fase4.edn\" (pr-str r)) (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))" \
  | tee "$OUT/raid-homolog-fase4.log"

echo "==> node hotfix contracts"
node --test test/pokemon-hotfix-pi-raid-isolation.test.cjs test/pokemon-hotfix-potion-http-contract.test.cjs \
  | tee "$OUT/node-hotfix.log"

echo "ALL_ISOLATED_HOMOLOG_OK"
