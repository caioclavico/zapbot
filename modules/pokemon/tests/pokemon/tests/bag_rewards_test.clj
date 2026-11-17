(ns pokemon.tests.bag-rewards-test
  "Domínio kit/diário — espelho das regras de loja.cljs."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.bag-rewards :as bag]))

(deftest sorteio-bola-diaria-faixas
  (is (= "ultra-bola" (bag/sortear-bola-diaria 0)))
  (is (= "ultra-bola" (bag/sortear-bola-diaria 9)))
  (is (= "grande-bola" (bag/sortear-bola-diaria 10)))
  (is (= "grande-bola" (bag/sortear-bola-diaria 39)))
  (is (= "pokebola" (bag/sortear-bola-diaria 40)))
  (is (= "pokebola" (bag/sortear-bola-diaria 99))))

(deftest sequencia-e-quantidade
  (is (= 1 (bag/proxima-sequencia-diaria nil "2026-10-29" 5)))
  (is (= 1 (bag/proxima-sequencia-diaria "2026-10-27" "2026-10-29" 3)))
  (is (= 4 (bag/proxima-sequencia-diaria "2026-10-28" "2026-10-29" 3)))
  (is (= 3 (bag/quantidade-bolas-diario 1)))
  (is (= 4 (bag/quantidade-bolas-diario 2)))
  (is (= 7 (bag/quantidade-bolas-diario 5)))
  (is (= 7 (bag/quantidade-bolas-diario 99))))

(deftest recompensas-diario-fruta-a-cada-7
  (let [r7 (bag/recompensas-diario 7 (constantly 50))
        r1 (bag/recompensas-diario 1 (constantly 50))]
    (is (= 7 (reduce + 0 (vals (dissoc r7 "fruta-dourada")))))
    (is (= 1 (get r7 "fruta-dourada")))
    (is (nil? (get r1 "fruta-dourada")))
    (is (= 3 (get r1 "pokebola")))))

(deftest kit-espaco
  (let [save {:trainer-level 1 :expansoes-mochila-50 0 :capacidade-mochila-legada 50}
        cheia (mapv (fn [i] {:item-id (str "x" i) :qty 5}) (range 9))]
    (is (true? (bag/kit-espaco-ok? save [])))
    (is (false? (bag/kit-espaco-ok? save cheia)) "45/50 → só 5 livres < 10")))

(deftest allocate-diario-pending
  (let [r (bag/allocate-diario {"pokebola" 5 "grande-bola" 2} {} 3)]
    (is (= {"pokebola" 3} (:added r)))
    (is (= {"pokebola" 2 "grande-bola" 2} (:pending r)))))

(deftest texto-recompensas-legado
  (is (= "10× Pokébola" (bag/texto-recompensas {"pokebola" 10})))
  (is (= "2× Grande Bola, 1× Ultra Bola"
         (bag/texto-recompensas {"grande-bola" 2 "ultra-bola" 1}))))
