(ns odisseu.smoke.a42-pokedex
  "Runner A4.2: Pokédex / coleção / ficha / favorito."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.pokedex-a42-test])
  (:import [java.time Instant]))

(defn -main
  [& _]
  (println "A4.2 pokedex/collection @" (str (Instant/now)))
  (let [summary (test/run-tests 'pokemon.tests.pokedex-a42-test)
        fails (+ (:fail summary) (:error summary))]
    (pprint/pprint summary)
    (System/exit (if (zero? fails) 0 1))))
