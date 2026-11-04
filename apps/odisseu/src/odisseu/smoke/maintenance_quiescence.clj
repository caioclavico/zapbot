(ns odisseu.smoke.maintenance-quiescence
  "Disposable HTTP/Datomic check for the bridge maintenance gate. No WhatsApp or production resources."
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.maintenance-gate :as gate]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files]
           [java.time Duration]))

(defn- request! [client base method path token]
  (let [b (-> (HttpRequest/newBuilder)
              (.uri (URI/create (str base path)))
              (.timeout (Duration/ofSeconds 5))
              (.header "X-A6-Maintenance-Token" token)
              (.header "Authorization" "Bearer maintenance-smoke-bridge-token")
              (.method method (HttpRequest$BodyPublishers/noBody))
              .build)
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
        s (doto (store/make-store conn) trainer/bootstrap!)
        datoms-before (:datoms (d/db-stats (d/db conn)))
        operator-token "maintenance-smoke-operator-token"
        opts {:host "127.0.0.1" :port 0 :token "maintenance-smoke-bridge-token"
              :store s :maintenance-dir maint :maintenance-token operator-token}
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
      (assert! (= 200 (:status (request! http-client base "POST" "/ops/maintenance/enter?timeoutMs=1000" operator-token)))
               "enter must reach quiescence" nil)
      (assert! (= 503 (:status (request! http-client base "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
               "authenticated bridge requests must fail closed during maintenance" nil)
      (assert! (= 503 (:status (request! http-client base "POST" "/v1/hunt/start" operator-token)))
               "mutation endpoint must be blocked before request parsing/dispatch" nil)
      (assert! (= datoms-before (:datoms (d/db-stats (d/db conn))))
               "blocked mutations must not add Datomic datoms" nil)
      (bridge/stop! srv)
      (let [restarted (bridge/start! (assoc opts :port 0))]
        (try
          (let [base2 (:base-url restarted)]
            (assert! (= 503 (:status (request! http-client base2 "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
                     "restart must preserve maintenance state" nil)
            (assert! (= 200 (:status (request! http-client base2 "POST" "/ops/maintenance/resume" operator-token)))
                     "explicit resume should reopen a quiescent bridge" nil)
            (assert! (not= 503 (:status (request! http-client base2 "GET" "/v1/trainer?chatId=test&playerId=test" operator-token)))
                     "bridge should accept requests after resume" nil))
          (finally (bridge/stop! restarted))))
      (println "PASS maintenance HTTP persistence/restart/resume; isolated Datomic; no external transport")
      (finally
        (when srv (try (bridge/stop! srv) (catch Exception _)))
        (shutdown-agents)))))
