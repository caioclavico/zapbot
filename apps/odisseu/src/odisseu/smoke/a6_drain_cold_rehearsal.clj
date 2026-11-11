(ns odisseu.smoke.a6-drain-cold-rehearsal
  "Disposable drain → freeze → cold copy → restore rehearsal.

  Uses an isolated Datomic Local tree, never production volume names.
  Raid-auto tick is enabled so scheduler admission is exercised."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.schema :as pokemon-schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.infrastructure.http.maintenance-gate :as gate])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.time Duration]
           [java.util.concurrent CountDownLatch Executors TimeUnit]))

(defn- ensure! [ok msg & [data]]
  (when-not ok (throw (ex-info msg (or data {})))))

(defn- request! [client base method path token & [body maint?]]
  (let [b (cond-> (HttpRequest/newBuilder)
            true (.uri (URI/create (str base path)))
            true (.timeout (Duration/ofSeconds 15))
            true (.header "Authorization" (str "Bearer " token))
            maint? (.header "X-A6-Maintenance-Token" token)
            true (.method method (if (some? body)
                                   (HttpRequest$BodyPublishers/ofString body)
                                   (HttpRequest$BodyPublishers/noBody)))
            true (.build))
        r (.send client b (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode r)
     :body (try (json/read-str (.body r)) (catch Exception _ {}))}))

(defn- copy-tree! [from to]
  (let [src (.toPath (io/file from))
        dst (.toPath (io/file to))]
    (Files/walkFileTree
     src
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [dir attrs]
         (let [rel (.relativize src dir)
               target (if (str/blank? (str rel)) dst (.resolve dst rel))]
           (Files/createDirectories target (make-array FileAttribute 0))
           FileVisitResult/CONTINUE))
       (visitFile [file attrs]
         (let [rel (.relativize src file)
               target (.resolve dst rel)]
           (Files/createDirectories (.getParent target) (make-array FileAttribute 0))
           (Files/copy file target (into-array java.nio.file.CopyOption [])))
         FileVisitResult/CONTINUE)))))

(defn- aggregate [conn]
  (let [db (d/db conn)
        saves (count (d/q '[:find ?e :where [?e :save/key]] db))
        pokemon (count (d/q '[:find ?e :where [?e :pokemon/id-pokemon]] db))
        receipts (count (d/q '[:find ?e :where [?e :operation-receipt/id]] db))
        datoms (:datoms (d/db-stats db))]
    {:saves saves :pokemon pokemon :receipts receipts :datoms datoms}))

(defn- fingerprint [agg]
  (str (:saves agg) "|" (:pokemon agg) "|" (:receipts agg) "|" (:datoms agg)))

(defn -main [& _]
  (let [root (.toFile (Files/createTempDirectory "a6-drain-cold-" (make-array FileAttribute 0)))
        storage (str (io/file root "datomic"))
        maint (str (io/file root "maint"))
        journal (str (io/file root "journal"))
        backup (str (io/file root "backup-datomic"))
        restore (str (io/file root "restore-datomic"))
        _ (doseq [p [storage maint journal]] (.mkdirs (io/file p)))
        system-name (str "a6-drain-cold-" (System/nanoTime))
        client (persistence/client {:system system-name
                                    :storage-dir storage
                                    :maintenance-control? true})
        conn (persistence/ensure-db! client "core")
        _ (pokemon-schema/install! conn)
        s (doto (store/make-store conn) trainer/bootstrap!)
        bridge-token "drain-cold-bridge-token"
        maint-token "drain-cold-maint-token"
        http (HttpClient/newHttpClient)
        srv (bridge/start!
             {:host "127.0.0.1" :port 0 :token bridge-token :store s
              :maintenance-dir maint :maintenance-token maint-token
              :raid-auto-tick-enabled? true})
        base (:base-url srv)
        req (fn [method path & [body]]
              (request! http base method path bridge-token body false))
        mreq (fn [method path]
               (request! http base method path maint-token nil true))]
    (try
      ;; Seed a save + raid schedule fixture via direct tx (isolated DB).
      (d/transact conn {:tx-data [{:save/key "drain-fixture|a"
                                   :save/coins 3
                                   :save/trainer-level 2}
                                  {:raid-auto-schedule/chat-id "drain-fixture-chat"
                                   :raid-auto-schedule/enabled true
                                   :raid-auto-schedule/next-at 1
                                   :raid-auto-schedule/revision 1}]})
      (let [before (aggregate conn)
            fp-before (fingerprint before)
            release (CountDownLatch. 1)
            pool (Executors/newFixedThreadPool 4)
            tick-statuses (atom [])]
        (println "BASELINE" before "fp" fp-before)
        ;; Hold a write, enter drain, THEN hammer ticks — all must be refused.
        (let [g (:maintenance-gate srv)]
          (ensure! (true? (gate/acquire-write! g)) "seed-write-admission" nil)
          (future
            (.await release 5 TimeUnit/SECONDS)
            (gate/release-write! g))
          (let [enter-fut (future (mreq "POST" "/ops/maintenance/enter?timeoutMs=5000"))]
            (loop [n 100]
              (when (and (pos? n)
                         (= "open" (get-in (mreq "GET" "/ops/maintenance/status")
                                           [:body "data" "phase"])))
                (Thread/sleep 20)
                (recur (dec n))))
            (dotimes [_ 4]
              (.submit pool
                       ^Callable
                       (fn []
                         (swap! tick-statuses conj
                                (:status (req "POST" "/v1/raids/auto/tick" "{\"limit\":1}")))
                         nil)))
            (.shutdown pool)
            (.awaitTermination pool 5 TimeUnit/SECONDS)
            (ensure! (every? #(= 503 %) @tick-statuses)
                     "concurrent-ticks-blocked" {:statuses @tick-statuses})
            (ensure! (= 503 (:status (req "POST" "/v1/hunt/start" "{}")))
                     "hunt-blocked-during-drain")
            (.countDown release)
            (let [enter @enter-fut]
              (ensure! (= 200 (:status enter)) "enter-quiescent" enter)
              (ensure! (true? (get-in enter [:body "data" "quiescent"]))
                       "enter-quiescent-flag" enter)
              (ensure! (= "quiescent" (get-in enter [:body "data" "phase"]))
                       "enter-phase" enter)))
          ;; Safe read still allowed while quiescent.
          (ensure! (= 200 (:status (req "GET" "/health"))) "health-up")
          (let [freeze (mreq "POST" "/ops/maintenance/freeze")]
            (ensure! (= 200 (:status freeze)) "freeze-ok" freeze)
            (ensure! (= "frozen" (get-in freeze [:body "data" "phase"])) "freeze-phase" freeze))
          (ensure! (= 503 (:status (req "GET" "/v1/trainer?chatId=x&playerId=y")))
                   "reads-blocked-when-frozen")
          (ensure! (zero? (:active-writes (gate/status g))) "no-active-writers")
          (let [mid (aggregate conn)
                fp-mid (fingerprint mid)]
            (ensure! (= fp-before fp-mid) "no-mutation-after-drain" {:before before :mid mid})
            ;; Stop disposable JVM then cold-copy storage.
            (bridge/stop! srv)
            (copy-tree! storage backup)
            (copy-tree! backup restore)
            (let [restore-client (persistence/client
                                  {:system system-name
                                   :storage-dir restore
                                   :maintenance-control? true})
                  restore-conn (d/connect restore-client {:db-name "core"})
                  after (aggregate restore-conn)
                  fp-after (fingerprint after)]
              (ensure! (= fp-before fp-after) "restore-fingerprint-match"
                       {:before before :after after})
              (println "RESTORE_FINGERPRINT_PASS" fp-after)
              ;; Resume path on a fresh gate over the backup marker dir copy is N/A;
              ;; prove restart of original maint marker remains frozen until resume.
              (let [restarted (gate/create maint)]
                (ensure! (= :frozen (:phase (gate/status restarted))) "marker-frozen-after-stop")
                (gate/resume! restarted)
                (ensure! (= :open (:phase (gate/status restarted))) "resume-after-rehearsal")))))
        (println "A6_DRAIN_COLD_REHEARSAL_PASS root=" (.getAbsolutePath root)
                 "baseline-fp=" fp-before)
        (flush))
      (finally
        (try (bridge/stop! srv) (catch Exception _))
        (shutdown-agents)))))
