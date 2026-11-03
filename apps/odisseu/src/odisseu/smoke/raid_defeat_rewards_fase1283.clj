(ns odisseu.smoke.raid-defeat-rewards-fase1283
  (:require [clojure.test :as test]
            [pokemon.tests.raid-defeat-rewards-fase1283-test]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-defeat-rewards-fase1283-test)]
    (println "=== raid defeat rewards 12.8.3 summary ===")
    (println summary)
    (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1))))
