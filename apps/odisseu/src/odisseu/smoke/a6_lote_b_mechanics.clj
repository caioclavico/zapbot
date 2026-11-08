(ns odisseu.smoke.a6-lote-b-mechanics
  "Smoke Lote B — dano/efetividade, Explosion, learn@5, Frambo, raridade."
  (:require [clojure.test :as test]
            [pokemon.tests.lote-b-mechanics-a6-test]
            [pokemon.tests.capture-rarity-frambo-a6-test]
            [pokemon.tests.explosion-autodesmaio-a6-test]
            [pokemon.tests.moves-audit-a6-test]
            [pokemon.tests.event-weight-sim-a6-test]
            [pokemon.tests.moves-level-offer-a6-test]
            [pokemon.tests.raid-faint-continue-a6-test]
            [pokemon.tests.raid-gym-capture-p0-a6-test]
            [pokemon.tests.raid-hp-sync-p0-a6-test]
            [pokemon.tests.raid-lifecycle-balance-a6-test]
            [pokemon.tests.raid-balance-sim-a6-test]
            [pokemon.tests.raid-balance-one-mon-sim-a6-test]
            [pokemon.tests.capture-rarity-flow-a6-test]))

(defn -main [& _]
  (let [summaries [(test/run-tests 'pokemon.tests.lote-b-mechanics-a6-test)
                   (test/run-tests 'pokemon.tests.capture-rarity-frambo-a6-test)
                   (test/run-tests 'pokemon.tests.explosion-autodesmaio-a6-test)
                   (test/run-tests 'pokemon.tests.moves-audit-a6-test)
                   (test/run-tests 'pokemon.tests.event-weight-sim-a6-test)
                   (test/run-tests 'pokemon.tests.moves-level-offer-a6-test)
                   (test/run-tests 'pokemon.tests.raid-faint-continue-a6-test)
                   (test/run-tests 'pokemon.tests.raid-gym-capture-p0-a6-test)
                   (test/run-tests 'pokemon.tests.raid-hp-sync-p0-a6-test)
                   (test/run-tests 'pokemon.tests.raid-lifecycle-balance-a6-test)
                   (test/run-tests 'pokemon.tests.raid-balance-sim-a6-test)
                   (test/run-tests 'pokemon.tests.raid-balance-one-mon-sim-a6-test)
                   (test/run-tests 'pokemon.tests.capture-rarity-flow-a6-test)]
        failed (reduce + (map #(+ (:fail %) (:error %)) summaries))]
    (println {:ok (zero? failed) :summaries summaries})
    (System/exit (if (pos? failed) 1 0))))
