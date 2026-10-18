(ns pokemon.tests.hunt-m45-test
  "M4.5 — caça selvagem: plano, start, batalha, captura."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.hunt :as hunt-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.mundo :as mundo]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.time Instant]))

(def ^:private system "odisseu-m45-tests")
(def ^:private storage-rel ".datomic-local/m45-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(def ^:private squirtle
  {:nome "Squirtle" :hp 100 :ataque 200 :defesa 70 :atq-esp 200 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10 :habilidade "torrent"
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe :especial}]})

(def ^:private magikarp-cand
  {:nome "Magikarp" :species "magikarp" :slug-especie "magikarp"
   :raridade "comum" :hp 20 :ataque 10 :defesa 20 :atq-esp 10 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "splash" :nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private geodude-cand
  {:nome "Geodude" :species "geodude" :slug-especie "geodude"
   :hp 40 :ataque 40 :defesa 50 :atq-esp 20 :def-esp 30 :veloc 20
   :tipos ["rock" "ground"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "tackle" :nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-balls-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 10}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil))

(deftest mundo-rotacao-deterministica
  (let [dia "2026-10-17"
        c1 (mundo/clima-do-dia dia)
        c2 (mundo/clima-do-dia dia)
        areas (mundo/areas-do-dia dia)
        praia (mundo/obter-area "praia")]
    (is (= (:id c1) (:id c2)))
    (is (= 3 (count areas)))
    (is (nil? (mundo/area-disponivel "area-inexistente" dia)))
    (is (= "#7dd3fc" (:ceu praia)))
    (is (= "#eab308" (:chao praia)))))

(deftest formulas-poder-e-raridade
  (is (= 320 (hunt/poder-alvo-caca 1)))
  (is (= 570 (hunt/poder-alvo-caca 11)))
  (is (= 570 (hunt/poder-alvo-caca 99)))
  (is (= "comum" (:raridade (hunt/com-raridade magikarp-cand))))
  (is (true? (:shiny? (hunt/sortear-shiny magikarp-cand 0))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp-cand 1)))))

(deftest selecao-respeita-bioma-e-teto
  (let [alvo 320
        tipos #{"water" "ice"}
        escolhido (hunt/selecionar-selvagem
                   [geodude-cand magikarp-cand] alvo tipos)]
    (is (= "Magikarp" (:nome escolhido)))))

(deftest caca-batalha-captura-e2e
  (let [s (fresh-store! "hunt-e2e")
        chat "chat-e2e"
        player "ash"
        agora 1700000000000]
    (boot! s chat player)
    (let [plano (hunt-app/plan s chat player
                               {:agora-ms agora
                                :roll-tipos 50  ; bioma (não clima)
                                :roll-surto 99}) ; sem surto
          start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-1"
                  :area-id (get-in plano [:area :id])
                  :tipos-encontro (:tipos-encontro plano)
                  :surto? false
                  :poder-alvo (:poder-alvo plano)
                  :candidates [magikarp-cand geodude-cand]
                  :agora-ms agora
                  :shiny-roll 1})
          bid (:battle-id start)
          win (battle-app/confirm-turn!
               s chat player
               {:operation-id "turn-win"
                :battle-id bid
                :expected-turn-index 0
                :action :attack
                :move-index 0
                :rng {:damage-var 10 :crit 99 :flee 99 :accuracy 0
                      :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99}})
          cap (capture-app/attempt-encounter-capture!
               s chat player
               {:operation-id "cap-1"
                :battle-id bid
                :bola "pokebola"
                :roll 0})]
      (is (false? (:duplicate start)))
      (is (true? (:aguardando-captura? (:battle win))))
      (is (true? (:capturou? cap)))
      (is (= 2 (count (ports/list-collection s chat player "a"))))
      (is (pos? (:ultima-cacada-at (ports/get-save s chat player "a"))))
      (is (nil? (:active-wild-battle-id (ports/get-group s chat)))))))

(deftest idempotencia-e-reinicio
  (let [db "hunt-idem"
        chat "chat-idem"
        player "misty"
        agora 1700000100000
        req {:operation-id "hunt-once"
             :area-id "praia"
             :tipos-encontro ["water" "ice" "flying" "electric"]
             :surto? false
             :poder-alvo 320
             :candidates [magikarp-cand]
             :agora-ms agora
             :shiny-roll 1}]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (let [r1 (hunt-app/start-hunt! s chat player req)
            r2 (hunt-app/start-hunt! s chat player req)]
        (is (false? (:duplicate r1)))
        (is (true? (:duplicate r2)))
        (is (some? (ports/active-battle s chat player)))))
    (let [s (reopen-store db)
          retry (hunt-app/start-hunt! s chat player req)
          b (ports/active-battle s chat player)]
      (is (true? (:duplicate retry)))
      (is (some? b))
      (is (true? (:active? b))))))

(deftest a-b-bloqueado-e-stale
  (let [s (fresh-store! "hunt-ab")
        chat "chat-ab"
        player "leaf"
        agora 1700000200000]
    (boot! s chat player)
    (hunt-app/start-hunt!
     s chat player
     {:operation-id "hunt-ab"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 320
      :candidates [magikarp-cand]
      :agora-ms agora
      :shiny-roll 1})
    (is (= :battle_pending
           (try (trainer/switch-save! s chat player "b") :ok
                (catch Exception ex (err/error-code ex)))))
    (let [writer-a (writer/freeze-writer s chat player)]
      ;; defend + flee 0: não KO (Magikarp frágil); selvagem foge
      (battle-app/confirm-turn!
       s chat player
       {:operation-id "flee-ab"
        :battle-id (:battle-id (ports/active-battle s chat player))
        :expected-turn-index 0
        :action :defend
        :rng {:flee 0 :damage-var 0 :crit 99 :accuracy 0 :impede-para 99
              :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
              :contagio 99 :status-golpe 99 :wild-move 0}})
      (trainer/switch-save! s chat player "b")
      (is (= :stale_writer_fence
             (try
               (hunt-app/start-hunt!
                s writer-a
                {:operation-id "stale-hunt"
                 :area-id "praia"
                 :tipos-encontro ["water"]
                 :surto? false
                 :poder-alvo 320
                 :candidates [magikarp-cand]
                 :agora-ms (+ agora hunt/cooldown-cacada-ms 1)
                 :shiny-roll 1})
               :ok
               (catch Exception ex (err/error-code ex))))))))

(deftest isolamento-chat-e-cooldown
  (let [s (fresh-store! "hunt-iso")
        chat "chat-iso"
        agora 1700000300000]
    (boot! s chat "ash")
    (boot! s chat "gary")
    (hunt-app/start-hunt!
     s chat "ash"
     {:operation-id "ash-hunt"
      :area-id "praia"
      :tipos-encontro ["water"]
      :surto? false
      :poder-alvo 320
      :candidates [magikarp-cand]
      :agora-ms agora
      :shiny-roll 1})
    (is (= :chat_hunt_busy
           (try
             (hunt-app/start-hunt!
              s chat "gary"
              {:operation-id "gary-hunt"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp-cand]
               :agora-ms agora
               :shiny-roll 1})
             :ok
             (catch Exception ex (err/error-code ex)))))
    (is (= :hunt_cooldown
           (try
             (hunt-app/start-hunt!
              s chat "ash"
              {:operation-id "ash-again"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [magikarp-cand]
               :agora-ms (+ agora 1000)
               :shiny-roll 1})
             :ok
             (catch Exception ex (err/error-code ex)))))))
