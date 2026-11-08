(ns pokemon.tests.moves-level-offer-a6-test
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.moves :as moves]))

(deftest marcos-cruzados-a-cada-5
  (is (= [5 10] (moves/marcos-cruzados 4 10)))
  (is (= [15] (moves/marcos-cruzados 10 15)))
  (is (= [] (moves/marcos-cruzados 5 9))))

(deftest oferta-por-nivel-idempotente
  (let [reg {:nome "Charmander" :tipos ["fire"] :nivel 4
             :golpes [{"slug" "scratch" "nome-exibicao" "Arranhao" "tipo" "normal"
                       "poder" 40 "classe" "fisico"}]
             :ofertas-golpes []}
        r1 (moves/aplicar-ofertas-por-nivel reg 4 5)
        r2 (moves/aplicar-ofertas-por-nivel r1 5 5)]
    (is (nil? (:resultado r1)) "unwrap :registro — sem wrapper")
    (is (= 5 (:nivel-oferta-verificado r1)))
    (is (= 2 (count (:golpes r1))) "Brasas básico do tipo auto-aprendido")
    (is (= (:golpes r1) (:golpes r2)))
    (is (= (:ofertas-golpes r1) (:ofertas-golpes r2)))))
