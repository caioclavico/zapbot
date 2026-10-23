#!/usr/bin/env bash
set -Eeuo pipefail

# A5.5.5d: executado no host Mac (ou em um contêiner com loopback permitido).
# Todos os testes usam seus próprios Datomic Local temporários; este runner não
# monta volumes, acessa VMs nem remove diretórios fora de sua área temporária.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT"

command -v node >/dev/null || { echo "node ausente" >&2; exit 2; }
command -v clojure >/dev/null || { echo "clojure CLI ausente" >&2; exit 2; }
java -version
node --version
clojure --version

if [[ "${RUN_DOCKER_CHECK:-1}" == "1" ]]; then
  if command -v docker >/dev/null && docker info >/dev/null 2>&1; then
    echo "Docker disponível: $(docker version --format '{{.Server.Version}}')"
  else
    echo "Docker indisponível; executando diretamente no host" >&2
  fi
fi

export CLOJURE_BIN="${CLOJURE_BIN:-$(command -v clojure)}"

run() {
  echo
  echo "+ $*"
  "$@"
}

run node --check scripts/test-pokemon-trade-e2e.cjs
run npx shadow-cljs compile test
run node target/test.js
run node --test test/pokemon-jvm-present.test.cjs
run node --test test/pokemon-jvm-commands.test.cjs
run clojure -M:test -e "(require 'clojure.test 'pokemon.tests.gym-history-a555b-test) (let [r (clojure.test/run-tests 'pokemon.tests.gym-history-a555b-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
run clojure -M:test -e "(require 'clojure.test 'pokemon.tests.gym-history-http-a555b-test) (let [r (clojure.test/run-tests 'pokemon.tests.gym-history-http-a555b-test)] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"
run clojure -M:test-m4-gym-battle
run npm run test:m4-jvm
run npm run test:trade-e2e

echo
echo "A555D_LOCAL_OK"
