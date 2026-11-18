(ns odisseu.smoke.raid-save-slots-reconcile
  "Runner Fase 5.1 — interferência save-slots × raides."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.raid-save-slots-reconcile-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-save-slots-reconcile-test)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== raid×save-slots reconcile summary ===")
    (pprint/pprint {:ok ok? :summary summary :at (str (Instant/now))})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
