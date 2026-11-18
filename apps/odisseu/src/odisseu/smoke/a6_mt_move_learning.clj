(ns odisseu.smoke.a6-mt-move-learning
  "Smoke A6 — Máquinas Técnicas (learn-move + consumo atômico)."
  (:require [clojure.test :as test]
            [pokemon.tests.mt-a6-test]
            [pokemon.tests.shop-m411-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.mt-a6-test)
                   (test/run-tests 'pokemon.tests.shop-m411-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
