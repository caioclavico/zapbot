(ns odisseu.smoke.a6-homolog-abc
  "Homologação integrada A+B+C — Datomic descartável + regressões dos lotes."
  (:require [clojure.test :as test]
            [pokemon.tests.homolog-abc-integrated-a6-test]
            [pokemon.tests.lote-a-integrity-a6-test]
            [pokemon.tests.lote-b-mechanics-a6-test]
            [pokemon.tests.lote-c-present-a6-test]
            [pokemon.tests.fase1281-thematic-store-test]
            [pokemon.tests.raid-faint-continue-a6-test]
            [pokemon.tests.raid-one-mon-a6-test]
            [pokemon.tests.raid-balance-one-mon-sim-a6-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.homolog-abc-integrated-a6-test)
                   (test/run-tests 'pokemon.tests.lote-a-integrity-a6-test)
                   (test/run-tests 'pokemon.tests.lote-b-mechanics-a6-test)
                   (test/run-tests 'pokemon.tests.lote-c-present-a6-test)
                   (test/run-tests 'pokemon.tests.raid-faint-continue-a6-test)
                   (test/run-tests 'pokemon.tests.raid-one-mon-a6-test)
                   (test/run-tests 'pokemon.tests.raid-balance-one-mon-sim-a6-test)
                   (test/run-tests 'pokemon.tests.fase1281-thematic-store-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))
        total-pass (reduce + (map :pass summaries))
        total-tests (reduce + (map :test summaries))]
    (println {:ok (zero? failed)
              :tests total-tests
              :pass total-pass
              :fail failed
              :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
