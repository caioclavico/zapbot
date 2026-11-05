(ns odisseu.smoke.a6-tm-xp-evolution
  "Smoke A6 — XP/evolução por nível no time + CSV MT."
  (:require [clojure.test :as test]
            [pokemon.tests.level-evolution-a6-test]
            [pokemon.tests.xp-idempotency-a6-test]
            [pokemon.tests.evolution-moves-a54-test]
            [pokemon.tests.xp-equivalence-test]))

(defn -main [& _]
  ;; team-a552b tem falha preexistente (liga bronze/ineligible) fora do escopo TM/XP.
  (let [summaries [(test/run-tests 'pokemon.tests.level-evolution-a6-test)
                   (test/run-tests 'pokemon.tests.xp-idempotency-a6-test)
                   (test/run-tests 'pokemon.tests.evolution-moves-a54-test)
                   (test/run-tests 'pokemon.tests.xp-equivalence-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries
              :nota "team-a552b CSV assertions cobertas em level-evolution-a6-test"})
    (System/exit (if (pos? failed) 1 0))))
