(ns odisseu.smoke.m6-1-staging
  "M6.1 — smoke sintético contra bridge de staging (loopback).
   Não usa WhatsApp/Chromium; não toca m53/m55-real-offline; sem produção."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.joy :as joy]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.lang.management ManagementFactory]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private storage ".datomic-local/m61-staging-smoke")
(def ^:private system "odisseu-m61-smoke")
(def ^:private db-name "staging-smoke")
(def ^:private token "m61-smoke-token")
(def ^:private chat "chat-m61-a")
(def ^:private chat-b "chat-m61-b")
(def ^:private player "ash")
(def ^:private player-b "misty")

(defn- rss-mb []
  (try
    (let [pid (first (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@"))
          proc (.. (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid])
                   (redirectErrorStream true)
                   start)
          out (str/trim (slurp (.getInputStream proc)))]
      (.waitFor proc)
      (when (re-matches #"\d+" out)
        (long (/ (Long/parseLong out) 1024))))
    (catch Exception _ nil)))

(defn- assert! [ok msg data]
  (when-not ok (throw (ex-info msg (or data {})))))

(defn- http [client base method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "Authorization" (str "Bearer " token)))
        bldr (if body
               (-> bldr
                   (.header "Content-Type" "application/json")
                   (.method method (HttpRequest$BodyPublishers/ofString
                                   (json/write-str body))))
               (.method bldr method (HttpRequest$BodyPublishers/noBody)))
        t0 (System/nanoTime)
        res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))
        ms (/ (double (- (System/nanoTime) t0)) 1e6)]
    {:status (.statusCode res)
     :body (json/read-str (.body res))
     :ms ms}))

(defn- fence [store c p]
  (long (:fence-generation (ports/get-player store c p) 0)))

(defn- seed-player! [store c p]
  (let [tag (str "m61-seed|" c "|" p)]
    (trainer/create-trainer! store {:chat-id c :player-id p})
    (coll-app/adicionar-pokemon!
     store c p
     {:species "Pikachu" :nome "Pikachu" :shiny false :nivel 15
      :hp 50 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
      :tipos ["elétrico"]
      :golpes [{:nome-exibicao "Choque do Trovão" :tipo "elétrico" :poder 40 :classe :especial}
               {:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
      :raridade "comum"}
     40 "envenenado")
    (coll-app/creditar-moedas! store c p 200 (str tag "|coins"))
    (coll-app/ajustar-inventario! store c p "poke-ball" 10 (str tag "|poke-ball"))
    (coll-app/ajustar-inventario! store c p "great-ball" 3 (str tag "|great-ball"))
    (coll-app/ajustar-inventario! store c p "pocao" 2 (str tag "|pocao"))))

(defn -main
  [& _]
  (println "=== M6.1 staging smoke (sintético, sem WhatsApp) ===")
  (when (re-find #"m53-real-offline|m55-real-offline" storage)
    (throw (ex-info "forbidden_storage" {:storage storage})))
  (let [client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db db-name)
        conn (persistence/ensure-db! client-db db-name)
        store (doto (store/make-store conn) trainer/bootstrap!)
        _ (seed-player! store chat player)
        _ (seed-player! store chat-b player-b)
        mem0 (rss-mb)
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store store :allow-test-rng true})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        passed (atom [])
        latencies (atom {})]
    (try
      (let [h (http client base "GET" "/health" nil)]
        (assert! (= 200 (:status h)) "health" h)
        (swap! latencies assoc :health (:ms h))
        (swap! passed conj :health))

      ;; trainer / saves
      (let [t (http client base "GET"
                    (str "/v1/trainer?chatId=" chat "&playerId=" player) nil)
            s (http client base "GET"
                    (str "/v1/saves?chatId=" chat "&playerId=" player) nil)]
        (assert! (= 200 (:status t)) "trainer" t)
        (assert! (= 200 (:status s)) "saves" s)
        (assert! (= 2 (count (get-in s [:body "data" "saves"]))) "2 saves" s)
        (swap! latencies assoc :trainer (:ms t) :saves (:ms s))
        (swap! passed conj :trainer-saves))

      ;; A→B→A
      (let [f0 (fence store chat player)
            b (http client base "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "b"
                     "expectedFenceGeneration" f0})
            f1 (get-in b [:body "data" "fenceGeneration"])
            a (http client base "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "a"
                     "expectedFenceGeneration" f1})]
        (assert! (= 200 (:status b)) "act b" b)
        (assert! (= 200 (:status a)) "act a" a)
        (swap! passed conj :switch-a-b-a))

      ;; moedas idempotentes + fence obsoleto
      (let [f (fence store chat player)
            r1 (http client base "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" "a"
                      "fenceGeneration" f "operationId" "m61-coins"
                      "coinsDelta" 10})
            r2 (http client base "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" "a"
                      "fenceGeneration" f "operationId" "m61-coins"
                      "coinsDelta" 10})
            stale (http client base "POST" "/v1/rewards/coins"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" 0 "operationId" "m61-stale"
                         "coinsDelta" 1})]
        (assert! (= 200 (:status r1)) "coins" r1)
        (assert! (true? (get-in r2 [:body "data" "duplicate"])) "dup" r2)
        (assert! (= 409 (:status stale)) "stale" stale)
        (swap! passed conj :idempotency-fence))

      ;; joy (antes da caça — usa o ferido semeado)
      (let [f (fence store chat player)
            j0 (http client base "GET"
                     (str "/v1/joy?chatId=" chat "&playerId=" player) nil)
            send (http client base "POST" "/v1/joy/send"
                       {"chatId" chat "playerId" player "saveId" "a"
                        "fenceGeneration" f "operationId" "m61-joy"
                        "collectionIndex" 0
                        "agoraMs" (System/currentTimeMillis)})
            early (http client base "POST" "/v1/joy/collect"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" (fence store chat player)
                         "operationId" "m61-joy-early"
                         "agoraMs" (System/currentTimeMillis)})
            ok (http client base "POST" "/v1/joy/collect"
                     {"chatId" chat "playerId" player "saveId" "a"
                      "fenceGeneration" (fence store chat player)
                      "operationId" "m61-joy-ok"
                      "agoraMs" (+ (System/currentTimeMillis) joy/tempo-tratamento-ms 1000)})]
        (assert! (= 200 (:status j0)) "joy status" j0)
        (assert! (= 200 (:status send)) "joy send" send)
        (assert! (not= 200 (:status early)) "joy early" early)
        (assert! (= 200 (:status ok)) "joy collect" ok)
        (swap! passed conj :joy))

      ;; hunt → turn → captura (RNG de teste)
      (let [cand {"nome" "Magikarp" "species" "Magikarp" "hp" 5 "ataque" 10
                  "defesa" 1 "atq-esp" 10 "def-esp" 1 "veloc" 80
                  "tipos" ["água"] "nivel" 1 "taxa-captura" 255
                  "golpes" [{"slug" "splash" "nome-exibicao" "Splash" "tipo" "normal"
                             "poder" 0 "classe" "status"}]}
            plan (http client base "GET"
                       (str "/v1/hunt/plan?chatId=" chat "&playerId=" player
                            "&rollTipos=50&rollSurto=99")
                       nil)
            _ (assert! (= 200 (:status plan)) "hunt plan" plan)
            data (get-in plan [:body "data"])
            f (get data "fenceGeneration")
            start (http client base "POST" "/v1/hunt/start"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" f "operationId" "m61-hunt"
                         "areaId" (get-in data ["area" "id"])
                         "climaId" (get-in data ["clima" "id"])
                         "tiposEncontro" (get data "tiposEncontro")
                         "surto" false
                         "poderAlvo" (get data "poderAlvo")
                         "shinyRoll" 1
                         "candidates" [cand]})
            _ (assert! (= 200 (:status start)) "hunt start" start)
            bid (get-in start [:body "data" "battleId"])
            turn (http client base "POST" "/v1/battles/turn"
                       {"chatId" chat "playerId" player "saveId" "a"
                        "fenceGeneration" f "operationId" "m61-turn"
                        "battleId" bid "expectedTurnIndex" 0
                        "action" "attack" "moveIndex" 0
                        "rng" {"damage-var" 10 "crit" 99 "flee" 99 "accuracy" 0
                               "contagio" 99 "status-golpe" 99 "wild-move" 0
                               "impede-para" 99}})
            _ (assert! (= 200 (:status turn)) "battle turn" turn)
            cap (http client base "POST" "/v1/encounter/capture"
                      {"chatId" chat "playerId" player "saveId" "a"
                       "fenceGeneration" f "operationId" "m61-cap"
                       "battleId" bid "bola" "pokebola" "roll" 0})]
        (assert! (or (= 200 (:status cap))
                     (true? (get-in turn [:body "data" "aguardandoCaptura"])))
                 "capture path" {:turn turn :cap cap})
        (swap! latencies assoc :hunt-start (:ms start) :battle-turn (:ms turn))
        (swap! passed conj :hunt-battle-capture))

      ;; ginásios + raids (leitura)
      (let [gyms (http client base "GET"
                       (str "/v1/gyms?chatId=" chat "&playerId=" player) nil)
            raids (http client base "GET"
                        (str "/v1/raids/active?chatId=" chat "&playerId=" player) nil)]
        (assert! (= 200 (:status gyms)) "gyms" gyms)
        (assert! (= 200 (:status raids)) "raids" raids)
        (swap! passed conj :gyms-raids-read))

      ;; shop / inventário
      (let [shop (http client base "GET"
                       (str "/v1/shop?chatId=" chat "&playerId=" player) nil)
            inv (http client base "GET"
                      (str "/v1/inventory?chatId=" chat "&playerId=" player) nil)]
        (assert! (= 200 (:status shop)) "shop" shop)
        (assert! (= 200 (:status inv)) "inv" inv)
        (swap! passed conj :shop))

      ;; isolamento entre jogadores
      (let [ta (http client base "GET"
                     (str "/v1/trainer?chatId=" chat "&playerId=" player) nil)
            tb (http client base "GET"
                     (str "/v1/trainer?chatId=" chat-b "&playerId=" player-b) nil)]
        (assert! (= 200 (:status ta) (:status tb)) "iso" {})
        (assert! (not= (get-in ta [:body "data" "playerId"])
                       (get-in tb [:body "data" "playerId"]))
                 "players differ" {})
        (swap! passed conj :player-isolation))

      ;; reinício JVM (reopen store)
      (bridge/stop! http-srv)
      (let [store2 (doto (store/make-store
                          (persistence/ensure-db!
                           (persistence/client {:system system :storage-dir storage})
                           db-name))
                     trainer/bootstrap!)
            coins (:coins (ports/get-save store2 chat player "a"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store store2 :allow-test-rng true})
            base2 (:base-url http2)
            t (http client base2 "GET"
                    (str "/v1/trainer?chatId=" chat "&playerId=" player) nil)]
        (try
          (assert! (pos? coins) "coins persist" {:coins coins})
          (assert! (= 200 (:status t)) "trainer after reopen" t)
          (swap! passed conj :persist-reopen)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M6.1"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :latencies-ms @latencies
                    :rss-mb {:before mem0 :after (rss-mb)}
                    :nota "Smoke JVM sintético. Chromium/WhatsApp não exercitados aqui."
                    :storage storage}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M6.1 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
