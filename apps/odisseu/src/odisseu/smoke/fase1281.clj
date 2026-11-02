(ns odisseu.smoke.fase1281
  "Smoke Fase 12.8.1 — eventos temáticos (domínio + store descartável)."
  (:require [clojure.test :as test]
            [pokemon.tests.fase128-missions-candy-capture-test]
            [pokemon.tests.fase1281-thematic-events-test]
            [pokemon.tests.fase1281-thematic-store-test]))

(defn -main [& _]
  (let [r (test/run-tests
           'pokemon.tests.fase128-missions-candy-capture-test
           'pokemon.tests.fase1281-thematic-events-test
           'pokemon.tests.fase1281-thematic-store-test)]
    (System/exit (if (and (zero? (:fail r 0)) (zero? (:error r 0))) 0 1))))
