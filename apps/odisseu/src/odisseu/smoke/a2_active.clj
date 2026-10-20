(ns odisseu.smoke.a2-active
  "Runner A2: Pokémon ativo, evolução e golpes."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.active-a2-test]
            [pokemon.tests.capture-m33-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (println "A2 active/evolve/moves @" (str (Instant/now)))
  (let [summaries [(test/run-tests 'pokemon.tests.active-a2-test)
                   ;; Regressão: evolução/golpes M3.3 não quebraram.
                   (test/run-tests 'pokemon.tests.capture-m33-test)]
        fails (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (pprint/pprint summaries)
    (System/exit (if (zero? fails) 0 1))))
