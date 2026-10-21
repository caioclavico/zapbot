(ns odisseu.smoke.a41-explore
  "Runner A4.1: áreas/clima/encontros.
  Regressão M4.5 completa: `clojure -M:test-m4-hunt`."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.explore-a41-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (println "A4.1 explore/hunt @" (str (Instant/now)))
  (let [summary (test/run-tests 'pokemon.tests.explore-a41-test)
        fails (+ (:fail summary) (:error summary))]
    (pprint/pprint summary)
    (System/exit (if (zero? fails) 0 1))))
