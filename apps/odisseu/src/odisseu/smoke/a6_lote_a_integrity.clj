(ns odisseu.smoke.a6-lote-a-integrity
  "Smoke Lote A — raids party/faint, Grande Bola, eventos/claim (Datomic descartável)."
  (:require [clojure.test :as test]
            [pokemon.tests.lote-a-integrity-a6-test]
            [pokemon.tests.raid-faint-continue-a6-test]
            [pokemon.tests.fase1281-thematic-store-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.raid-faint-continue-a6-test)
                   (test/run-tests 'pokemon.tests.lote-a-integrity-a6-test)
                   (test/run-tests 'pokemon.tests.fase1281-thematic-store-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
