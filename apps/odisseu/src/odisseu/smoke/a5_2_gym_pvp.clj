(ns odisseu.smoke.a5-2-gym-pvp
  "A5.2 — contratos HTTP reais de ginásio e PvP sobre Datomic Local descartável."
  (:require [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]))

(defn- fail! [message data]
  (throw (ex-info message data)))

(defn- request!
  [client base token actor method path body]
  (let [builder (-> (HttpRequest/newBuilder (URI/create (str base path)))
                    (.timeout (Duration/ofSeconds 30))
                    (.header "Authorization" (str "Bearer " token)))]
    (when actor (.header builder "X-Actor-Player-Id" actor))
    (when body (.header builder "Content-Type" "application/json"))
    (let [request (if (= method "GET")
                    (.GET builder)
                    (.method builder method
                             (HttpRequest$BodyPublishers/ofString
                              (json/write-str (or body {})))))
          response (.send client (.build request)
                          (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response)
       :body (json/read-str (.body response))})))

(defn- fixture!
  [prefix]
  (let [id (str (UUID/randomUUID))
        system (str "odisseu-a52-" prefix "-" id)
        storage (str ".datomic-local/a52-" prefix "-" id)
        db-name (str "a52-" prefix)
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db-name)
        s (doto (store/make-store conn) trainer/bootstrap!)]
    (doseq [player ["ash" "misty" "brock"]]
      (trainer/create-trainer! s {:chat-id "a52-chat" :player-id player}))
    {:conn conn :store s :chat "a52-chat" :db-name db-name}))

(def ^:private pvp-strong
  {:nome "Hitmonlee" :species "hitmonlee" :raridade "incomum"
   :hp 100 :ataque 200 :defesa 80 :atq-esp 80 :def-esp 80 :veloc 120
   :tipos ["fighting"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Chute" :tipo "fighting" :poder 100
             :classe :fisico :precisao 100}]})

(def ^:private pvp-weak
  {:nome "Magikarp" :species "magikarp" :raridade "comum"
   :hp 10 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
   :tipos ["water"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(defn- add-pvp-pokemon! [s chat]
  (collection/adicionar-pokemon! s chat "ash" pvp-strong 100 nil)
  (collection/adicionar-pokemon! s chat "misty" pvp-weak 10 nil))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- pvp-http-flow!
  [{:keys [store chat]}]
  (add-pvp-pokemon! store chat)
  (let [token "a52-token"
        server (bridge/start! {:host "127.0.0.1" :port 0 :token token :store store})
        client (HttpClient/newHttpClient)
        base (:base-url server)]
    (try
      (let [open (request! client base token "ash" "POST" "/v1/pvp/open"
                           {:chatId chat :playerId "ash" :saveId "a"
                            :fenceGeneration (fence store chat "ash")
                            :operationId "a52-pvp-open" :playerName "Ash"})
            _ (when-not (= 200 (:status open)) (fail! "pvp_open_http" open))
            battle-id (get-in open [:body "data" "battle" "battleId"])
            _ (when-not battle-id (fail! "pvp_open_missing_battle_id" open))
            accept (request! client base token "misty" "POST" "/v1/pvp/accept"
                             {:chatId chat :playerId "misty" :saveId "a"
                              :fenceGeneration (fence store chat "misty")
                              :operationId "a52-pvp-accept" :battleId battle-id
                              :firstTurnTie "x"})
            _ (when-not (= 200 (:status accept)) (fail! "pvp_accept_http" accept))
            _ (when-not (= 409 (:status (request! client base token "brock" "POST"
                                                     "/v1/pvp/turn"
                                                     {:chatId chat :playerId "brock" :saveId "a"
                                                      :fenceGeneration (fence store chat "brock") :operationId "a52-pvp-third"
                                                      :battleId battle-id :action "attack"
                                                      :expectedTurnIndex 0})))
                (fail! "pvp_third_party_not_rejected" {}))]
        ;; O primeiro turno é exercitado pelo proponente; a resposta é repetida com o
        ;; mesmo operationId para comprovar que o recibo não aplica dano duas vezes.
        (let [turn (request! client base token "ash" "POST" "/v1/pvp/turn"
                             {:chatId chat :playerId "ash" :saveId "a"
                              :fenceGeneration (fence store chat "ash")
                              :operationId "a52-pvp-turn-0" :battleId battle-id
                              :expectedTurnIndex 0 :action "attack" :moveIndex 0
                              :rng {:accuracy 0 :crit 99 :dodge 99 :damage-var 0}})]
          (when-not (= 200 (:status turn)) (fail! "pvp_turn_http" turn))
          (let [retry (request! client base token "ash" "POST" "/v1/pvp/turn"
                                {:chatId chat :playerId "ash" :saveId "a"
                                 :fenceGeneration (fence store chat "ash")
                                 :operationId "a52-pvp-turn-0" :battleId battle-id
                                 :expectedTurnIndex 0 :action "attack" :moveIndex 0
                                 :rng {:accuracy 0 :crit 99 :dodge 99 :damage-var 0}})]
            (when-not (= 200 (:status retry)) (fail! "pvp_turn_retry_http" retry))))
        {:battle-id battle-id :ok true})
      (finally (bridge/stop! server)))))

(defn- gym-http-flow!
  [{:keys [store chat]}]
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon!
     store chat "ash"
     {:nome n :hp 200 :ataque 200 :defesa 50 :atq-esp 200 :def-esp 50 :veloc 50
      :tipos ["normal"] :nivel 10
      :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40
                :classe :fisico :slug "tackle"}]}
     200 nil))
  (let [token "a52-token"
        server (bridge/start! {:host "127.0.0.1" :port 0 :token token :store store})
        client (HttpClient/newHttpClient)
        base (:base-url server)
        f (fence store chat "ash")]
    (try
      (let [team (request! client base token "ash" "POST" "/v1/gyms/team"
                           {:chatId chat :playerId "ash" :saveId "a"
                            :fenceGeneration f :indices [0 1 2]})
            _ (when-not (= 200 (:status team)) (fail! "gym_team_http" team))
            overview (request! client base token "ash" "GET"
                               (str "/v1/gyms?chatId=" chat "&playerId=ash") nil)
            _ (when-not (= 200 (:status overview)) (fail! "gym_overview_http" overview))
            start (request! client base token "ash" "POST" "/v1/gyms/challenge"
                            {:chatId chat :playerId "ash" :saveId "a"
                             :fenceGeneration (fence store chat "ash")
                             :operationId "a52-gym-start" :gymId "pedra"
                             :candidates [{:nome "Geodude" :hp 5 :ataque 10 :defesa 40
                                           :atq-esp 10 :def-esp 40 :veloc 40 :tipos ["rock"]
                                           :nivel 10 :golpes [{:nome-exibicao "Investida"
                                                               :tipo "normal" :poder 40
                                                               :classe "fisico"}]}
                                          {:nome "Sandshrew" :hp 5 :ataque 10 :defesa 40
                                           :atq-esp 10 :def-esp 40 :veloc 40 :tipos ["ground"]
                                           :nivel 10 :golpes [{:nome-exibicao "Investida"
                                                               :tipo "normal" :poder 40
                                                               :classe "fisico"}]}
                                          {:nome "Onix" :hp 5 :ataque 10 :defesa 40
                                           :atq-esp 10 :def-esp 40 :veloc 40 :tipos ["rock"]
                                           :nivel 10 :golpes [{:nome-exibicao "Investida"
                                                               :tipo "normal" :poder 40
                                                               :classe "fisico"}]}]})]
        (when-not (= 200 (:status start)) (fail! "gym_challenge_http" start))
        (when-not (= "gym" (get-in start [:body "data" "battle" "kind"]))
          (fail! "gym_battle_kind" start))
        {:battle-id (get-in start [:body "data" "battle" "battleId"]) :ok true})
      (finally (bridge/stop! server)))))

(defn- run-a52! []
  (let [gym (fixture! "gym")
        pvp (fixture! "pvp")]
    (println "A5.2 HTTP gym:" (gym-http-flow! gym))
    (println "A5.2 HTTP pvp:" (pvp-http-flow! pvp))
    (println "A5_2_GYM_PVP_OK")))

(defn -main [& _] (run-a52!))
