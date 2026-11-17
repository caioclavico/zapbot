(ns pokemon.tests.explosion-win-persist-a6-test
  "P0 Explosion: HP 0 persiste após vitória + captura/XP/liberar/restart."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- fresh []
  (let [client (persistence/client {:system (str "exp-win-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/exp-win-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "exp-win")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    s))

(def ^:private rng-hit
  {:accuracy 0 :crit 99 :damage-var 0 :dodge 99 :flee 99
   :status 99 :contagio 99 :multi-hit 0 :wild-move 0
   :status-golpe 99 :impede-para 99 :cura-sono 99 :cura-gelo 99
   :cura-confusao 99})

(defn- electrode []
  {:nome "Electrode" :species "electrode" :hp 100 :hp-atual 100
   :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40 :veloc 90
   :tipos ["electric"] :nivel 20 :xp-desde-nivel 8
   :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
             :poder 250 :classe :fisico :autodesmaio true :precisao 100}]})

(defn- wild []
  {:nome "Rattata" :species "rattata" :hp 40 :ataque 40 :defesa 30
   :atq-esp 20 :def-esp 30 :veloc 50 :tipos ["normal"] :nivel 5 :raridade "comum"
   :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
             :poder 40 :classe :fisico :precisao 100}]})

(deftest explosion-vitoria-captura-preserva-hp-zero
  (let [s (fresh)
        _ (collection/adicionar-pokemon! s "chat" "ash" (electrode) 100 nil)
        _ (reward-app/admin-seed-reward!
           s {:operation-id "seed-b" :chat-id "chat" :player-id "ash" :save-id "a"
              :item {:item-id "pokebola" :qty 3}})
        bid "b-exp-cap"
        _ (battle-app/start-wild-battle!
           s "chat" "ash"
           {:operation-id "start" :battle-id bid
            :pokemon-x (electrode) :pokemon-o (wild)
            :hp-x 100 :hp-o 40 :collection-index 0})
        turn (battle-app/confirm-turn!
              s "chat" "ash"
              {:operation-id "atk" :battle-id bid :expected-turn-index 0
               :action :attack :move-index 0 :rng rng-hit})
        after-turn (first (ports/list-collection s "chat" "ash" "a"))
        _ (is (= :win (get-in turn [:battle :outcome])))
        _ (is (zero? (long (:hp-atual after-turn 1))))
        cap (capture-app/attempt-encounter-capture!
             s "chat" "ash"
             {:operation-id "cap" :battle-id bid :bola "pokebola" :roll 0})
        after-cap (first (ports/list-collection s "chat" "ash" "a"))]
    (is (true? (:capturou? cap)))
    (is (zero? (long (:hp-atual after-cap 1))) "HP 0 após captura+XP")
    (is (= 21 (long (:nivel after-cap))))
    (is (= 2 (long (get-in cap [:recompensa :xp]))))
    (is (= 1 (long (get-in cap [:recompensa :pe]))))
    (is (zero? (long (get-in cap [:recompensa :moedas]))))))

(deftest explosion-vitoria-liberar-preserva-hp-zero
  (let [s (fresh)
        _ (collection/adicionar-pokemon! s "chat" "ash" (electrode) 100 nil)
        bid "b-exp-rel"
        _ (battle-app/start-wild-battle!
           s "chat" "ash"
           {:operation-id "start-r" :battle-id bid
            :pokemon-x (electrode) :pokemon-o (wild)
            :hp-x 100 :hp-o 40 :collection-index 0})
        _ (battle-app/confirm-turn!
           s "chat" "ash"
           {:operation-id "atk-r" :battle-id bid :expected-turn-index 0
            :action :attack :move-index 0 :rng rng-hit})
        rel (capture-app/encounter-release!
             s "chat" "ash"
             {:operation-id "rel" :battle-id bid})
        after (first (ports/list-collection s "chat" "ash" "a"))
        save (ports/get-save s "chat" "ash" "a")]
    (is (true? (:liberou? rel)))
    (is (zero? (long (:hp-atual after 1))))
    (is (zero? (long (:sequencia-capturas save 1))))
    (is (nil? (ports/active-battle s "chat" "ash")))))

(deftest gym-explosion-both-down-marca-fainted-x
  (let [exp {:nome "Electrode" :species "electrode" :hp 80 :hp-atual 80
             :ataque 200 :defesa 40 :atq-esp 200 :def-esp 40 :veloc 200
             :tipos ["electric"] :nivel 30
             :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
                       :poder 250 :classe :fisico :autodesmaio true :precisao 100}]}
        reserve {:nome "Voltorb" :species "voltorb" :hp 60 :hp-atual 60
                 :ataque 50 :defesa 40 :atq-esp 50 :def-esp 40 :veloc 100
                 :tipos ["electric"] :nivel 20
                 :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                           :poder 40 :classe :fisico :precisao 100}]}
        foe {:nome "A" :species "a" :hp 15 :hp-atual 15
             :ataque 10 :defesa 1 :atq-esp 10 :def-esp 1 :veloc 10
             :tipos ["normal"] :nivel 5
             :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                       :poder 40 :classe :fisico :precisao 100}]}
        b (battle/create-gym-battle
           {:battle-id "g-exp" :chat-id "c" :player-id "ash" :save-id "a"
            :fence-generation 0 :gym-id "pedra" :gym-nome "Pedra"
            :team-x [(assoc exp :collection-index 0)
                     (assoc reserve :collection-index 1)
                     (assoc reserve :nome "Geodude" :species "geodude"
                            :collection-index 2)]
            :team-o [foe foe foe]})
        out (battle/apply-player-turn b {:action :attack :move-index 0} rng-hit)]
    (is (contains? (set (map long (:fainted-x out #{}))) 0)
        "índice do Explosion entra em fainted-x mesmo com KO do oponente")))
