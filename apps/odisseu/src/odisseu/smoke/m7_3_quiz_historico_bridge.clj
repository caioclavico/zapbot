(ns odisseu.smoke.m7-3-quiz-historico-bridge
  "M7.3 — smoke HTTP quiz-historico + regressões M7.0–M7.2.
   Sintético; sem WhatsApp; sem produção. Rank permanece Cassandra."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.tests.m70-bloqueio-admins-test]
            [odisseu.platform.tests.m72-enquetes-test]
            [odisseu.platform.tests.m73-quiz-historico-test]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m73-platform-token")
(def ^:private storage ".datomic-local/m73-platform-bridge")
(def ^:private system "odisseu-m73-bridge")

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
  (println "=== M7.3 quiz-historico bridge smoke ===")
  (println "==> regressão M7.0")
  (let [m70 (test/run-tests 'odisseu.platform.tests.m70-bloqueio-admins-test)]
    (assert! (and (zero? (:fail m70)) (zero? (:error m70))) "m70" m70))
  (println "==> regressão M7.2")
  (let [m72 (test/run-tests 'odisseu.platform.tests.m72-enquetes-test)]
    (assert! (and (zero? (:fail m72)) (zero? (:error m72))) "m72" m72))
  (println "==> testes M7.3 domínio")
  (let [m73 (test/run-tests 'odisseu.platform.tests.m73-quiz-historico-test)]
    (assert! (and (zero? (:fail m73)) (zero? (:error m73))) "m73" m73))

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
      (let [h (http client base "GET" "/health" nil)]
        (assert! (= 200 (:status h)) "health" h)
        (swap! passed conj :health))

      (let [g (http client base "GET" "/v1/platform/quiz-history" nil)
            entries (get-in g [:body "data" "histories"])
            a (some #(when (= "chat-m70-a@g.us" (get % "chatId")) %) entries)]
        (assert! (= 200 (:status g)) "get" g)
        (assert! (= ["Capital do Brasil?" "Maior planeta?"] (get a "questions")) "imp" a)
        (swap! passed conj :get-imported))

      (let [a1 (http client base "POST" "/v1/platform/quiz-history/append"
                     {"chatId" "chat-m73-a@g.us" "question" "Nova?"
                      "operationId" "m73-a1"})
            a2 (http client base "POST" "/v1/platform/quiz-history/append"
                     {"chatId" "chat-m73-a@g.us" "question" "Nova?"
                      "operationId" "m73-a1"})
            other (http client base "GET"
                        "/v1/platform/quiz-history?chatId=chat-m73-b@g.us" nil)]
        (assert! (= 200 (:status a1)) "append" a1)
        (assert! (true? (get-in a2 [:body "data" "duplicate"])) "dup" a2)
        (assert! (= [] (get-in other [:body "data" "questions"])) "iso" other)
        (swap! passed conj :append-idempotent-iso))

      ;; polls ainda ok (M7.2)
      (let [p (http client base "GET" "/v1/platform/polls" nil)]
        (assert! (= 200 (:status p)) "polls" p)
        (swap! passed conj :polls-regression))

      (bridge/stop! http-srv)
      (let [down (try
                   (http client base "GET" "/v1/platform/quiz-history" nil)
                   (catch Exception e {:ok? false :error (.getMessage e)}))]
        (assert! (or (false? (:ok? down)) (not= 200 (:status down))) "down" down)
        (swap! passed conj :bridge-down))

      (let [platform2 (pstore/make-store (persistence/ensure-db! client-db "platform"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store poke :platform-store platform2})
            base2 (:base-url http2)
            g (http client base2 "GET"
                    "/v1/platform/quiz-history?chatId=chat-m73-a@g.us" nil)]
        (try
          (assert! (= ["Nova?"] (get-in g [:body "data" "questions"])) "reopen" g)
          (swap! passed conj :reopen-persist)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M7.3"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :rank-migrated? false
                    :nota "quiz-historico via Datomic em staging. Rank/participantes Cassandra. Sem dual-write."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.3 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
