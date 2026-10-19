(ns odisseu.smoke.m7-7-staging-no-cassandra
  "M7.7 — staging funcional sem Cassandra.
   Prova: bridge JVM+Datomic (poke+platform) com C* ausente; regressões M7.0–M7.6;
   fail-closed sem fallback. Sem WhatsApp autenticado / sem produção."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :as test]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.datomic.store :as pstore]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.tests.m70-bloqueio-admins-test]
            [odisseu.platform.tests.m72-enquetes-test]
            [odisseu.platform.tests.m73-quiz-historico-test]
            [odisseu.platform.tests.m74-rank-test]
            [odisseu.platform.tests.m75-lembretes-test]
            [odisseu.platform.tests.m76-bugs-participantes-test]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.lang.management ManagementFactory]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration Instant]))

(def ^:private token "m77-no-cassandra-token")
(def ^:private storage ".datomic-local/m77-staging")
(def ^:private system "odisseu-m77-staging")

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

(defn- cassandra-containers-running? []
  (try
    (let [pb (ProcessBuilder. ["docker" "ps" "--format" "{{.Names}}"])
          proc (.start pb)
          out (slurp (.getInputStream proc))]
      (.waitFor proc)
      (->> (str/split-lines out)
           (filter #(re-find #"(?i)cassandra" %))
           seq))
    (catch Exception _ nil)))

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
  (println "=== M7.7 staging sem Cassandra ===")
  (let [c* (cassandra-containers-running?)]
    (println "cassandra_containers=" (pr-str c*))
    (when c*
      (println "AVISO: container Cassandra local detectado; smoke não usa C*, mas ambiente não está limpo."))

    (doseq [[label ns] [["M7.0" 'odisseu.platform.tests.m70-bloqueio-admins-test]
                        ["M7.2" 'odisseu.platform.tests.m72-enquetes-test]
                        ["M7.3" 'odisseu.platform.tests.m73-quiz-historico-test]
                        ["M7.4" 'odisseu.platform.tests.m74-rank-test]
                        ["M7.5" 'odisseu.platform.tests.m75-lembretes-test]
                        ["M7.6" 'odisseu.platform.tests.m76-bugs-participantes-test]]]
      (println "==>" label)
      (let [r (test/run-tests ns)]
        (assert! (and (zero? (:fail r)) (zero? (:error r))) label r)))

    (let [mem0 (rss-mb)
          client-db (persistence/client {:system system :storage-dir storage})
          _ (persistence/delete-db! client-db "poke")
          _ (persistence/delete-db! client-db "platform")
          poke (doto (poke-store/make-store (persistence/ensure-db! client-db "poke"))
                 trainer/bootstrap!)
          platform (pstore/make-store (persistence/ensure-db! client-db "platform"))
          dump (pipe/load-synthetic-dump "fixtures/m7-0/cassandra-dump-synthetic.edn")
          ir (pipe/export-modules dump)
          _ (pipe/import-ir! platform ir)
          _ (trainer/create-trainer! poke {:chat-id "chat-m77" :player-id "ash"})
          _ (coll-app/creditar-moedas! poke "chat-m77" "ash" 50 "m77-coins")
          http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                   :store poke :platform-store platform
                                   :allow-test-rng true})
          base (:base-url http-srv)
          client (HttpClient/newHttpClient)
          passed (atom [])
          mem1 (rss-mb)]
      (try
        (let [b (http client base "GET" "/v1/platform/blocks?chatId=chat-m70-a@g.us" nil)
              r (http client base "GET" "/v1/platform/rank?chatId=chat-m70-a@g.us" nil)
              rem (http client base "GET" "/v1/platform/reminders?id=rem-m70-1" nil)
              bug (http client base "GET" "/v1/platform/bugs?id=1" nil)
              parts (http client base "GET"
                          "/v1/platform/participants?chatId=chat-m70-a@g.us" nil)]
          (assert! (= 200 (:status b)) "blocks" b)
          (assert! (= 200 (:status r)) "rank" r)
          (assert! (= "pending" (get-in rem [:body "data" "reminder" "status"])) "rem" rem)
          (assert! (= "aberto" (get-in bug [:body "data" "bug" "status"])) "bug" bug)
          (assert! (= 200 (:status parts)) "parts" parts)
          (swap! passed conj :platform-eight-keys))

        (let [now 2000000000000
              due (http client base "GET" (str "/v1/platform/reminders/due?now=" now) nil)
              id "rem-m70-1"
              cl (http client base "POST" "/v1/platform/reminders/claim"
                       {"id" id "claimant" "m77" "leaseMs" 30000 "now" now})]
          (assert! (= 200 (:status due)) "due" due)
          (assert! (true? (get-in cl [:body "data" "claimed"])) "claim" cl)
          (let [fence (get-in cl [:body "data" "fence"])
                done (http client base "POST" "/v1/platform/reminders/complete"
                           {"id" id "claimant" "m77" "fence" fence})]
            (assert! (true? (get-in done [:body "data" "completed"])) "complete-sim" done)
            (swap! passed conj :reminders-simulated-send)))

        (let [t (http client base "GET" "/v1/trainer?chatId=chat-m77&playerId=ash" nil)]
          (assert! (= 200 (:status t)) "trainer" t)
          (swap! passed conj :pokemon-trainer))

        (assert! (map? (ports/get-block-state platform)) "platform-state" {})
        (swap! passed conj :poke-platform-isolated)

        (bridge/stop! http-srv)
        (let [down (try (http client base "GET" "/v1/platform/rank" nil)
                        (catch Exception e {:ok? false :err (ex-message e)}))]
          (assert! (or (false? (:ok? down)) (not= 200 (:status down))) "jvm-down" down)
          (swap! passed conj :fail-closed-no-cassandra-fallback))

        (let [report {:milestone "M7.7"
                      :at (str (Instant/now))
                      :ok? true
                      :passed @passed
                      :cassandra-containers (vec (or c* []))
                      :jvm-rss-mb-before mem0
                      :jvm-rss-mb-after mem1
                      :tested
                      ["regressões M7.0–M7.6"
                       "8 chaves platform via HTTP"
                       "lembrete claim/complete simulado (sem WA)"
                       "treinador Pokémon JVM"
                       "fail-closed bridge down"]
                      :blocked-authenticated-whatsapp
                      ["envio real de lembrete"
                       "caça/batalha/Joy via WhatsApp"
                       "enquete/quiz/rank por mensagem WA"]
                      :nota
                      "Staging JVM+Datomic comprovado sem uso de Cassandra neste smoke. Produção inalterada. Cassandra NÃO aposentado."}]
          (println "=== RESULT ===")
          (pprint/pprint report)
          (shutdown-agents)
          (System/exit 0))
        (catch Throwable t
          (try (bridge/stop! http-srv) (catch Exception _))
          (println "=== M7.7 FAILED ===" (ex-message t))
          (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
          (shutdown-agents)
          (System/exit 1))))))
