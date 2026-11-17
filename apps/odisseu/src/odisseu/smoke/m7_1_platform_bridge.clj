(ns odisseu.smoke.m7-1-platform-bridge
  "M7.1 — smoke HTTP platform (bloqueio/admins) + regressão M7.0.
   Sintético; sem WhatsApp; sem produção."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.tests.m70-bloqueio-admins-test]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m71-platform-token")
(def ^:private storage ".datomic-local/m71-platform-bridge")
(def ^:private system "odisseu-m71-bridge")

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
  (println "=== M7.1 platform bridge smoke ===")
  (println "==> regressão M7.0")
  (let [m70 (test/run-tests 'odisseu.platform.tests.m70-bloqueio-admins-test)]
    (assert! (and (zero? (:fail m70)) (zero? (:error m70))) "m70" m70))

  (let [client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db "poke")
        _ (persistence/delete-db! client-db "platform")
        poke (doto (poke-store/make-store (persistence/ensure-db! client-db "poke"))
               trainer/bootstrap!)
        platform (pstore/make-store (persistence/ensure-db! client-db "platform"))
        ;; import sintético
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
      (let [h (http client base "GET" "/health" nil)]
        (assert! (= 200 (:status h)) "health" h)
        (assert! (true? (get-in h [:body "data" "platformEnabled"])) "platform on" h)
        (swap! passed conj :health-platform))

      (let [b (http client base "GET" "/v1/platform/blocks" nil)
            entries (get-in b [:body "data" "blocks"])
            a (some #(when (= "chat-m70-a@g.us" (get % "chatId")) %) entries)]
        (assert! (= 200 (:status b)) "blocks" b)
        (assert! (some? a) "chat-a" b)
        (assert! (= ["naval" "quiz"] (get a "comandos")) "cmds-a" a)
        (swap! passed conj :get-blocks))

      (let [r1 (http client base "POST" "/v1/platform/blocks/bot"
                     {"chatId" "chat-iso-a@g.us" "blocked" true
                      "operationId" "m71-bot-1"})
            r2 (http client base "POST" "/v1/platform/blocks/bot"
                     {"chatId" "chat-iso-a@g.us" "blocked" true
                      "operationId" "m71-bot-1"})
            other (http client base "GET"
                        "/v1/platform/blocks?chatId=chat-iso-b@g.us" nil)]
        (assert! (= 200 (:status r1)) "bot1" r1)
        (assert! (true? (get-in r2 [:body "data" "duplicate"])) "dup" r2)
        (assert! (false? (get-in other [:body "data" "block" "bot?"])) "iso" other)
        (swap! passed conj :idempotent-iso))

      (let [a (http client base "POST" "/v1/platform/admins/remember"
                    {"chatId" "chat-iso-a@g.us" "playerId" "u1@c.us"
                     "operationId" "m71-adm-1"})
            k (http client base "POST" "/v1/platform/admins/known"
                    {"chatId" "chat-iso-a@g.us" "playerIds" ["u1@c.us"]})
            k2 (http client base "POST" "/v1/platform/admins/known"
                     {"chatId" "chat-iso-b@g.us" "playerIds" ["u1@c.us"]})]
        (assert! (= 200 (:status a) (:status k)) "adm" {})
        (assert! (true? (get-in k [:body "data" "known"])) "known-a" k)
        (assert! (false? (get-in k2 [:body "data" "known"])) "known-b" k2)
        (swap! passed conj :admins))

      ;; bridge down → cliente falha (simulado: stop + request)
      (bridge/stop! http-srv)
      (let [down (try
                   (http client base "GET" "/v1/platform/blocks" nil)
                   (catch Exception e {:ok? false :error (.getMessage e)}))]
        (assert! (or (false? (:ok? down)) (not= 200 (:status down))) "down" down)
        (swap! passed conj :bridge-down))

      ;; reopen
      (let [platform2 (pstore/make-store (persistence/ensure-db! client-db "platform"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store poke :platform-store platform2})
            base2 (:base-url http2)
            b (http client base2 "GET"
                    "/v1/platform/blocks?chatId=chat-iso-a@g.us" nil)]
        (try
          (assert! (true? (get-in b [:body "data" "block" "bot?"])) "persist" b)
          (swap! passed conj :reopen-persist)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M7.1"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :nota "Bridge platform staging. Produção continua Cassandra. Sem dual-write."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.1 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
