(ns odisseu.smoke.raid-fase12
  (:require [clojure.test :as test]
            [pokemon.tests.raid-fase12-test]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-fase12-test)]
    (println "=== raid fase 12 summary ===")
    (println summary)
    (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1))))
