(ns odisseu.smoke.m7-4-rank-bridge
  "M7.4 — smoke HTTP rank + regressões M7.0–M7.3.
   Sintético; sem WhatsApp; sem produção."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.tests.m70-bloqueio-admins-test]
            [odisseu.platform.tests.m72-enquetes-test]
            [odisseu.platform.tests.m73-quiz-historico-test]
            [odisseu.platform.tests.m74-rank-test]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m74-platform-token")
(def ^:private storage ".datomic-local/m74-platform-bridge")
(def ^:private system "odisseu-m74-bridge")

(defn- http [client base method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 20))
                 (.header "Authorization" (str "Bearer " token)))
        bldr (if body
               (-> bldr
                   (.header "Content-Type" "application/json")
                   (.method method (HttpRequest$BodyPublishers/ofString
                                   (json/write-str body))))
               (.method bldr method (HttpRequest$BodyPublishers/noBody)))
        res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn- assert! [ok msg data]
  (when-not ok (throw (ex-info msg (or data {})))))

(defn -main
  [& _]
  (println "=== M7.4 rank bridge smoke ===")
  (doseq [[label ns] [["M7.0" 'odisseu.platform.tests.m70-bloqueio-admins-test]
                      ["M7.2" 'odisseu.platform.tests.m72-enquetes-test]
                      ["M7.3" 'odisseu.platform.tests.m73-quiz-historico-test]
                      ["M7.4" 'odisseu.platform.tests.m74-rank-test]]]
    (println "==>" label)
    (let [r (test/run-tests ns)]
      (assert! (and (zero? (:fail r)) (zero? (:error r))) label r)))

  (let [client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db "poke")
        _ (persistence/delete-db! client-db "platform")
        poke (doto (poke-store/make-store (persistence/ensure-db! client-db "poke"))
               trainer/bootstrap!)
        platform (pstore/make-store (persistence/ensure-db! client-db "platform"))
        dump (pipe/load-synthetic-dump "fixtures/m7-0/cassandra-dump-synthetic.edn")
        ir (pipe/export-modules dump)
        _ (pipe/import-ir! platform ir)
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store poke :platform-store platform
                                 :allow-test-rng true})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        passed (atom [])]
    (try
      (let [g (http client base "GET" "/v1/platform/rank?chatId=chat-m70-a@g.us" nil)
            top (get-in g [:body "data" "top"])]
        (assert! (= 200 (:status g)) "get" g)
        (assert! (= 3 (get (first top) "pontos")) "imported" top)
        (swap! passed conj :get-imported))

      (let [s1 (http client base "POST" "/v1/platform/rank/score"
                     {"chatId" "chat-m74@g.us" "playerId" "u1@c.us"
                      "name" "Ash" "game" "velha" "operationId" "m74-s1"})
            s2 (http client base "POST" "/v1/platform/rank/score"
                     {"chatId" "chat-m74@g.us" "playerId" "u1@c.us"
                      "name" "Ash" "game" "velha" "operationId" "m74-s1"})
            s3 (http client base "POST" "/v1/platform/rank/score"
                     {"chatId" "chat-m74@g.us" "playerId" "u1@c.us"
                      "name" "Ash" "game" "quiz" "operationId" "m74-s2"})]
        (assert! (= 200 (:status s1)) "score" s1)
        (assert! (true? (get-in s2 [:body "data" "duplicate"])) "dup" s2)
        (assert! (= 2 (get-in s3 [:body "data" "player" "pontos"])) "acc" s3)
        (swap! passed conj :score-idempotent))

      (let [e1 (http client base "POST" "/v1/platform/rank/effect"
                     {"id" "eff-m74" "type" "rank.increment"
                      "chatId" "chat-m74@g.us" "playerId" "u2@c.us"
                      "playerName" "Misty" "game" "pokemon"})
            e2 (http client base "POST" "/v1/platform/rank/effect"
                     {"id" "eff-m74" "type" "rank.increment"
                      "chatId" "chat-m74@g.us" "playerId" "u2@c.us"
                      "playerName" "Misty" "game" "pokemon"})]
        (assert! (= 200 (:status e1)) "eff" e1)
        (assert! (true? (get-in e2 [:body "data" "duplicate"])) "eff-dup" e2)
        (swap! passed conj :effect-dedupe))

      (bridge/stop! http-srv)
      (let [down (try
                   (http client base "GET" "/v1/platform/rank" nil)
                   (catch Exception e {:ok? false}))]
        (assert! (or (false? (:ok? down)) (not= 200 (:status down))) "down" down)
        (swap! passed conj :bridge-down))

      (let [platform2 (pstore/make-store (persistence/ensure-db! client-db "platform"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store poke :platform-store platform2})
            base2 (:base-url http2)
            g (http client base2 "GET" "/v1/platform/rank?chatId=chat-m74@g.us" nil)]
        (try
          (assert! (= 2 (get-in g [:body "data" "top" 0 "pontos"])) "reopen" g)
          (swap! passed conj :reopen-persist)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M7.4"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :nota "Rank via Datomic em staging. Produção Cassandra. Sem dual-write."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.4 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
