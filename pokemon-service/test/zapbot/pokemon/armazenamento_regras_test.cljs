(ns zapbot.pokemon.armazenamento-regras-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [zapbot.pokemon.armazenamento-regras :as regras]))

(deftest capacidades-iniciais
  (is (= 30 (regras/capacidade-pokemon 1 0 0)))
  (is (= 50 (regras/capacidade-mochila 1 0 0))))

(deftest marcos-retroativos
  (is (= 80 (regras/capacidade-pokemon 5 0 0)))
  (is (= 100 (regras/capacidade-mochila 10 0 0)))
  (is (= 280 (regras/capacidade-pokemon 50 0 0)))
  (is (= 300 (regras/capacidade-mochila 50 0 0))))

(deftest expansoes-e-legado-nao-reduzem-capacidade
  (is (= 130 (regras/capacidade-pokemon 1 2 0)))
  (is (= 225 (regras/capacidade-mochila 1 1 175)))
  (is (= 200 (regras/capacidade-mochila 1 3 0))))

(deftest niveis-e-compras-legadas
  (doseq [[nivel pokemon mochila] [[1 30 50] [5 80 50] [10 80 100]
                                    [15 130 100] [25 180 150] [50 280 300]]]
    (is (= pokemon (regras/capacidade-pokemon nivel 0 0)))
    (is (= mochila (regras/capacidade-mochila nivel 0 0))))
  (doseq [compras (range 5)]
    (is (= (+ 175 (* 50 compras)) (regras/capacidade-mochila 1 compras 175)))
    (is (= (+ 225 (* 50 compras)) (regras/capacidade-mochila 10 compras 175)))))
