(ns odisseu.smoke.m6-2-resilience
  "M6.2 — resiliência operacional (sintético, sem WhatsApp/produção).
   Cobre reinícios, idempotência pós-queda, bridge down, backup/restore,
   cutover/rollback entre Datomic descartáveis e carga concorrente."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.lang.management ManagementFactory]
           [java.net URI ConnectException]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.file Files FileVisitResult SimpleFileVisitor]
           [java.nio.file.attribute BasicFileAttributes]
           [java.time Duration Instant]
           [java.util.concurrent Executors TimeUnit CountDownLatch]))

(def ^:private storage-primary ".datomic-local/m62-resilience")
(def ^:private storage-backup ".datomic-local/m62-resilience-bak")
(def ^:private storage-restore ".datomic-local/m62-resilience-restore")
(def ^:private storage-cutover-a ".datomic-local/m62-cutover-a")
(def ^:private storage-cutover-b ".datomic-local/m62-cutover-b")
(def ^:private system "odisseu-m62-resilience")
(def ^:private db-name "resilience")
(def ^:private token "m62-resilience-token")
(def ^:private chat "chat-m62")
(def ^:private player "ash")
(def ^:private player-b "misty")

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

(defn- assert! [ok msg data]
  (when-not ok (throw (ex-info msg (or data {})))))

(defn- rm-rf! [path]
  (let [root (io/file path)]
    (when (.exists root)
      (Files/walkFileTree
       (.toPath root)
       (proxy [SimpleFileVisitor] []
         (visitFile [f _attrs]
           (Files/delete f)
           FileVisitResult/CONTINUE)
         (postVisitDirectory [d _exc]
           (Files/delete d)
           FileVisitResult/CONTINUE))))))

(defn- copy-tree! [src dest]
  (let [src-f (io/file src)
        dest-f (io/file dest)]
    (assert! (.exists src-f) "backup_src_missing" {:src src})
    (rm-rf! dest)
    (.mkdirs dest-f)
    (Files/walkFileTree
     (.toPath src-f)
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [dir _attrs]
         (let [rel (.relativize (.toPath src-f) dir)
               target (.resolve (.toPath dest-f) rel)]
           (.mkdirs (.toFile target))
           FileVisitResult/CONTINUE))
       (visitFile [f ^BasicFileAttributes _attrs]
         (let [rel (.relativize (.toPath src-f) f)
               target (.resolve (.toPath dest-f) rel)]
           (Files/copy f target (into-array java.nio.file.CopyOption []))
           FileVisitResult/CONTINUE))))))

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
        t0 (System/nanoTime)]
    (try
      (let [res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))
            ms (/ (double (- (System/nanoTime) t0)) 1e6)]
        {:status (.statusCode res)
         :body (json/read-str (.body res))
         :ms ms
         :ok? true})
      (catch ConnectException e
        {:status 0 :body nil :ms (/ (double (- (System/nanoTime) t0)) 1e6)
         :ok? false :error (.getMessage e)})
      (catch Exception e
        {:status 0 :body nil :ms (/ (double (- (System/nanoTime) t0)) 1e6)
         :ok? false :error (.getMessage e)}))))

(defn- fence [store c p]
  (long (:fence-generation (ports/get-player store c p) 0)))

(defn- coins [store c p slot]
  (long (:coins (ports/get-save store c p slot) 0)))

(defn- open-store! [storage sys db]
  (when (re-find #"m53-real-offline|m55-real-offline" (str storage))
    (throw (ex-info "forbidden_storage" {:storage storage})))
  (let [client (persistence/client {:system sys :storage-dir storage})
        conn (persistence/ensure-db! client db)]
    (doto (store/make-store conn) trainer/bootstrap!)))

(defn- seed-player! [store c p tag]
  (trainer/create-trainer! store {:chat-id c :player-id p})
  (coll-app/adicionar-pokemon!
   store c p
   {:species "Pikachu" :nome "Pikachu" :shiny false :nivel 12
    :hp 45 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
    :tipos ["elétrico"]
    :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
    :raridade "comum"}
   40 nil)
  (coll-app/creditar-moedas! store c p 100 (str tag "|coins"))
  (coll-app/ajustar-inventario! store c p "poke-ball" 5 (str tag "|ball")))

(defn- fresh-primary! []
  (rm-rf! storage-primary)
  (let [store (open-store! storage-primary system db-name)]
    (seed-player! store chat player "m62-a")
    (seed-player! store chat player-b "m62-b")
    store))

(defn- start-bridge! [store]
  (bridge/start! {:host "127.0.0.1" :port 0 :token token
                  :store store :allow-test-rng true}))

(defn -main
  [& _]
  (println "=== M6.2 resilience (sintético, sem WhatsApp) ===")
  (doseq [p [storage-primary storage-backup storage-restore
             storage-cutover-a storage-cutover-b]]
    (rm-rf! p))
  (let [client (HttpClient/newHttpClient)
        passed (atom [])
        latencies (atom {})
        mem0 (rss-mb)
        store0 (fresh-primary!)
        srv0 (start-bridge! store0)
        base0 (:base-url srv0)]
    (try
      (let [h (http client base0 "GET" "/health" nil)]
        (assert! (= 200 (:status h)) "health" h)
        (swap! latencies assoc :health (:ms h))
        (swap! passed conj :health))

      (let [f0 (fence store0 chat player)
            coins0 (coins store0 chat player "a")
            b (http client base0 "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "b"
                     "expectedFenceGeneration" f0})
            a (http client base0 "POST" "/v1/saves/activate"
                    {"chatId" chat "playerId" player "saveId" "a"
                     "expectedFenceGeneration"
                     (get-in b [:body "data" "fenceGeneration"])})]
        (assert! (= 200 (:status b) (:status a)) "ab" {:b b :a a})
        (swap! latencies assoc :activate (:ms a))
        (swap! passed conj :saves-a-b)
        (assert! (= 100 coins0) "seed-coins" {:coins0 coins0}))

      (let [f (fence store0 chat player)
            r1 (http client base0 "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" "a"
                      "fenceGeneration" f "operationId" "m62-coins-1"
                      "coinsDelta" 25})
            r2 (http client base0 "POST" "/v1/rewards/coins"
                     {"chatId" chat "playerId" player "saveId" "a"
                      "fenceGeneration" f "operationId" "m62-coins-1"
                      "coinsDelta" 25})
            after (coins store0 chat player "a")]
        (assert! (= 200 (:status r1)) "coins1" r1)
        (assert! (true? (get-in r2 [:body "data" "duplicate"])) "dup" r2)
        (assert! (= 125 after) "no-dup-coins" {:after after})
        (swap! latencies assoc :coins-write (:ms r1))
        (swap! passed conj :idempotent-resend))

      ;; Tx confirmada no store; bridge morta; retry do mesmo operationId.
      (coll-app/creditar-moedas! store0 chat player 7 "m62-crash-mid")
      (let [coins-mid (coins store0 chat player "a")]
        (assert! (= 132 coins-mid) "mid-commit" {:coins-mid coins-mid}))
      (bridge/stop! srv0)
      (let [down (http client base0 "GET" "/health" nil)]
        (assert! (false? (:ok? down)) "bridge-down" down)
        (swap! passed conj :bridge-unavailable-mid))

      (let [store1 (open-store! storage-primary system db-name)
            srv1 (start-bridge! store1)
            base1 (:base-url srv1)
            retry (http client base1 "POST" "/v1/rewards/coins"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" (fence store1 chat player)
                         "operationId" "m62-crash-mid"
                         "coinsDelta" 7})
            after (coins store1 chat player "a")]
        (try
          (assert! (or (true? (get-in retry [:body "data" "duplicate"]))
                       (= 200 (:status retry)))
                   "retry-after-crash" retry)
          (assert! (= 132 after) "no-extra-after-retry" {:after after})
          (swap! passed conj :crash-after-commit-retry)

          (bridge/stop! srv1)
          (let [store2 (open-store! storage-primary system db-name)
                srv2 (start-bridge! store2)
                base2 (:base-url srv2)
                saves (http client base2 "GET"
                            (str "/v1/saves?chatId=" chat "&playerId=" player) nil)
                active (get-in saves [:body "data" "activeSaveId"])]
            (try
              (assert! (= 200 (:status saves)) "saves-after-restart" saves)
              (assert! (= "a" active) "active-a" {:active active})
              (assert! (= 132 (coins store2 chat player "a")) "coins-persist" {})
              (swap! passed conj :jvm-restart-persist)

              (let [client2 (HttpClient/newHttpClient)
                    h (http client2 base2 "GET" "/health" nil)]
                (assert! (= 200 (:status h)) "jvm-alive-after-node-sim" h)
                (swap! passed conj :node-restart-jvm-alive))

              (bridge/stop! srv2)
              (let [down2 (http client base2 "GET"
                                (str "/v1/trainer?chatId=" chat "&playerId=" player) nil)]
                (assert! (false? (:ok? down2)) "unavailable" down2)
                (swap! passed conj :bridge-unavailable))

              (let [store3 (open-store! storage-primary system db-name)
                    srv3 (start-bridge! store3)
                    base3 (:base-url srv3)]
                (try
                  (assert! (= 200 (:status (http client base3 "GET" "/health" nil)))
                           "graceful-restart" {})
                  (swap! passed conj :graceful-restart)

                  (bridge/stop! srv3)
                  (copy-tree! (persistence/resolve-storage-dir storage-primary)
                              (persistence/resolve-storage-dir storage-backup))
                  (copy-tree! (persistence/resolve-storage-dir storage-backup)
                              (persistence/resolve-storage-dir storage-restore))
                  ;; Mesmo :system do backup — Datomic Local aninha dados em
                  ;; <storage>/<system>/; trocar o system esvazia o restore.
                  (let [restored (open-store! storage-restore system db-name)
                        c (coins restored chat player "a")]
                    (assert! (= 132 c) "restore-coins" {:c c})
                    (swap! passed conj :backup-restore))

                  ;; Path bloqueado: arquivo no lugar do diretório de storage.
                  (let [blocked ".datomic-local/m62-blocked-as-file"
                        _ (rm-rf! blocked)
                        _ (spit blocked "not-a-directory\n")
                        err (try
                              (open-store! blocked system "ghost")
                              nil
                              (catch Exception e e))
                        _ (.delete (io/file blocked))
                        primary-ok (open-store! storage-primary system db-name)]
                    (assert! (some? err) "bad-storage-fails" {:err (ex-message err)})
                    (assert! (= 132 (coins primary-ok chat player "a"))
                             "primary-intact" {})
                    (swap! passed conj :storage-unavailable-no-corrupt))

                  ;; Cutover/rollback entre Datomic descartáveis.
                  ;; Mesmo system name em dirs distintos: B = snapshot frio de A.
                  (rm-rf! storage-cutover-a)
                  (rm-rf! storage-cutover-b)
                  (let [cut-sys "odisseu-m62-cut"
                        a-store (open-store! storage-cutover-a cut-sys "cuta")
                        _ (seed-player! a-store chat player "cut-a")
                        _ (coll-app/creditar-moedas! a-store chat player 50 "cut-pre")
                        coins-pre (coins a-store chat player "a")
                        _ (copy-tree! (persistence/resolve-storage-dir storage-cutover-a)
                                      (persistence/resolve-storage-dir storage-cutover-b))
                        _ (coll-app/creditar-moedas! a-store chat player 40 "cut-post")
                        coins-post (coins a-store chat player "a")
                        b-store (open-store! storage-cutover-b cut-sys "cuta")
                        coins-rolled (coins b-store chat player "a")]
                    (assert! (= 150 coins-pre) "cut-pre" {:coins-pre coins-pre})
                    (assert! (= 190 coins-post) "cut-post" {:coins-post coins-post})
                    (assert! (= 150 coins-rolled) "rollback-loses-post"
                             {:coins-rolled coins-rolled})
                    (swap! passed conj :cutover-rollback-lossy))

                  (let [load-store (open-store! storage-primary system db-name)
                        load-http (start-bridge! load-store)
                        load-base (:base-url load-http)
                        n-players 8
                        ops-each 4
                        pool (Executors/newFixedThreadPool n-players)
                        latch (CountDownLatch. n-players)
                        errors (atom [])
                        t0 (System/nanoTime)]
                    (try
                      (doseq [i (range n-players)]
                        (let [pid (str "load-" i)]
                          (when-not (ports/get-player load-store chat pid)
                            (seed-player! load-store chat pid (str "load|" i)))
                          (.submit pool
                                   ^Runnable
                                   (fn []
                                     (try
                                       (dotimes [j ops-each]
                                         (let [f (fence load-store chat pid)
                                               r (http client load-base "POST"
                                                       "/v1/rewards/coins"
                                                       {"chatId" chat "playerId" pid
                                                        "saveId" "a"
                                                        "fenceGeneration" f
                                                        "operationId"
                                                        (str "m62-load|" i "|" j)
                                                        "coinsDelta" 1})]
                                           (when-not (= 200 (:status r))
                                             (swap! errors conj r))))
                                       (catch Exception e
                                         (swap! errors conj {:error (.getMessage e)}))
                                       (finally (.countDown latch)))))))
                      (.await latch 60 TimeUnit/SECONDS)
                      (let [elapsed-ms (/ (double (- (System/nanoTime) t0)) 1e6)
                            sample (coins load-store chat "load-0" "a")]
                        (assert! (empty? @errors) "load-errors" {:errors @errors})
                        (assert! (= 104 sample) "load-coins" {:sample sample})
                        (swap! latencies assoc :concurrent-batch-ms elapsed-ms
                               :concurrent-ops (* n-players ops-each))
                        (swap! passed conj :concurrent-load))
                      (finally
                        (.shutdown pool)
                        (bridge/stop! load-http))))

                  (let [report {:milestone "M6.2"
                                :at (str (Instant/now))
                                :ok? true
                                :passed @passed
                                :latencies-ms @latencies
                                :rss-mb {:before mem0 :after (rss-mb)}
                                :nota (str "Resiliência JVM/Datomic sintético. "
                                           "Chromium/WhatsApp não exercitados. "
                                           "Rollback para snapshot pré-cutover perde progresso pós — "
                                           "NÃO declarar rollback Cassandra aprovado.")
                                :storages {:primary storage-primary
                                           :backup storage-backup
                                           :restore storage-restore}}]
                    (println "=== RESULT ===")
                    (pprint/pprint report)
                    (shutdown-agents)
                    (System/exit 0))
                  (finally
                    (try (bridge/stop! srv3) (catch Exception _)))))
              (finally
                (try (bridge/stop! srv2) (catch Exception _)))))
          (finally
            (try (bridge/stop! srv1) (catch Exception _)))))
      (catch Throwable t
        (try (bridge/stop! srv0) (catch Exception _))
        (println "=== M6.2 FAILED ===" (ex-message t))
        (pprint/pprint {:error (ex-message t) :data (ex-data t) :passed @passed})
        (shutdown-agents)
        (System/exit 1)))))
