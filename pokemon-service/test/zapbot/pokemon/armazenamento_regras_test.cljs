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
  (is (= 175 (regras/capacidade-mochila 1 1 175)))
  (is (= 200 (regras/capacidade-mochila 1 3 0))))
