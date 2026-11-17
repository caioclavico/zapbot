(ns pokemon.tests.explosion-autodesmaio-a6-test
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]))

(defn- mon [nome tipos]
  {:nome nome :species nome :hp 100 :ataque 80 :defesa 40
   :atq-esp 80 :def-esp 40 :veloc 50 :tipos tipos
   :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
             :poder 250 :classe :fisico :autodesmaio true
             :precisao 100}]})

(deftest explosion-desmaia-usuario-apos-hit
  (let [b (battle/create-wild-battle
           {:battle-id "b-exp" :player-id "ash" :chat-id "c"
            :pokemon-x (mon "Electrode" ["electric"])
            :pokemon-o (mon "Rattata" ["normal"])})
        ;; accuracy 0 = hit; crit 99 = no crit; flee 99 = no flee; wild-move 0
        rng {:accuracy 0 :crit 99 :damage-var 0 :dodge 99
             :status 99 :contagio 99 :flee 99 :multi-hit 0
             :wild-move 0}
        out (battle/apply-player-turn b {:action :attack :move-index 0} rng)]
    (is (zero? (long (get-in out [:hp :x]))) "usuario desmaia")
    (is (< (long (get-in out [:hp :o])) 100) "alvo recebe dano")))
