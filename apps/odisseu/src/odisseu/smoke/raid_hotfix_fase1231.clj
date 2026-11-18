(ns odisseu.smoke.raid-hotfix-fase1231
  (:require [clojure.test :as test]
            [pokemon.tests.raid-hotfix-fase1231-test]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-hotfix-fase1231-test)]
    (println "=== raid hotfix 12.3.1 summary ===")
    (println summary)
    (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1))))
