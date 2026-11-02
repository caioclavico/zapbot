(ns odisseu.smoke.raid-turn-gym-level-hotfix
  "Runner — 1º turno auto · sem nível mínimo · ginásio por tipo."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-turn-gym-level-hotfix-test]
            [pokemon.tests.raid-lobby-join-hotfix-test]
            [pokemon.tests.raid-m49-test]
            [pokemon.tests.raid-auto-readiness-fase127-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [suites ['pokemon.tests.raid-turn-gym-level-hotfix-test
                'pokemon.tests.raid-lobby-join-hotfix-test
                'pokemon.tests.raid-m49-test
                'pokemon.tests.raid-auto-readiness-fase127-test]
        results (mapv (fn [ns]
                        (println "=== suite" ns "===")
                        (test/run-tests ns))
                      suites)
        fail (reduce + (map #(or (:fail %) 0) results))
        err (reduce + (map #(or (:error %) 0) results))
        ok? (and (zero? fail) (zero? err))]
    (println "=== raid turn/gym/level hotfix summary ===")
    (pprint/pprint {:ok ok? :fail fail :error err :suites (count suites)
                    :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
