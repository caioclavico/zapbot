(ns odisseu.smoke.fase128
  "Runner Fase 12.8 — missões/doces/captura/clima/eventos (domínio)."
  (:require [clojure.test :as test]
            [pokemon.tests.fase128-missions-candy-capture-test]
            [pokemon.tests.missions-a431-test]
            [pokemon.tests.profile-a551-test]
            [pokemon.tests.capture-m33-test]))

(defn -main
  [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.fase128-missions-candy-capture-test)
                   (test/run-tests 'pokemon.tests.missions-a431-test)
                   (test/run-tests 'pokemon.tests.profile-a551-test)
                   (test/run-tests 'pokemon.tests.capture-m33-test)]
        fails (reduce + 0 (map :fail summaries))
        errors (reduce + 0 (map :error summaries))]
    (println {:fails fails :errors errors})
    (System/exit (if (and (zero? fails) (zero? errors)) 0 1))))
