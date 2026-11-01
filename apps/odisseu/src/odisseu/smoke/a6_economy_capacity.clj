(ns odisseu.smoke.a6-economy-capacity
  "Runner A6 — economy ledger + capacity storage (save-slots)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.economy-ledger-test]
            [pokemon.tests.capacity-storage-test]
            [pokemon.tests.battle-idle-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [s1 (test/run-tests 'pokemon.tests.economy-ledger-test)
        s2 (test/run-tests 'pokemon.tests.capacity-storage-test)
        s3 (test/run-tests 'pokemon.tests.battle-idle-test)
        ok? (every? #(and (zero? (:fail %)) (zero? (:error %))) [s1 s2 s3])]
    (println "=== a6 economy/capacity/idle summary ===")
    (pprint/pprint {:ok ok? :economy s1 :capacity s2 :battle-idle s3
                    :at (str (Instant/now))})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
