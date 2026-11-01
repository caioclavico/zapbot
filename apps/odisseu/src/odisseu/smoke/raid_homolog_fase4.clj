(ns odisseu.smoke.raid-homolog-fase4
  "Runner Fase 4 — homologação A6 (fixtures sintéticas / Datomic local)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-homolog-fase4-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-homolog-fase4-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid homolog fase 4 summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))
                    :java (System/getProperty "java.version")
                    :isolation "datomic-local:.datomic-local/raid-homolog-f4"
                    :staging-touched? false})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
