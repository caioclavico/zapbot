(ns odisseu.smoke.a3-pvp
  "Runner A3: PvP 1×1 (desafio/aceitação/turnos/idempotência)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.pvp-a3-test]
            [pokemon.tests.battle-m34-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (println "A3 PvP @" (str (Instant/now)))
  (let [summaries [(test/run-tests 'pokemon.tests.pvp-a3-test)
                   ;; Regressão: motor de dano/status M3.4.
                   (test/run-tests 'pokemon.tests.battle-m34-test)]
        fails (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (pprint/pprint summaries)
    (System/exit (if (zero? fails) 0 1))))
