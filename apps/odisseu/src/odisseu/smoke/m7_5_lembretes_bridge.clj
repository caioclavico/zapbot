(ns odisseu.smoke.m7-5-lembretes-bridge
  "M7.5 — smoke HTTP lembretes + regressões M7.0–M7.4.
   Sintético; sem WhatsApp; sem produção; sem envio externo."
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
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m75-platform-token")
(def ^:private storage ".datomic-local/m75-platform-bridge")
(def ^:private system "odisseu-m75-bridge")

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
  (println "=== M7.5 lembretes bridge smoke ===")
  (doseq [[label ns] [["M7.0" 'odisseu.platform.tests.m70-bloqueio-admins-test]
                      ["M7.2" 'odisseu.platform.tests.m72-enquetes-test]
                      ["M7.3" 'odisseu.platform.tests.m73-quiz-historico-test]
                      ["M7.4" 'odisseu.platform.tests.m74-rank-test]
                      ["M7.5" 'odisseu.platform.tests.m75-lembretes-test]]]
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
        passed (atom [])
        now 1700000000000]
    (try
      (let [g (http client base "GET" "/v1/platform/reminders?id=rem-m70-1" nil)]
        (assert! (= 200 (:status g)) "get-imported" g)
        (assert! (= "pending" (get-in g [:body "data" "reminder" "status"])) "status" g)
        (assert! (= 1893456000000 (get-in g [:body "data" "reminder" "em"])) "due" g)
        (swap! passed conj :import-pending-no-auto-send))

      (let [c1 (http client base "POST" "/v1/platform/reminders/create"
                     {"id" "L-m75" "chatId" "chat-m75@g.us" "texto" "teste"
                      "criador" "u@c.us" "em" (- now 1000) "operationId" "m75-c1"})
            c2 (http client base "POST" "/v1/platform/reminders/create"
                     {"id" "L-m75" "chatId" "chat-m75@g.us" "texto" "teste"
                      "criador" "u@c.us" "em" (- now 1000) "operationId" "m75-c1"})]
        (assert! (= 200 (:status c1)) "create" c1)
        (assert! (true? (get-in c2 [:body "data" "duplicate"])) "create-dup" c2)
        (swap! passed conj :create-idempotent))

      (let [due (http client base "GET" (str "/v1/platform/reminders/due?now=" now "&limit=10") nil)
            ids (set (map #(get % "id") (get-in due [:body "data" "reminders"])))]
        (assert! (= 200 (:status due)) "due" due)
        (assert! (contains? ids "L-m75") "due-has" due)
        (swap! passed conj :due-query))

      (let [a (http client base "POST" "/v1/platform/reminders/claim"
                    {"id" "L-m75" "claimant" "w-a" "leaseMs" 30000 "now" now})
            b (http client base "POST" "/v1/platform/reminders/claim"
                    {"id" "L-m75" "claimant" "w-b" "leaseMs" 30000 "now" now})]
        (assert! (true? (get-in a [:body "data" "claimed"])) "claim-a" a)
        (assert! (false? (get-in b [:body "data" "claimed"])) "claim-b" b)
        (let [fence (get-in a [:body "data" "fence"])
              done (http client base "POST" "/v1/platform/reminders/complete"
                         {"id" "L-m75" "claimant" "w-a" "fence" fence})
              done2 (http client base "POST" "/v1/platform/reminders/complete"
                          {"id" "L-m75" "claimant" "w-a" "fence" fence})]
          (assert! (true? (get-in done [:body "data" "completed"])) "complete" done)
          (assert! (true? (get-in done2 [:body "data" "duplicate"])) "complete-dup" done2)
          (swap! passed conj :claim-complete-fence)))

      (let [f1 (http client base "POST" "/v1/platform/reminders/create"
                     {"id" "L-fail" "chatId" "chat-m75@g.us" "texto" "retry"
                      "em" (- now 1) "operationId" "m75-f1"})
            cl (http client base "POST" "/v1/platform/reminders/claim"
                     {"id" "L-fail" "claimant" "w" "leaseMs" 30000 "now" now})
            fl (http client base "POST" "/v1/platform/reminders/fail"
                     {"id" "L-fail" "claimant" "w"
                      "fence" (get-in cl [:body "data" "fence"])
                      "error" "wa_timeout"})]
        (assert! (= 200 (:status f1)) "fail-create" f1)
        (assert! (true? (get-in fl [:body "data" "failed"])) "failed" fl)
        (assert! (= "failed" (get-in fl [:body "data" "reminder" "status"])) "st" fl)
        (swap! passed conj :fail-recoverable))

      (bridge/stop! http-srv)
      (let [platform2 (pstore/make-store (persistence/ensure-db! client-db "platform"))
            http2 (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                  :store poke :platform-store platform2})
            base2 (:base-url http2)
            g (http client base2 "GET" "/v1/platform/reminders?id=L-m75" nil)]
        (try
          (assert! (= "completed" (get-in g [:body "data" "reminder" "status"])) "reopen" g)
          (swap! passed conj :reopen-persist)
          (finally (bridge/stop! http2))))

      (let [report {:milestone "M7.5"
                    :at (str (Instant/now))
                    :ok? true
                    :passed @passed
                    :whatsapp "nenhum envio — smoke só JVM/HTTP"
                    :nota "Lembretes via Datomic em staging com claim/lease/fence. Produção Cassandra. Sem dual-write. Sem twin pollers."}]
        (println "=== RESULT ===")
        (pprint/pprint report)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (try (bridge/stop! http-srv) (catch Exception _))
        (println "=== M7.5 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
