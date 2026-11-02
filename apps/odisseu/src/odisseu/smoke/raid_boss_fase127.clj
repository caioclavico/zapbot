(ns odisseu.smoke.raid-boss-fase127
  "Runner Fase 12.7 — catálogo dinâmico + readiness auto-raides."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-boss-fase127-test]
            [pokemon.tests.raid-auto-readiness-fase127-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [s1 (test/run-tests 'pokemon.tests.raid-boss-fase127-test)
        s2 (test/run-tests 'pokemon.tests.raid-auto-readiness-fase127-test)
        merge-sum (merge-with +
                              (select-keys s1 [:test :pass :fail :error])
                              (select-keys s2 [:test :pass :fail :error]))
        ok? (and (zero? (:fail merge-sum)) (zero? (:error merge-sum)))]
    (println "=== raid boss / readiness fase 12.7 summary ===")
    (pprint/pprint {:ok ok?
                    :boss s1
                    :readiness s2
                    :combined merge-sum
                    :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
