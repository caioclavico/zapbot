(ns odisseu.smoke.m4-tx
  "Smoke M4.2: fluxo transacional ensure → saves → activate → coins → reconcile.
  Inclui fence obsoleto, idempotência, corrida escrita×activate e reabertura."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.lang.management ManagementFactory]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Instant Duration]
           [java.util.concurrent Executors TimeUnit]))

(defn- rss-mb
  []
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

(defn- assert!
  [cond msg]
  (when-not cond (throw (ex-info msg {}))))

(defn- http
  [client base token method path & [body]]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base path)))
                    (.timeout (Duration/ofSeconds 8))
                    (.header "Authorization" (str "Bearer " token))
                    (.header "Accept" "application/json"))
        builder (if body
                  (-> builder
                      (.header "Content-Type" "application/json")
                      (.method method (HttpRequest$BodyPublishers/ofString
                                       (json/write-str body))))
                  (.method builder method (HttpRequest$BodyPublishers/noBody)))
        res (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn- boot-store!
  [system storage db]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db)
    (let [conn (persistence/ensure-db! client db)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [system storage db]
  (let [client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn -main
  [& _]
  (let [system "odisseu-m42-tests"
        storage ".datomic-local/m42-tests"
        db "bridge-tx"
        token "m42-smoke-token"
        chat "chat-m42"
        player "player-m42"
        s (boot-store! system storage db)
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        results (atom [])]
    (try
      ;; 1) ensure + list saves
      (let [ens (http client base token "POST" "/v1/trainer/ensure"
                      {"chatId" chat "playerId" player})]
        (assert! (= 200 (:status ens)) "ensure status")
        (assert! (true? (get-in ens [:body "data" "created"])) "created")
        (swap! results conj :ensure))

      (let [saves (http client base token "GET"
                        (str "/v1/saves?chatId=" chat "&playerId=" player))]
        (assert! (= 200 (:status saves)) "saves")
        (assert! (= "a" (get-in saves [:body "data" "activeSaveId"])) "active a")
        (assert! (= 2 (count (get-in saves [:body "data" "saves"]))) "two saves")
        (swap! results conj :saves))

      ;; 2) credit coins on A with explicit fence
      (let [snap (http client base token "GET"
                       (str "/v1/active-save?chatId=" chat "&playerId=" player))
            fence (get-in snap [:body "data" "fenceGeneration"])
            save-id (get-in snap [:body "data" "activeSaveId"])
            r1 (http client base token "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" save-id
                      "fenceGeneration" fence "operationId" "op-m42-coins-1"
                      "coinsDelta" 50})
            r2 (http client base token "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" save-id
                      "fenceGeneration" fence "operationId" "op-m42-coins-1"
                      "coinsDelta" 50})]
        (assert! (= 200 (:status r1)) "coins1")
        (assert! (false? (get-in r1 [:body "data" "duplicate"])) "not dup")
        (assert! (= 50 (get-in r1 [:body "data" "coins"])) "coins 50")
        (assert! (= 200 (:status r2)) "coins retry")
        (assert! (true? (get-in r2 [:body "data" "duplicate"])) "dup")
        (assert! (= 50 (get-in r2 [:body "data" "coins"])) "still 50")
        (swap! results conj :idempotent))

      ;; 3) payload mismatch
      (let [snap (http client base token "GET"
                       (str "/v1/active-save?chatId=" chat "&playerId=" player))
            fence (get-in snap [:body "data" "fenceGeneration"])
            bad (http client base token "POST" "/v1/rewards/coins"
                      {"chatId" chat "playerId" player "saveId" "a"
                       "fenceGeneration" fence "operationId" "op-m42-coins-1"
                       "coinsDelta" 99})]
        (assert! (= 409 (:status bad)) "mismatch 409")
        (assert! (= "receipt_payload_mismatch"
                    (get-in bad [:body "error" "code"])) "mismatch code")
        (swap! results conj :payload-mismatch))

      ;; 4) A→B→A isolation + stale fence
      (let [fence-a (get-in (http client base token "GET"
                                  (str "/v1/active-save?chatId=" chat "&playerId=" player))
                            [:body "data" "fenceGeneration"])
            act-b (http client base token "POST" "/v1/saves/activate"
                        {"chatId" chat "playerId" player "saveId" "b"
                         "expectedFenceGeneration" fence-a})
            _ (assert! (= 200 (:status act-b)) "activate b")
            fence-b (get-in act-b [:body "data" "fenceGeneration"])
            stale (http client base token "POST" "/v1/rewards/coins"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" fence-a "operationId" "op-stale-a"
                         "coinsDelta" 7})
            _ (assert! (= 409 (:status stale)) "stale 409")
            _ (assert! (= "stale_writer_fence"
                          (get-in stale [:body "error" "code"])) "stale code")
            ok-b (http client base token "POST" "/v1/rewards/coins"
                       {"chatId" chat "playerId" player "saveId" "b"
                        "fenceGeneration" fence-b "operationId" "op-coins-b"
                        "coinsDelta" 11})
            _ (assert! (= 200 (:status ok-b)) "coins b")
            act-a (http client base token "POST" "/v1/saves/activate"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "expectedFenceGeneration" fence-b})
            saves (http client base token "GET"
                        (str "/v1/saves?chatId=" chat "&playerId=" player))
            by-id (into {} (map (fn [x] [(get x "saveId") x])
                                (get-in saves [:body "data" "saves"])))]
        (assert! (= 200 (:status act-a)) "back to a")
        (assert! (= 50 (get-in by-id ["a" "coins"])) "a retains 50")
        (assert! (= 11 (get-in by-id ["b" "coins"])) "b has 11")
        (swap! results conj :a-b-a))

      ;; 5) race: mutate A vs activate B
      (let [ens2-chat "chat-race"
            ens2-player "player-race"
            _ (http client base token "POST" "/v1/trainer/ensure"
                    {"chatId" ens2-chat "playerId" ens2-player})
            snap (http client base token "GET"
                       (str "/v1/active-save?chatId=" ens2-chat "&playerId=" ens2-player))
            fence (get-in snap [:body "data" "fenceGeneration"])
            pool (Executors/newFixedThreadPool 2)
            fut-write (.submit pool
                               ^Callable
                               (fn []
                                 (http client base token "POST" "/v1/rewards/coins"
                                       {"chatId" ens2-chat "playerId" ens2-player
                                        "saveId" "a" "fenceGeneration" fence
                                        "operationId" "op-race-write"
                                        "coinsDelta" 3})))
            fut-act (.submit pool
                             ^Callable
                             (fn []
                               (Thread/sleep 2)
                               (http client base token "POST" "/v1/saves/activate"
                                     {"chatId" ens2-chat "playerId" ens2-player
                                      "saveId" "b"
                                      "expectedFenceGeneration" fence})))
            w (.get fut-write 5 TimeUnit/SECONDS)
            a (.get fut-act 5 TimeUnit/SECONDS)
            _ (.shutdown pool)
            saves (http client base token "GET"
                        (str "/v1/saves?chatId=" ens2-chat "&playerId=" ens2-player))
            coins-a (get (first (filter #(= "a" (get % "saveId"))
                                        (get-in saves [:body "data" "saves"])))
                         "coins")]
        ;; Uma das duas: escrita ok (3) ou stale/conflict; activate 200 ou 409.
        (assert! (contains? #{200 409} (:status w)) "race write status")
        (assert! (contains? #{200 409} (:status a)) "race act status")
        (assert! (contains? #{0 3} coins-a) "race coins a")
        (swap! results conj :race))

      ;; 6) reconcile via GET operation + admin blocked + reopen
      (let [op (http client base token "GET"
                     (str "/v1/operations/op-m42-coins-1?chatId=" chat "&playerId=" player))
            forbidden (http client base token "GET"
                            (str "/v1/operations/op-m42-coins-1?chatId=" chat
                                 "&playerId=outro-player"))
            bare (http client base token "GET" "/v1/operations/op-m42-coins-1")
            admin (http client base token "GET" "/admin/seed")
            admin2 (http client base token "POST" "/v1/admin/seed" {})]
        (assert! (= 200 (:status op)) "get op")
        (assert! (= "op-m42-coins-1" (get-in op [:body "data" "operationId"])) "op id")
        (assert! (true? (get-in op [:body "data" "persisted"])) "persisted")
        (assert! (= 409 (:status forbidden)) "op forbidden")
        (assert! (= 400 (:status bare)) "op requires identity")
        (assert! (= 404 (:status admin)) "admin get")
        (assert! (= 404 (:status admin2)) "admin post")
        (swap! results conj :reconcile-admin))

      (bridge/stop! http-srv)
      (let [s2 (reopen-store system storage db)
            coins (:coins (ports/get-save s2 chat player "a"))
            receipt (ports/get-receipt s2 "op-m42-coins-1")
            retry (tx/credit-coins!
                   s2 {:chat-id chat :player-id player :save-id "a"
                       :fence-generation (:fence-generation
                                          (ports/get-player s2 chat player))
                       :operation-id "op-m42-coins-1"
                       :coins-delta 50})]
        (assert! (= 50 coins) "reopen coins")
        (assert! (some? receipt) "reopen receipt")
        (assert! (true? (:duplicate retry)) "reopen idempotent")
        (swap! results conj :reopen))

      (println "=== M4.2 bridge tx smoke ===")
      (pprint/pprint {:ok true
                      :checks @results
                      :rss-mb (rss-mb)
                      :at (str (Instant/now))
                      :nota "JVM+HTTP loopback; dados fictícios; sem WhatsApp."})
      (shutdown-agents)
      (System/exit 0)
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M4.2 bridge tx FAILED ===")
        (pprint/pprint {:ok false :error (ex-message t) :data (ex-data t) :checks @results})
        (shutdown-agents)
        (System/exit 1)))))
