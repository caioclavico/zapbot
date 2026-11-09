(ns pokemon.tests.tx-datoms-conflict-persistent-a6-test
  "P0: vitória+XP+HP combate sem datoms-conflict; idle deslizante sob multi-turnos."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.species-hp :as species-hp]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-txc-persistent")
(def ^:private storage-rel ".datomic-local/txc-persistent")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- wctx [s chat player]
  (writer/freeze-writer s chat player))

(def ^:private rng-hit
  {:accuracy 0 :crit 99 :damage-var 0 :dodge 99 :flee 99
   :status 99 :contagio 99 :multi-hit 0 :wild-move 0
   :status-golpe 99 :impede-para 99 :cura-sono 99 :cura-gelo 99
   :cura-confusao 99})

(defn- magikarp-weak []
  {:nome "Magikarp" :species "magikarp" :hp 20 :hp-atual 20
   :ataque 10 :defesa 10 :atq-esp 10 :def-esp 10 :veloc 10
   :tipos ["water"] :nivel 5 :raridade "comum" :taxa-captura 255
   :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
             :poder 40 :classe :fisico :precisao 100}]})

(defn- fighter-near-level
  "Quase sobe de nível (xp 8/9) com HP parcial + species-hp-v1."
  []
  (let [base 45
        nivel 15
        mx (species-hp/hp-max base nivel)
        cur (max 1 (long (Math/floor (* 0.4 (double mx)))))]
    {:nome "Wartortle" :species "wartortle" :hp mx :hp-atual cur
     :hp-base base :hp-formula-version species-hp/formula-version
     :ataque 60 :defesa 70 :atq-esp 55 :def-esp 65 :veloc 50
     :tipos ["water"] :nivel nivel :xp-desde-nivel 8 :amizade 70
     :golpes [{:slug "water-gun" :nome-exibicao "Jato d'Agua" :tipo "water"
               :poder 40 :classe :especial :precisao 100}]}))

(defn- boot! [s chat player poke]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player poke (:hp-atual poke) nil))

(deftest vitoria-xp-hp-parcial-sem-datoms-conflict
  (let [s (fresh-store! "txc-win")
        chat "c-win" player "ash"
        f (fighter-near-level)
        _ (boot! s chat player f)
        col0 (first (ports/list-collection s chat player "a"))
        hp-antes (long (:hp-atual col0))
        t0 5000000000000
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-txc"
                  :battle-id "txc-b1"
                  :pokemon-x (assoc f :hp-atual hp-antes)
                  :pokemon-o (magikarp-weak)
                  :hp-x hp-antes :hp-o 20
                  :collection-index 0
                  :agora t0})
        bid (get-in started [:battle :battle-id])
        turn (ports/confirm-battle-turn!
              s (wctx s chat player)
              {:operation-id "atk-win"
               :battle-id bid
               :expected-turn-index 0
               :action :attack
               :move-index 0
               :agora (+ t0 5000)
               :rng rng-hit})
        after (first (ports/list-collection s chat player "a"))]
    (is (= :win (get-in turn [:battle :outcome])))
    (is (true? (get-in turn [:battle :aguardando-captura?])))
    (is (= (long (get-in turn [:battle :hp :x])) (long (:hp-atual after)))
        "HP coleção = HP canônico da batalha (sem heal de level-up)")
    (is (>= (long (:nivel after)) 16) "XP de batalha aplica level-up")))

(deftest multi-ataques-renovam-idle-alem-do-timeout-inicial
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "30"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "txc-slide")
          chat "c-slide" player "ash"
          f (assoc (fighter-near-level) :xp-desde-nivel 0 :nivel 20
                   :hp 120 :hp-atual 120)
          _ (boot! s chat player f)
          wild {:nome "Rattata" :species "rattata" :hp 200 :hp-atual 200
                :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
                :tipos ["normal"] :nivel 3 :raridade "comum"
                :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal"
                          :poder 1 :classe :fisico :precisao 100}]}
          t0 6000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-idle-slide"
                    :battle-id "slide-b"
                    :pokemon-x f :pokemon-o wild
                    :hp-x 120 :hp-o 200
                    :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          timeout-ms 30000]
      (is (= (+ t0 timeout-ms) (:idle-expires-at (ports/get-battle s bid))))
      (doseq [[i agora] [[1 (+ t0 20000)]
                         [2 (+ t0 45000)]
                         [3 (+ t0 70000)]]]
        (ports/confirm-battle-turn!
         s (wctx s chat player)
         {:operation-id (str "def-" i)
          :battle-id bid
          :expected-turn-index (dec i)
          :action :defend
          :agora agora
          :rng (assoc rng-hit :impede-para 99)})
        (let [b (ports/get-battle s bid)]
          (is (true? (:active? b)))
          (is (= (+ agora timeout-ms) (:idle-expires-at b))
              (str "renova no turno " i))))
      (is (zero? (:expired-battles
                  (ports/expire-idle-battles!
                   s {:agora (+ t0 70000 timeout-ms -1) :limit 8}))))
      (is (= 1 (:expired-battles
                (ports/expire-idle-battles!
                 s {:agora (+ t0 70000 timeout-ms) :limit 8})))))))

(deftest explosion-hp0-vitoria-sem-conflict
  (let [s (fresh-store! "txc-exp")
        chat "c-exp" player "ash"
        exp {:nome "Electrode" :species "electrode" :hp 100 :hp-atual 100
             :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40 :veloc 90
             :tipos ["electric"] :nivel 20 :xp-desde-nivel 8
             :golpes [{:slug "explosion" :nome-exibicao "Explosao" :tipo "normal"
                       :poder 250 :classe :fisico :autodesmaio true :precisao 100}]}
        _ (boot! s chat player exp)
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-exp"
                  :battle-id "exp-b"
                  :pokemon-x exp :pokemon-o (magikarp-weak)
                  :hp-x 100 :hp-o 20 :collection-index 0})
        bid (get-in started [:battle :battle-id])
        turn (ports/confirm-battle-turn!
              s (wctx s chat player)
              {:operation-id "boom"
               :battle-id bid
               :expected-turn-index 0
               :action :attack :move-index 0
               :rng rng-hit})
        after (first (ports/list-collection s chat player "a"))]
    (is (= :win (get-in turn [:battle :outcome])))
    (is (zero? (long (:hp-atual after))) "Explosion preserva HP 0")))
