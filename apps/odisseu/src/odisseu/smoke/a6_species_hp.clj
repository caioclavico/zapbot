(ns odisseu.smoke.a6-species-hp
  "Smoke species-hp-v1 + simulações de catálogo."
  (:require [clojure.test :as test]
            [pokemon.tests.species-hp-a6-test]
            [pokemon.tests.species-hp-sim-a6-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.species-hp-a6-test)
                   (test/run-tests 'pokemon.tests.species-hp-sim-a6-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))
        total-pass (reduce + (map :pass summaries))
        total-tests (reduce + (map :test summaries))]
    (println {:ok (zero? failed)
              :tests total-tests
              :pass total-pass
              :fail failed
              :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
