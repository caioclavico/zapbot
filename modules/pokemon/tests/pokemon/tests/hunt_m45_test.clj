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
  (is (= "comum" (:raridade (hunt/com-raridade (dissoc magikarp-cand :taxa-captura))))
      "taxa-captura ausente (species falhou) não quebra raridade")
  (is (true? (:shiny? (hunt/sortear-shiny magikarp-cand 0))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp-cand 1))))
  (is (false? (:shiny? (hunt/sortear-shiny magikarp-cand nil)))
      "shiny-roll nil não lança NPE (produção)"))

(deftest cooldown-de-caca-e-configuravel-com-protecao-de-producao
  (let [at 1700000000000]
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "production"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
      (is (= 1800 (hunt/cooldown-cacada-segundos)))
      (is (= (* 30 60 1000) (hunt/cooldown-restante-ms at at))))
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "staging"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
      (is (zero? (hunt/cooldown-cacada-segundos)))
      (is (zero? (hunt/cooldown-restante-ms at at)))
      (is (hunt/pode-cacar? at at)))
    (with-redefs [hunt/environment (fn [k]
                                     ({"APP_ENV" "production"
                                       "POKEMON_HUNT_COOLDOWN_SECONDS" "45"} k))]
      (is (= 45 (hunt/cooldown-cacada-segundos)))
      (is (= 44000 (hunt/cooldown-restante-ms at (+ at 1000)))))
    (with-redefs [hunt/environment (constantly nil)]
      (is (= 1800 (hunt/cooldown-cacada-segundos)))
      (is (= (* 30 60 1000) (hunt/cooldown-restante-ms at at))))))

(deftest selecao-respeita-bioma-e-teto
  (let [alvo 320
        tipos #{"water" "ice"}
        escolhido (hunt/selecionar-selvagem
                   [geodude-cand magikarp-cand] alvo tipos)]
    (is (= "Magikarp" (:nome escolhido)))))

(deftest start-sem-shiny-roll-nem-taxa-captura
  "Regressão INTERNAL_ERROR !pk caçar: Node não envia shinyRoll;
   species pode omitir taxa-captura."
  (let [s (fresh-store! "hunt-nil-shiny")
        chat "chat-nil"
        player "ash"
        agora 1700000300000
        cand (dissoc magikarp-cand :taxa-captura)]
    (boot! s chat player)
    (let [start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-no-shiny"
                  :area-id "praia"
                  :tipos-encontro ["water" "ice" "flying" "electric"]
                  :surto? false
                  :poder-alvo 320
                  :candidates [cand]
                  :agora-ms agora})
          b (:battle start)
          selv (:selvagem start)]
      (is (false? (:duplicate start)))
      (is (some? b))
      (is (true? (:active? b)))
      (is (= "Magikarp" (:nome selv)))
      (is (contains? #{true false} (boolean (:shiny? selv))))
      (is (= "comum" (:raridade selv))))))

(deftest candidato-incompleto-rejeitado
  (let [s (fresh-store! "hunt-bad-cand")
        chat "chat-bad"
        player "ash"]
    (boot! s chat player)
    (is (= :invalid_wild_pokemon
           (try
             (hunt-app/start-hunt!
              s chat player
              {:operation-id "hunt-bad"
               :area-id "praia"
               :tipos-encontro ["water"]
               :surto? false
               :poder-alvo 320
               :candidates [{:nome "X" :tipos ["water"] :hp 10
                             :golpes [{:nome-exibicao "A" :tipo "normal"
                                       :poder 40 :classe :fisico}]}]
               :agora-ms 1700000400000})
             :ok
             (catch Exception ex (err/error-code ex)))))))

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

(def ^:private rng-safe
  {:damage-var 0 :crit 99 :flee 99 :accuracy 0
   :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99
   :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99})

(deftest defender-e-pocao-mid-caca-a1
  "A1: !pk defender e !pk pocao durante caçada (turno + inventário)."
  (let [s (fresh-store! "hunt-a1")
        chat "chat-a1"
        player "ash"
        agora 1700000500000]
    (boot! s chat player)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-pocao-a1"
        :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 2}})
    (let [start (hunt-app/start-hunt!
                 s chat player
                 {:operation-id "hunt-a1"
                  :area-id "praia"
                  :tipos-encontro ["water"]
                  :surto? false
                  :poder-alvo 320
                  :candidates [geodude-cand]
                  :agora-ms agora
                  :shiny-roll 1})
          bid (:battle-id start)
          def1 (battle-app/confirm-turn!
                s chat player
                {:operation-id "def-1"
                 :battle-id bid
                 :expected-turn-index 0
                 :action :defend
                 :rng rng-safe})
          def2 (battle-app/confirm-turn!
                s chat player
                {:operation-id "def-1"
                 :battle-id bid
                 :expected-turn-index 0
                 :action :defend
                 :rng rng-safe})
          pot (battle-app/confirm-turn!
               s chat player
               {:operation-id "pot-1"
                :battle-id bid
                :expected-turn-index 1
                :action :item
                :item-id "pocao"
                :rng rng-safe})
          pot2 (battle-app/confirm-turn!
                s chat player
                {:operation-id "pot-1"
                 :battle-id bid
                 :expected-turn-index 1
                 :action :item
                 :item-id "pocao"
                 :rng rng-safe})
          la (:last-action (:battle pot))
          inv (ports/list-inventory s chat player "a")
          qty-pocao (:qty (first (filter #(= "pocao" (:item-id %)) inv)) 0)]
      (is (= :defend (get-in def1 [:battle :last-action :action])))
      (is (true? (:active? (:battle def1))))
      (is (true? (:duplicate def2)))
      (is (= :item (:action la)))
      (is (= "pocao" (:item-id la)))
      (is (pos? (- (long (:hp-depois la 0)) (long (:hp-antes la 0))))
          "cura da poção registrada no last-action (antes do revide)")
      (is (true? (:duplicate pot2)))
      (is (= 1 qty-pocao) "uma poção consumida; retry idempotente não consome de novo"))))

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

(deftest cooldown-zero-permite-nova-caca-apos-batalha-encerrada
  (with-redefs [hunt/environment (fn [k]
                                   ({"APP_ENV" "staging"
                                     "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
    (let [s (fresh-store! "hunt-zero-cooldown")
          chat "chat-zero"
          player "ash"
          agora 1700000400000
          request (fn [operation-id]
                    {:operation-id operation-id :area-id "praia"
                     :tipos-encontro ["water"] :surto? false :poder-alvo 320
                     :candidates [magikarp-cand] :agora-ms agora :shiny-roll 1})]
      (boot! s chat player)
      (let [first-hunt (hunt-app/start-hunt! s chat player (request "hunt-zero-1"))]
        ;; Mesmo com cooldown zero, uma batalha ainda ativa bloqueia concorrência.
        (is (= :battle_already_active
               (try (hunt-app/start-hunt! s chat player (request "hunt-zero-active"))
                    :ok (catch Exception ex (err/error-code ex)))))
        ;; A fuga encerra a batalha sem captura e libera a trava canônica do chat.
        (battle-app/confirm-turn!
         s chat player
         {:operation-id "hunt-zero-flee" :battle-id (:battle-id first-hunt)
          :expected-turn-index 0 :action :defend
          :rng {:flee 0 :damage-var 0 :crit 99 :accuracy 0 :impede-para 99
                :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                :contagio 99 :status-golpe 99 :wild-move 0}})
        (is (nil? (:active-wild-battle-id (ports/get-group s chat))))
        (is (= "hunt-zero-2"
               (:operation-id (hunt-app/start-hunt! s chat player (request "hunt-zero-2")))))))))

(deftest cooldown-zero-nao-retorna-apos-derrota
  (with-redefs [hunt/environment (fn [k]
                                   ({"APP_ENV" "staging"
                                     "POKEMON_HUNT_COOLDOWN_SECONDS" "0"} k))]
    (let [s (fresh-store! "hunt-zero-defeat")
          chat "chat-zero-defeat"
          player "ash"
          agora 1700000500000
          hostile (assoc magikarp-cand :hp 500 :ataque 999 :atq-esp 999
                          :defesa 999 :def-esp 999
                          :golpes [{:slug "crush" :nome-exibicao "Crush"
                                    :tipo "normal" :poder 500 :classe :fisico}])]
      (boot! s chat player)
      (let [started (hunt-app/start-hunt!
                     s chat player
                     {:operation-id "hunt-zero-defeat-1" :area-id "praia"
                      :tipos-encontro ["water"] :surto? false :poder-alvo 320
                      :candidates [hostile] :agora-ms agora :shiny-roll 1})
            ended (battle-app/confirm-turn!
                   s chat player
                   {:operation-id "hunt-zero-defeat-turn" :battle-id (:battle-id started)
                    :expected-turn-index 0 :action :defend
                    :rng {:flee 99 :damage-var 10 :crit 0 :accuracy 0 :impede-para 99
                          :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99
                          :contagio 99 :status-golpe 99 :wild-move 0}})]
        (is (= :lose (get-in ended [:battle :outcome])))
        (is (nil? (:active-wild-battle-id (ports/get-group s chat))))
        (is (true? (get-in (hunt-app/plan s chat player {:agora-ms agora})
                           [:eligibilidade :cooldown-ok])))))))
