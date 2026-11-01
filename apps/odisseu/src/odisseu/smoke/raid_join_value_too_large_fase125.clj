(ns odisseu.smoke.raid-join-value-too-large-fase125
  (:require [clojure.test :as test]
            [pokemon.tests.raid-join-value-too-large-fase125-test]))

(defn -main
  [& _]
  (let [summary (test/run-tests 'pokemon.tests.raid-join-value-too-large-fase125-test)]
    (println "=== raid join value-too-large 12.5 summary ===")
    (println summary)
    (System/exit (if (and (zero? (:fail summary)) (zero? (:error summary))) 0 1))))
