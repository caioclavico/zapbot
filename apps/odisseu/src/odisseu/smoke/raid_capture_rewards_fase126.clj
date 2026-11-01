(ns odisseu.smoke.raid-capture-rewards-fase126
  (:require [clojure.test :as test]
            [pokemon.tests.raid-capture-rewards-fase126-test]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-capture-rewards-fase126-test)]
    (println "=== raid capture rewards 12.6 summary ===")
    (println summary)
    (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1))))
