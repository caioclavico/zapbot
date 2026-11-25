(ns odisseu.smoke.raid-counter-selection-a6
  "Runner CI — seleção inteligente `raid entrar auto` (A6)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-counter-selection-a6-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-counter-selection-a6-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid counter selection a6 ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))