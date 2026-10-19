(ns odisseu.smoke.m5-53-gates
  "Gates M5.3B + M5.5 Joy beds (fixtures) — antes do import real descartável."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.migration-m50-test]
            [pokemon.tests.migration-m51-test]
            [pokemon.tests.migration-m53-test]
            [pokemon.tests.migration-m55-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [s50 (test/run-tests 'pokemon.tests.migration-m50-test)
        s51 (test/run-tests 'pokemon.tests.migration-m51-test)
        s53 (test/run-tests 'pokemon.tests.migration-m53-test)
        s55 (test/run-tests 'pokemon.tests.migration-m55-test)
        ok? (every? (fn [s] (and (zero? (:fail s)) (zero? (:error s))))
                    [s50 s51 s53 s55])]
    (println "=== M5.3B/M5.5 gates summary ===")
    (pprint/pprint {:ok ok?
                    :m50 s50
                    :m51 s51
                    :m53 s53
                    :m55 s55
                    :at (str (Instant/now))
                    :nota "Import real só se ok=true; storage m53/m55-tests é sintético."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
