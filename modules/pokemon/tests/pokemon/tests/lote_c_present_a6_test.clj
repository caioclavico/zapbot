(ns pokemon.tests.lote-c-present-a6-test
  "Lote C — last-action canônico (HP/efetividade/autodesmaio) para o present Node."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.damage :as damage]))

(def ^:private rng-hit
  {:accuracy 0 :crit 99 :damage-var 0 :dodge 99 :flee 99
   :status 99 :contagio 99 :multi-hit 0 :wild-move 0
   :status-golpe 99 :impede-para 99 :cura-sono 99 :cura-gelo 99 :cura-confusao 99})

(deftest last-action-inclui-hp-e-autodesmaio
  (let [atk {:nome "Electrode" :tipos ["electric"] :hp 100}
        def {:nome "Rattata" :tipos ["normal"] :hp 100}
        golpe {:nome-exibicao "Explosao" :tipo "normal" :poder 250
               :classe :fisico :autodesmaio true :precisao 100}
        resolved {:dano 40 :acertou? true :critico? true :multiplicador 1.0
                  :errou? false :esquivou? false :acertos 1 :impulso 1.0}
        la (damage/last-action-ataque golpe atk def resolved false
                                      :hp-antes 100 :hp-depois 60
                                      :hp-atk-antes 100 :hp-atk-depois 0
                                      :autodesmaio? true)]
    (is (true? (:autodesmaio? la)))
    (is (= 100 (:hp-antes la)))
    (is (= 60 (:hp-depois la)))
    (is (true? (:critico? la)))
    (is (re-find #"desmaiou após o golpe" (:mensagem la)))
    (is (re-find #"Explosao" (:mensagem la)))
    (is (re-find #"crítico" (:mensagem la)))))

(deftest explosion-turno-propaga-autodesmaio-no-last-action
  (let [b (battle/create-wild-battle
           {:battle-id "c-exp" :player-id "ash" :chat-id "c"
            :pokemon-x {:nome "Electrode" :species "electrode" :hp 100 :ataque 80
                        :defesa 40 :atq-esp 80 :def-esp 40 :veloc 90 :tipos ["electric"]
                        :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
                                  :poder 250 :classe :fisico :autodesmaio true :precisao 100}]}
            :pokemon-o {:nome "Rattata" :species "rattata" :hp 80 :ataque 40
                        :defesa 30 :atq-esp 20 :def-esp 30 :veloc 50 :tipos ["normal"]
                        :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                                  :poder 40 :classe :fisico :precisao 100}]}})
        out (battle/apply-player-turn b {:action :attack :move-index 0} rng-hit)
        la (:last-action out)]
    (is (true? (:autodesmaio? la)))
    (is (some? (:hp-antes la)))
    (is (some? (:hp-depois la)))
    (is (<= (long (:hp-depois la)) (long (:hp-antes la))))
    (is (zero? (long (get-in out [:hp :x]))))
    (is (re-find #"desmaiou após o golpe" (str (:mensagem la))))))
