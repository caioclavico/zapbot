(ns odisseu.smoke.a5-3-raid-eggs
  "A5.3 — contrato HTTP de raids e ovos em Datomic Local descartável."
  (:require [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util UUID]))

(defn- assert! [ok message data]
  (when-not ok (throw (ex-info message data))))

(defn- http!
  [client base token actor method path body]
  (let [b (doto (HttpRequest/newBuilder (URI/create (str base path)))
            (.timeout (Duration/ofSeconds 30))
            (.header "Authorization" (str "Bearer " token)))]
    (when actor (.header b "X-Actor-Player-Id" actor))
    (when body (.header b "Content-Type" "application/json"))
    (let [req (if (= method "GET")
                (.GET b)
                (.method b method
                         (HttpRequest$BodyPublishers/ofString
                          (json/write-str (or body {})))))
          res (.send client (.build req) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode res) :body (json/read-str (.body res))})))

(defn- fixture! [kind]
  (let [id (str (UUID/randomUUID))
        system (str "odisseu-a53-" kind "-" id)
        storage (str ".datomic-local/a53-" kind "-" id)
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client (str "a53-" kind))
        s (doto (store/make-store conn) trainer/bootstrap!)]
    {:store s :chat (str "a53-" kind "-chat")}))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon!
   s chat player
   {:nome "Snorlax" :hp 220 :ataque 250 :defesa 80 :atq-esp 80 :def-esp 80
    :veloc 120 :tipos ["normal"] :nivel 20 :raridade "raro"
    :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 100
              :classe :fisico}]}
   220 nil))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(def ^:private boss
  {"nome" "Snorlax" "hp" 40 "ataque" 20 "defesa" 20 "atq-esp" 20
   "def-esp" 20 "veloc" 10 "tipos" ["normal"] "nivel" 20 "raridade" "raro"
   "golpes" [{"nome-exibicao" "Tackle" "tipo" "normal" "poder" 1
              "classe" "fisico"}]})

(defn- raid-flow! []
  (let [{:keys [store chat]} (fixture! "raid")
        _ (boot! store chat "ash")
        _ (boot! store chat "misty")
        _ (doseq [player ["ash" "misty"]]
            (reward/admin-seed-reward!
             store {:operation-id (str "a53-ball-" player) :chat-id chat
                    :player-id player :save-id "a"
                    :item {:item-id "ultra-bola" :qty 2}}))
        token "a53-token"
        server (bridge/start! {:host "127.0.0.1" :port 0 :token token
                               :store store :allow-test-rng true})
        client (HttpClient/newHttpClient)
        base (:base-url server)]
    (try
      (let [open (http! client base token "ash" "POST" "/v1/raids/open"
                        {:chatId chat :playerId "ash" :saveId "a"
                         :fenceGeneration (fence store chat "ash")
                         :operationId "a53-open" :raidId "a53-raid"
                         :ginasio "pedra" :liga "bronze" :chefe boss})]
        (assert! (= 200 (:status open)) "raid-open" open)
        (let [join-a (http! client base token "ash" "POST" "/v1/raids/join"
                            {:chatId chat :playerId "ash" :saveId "a"
                             :fenceGeneration (fence store chat "ash")
                             :operationId "a53-join-a" :raidId "a53-raid"
                             :collectionIndex 0})
              join-a-retry (http! client base token "ash" "POST" "/v1/raids/join"
                                   {:chatId chat :playerId "ash" :saveId "a"
                                    :fenceGeneration (fence store chat "ash")
                                    :operationId "a53-join-a" :raidId "a53-raid"
                                    :collectionIndex 0})
              join-m (http! client base token "misty" "POST" "/v1/raids/join"
                            {:chatId chat :playerId "misty" :saveId "a"
                             :fenceGeneration (fence store chat "misty")
                             :operationId "a53-join-m" :raidId "a53-raid"
                             :collectionIndex 0})]
          (assert! (= 200 (:status join-a)) "raid-join-a" join-a)
          (assert! (= 200 (:status join-m)) "raid-join-m" join-m)
          (assert! (true? (get-in join-a-retry [:body "data" "duplicate"]))
                   "raid-join-idempotency" join-a-retry)
          (let [start (http! client base token "ash" "POST" "/v1/raids/start"
                             {:chatId chat :playerId "ash" :saveId "a"
                              :fenceGeneration (fence store chat "ash")
                              :operationId "a53-start" :raidId "a53-raid"})]
            (assert! (= 200 (:status start)) "raid-start" start)
            (loop [n 0]
              (let [active (http! client base token "ash" "GET"
                                  (str "/v1/raids/active?chatId=" chat "&playerId=ash") nil)
                    raid (get-in active [:body "data" "raid"])
                    phase (get raid "fase")]
                (when (and (= "combate" phase) (< n 20))
                  (let [pid (get raid "vez")
                        turn (long (get raid "turnIndex"))
                        f (fence store chat pid)
                        attack (http! client base token pid "POST" "/v1/raids/attack"
                                       {:chatId chat :playerId pid :saveId "a"
                                        :fenceGeneration f :operationId (str "a53-atk-" n)
                                        :raidId "a53-raid" :expectedTurnIndex turn
                                        :moveIndex 0})]
                    (assert! (= 200 (:status attack)) "raid-attack" attack)
                    (recur (inc n))))))
            (let [done (http! client base token "ash" "GET"
                              (str "/v1/raids/active?chatId=" chat "&playerId=ash") nil)
                  phase (get-in done [:body "data" "raid" "fase"])
                  capture (http! client base token "ash" "POST" "/v1/raids/capture"
                                 {:chatId chat :playerId "ash" :saveId "a"
                                  :fenceGeneration (fence store chat "ash")
                                  :operationId "a53-capture" :raidId "a53-raid"
                                  :bola "ultra-bola" :roll 0})
                  capture-retry (http! client base token "ash" "POST" "/v1/raids/capture"
                                       {:chatId chat :playerId "ash" :saveId "a"
                                        :fenceGeneration (fence store chat "ash")
                                        :operationId "a53-capture" :raidId "a53-raid"
                                        :bola "ultra-bola" :roll 0})]
              (assert! (= "vitoria" phase) "raid-victory" done)
              (assert! (= 200 (:status capture)) "raid-capture" capture)
              (assert! (true? (get-in capture-retry [:body "data" "duplicate"]))
                       "raid-capture-idempotency" capture-retry)))))
      (finally (bridge/stop! server)))))

(defn- eggs-flow! []
  (let [{:keys [store chat]} (fixture! "eggs")
        _ (boot! store chat "ash")
        token "a53-eggs-token"
        server (bridge/start! {:host "127.0.0.1" :port 0 :token token
                               :store store :allow-test-rng true})
        client (HttpClient/newHttpClient)
        base (:base-url server)
        t0 1000000
        f #(fence store chat "ash")]
    (try
      (let [grant (http! client base token "ash" "POST" "/v1/adventure/eggs/grant"
                         {:chatId chat :playerId "ash" :saveId "a" :fenceGeneration (f)
                          :operationId "a53-egg-grant" :eggId "a53-egg"
                          :category "comum" :species "Pichu" :agoraMs t0})]
        (assert! (= 200 (:status grant)) "egg-grant" grant)
        (let [incu (http! client base token "ash" "POST" "/v1/adventure/eggs/incubate"
                          {:chatId chat :playerId "ash" :saveId "a" :fenceGeneration (f)
                           :operationId "a53-egg-incubate" :eggId "a53-egg" :agoraMs t0})
              due (+ t0 (* 2 60 60 1000))
              hatch (http! client base token "ash" "POST" "/v1/adventure/eggs/hatch"
                           {:chatId chat :playerId "ash" :saveId "a" :fenceGeneration (f)
                            :operationId "a53-egg-hatch" :eggId "a53-egg"
                            :hatchId "a53-hatch" :agoraMs due})
              retry (http! client base token "ash" "POST" "/v1/adventure/eggs/hatch"
                          {:chatId chat :playerId "ash" :saveId "a" :fenceGeneration (f)
                           :operationId "a53-egg-hatch" :eggId "a53-egg"
                           :hatchId "a53-hatch" :agoraMs due})]
          (assert! (= 200 (:status incu)) "egg-incubate" incu)
          (assert! (= 200 (:status hatch)) "egg-hatch" hatch)
          (assert! (true? (get-in retry [:body "data" "duplicate"]))
                   "egg-hatch-idempotency" retry)))
      (finally (bridge/stop! server)))))

(defn -main [& _]
  (raid-flow!)
  (eggs-flow!)
  (println "A5_3_RAID_EGGS_OK"))
