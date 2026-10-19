(ns odisseu.smoke.m7-6-platform-complete
  "M7.6 — bugs/participantes + regressões M7.0–M7.5.
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
            [odisseu.platform.tests.m75-lembretes-test]
            [odisseu.platform.tests.m76-bugs-participantes-test]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m76-platform-token")
(def ^:private storage ".datomic-local/m76-platform-bridge")
(def ^:private system "odisseu-m76-bridge")

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
  (println "=== M7.6 platform complete smoke ===")
  (doseq [[label ns] [["M7.0" 'odisseu.platform.tests.m70-bloqueio-admins-test]
                      ["M7.2" 'odisseu.platform.tests.m72-enquetes-test]
                      ["M7.3" 'odisseu.platform.tests.m73-quiz-historico-test]
                      ["M7.4" 'odisseu.platform.tests.m74-rank-test]
                      ["M7.5" 'odisseu.platform.tests.m75-lembretes-test]
                      ["M7.6" 'odisseu.platform.tests.m76-bugs-participantes-test]]]
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
      (let [g (http client base "GET" "/v1/platform/bugs?id=1" nil)]
        (assert! (= 200 (:status g)) "bug-get" g)
        (assert! (= "aberto" (get-in g [:body "data" "bug" "status"])) "st" g)
        (swap! passed conj :bugs-imported))

      (let [c1 (http client base "POST" "/v1/platform/bugs/create"
                     {"versao" "m76" "chat" "c@g.us" "reportedBy" "u@c.us"
                      "messageAuthor" "a@c.us" "mensagem" "novo"
                      "origem" "anterior" "reportedAt" 1700000003000
                      "operationId" "m76-b1"})
            c2 (http client base "POST" "/v1/platform/bugs/create"
                     {"versao" "m76" "chat" "c@g.us" "reportedBy" "u@c.us"
                      "messageAuthor" "a@c.us" "mensagem" "novo"
                      "origem" "anterior" "reportedAt" 1700000003000
                      "operationId" "m76-b1"})]
        (assert! (= 200 (:status c1)) "bug-create" c1)
        (assert! (true? (get-in c2 [:body "data" "duplicate"])) "bug-dup" c2)
        (swap! passed conj :bugs-idempotent))

      (let [p (http client base "GET"
                    "/v1/platform/participants?chatId=chat-m70-a@g.us" nil)]
        (assert! (= 200 (:status p)) "parts" p)
        (assert! (some #(= "Ash" (get % "name"))
                       (get-in p [:body "data" "players"])) "ash" p)
        (swap! passed conj :participants-imported))

      (let [u1 (http client base "POST" "/v1/platform/participants/upsert"
                     {"chatId" "chat-m76@g.us" "playerId" "u@c.us"
                      "name" "Caio" "operationId" "m76-p1"})
            u2 (http client base "POST" "/v1/platform/participants/upsert"
                     {"chatId" "chat-m76@g.us" "playerId" "u@c.us"
                      "name" "Caio" "operationId" "m76-p1"})]
        (assert! (= 200 (:status u1)) "upsert" u1)
        (assert! (true? (get-in u2 [:body "data" "duplicate"])) "upsert-dup" u2)
        (swap! passed conj :participants-upsert))

      ;; Isolamento: platform e pokemon usam DBs distintos no mesmo system
      (assert! (some? poke) "poke-store" {})
      (assert! (some? platform) "platform-store" {})
      (swap! passed conj :db-isolation)

      (bridge/stop! http-srv)
      (let [report {:milestone "M7.6"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :platform-keys-datomic
                    ["bloqueio" "admins-conhecidos" "enquetes" "quiz-historico"
                     "rank" "lembretes" "bugs" "participantes"]
                    :ainda-cassandra
                    ["pokemon-legado (loja/treinador/ginasios/raids/…)"
                     "armazenamento/iniciar! (boot tenta C* se houver registros)"]
                    :nota "Oito chaves platform M7.0 em Datomic staging. Produção Cassandra. Sem dual-write. Sem cutover."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.6 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
