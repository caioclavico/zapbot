(ns odisseu.smoke.a6-lote-c-present
  "Smoke Lote C — last-action canônico + regressões raid/explosion."
  (:require [clojure.test :as test]
            [pokemon.tests.lote-c-present-a6-test]
            [pokemon.tests.explosion-autodesmaio-a6-test]
            [pokemon.tests.raid-faint-continue-a6-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.lote-c-present-a6-test)
                   (test/run-tests 'pokemon.tests.explosion-autodesmaio-a6-test)
                   (test/run-tests 'pokemon.tests.raid-faint-continue-a6-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
