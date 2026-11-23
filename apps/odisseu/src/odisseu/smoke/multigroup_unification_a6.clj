(ns odisseu.smoke.multigroup-unification-a6
  "Runner A6 — unificação multigrupo + suspensão/bloqueios."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.multigroup-unification-a6-test]
            [pokemon.tests.multigroup-block-suspend-a6-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (apply test/run-tests
                       '[pokemon.tests.multigroup-unification-a6-test
                         pokemon.tests.multigroup-block-suspend-a6-test])
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== multigroup unification a6 summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
