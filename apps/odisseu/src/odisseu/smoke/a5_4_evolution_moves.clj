(ns odisseu.smoke.a5-4-evolution-moves
  (:require [clojure.test :as test]
            [pokemon.tests.evolution-moves-a54-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.evolution-moves-a54-test)]
        failed (reduce + (map #( + (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries})
    (when (pos? failed) (System/exit 1))))
