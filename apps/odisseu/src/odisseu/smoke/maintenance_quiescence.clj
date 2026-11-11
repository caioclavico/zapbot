(ns odisseu.smoke.maintenance-quiescence
  "Disposable HTTP/Datomic check for the bridge maintenance gate. No WhatsApp or production resources."
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.gym-audit :as gym-audit]
            [pokemon.infrastructure.datomic.schema :as pokemon-schema]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.maintenance-gate :as gate]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.time Duration]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- request! [client base method path token & [body]]
  (let [b (cond-> (HttpRequest/newBuilder)
            true (.uri (URI/create (str base path)))
            true (.timeout (Duration/ofSeconds 5))
            token (.header "X-A6-Maintenance-Token" token)
            true (.header "Authorization" "Bearer maintenance-smoke-bridge-token")
            true (.method method (if (some? body)
                                   (HttpRequest$BodyPublishers/ofString body)
                                   (HttpRequest$BodyPublishers/noBody)))
            true (.build))
        r (.send client b (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r)
     :body (try (json/read-str (.body r)) (catch Exception _ {}))}))

(defn- assert! [ok message data]
  (when-not ok (throw (ex-info message (or data {})))))

(defn -main [& _]
  (let [root (.toFile (Files/createTempDirectory "a6-maint-quiescence-" (make-array java.nio.file.attribute.FileAttribute 0)))
        storage (str (io/file root "datomic"))
        maint (str (io/file root "maint"))
        client-db (persistence/client {:system (str "a6-maint-smoke-" (System/nanoTime))
                                       :storage-dir storage})
        conn (persistence/ensure-db! client-db "smoke")
        _schema (pokemon-schema/install! conn)
        s (doto (store/make-store conn) trainer/bootstrap!)
        _fixtures (d/transact conn
                              {:tx-data [{:raid-auto-schedule/chat-id "audit-fixture-group-a"
                                          :raid-auto-schedule/enabled true
                                          :raid-auto-schedule/next-at 1000
                                          :raid-auto-schedule/last-raid-id "audit-fixture-raid"}
                                         {:raid-auto-schedule/chat-id "audit-fixture-group-b"
                                          :raid-auto-schedule/enabled true
                                          :raid-auto-schedule/next-at 2000
                                          :raid-auto-schedule/last-raid-id "audit-fixture-raid"}
                                         {:thematic-event-schedule/chat-id "audit-fixture-group-a"
                                          :thematic-event-schedule/enabled true
                                          :thematic-event-schedule/next-at 3000
                                          :thematic-event-schedule/last-occurrence-id "audit-fixture-occurrence"
                                          :thematic-event-schedule/last-start-ms 500}
                                         {:thematic-event-occurrence/id "audit-fixture-occurrence"
                                          :thematic-event-occurrence/chat-id "audit-fixture-group-a"
                                          :thematic-event-occurrence/catalog-id "fixture"
                                          :thematic-event-occurrence/inicio-ms 500
                                          :thematic-event-occurrence/fim-ms 4000
                                          :thematic-event-occurrence/status "active"}
                                         {:save/key "audit-fixture-save"
                                          :save/ultimo-bonus-diario "2026-11-06"
                                          :save/sequencia-bonus-diario 1
                                          :save/kit-inicial-resgatado true}
                                         {:pokemon/key "audit-fixture-pokemon"
                                          :pokemon/hp 20 :pokemon/hp-atual 0
                                          :pokemon/species "fixture" :pokemon/level 5 :pokemon/xp 12}
                                         {:battle/id "audit-fixture-battle"
                                          :battle/kind "gym" :battle/active? true
                                          :battle/phase "active" :battle/idle-expired? false
                                          :battle/last-activity-at 1000 :battle/state-edn "{}"}
                                         {:operation-receipt/id "audit-fixture-receipt"
                                          :operation-receipt/kind "gym-challenge-start"
                                          :operation-receipt/summary "{:battle-id \"audit-fixture-battle\"}"}
                                         {:gym-occupation/key "audit-fixture-occupation"
                                          :gym-occupation/team-edn "[]"}]})
        datoms-before (:datoms (d/db-stats (d/db conn)))
        operator-token "maintenance-smoke-operator-token"
        opts {:host "127.0.0.1" :port 0 :token "maintenance-smoke-bridge-token"
              :store s :maintenance-dir maint :maintenance-token operator-token
              :gym-audit! #(gym-audit/summarize (d/db conn) nil)}
        http-client (HttpClient/newHttpClient)
        srv (bridge/start! opts)
        base (:base-url srv)]
    (try
      (let [gate-dir (str (io/file root "gate-drain-test"))
            g (gate/create gate-dir)]
        (assert! (true? (gate/acquire! g)) "first active operation should enter" nil)
        (let [drain (future (gate/enter! g 2000))]
          (loop [remaining 100]
            (when (and (pos? remaining) (= :open (:phase (gate/status g))))
              (Thread/sleep 10)
              (recur (dec remaining))))
          (assert! (= :draining (:phase (gate/status g))) "drain must close admission first" nil)
          (assert! (not (gate/acquire! g)) "new job must be blocked during drain" nil)
          (gate/release! g)
          (assert! (:quiescent? @drain) "active operation must drain" nil))
        (let [g2 (gate/create gate-dir)]
          (assert! (= :quiescent (:phase (gate/status g2))) "restart must keep quiescent state" nil)
          (gate/resume! g2)
          (assert! (true? (gate/acquire! g2)) "explicit resume reopens admission" nil)
          (gate/release! g2)))

      (let [restart-dir (str (io/file root "jvm-process-restart"))
            g (gate/create restart-dir)
            _ (gate/enter! g 100)
            pb (ProcessBuilder. (into-array String
                                            ["clojure" "-M" "-e"
                                             "(require '[pokemon.infrastructure.http.maintenance-gate :as g]) (println (:phase (g/status (g/create (System/getenv \"A6_MAINT_DIR\")))))"]))
            _ (.put (.environment pb) "A6_MAINT_DIR" restart-dir)
            _ (.redirectErrorStream pb true)
            child (.start pb)
            output (slurp (.getInputStream child))
            child-exit (.waitFor child)]
        (assert! (zero? child-exit) "new JVM process must start" {:exit child-exit})
        (assert! (re-find #"quiescent" output) "new JVM must recover persistent maintenance state" nil))

      (let [timeout-dir (str (io/file root "gate-timeout-test"))
            g (gate/create timeout-dir)]
        (gate/acquire! g)
        (let [result (gate/enter! g 0)]
          (assert! (false? (:quiescent? result)) "drain timeout must not claim quiescence" result))
        (gate/release! g)
        (let [restarted (gate/create timeout-dir)]
          (assert! (= :draining (:phase (gate/status restarted))) "timeout/restart stays fail-closed" nil)
          (assert! (:quiescent? (gate/enter! restarted 100)) "retry drain after active work ends" nil)))

      (assert! (= 200 (:status (request! http-client base "GET" "/health" operator-token)))
               "health must remain available" nil)
      (assert! (= 401 (:status (request! http-client base "GET" "/ops/maintenance/status" "wrong-token")))
               "maintenance control must require its own token" nil)
      (assert! (= 401 (:status (request! http-client base "GET"
                                           "/ops/maintenance/a6-gym-state-audit" "wrong-token")))
               "aggregate audit must require the maintenance token" nil)
      (assert! (= 401 (:status (request! http-client base "GET"
                                           "/ops/maintenance/a6-gym-state-audit" nil)))
               "aggregate audit must reject a missing token" nil)
      (assert! (= 400 (:status (request! http-client base "GET"
                                           "/ops/maintenance/a6-gym-state-audit" operator-token "{")))
               "malformed request bodies must be rejected without parsing" nil)
      (let [audit (request! http-client base "GET" "/ops/maintenance/a6-gym-state-audit" operator-token)
            data (get-in audit [:body "data"])]
        (assert! (= 200 (:status audit)) "aggregate audit route must be available" audit)
        (assert! (and (map? data) (map? (get data "battles")) (map? (get data "pokemon")))
                 "audit route must return aggregate sections" audit)
        (assert! (not (re-find #"(?i)(player.?id|chat.?id|pokemon.?key|save.?id|battle.?id|@c\\.us|@lid)"
                               (pr-str data)))
                 "audit response must not contain identifiers" nil)
        (assert! (= true (get-in data ["migrations" "pokemon034"]))
                 "audit should report the disposable schema 034 marker" nil)
        (assert! (= "available" (get-in data ["schedulers" "raids" "status"]))
                 "raid scheduler aggregates should be schema-aware" nil)
        (assert! (= "available" (get-in data ["schedulers" "thematicEvents" "status"]))
                 "thematic event aggregates should be schema-aware" nil)
        (assert! (= 1 (get-in data ["battles" "gymOpenOrResumable"]))
                 "open gym battles should be counted without returning their identifiers" nil)
        (assert! (= 1 (get-in data ["gymStartReceipts" "committed"]))
                 "gym receipts should be aggregated" nil)
        (assert! (= 1 (get-in data ["pokemon" "hpZero"]))
                 "HP integrity should be aggregated" nil)
        (assert! (= 1 (get-in data ["gymOccupations" "invalidDefenderTeamCount"]))
                 "invalid gym occupation teams should be aggregated" nil)
        (assert! (= 1 (get-in data ["schedulers" "raids" "duplicateMarkerGroups"]))
                 "duplicate raid markers should be reported as counts" nil)
        (assert! (= 1 (get-in data ["schedulers" "thematicEvents" "activeOccurrences"]))
                 "active thematic events should be aggregated" nil)
        (assert! (= "unknown" (get-in data ["bagDaily" "dailyKitStatus"]))
                 "unsupported daily kit state must remain UNKNOWN" nil)
        (assert! (= datoms-before (:datoms (d/db-stats (d/db conn))))
                 "aggregate audit must not write Datomic" nil))

      (let [started (CountDownLatch. 1)
            release (CountDownLatch. 1)
            concurrent-dir (str (io/file root "maint-concurrent"))
            concurrent (bridge/start!
                        (assoc opts :port 0 :maintenance-dir concurrent-dir
                               :gym-audit! (fn []
                                             (.countDown started)
                                             (.await release 2 TimeUnit/SECONDS)
                                             {:aggregate true})))
            concurrent-base (:base-url concurrent)]
        (try
          (let [first-request (future (request! http-client concurrent-base "GET"
                                                "/ops/maintenance/a6-gym-state-audit" operator-token))]
            (assert! (.await started 2 TimeUnit/SECONDS) "first audit should start" nil)
            (assert! (= 429 (:status (request! http-client concurrent-base "GET"
                                               "/ops/maintenance/a6-gym-state-audit" operator-token)))
                     "concurrent audit should be rejected" nil)
            (.countDown release)
            (assert! (= 200 (:status @first-request)) "first audit should complete" nil))
          (finally
            (.countDown release)
            (bridge/stop! concurrent))))

      (let [finished (CountDownLatch. 1)
            timeout-dir (str (io/file root "maint-audit-timeout"))
            timed (bridge/start!
                   (assoc opts :port 0 :maintenance-dir timeout-dir :gym-audit-timeout-ms 100
                          :gym-audit! (fn []
                                        (try (Thread/sleep 2000) {:should-not-complete true}
                                             (finally (.countDown finished))))))
            timed-base (:base-url timed)]
        (try
          (assert! (= 504 (:status (request! http-client timed-base "GET"
                                             "/ops/maintenance/a6-gym-state-audit" operator-token)))
                   "long audit must time out" nil)
          (assert! (.await finished 2 TimeUnit/SECONDS) "timed out task should release the gate" nil)
          (finally (bridge/stop! timed))))

      (let [failed-dir (str (io/file root "maint-audit-unavailable"))
            failed (bridge/start!
                    (assoc opts :port 0 :maintenance-dir failed-dir
                           :gym-audit! (fn [] (throw (ex-info "PRIVATE_SENTINEL" {})))))
            failed-base (:base-url failed)]
        (try
          (let [response (request! http-client failed-base "GET"
                                   "/ops/maintenance/a6-gym-state-audit" operator-token)]
            (assert! (= 503 (:status response)) "unavailable DB/query should fail closed" response)
            (assert! (not (re-find #"PRIVATE_SENTINEL" (pr-str (:body response))))
                     "audit failure must not expose internal error details" nil))
          (finally (bridge/stop! failed))))

      (let [large-dir (str (io/file root "maint-audit-large"))
            large (bridge/start!
                   (assoc opts :port 0 :maintenance-dir large-dir
                          :gym-audit! (fn [] {:aggregate (apply str (repeat 20000 "x"))})))
            large-base (:base-url large)]
        (try
          (let [response (request! http-client large-base "GET"
                                   "/ops/maintenance/a6-gym-state-audit" operator-token)]
            (assert! (= 500 (:status response)) "oversized audit response must be capped" response)
            (assert! (< (count (pr-str (:body response))) 200)
                     "oversized response must be replaced with a bounded error" nil))
          (finally (bridge/stop! large))))

      (let [unknown-client (persistence/client
                            {:system (str "a6-audit-unknown-" (System/nanoTime))
                             :storage-dir (str (io/file root "audit-unknown"))})
            unknown-conn (persistence/ensure-db! unknown-client "unknown")
            unknown (gym-audit/summarize (d/db unknown-conn) nil)]
        (assert! (= :unknown (get-in unknown [:battles :status]))
                 "missing battle schema must be reported UNKNOWN" nil)
        (assert! (= :unknown (get-in unknown [:schedulers :raids :status]))
                 "missing raid scheduler schema must be reported UNKNOWN" nil))

      (assert! (= 200 (:status (request! http-client base "POST" "/ops/maintenance/enter?timeoutMs=1000" operator-token)))
               "enter must reach quiescence" nil)
      (assert! (= 200 (:status (request! http-client base "GET"
                                         "/ops/maintenance/a6-gym-state-audit" operator-token)))
               "safe aggregate audit remains available while quiescent" nil)
      (assert! (not= 503 (:status (request! http-client base "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
               "classified safe reads remain available while quiescent" nil)
      (assert! (= 503 (:status (request! http-client base "POST" "/v1/hunt/start" operator-token)))
               "mutation endpoint must be blocked before request parsing/dispatch" nil)
      (assert! (= 503 (:status (request! http-client base "POST" "/v1/raids/auto/tick" operator-token "{}")))
               "raid scheduler ticks must be refused while quiescent" nil)
      (assert! (= datoms-before (:datoms (d/db-stats (d/db conn))))
               "blocked mutations must not add Datomic datoms" nil)
      (assert! (= 200 (:status (request! http-client base "POST" "/ops/maintenance/freeze" operator-token)))
               "freeze must succeed from quiescence" nil)
      (assert! (= 503 (:status (request! http-client base "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
               "frozen phase must block game reads" nil)
      (bridge/stop! srv)
      (let [restarted (bridge/start! (assoc opts :port 0))]
        (try
          (let [base2 (:base-url restarted)
                st (request! http-client base2 "GET" "/ops/maintenance/status" operator-token)]
            (assert! (= "frozen" (get-in st [:body "data" "phase"]))
                     "restart must preserve frozen state" st)
            (assert! (= 503 (:status (request! http-client base2 "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
                     "frozen restart must keep game reads closed" nil)
            (assert! (= 200 (:status (request! http-client base2 "POST" "/ops/maintenance/resume" operator-token)))
                     "explicit resume should reopen a frozen bridge" nil)
            (assert! (not= 503 (:status (request! http-client base2 "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
                     "bridge should accept requests after resume" nil))
          (finally (bridge/stop! restarted))))
      (println "PASS maintenance HTTP drain/freeze/persistence/restart/resume; isolated Datomic; no external transport")
      (finally
        (when srv (try (bridge/stop! srv) (catch Exception _)))
        (shutdown-agents)))))
