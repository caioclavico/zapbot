(ns zapbot.pokemon.navegacao-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [zapbot.pokemon.navegacao :as nav]))

(use-fixtures :each {:before #(reset! nav/menus {})})

(deftest menus-sao-individuais-e-isolados-por-chat
  (nav/abrir! ["grupo" "ash"] {"1" "treinador"} 0)
  (is (= {:comando "treinador"} (nav/resolver! ["grupo" "ash"] "1" "atacar" 1)))
  (is (= {:comando "atacar 1"} (nav/resolver! ["grupo" "misty"] "1" "atacar" 1)))
  (is (:erro (nav/resolver! ["outro" "ash"] "1" nil 1))))

(deftest menu-expirado-nao-vira-ataque
  (nav/abrir! ["g" "p"] {"1" "pokedex 1"} 0)
  (is (:erro (nav/resolver! ["g" "p"] "1" "atacar" nav/duracao-menu)))
  (is (nil? (get @nav/menus ["g" "p"]))))

(deftest acao-direta-descarta-menu-e-restaura-atalho-de-combate
  (nav/abrir! ["g" "p"] {"1" "pokedex 1"} 0)
  (is (= {:comando "atacar 2"} (nav/resolver! ["g" "p"] " ATACAR 2 " "atacar" 1)))
  (is (= {:comando "atacar 1"} (nav/resolver! ["g" "p"] "1" "atacar" 2))))

(deftest respostas-invalidas-nao-executam-acao
  (nav/abrir! ["g" "p"] {"2" "pokedex 7"} 0)
  (is (:erro (nav/resolver! ["g" "p"] "1" "atacar" 1)))
  (is (= {:comando "pokedex 7"} (nav/resolver! ["g" "p"] "2" "atacar" 2)))
  (is (= {:menu? true} (nav/resolver! ["g" "p"] "0" "atacar" 3)))
  (is (:erro (nav/resolver! ["g" "n"] "2" nil 3)))
  (is (= {:comando "raid atacar 3"} (nav/resolver! ["g" "n"] "3" "raid atacar" 3)))
  (is (= {:comando "capturar 1"} (nav/resolver! ["g" "n"] "1" "capturar" 3))))
