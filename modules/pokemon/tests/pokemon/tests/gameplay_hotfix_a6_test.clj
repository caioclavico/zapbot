(ns pokemon.tests.gameplay-hotfix-a6-test
  "A6 hotfix — XP derrota caça, PI só com incubação, biomas/aliases."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.active :as active-app]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.time Instant]))

(def ^:private system "odisseu-hotfix-a6-tests")
(def ^:private storage-rel ".datomic-local/hotfix-a6-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(def ^:private squirtle
  {:species "squirtle" :nome "Squirtle" :nivel 5
   :hp 44 :hp-atual 44 :ataque 48 :defesa 65
   :atq-esp 50 :def-esp 64 :veloc 43
   :tipos ["water"] :golpes [{:slug "tackle" :nome-exibicao "Tackle"
                              :tipo "normal" :poder 40 :classe :fisico}]
   :growth-rate :medium-fast :xp-progress-version "flat-9-v2"
   :xp-desde-nivel 0 :xp-total 0 :amizade 70
   :species-hp-skip? true})

(def ^:private hostile
  (-> squirtle
      (assoc :species "magikarp" :nome "Magikarp" :hp 500 :ataque 999
             :atq-esp 999 :defesa 999 :def-esp 999
             :golpes [{:slug "crush" :nome-exibicao "Crush"
                       :tipo "normal" :poder 999 :classe :fisico}])))

(deftest derrota-caca-persiste-xp-minimo-por-id
  (let [s (fresh-store! "hunt-lose-xp")
        chat "c-lose" player "ash" agora 1700000800000]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-balls-lose"
        :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pokebola" :qty 5}})
    (collection/adicionar-pokemon!
     s chat player (assoc squirtle :nome "Bench" :id-pokemon "bench-1"
                          :hp 5 :defesa 1 :def-esp 1) 5 nil)
    (collection/adicionar-pokemon!
     s chat player (assoc squirtle :nome "Fighter" :id-pokemon "fighter-1"
                          :hp 5 :defesa 1 :def-esp 1) 5 nil)
    (let [col0 (ports/list-collection s chat player "a")
          fighter0 (some #(when (= "fighter-1" (:id-pokemon %)) %) col0)
          idx (long (:collection-index fighter0))
          _ (active-app/set-active!
             s chat player {:operation-id "set-fighter" :idx idx})
          _ (is (= 0 (long (:xp-desde-nivel fighter0 0))))
          started (hunt-app/start-hunt!
                   s chat player
                   {:operation-id "hunt-lose-1" :area-id "praia"
                    :tipos-encontro ["water"] :surto? false :poder-alvo 320
                    :candidates [hostile] :agora-ms agora :shiny-roll 1})
          ended (battle-app/confirm-turn!
                 s chat player
                 {:operation-id "hunt-lose-turn" :battle-id (:battle-id started)
                  :expected-turn-index 0 :action :defend
                  :agora agora
                  :rng {:flee 99 :damage-var 10 :crit 0 :accuracy 0 :dodge 99
                        :impede-para 99
                        :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                        :contagio 99 :status-golpe 99 :wild-move 0}})
          col1 (ports/list-collection s chat player "a")
          fighter1 (some #(when (= "fighter-1" (:id-pokemon %)) %) col1)
          bench1 (some #(when (= "bench-1" (:id-pokemon %)) %) col1)]
      (is (= :lose (get-in ended [:battle :outcome])))
      (is (= 1 (long (:pokemon-xp (:reward ended) 0)))
          "DTO anuncia somente XP aplicado")
      (is (= 1 (long (:xp-desde-nivel fighter1 0)))
          "XP mínimo persistido no combatente por id")
      (is (= 0 (long (:xp-desde-nivel bench1 0)))
          "banco não recebe XP da derrota"))))

(deftest pi-zero-sem-ovo-incubando
  (let [agora 1700000000000
        base (-> (eggs/estado-inicial)
                 (eggs/conceder-ovo (eggs/novo-ovo "e1" :comum agora "bulbasaur")))
        out (egg-activity/apply-activity
             base {:event-id "act-empty" :activity :wild-victory :agora agora})
        daily (:pi-daily (:estado out))]
    (is (zero? (:pi-granted out)))
    (is (zero? (long (:points daily 0)))
        "teto diário não consumido sem incubação")))

(deftest pi-aplica-com-ovo-incubando
  (let [agora 1700000000000
        base (-> (eggs/estado-inicial)
                 (eggs/conceder-ovo (eggs/novo-ovo "e1" :comum agora "bulbasaur"))
                 (eggs/incubar "e1" "permanente" agora "a" 0))
        out (egg-activity/apply-activity
             base {:event-id "act-inc" :activity :wild-victory :agora agora})]
    (is (pos? (:pi-granted out)))
    (is (< 0 (long (get-in out [:estado :eggs 0 :points]))))))

(deftest biomas-aliases-e-acentos
  (doseq [[pedido id] [["vulcao" "vulcao"]
                       ["vulcão" "vulcao"]
                       ["VULCAO RUBRO" "vulcao"]
                       ["  vulcao   rubro  " "vulcao"]
                       ["rubro" "vulcao"]
                       ["floresta verde" "floresta"]
                       ["praia azul" "praia"]
                       ["caverna rochosa" "caverna"]
                       ["lago encantado" "lago"]
                       ["cidade pokemon" "cidade"]]]
    (is (= id (:id (mundo/obter-area pedido))) pedido))
  (doseq [clima-id (map :id mundo/climas)]
    (let [oficial (:nome (mundo/obter-clima clima-id))
          sem (mundo/normalizar oficial)]
      (is (= clima-id (:id (mundo/obter-clima oficial))))
      (is (= clima-id (:id (mundo/obter-clima sem))))
      (is (= clima-id (:id (mundo/obter-clima (.toUpperCase ^String sem)))))))
  (is (nil? (mundo/obter-area "vulcao azul"))))

(deftest area-unknown-vs-unavailable
  (let [instant (Instant/parse "2026-10-17T12:00:00-03:00")
        ex-unk (try
                 (hunt/montar-plano
                  {:area-pedida "vulcao azul"
                   :instant instant
                   :trainer-level 1
                   :roll-tipos 0 :roll-surto 99})
                 nil
                 (catch Exception e e))
        night (Instant/parse "2026-10-17T22:00:00-03:00")
        ex-closed (try
                    (hunt/montar-plano
                     {:area-pedida "vulcao rubro"
                      :instant night
                      :trainer-level 1
                      :roll-tipos 0 :roll-surto 99})
                    nil
                    (catch Exception e e))]
    (is (= :area_unknown (err/error-code ex-unk)))
    (is (= :area_unavailable (err/error-code ex-closed)))
    (is (= "vulcao" (:area-id (ex-data ex-closed)))))
  (testing "create-wild carimba indices-ativos"
    (let [b (battle/create-wild-battle
             {:battle-id "b1" :chat-id "c" :player-id "p" :save-id "a"
              :fence-generation 1
              :pokemon-x squirtle :pokemon-o hostile
              :collection-index 2})]
      (is (= 2 (get-in b [:indices-ativos :x]))))))
