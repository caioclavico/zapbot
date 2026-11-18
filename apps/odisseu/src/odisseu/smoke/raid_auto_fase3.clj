(ns odisseu.smoke.raid-auto-fase3
  "Runner Fase 3 — raides automáticas."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-auto-fase3-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-auto-fase3-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid auto fase 3 summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
