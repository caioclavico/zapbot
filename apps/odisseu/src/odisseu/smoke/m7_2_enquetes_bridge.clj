(ns odisseu.smoke.m7-2-enquetes-bridge
  "M7.2 — smoke HTTP enquetes + regressões M7.0/M7.1.
   Sintético; sem WhatsApp; sem produção."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.tests.m70-bloqueio-admins-test]
            [odisseu.platform.tests.m72-enquetes-test]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m72-platform-token")
(def ^:private storage ".datomic-local/m72-platform-bridge")
(def ^:private system "odisseu-m72-bridge")

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
  (println "=== M7.2 enquetes bridge smoke ===")
  (println "==> regressão M7.0")
  (let [m70 (test/run-tests 'odisseu.platform.tests.m70-bloqueio-admins-test)]
    (assert! (and (zero? (:fail m70)) (zero? (:error m70))) "m70" m70))
  (println "==> testes M7.2 domínio")
  (let [m72 (test/run-tests 'odisseu.platform.tests.m72-enquetes-test)]
    (assert! (and (zero? (:fail m72)) (zero? (:error m72))) "m72" m72))

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
        (assert! (true? (get-in h [:body "data" "platformEnabled"])) "platform on" h)
        (swap! passed conj :health))

      (let [g (http client base "GET" "/v1/platform/polls" nil)
            entries (get-in g [:body "data" "polls"])
            a (some #(when (= "chat-m70-a@g.us" (get % "chatId")) %) entries)]
        (assert! (= 200 (:status g)) "get-polls" g)
        (assert! (some? a) "imported-poll" g)
        (assert! (= "Preferem café ou chá?" (get a "pergunta")) "q" a)
        (swap! passed conj :get-imported))

      (let [c1 (http client base "POST" "/v1/platform/polls/create"
                     {"chatId" "chat-m72-a@g.us"
                      "pergunta" "Cor favorita?"
                      "opcoes" ["Azul" "Verde" "Vermelho"]
                      "prefix" "!"
                      "operationId" "m72-create-1"})
            c2 (http client base "POST" "/v1/platform/polls/create"
                     {"chatId" "chat-m72-a@g.us"
                      "pergunta" "Cor favorita?"
                      "opcoes" ["Azul" "Verde" "Vermelho"]
                      "prefix" "!"
                      "operationId" "m72-create-1"})
            other (http client base "GET"
                        "/v1/platform/polls?chatId=chat-m72-b@g.us" nil)]
        (assert! (= 200 (:status c1)) "create" c1)
        (assert! (true? (get-in c2 [:body "data" "duplicate"])) "dup-create" c2)
        (assert! (nil? (get-in other [:body "data" "poll"])) "iso-empty" other)
        (swap! passed conj :create-idempotent-iso))

      (let [v1 (http client base "POST" "/v1/platform/polls/vote"
                     {"chatId" "chat-m72-a@g.us" "voterId" "u1@c.us"
                      "choice" 2 "operationId" "m72-vote-1"})
            v2 (http client base "POST" "/v1/platform/polls/vote"
                     {"chatId" "chat-m72-a@g.us" "voterId" "u1@c.us"
                      "choice" 2 "operationId" "m72-vote-1"})
            v3 (http client base "POST" "/v1/platform/polls/vote"
                     {"chatId" "chat-m72-a@g.us" "voterId" "u1@c.us"
                      "choice" 1 "operationId" "m72-vote-2"})]
        (assert! (= 200 (:status v1) (:status v3)) "vote" {})
        (assert! (true? (get-in v2 [:body "data" "duplicate"])) "dup-vote" v2)
        (assert! (= 1 (get-in v3 [:body "data" "poll" "votos" "u1@c.us"])) "revote" v3)
        (swap! passed conj :vote-change))

      (let [x1 (http client base "POST" "/v1/platform/polls/close"
                     {"chatId" "chat-m72-a@g.us" "operationId" "m72-close-1"})
            x2 (http client base "POST" "/v1/platform/polls/close"
                     {"chatId" "chat-m72-a@g.us" "operationId" "m72-close-1"})]
        (assert! (= 200 (:status x1)) "close" x1)
        (assert! (true? (get-in x1 [:body "data" "closed"])) "closed" x1)
        (assert! (true? (get-in x2 [:body "data" "duplicate"])) "dup-close" x2)
        (assert! (= "Cor favorita?" (get-in x2 [:body "data" "poll" "pergunta"])) "snap" x2)
        (swap! passed conj :close-idempotent))

      ;; blocks regressão rápida (M7.1 path)
      (let [b (http client base "GET" "/v1/platform/blocks" nil)]
        (assert! (= 200 (:status b)) "blocks-still" b)
        (swap! passed conj :blocks-regression))

      (bridge/stop! http-srv)
      (let [down (try
                   (http client base "GET" "/v1/platform/polls" nil)
                   (catch Exception e {:ok? false :error (.getMessage e)}))]
        (assert! (or (false? (:ok? down)) (not= 200 (:status down))) "down" down)
        (swap! passed conj :bridge-down))

      (let [platform2 (pstore/make-store (persistence/ensure-db! client-db "platform"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store poke :platform-store platform2})
            base2 (:base-url http2)
            g (http client base2 "GET"
                    "/v1/platform/polls?chatId=chat-m70-a@g.us" nil)]
        (try
          (assert! (= "Preferem café ou chá?"
                      (get-in g [:body "data" "poll" "pergunta"]))
                   "reopen" g)
          (swap! passed conj :reopen-persist)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M7.2"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :nota "Enquetes via Datomic em staging. Produção Cassandra. Sem dual-write."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.2 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
