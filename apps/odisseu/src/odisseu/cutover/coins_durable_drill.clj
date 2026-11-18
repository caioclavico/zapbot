(ns odisseu.cutover.coins-durable-drill
  "M8.5 — ensaio real coins-delta + durable-op em Datomic Local descartável."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.coins-durable :as coins]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.cutover.recovery :as recovery]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.migration.backup :as backup])
  (:import [java.net HttpURLConnection URL]
           [java.nio.charset StandardCharsets]
           [java.time Instant]))

(def chat "chat-m85-syn@g.us")
(def player "player-m85-syn@c.us")
(def db-name "m85-pokemon")

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m85_path_blocked" {:path p}))))

(defn- wipe-dir! [path]
  (let [f (io/file (persistence/resolve-storage-dir path))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- ctx [id]
  (let [base (str ".datomic-local/m85-" id)
        c {:id id
           :system (str "odisseu-m85-" id)
           :poke-live (str base "-poke")
           :poke-bak (str base "-poke-bak")
           :poke-rec (str base "-poke-rec")
           :journal (str base "-journal")
           :maint (str base "-maint")}]
    (doseq [p [(:poke-live c) (:poke-bak c) (:poke-rec c) (:journal c) (:maint c)]]
      (assert-disposable! p)
      (.mkdirs (io/file (persistence/resolve-storage-dir p))))
    (journal/wipe! (:journal c))
    (wipe-dir! (:maint c))
    (.mkdirs (io/file (:maint c)))
    c))

(defn- durable-cfg
  ([c] (durable-cfg c {}))
  ([c hooks]
   {:journal-dir (:journal c)
    :maint-dir (:maint c)
    :policy :strict
    :hooks hooks}))

(defn- open! [c]
  (cut-import/open-pokemon-store! (:poke-live c) (:system c) db-name))

(defn- reopen! [c]
  (cut-import/reopen-pokemon-store! (:poke-live c) (:system c) db-name))

(defn- reopen-rec! [c]
  (cut-import/reopen-pokemon-store! (:poke-rec c) (:system c) db-name))

(defn- fence [store]
  (long (:fence-generation (ports/get-player store chat player) 0)))

(defn- coins-of [store save]
  (long (or (:coins (ports/get-save store chat player save)) 0)))

(defn- seed! [store]
  (trainer/create-trainer! store {:chat-id chat :player-id player})
  (let [f (fence store)
        r (tx/credit-coins!
           store {:chat-id chat :player-id player :save-id "a"
                  :fence-generation f :operation-id "m85-seed"
                  :coins-delta 1000})]
    {:coins (:coins r) :fence f}))

(defn- credit!
  [store c op-id delta & [hooks]]
  (coins/credit-coins!
   store (durable-cfg c (or hooks {}))
   {:chat-id chat :player-id player :save-id "a"
    :fence-generation (fence store)
    :operation-id op-id
    :coins-delta delta}))

(defn- caught-code [thunk]
  (try (thunk) nil
       (catch Exception ex
         (or (err/error-code ex) (.getMessage ex)))))

(defn scenario-normal!
  []
  (let [c (ctx "normal")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        r (credit! s c "m85-normal" 25)
        after (coins-of s "a")]
    {:scenario :normal
     :ok? (and (= (+ before 25) after)
               (false? (:duplicate r))
               (= :ok (get-in r [:durable :status])))}))

(defn scenario-retry-same-op!
  []
  (let [c (ctx "retry")
        s (open! c)
        _ (seed! s)
        r1 (credit! s c "m85-retry" 11)
        mid (coins-of s "a")
        r2 (credit! s c "m85-retry" 11)
        after (coins-of s "a")]
    {:scenario :retry-same-op
     :ok? (and (= mid after)
               (true? (:duplicate r2))
               (true? (get-in r2 [:durable :duplicate?])))}))

(defn scenario-payload-mismatch!
  []
  (let [c (ctx "mismatch")
        s (open! c)
        _ (seed! s)
        _ (credit! s c "m85-same" 5)
        before (coins-of s "a")
        code (caught-code #(credit! s c "m85-same" 9))]
    {:scenario :payload-mismatch
     :code code
     :coins before
     :ok? (and (or (= :receipt_payload_mismatch code)
                   (= :journal_operation_id_payload_mismatch code)
                   (re-find #"payload_mismatch|journal_operation_id" (str code)))
               (= before (coins-of s "a")))}))

(defn scenario-stale-fence!
  []
  (let [c (ctx "stale-fence")
        s (open! c)
        _ (seed! s)
        bad-fence (dec (fence s))
        code (caught-code
              #(coins/credit-coins!
                s (durable-cfg c)
                {:chat-id chat :player-id player :save-id "a"
                 :fence-generation bad-fence
                 :operation-id "m85-stale"
                 :coins-delta 3}))]
    {:scenario :stale-fence
     :code code
     :ok? (= :stale_writer_fence code)}))

(defn scenario-concurrent!
  []
  (let [c (ctx "concurrent")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        f (fence s)
        ;; sequencial sob mesmo fence (Datomic serializa); dois op-ids distintos
        r1 (coins/credit-coins!
            s (durable-cfg c)
            {:chat-id chat :player-id player :save-id "a"
             :fence-generation f :operation-id "m85-c1" :coins-delta 2})
        r2 (coins/credit-coins!
            s (durable-cfg c)
            {:chat-id chat :player-id player :save-id "a"
             :fence-generation f :operation-id "m85-c2" :coins-delta 3})
        after (coins-of s "a")]
    {:scenario :concurrent
     :ok? (and (= (+ before 5) after)
               (false? (:duplicate r1))
               (false? (:duplicate r2)))}))

(defn- boom [msg]
  (fn [] (throw (ex-info msg {}))))

(defn scenario-before-prepare!
  []
  (let [c (ctx "before-prep")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        code (caught-code
              (fn [] (credit! s c "m85-bp" 4
                              {:before-prepare (boom "crash_before_prepare")})))]
    {:scenario :before-prepare
     :ok? (and (= before (coins-of s "a"))
               (empty? (journal/list-entries (:journal c)))
               (some? code))}))

(defn scenario-after-prepare-before-datomic!
  []
  (let [c (ctx "prep-no-dt")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        code (caught-code
              (fn [] (credit! s c "m85-apd" 4
                              {:before-datomic (boom "crash_before_datomic")})))]
    {:scenario :after-prepare-before-datomic
     :orphans (journal/prepared-without-durable (:journal c))
     :ok? (and (= before (coins-of s "a"))
               (seq (journal/prepared-without-durable (:journal c)))
               (nil? (ports/get-receipt s "m85-apd"))
               (some? code))}))

(defn scenario-datomic-ok-journal-fail!
  []
  (let [c (ctx "jfail")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        code (caught-code
              (fn [] (credit! s c "m85-jfail" 17
                              {:before-journal (boom "crash_journal")})))
        after (coins-of s "a")
        receipt (ports/get-receipt s "m85-jfail")
        recon (coins/reconcile-uncertain! s (:journal c) (:maint c) "m85-jfail")]
    {:scenario :datomic-ok-journal-fail
     :code code
     :coins-delta (- after before)
     :receipt? (boolean receipt)
     :recon recon
     :maintenance? (coins/maintenance? (:maint c))
     :ok? (and (= :durability_compromised code)
               (= 17 (- after before))
               (some? receipt)
               (= :reconciled (:status recon))
               (false? (:coins-reapplied? recon))
               (true? (coins/maintenance? (:maint c))))}))

(defn scenario-before-http!
  []
  (let [c (ctx "before-http")
        s (open! c)
        _ (seed! s)
        before (coins-of s "a")
        code (caught-code
              (fn [] (credit! s c "m85-http" 6
                              {:before-http (boom "crash_before_http")})))
        mid (coins-of s "a")
        r2 (credit! s c "m85-http" 6)]
    {:scenario :before-http
     :ok? (and (some? code)
               (= (+ before 6) mid)
               (= mid (coins-of s "a"))
               (true? (:duplicate r2)))}))

(defn scenario-jvm-reopen!
  []
  (let [c (ctx "reopen")
        s1 (open! c)
        _ (seed! s1)
        _ (credit! s1 c "m85-reopen" 9)
        coins1 (coins-of s1 "a")
        s2 (reopen! c)
        r (credit! s2 c "m85-reopen" 9)]
    {:scenario :jvm-reopen
     :ok? (and (= coins1 (coins-of s2 "a"))
               (true? (:duplicate r)))}))

(defn scenario-volume-loss-replay!
  []
  (let [c (ctx "volume")
        s (open! c)
        _ (seed! s)
        _ (credit! s c "m85-pre" 10)
        wm (journal/watermark (:journal c))
        _ (backup/snapshot-storage! (:poke-live c) (:poke-bak c))
        _ (credit! s c "m85-post" 40)
        live (coins-of s "a")
        _ (wipe-dir! (:poke-live c))
        _ (wipe-dir! (:poke-rec c))
        _ (.mkdirs (io/file (persistence/resolve-storage-dir (:poke-rec c))))
        _ (backup/restore-storage! (:poke-rec c) (:poke-bak c))
        s2 (reopen-rec! c)
        after-restore (coins-of s2 "a")
        durable-entries (->> (journal/entries-after (:journal c) wm)
                             (filter #(and (= :journal-durable (:phase %))
                                           (= :coins-delta (:kind %))
                                           (true? (:datomic-confirmed? %)))))
        replay (mapv #(coins/replay-coins-entry! s2 %) durable-entries)
        after-replay (coins-of s2 "a")
        again (mapv #(coins/replay-coins-entry! s2 %) durable-entries)]
    {:scenario :volume-loss-replay
     :live live
     :after-restore after-restore
     :after-replay after-replay
     :ok? (and (< after-restore live)
               (= live after-replay)
               (every? :duplicate again)
               (some #(false? (:duplicate %)) replay))}))

(defn scenario-journal-corrupt!
  []
  (let [c (ctx "corrupt")
        s (open! c)
        _ (seed! s)
        _ (credit! s c "m85-ok" 1)
        _ (journal/corrupt-last! (:journal c))
        read-fail? (try (journal/list-entries (:journal c)) false
                        (catch Exception _ true))
        _ (journal/fail-closed! (:maint c) "journal-corrupt")
        code (caught-code #(credit! s c "m85-after-corrupt" 1))]
    {:scenario :journal-corrupt
     :ok? (and read-fail?
               (= :durability_maintenance code))}))

(defn scenario-maintenance-blocks!
  []
  (let [c (ctx "maint-block")
        s (open! c)
        _ (seed! s)
        _ (caught-code
           (fn [] (credit! s c "m85-trig" 8
                           {:before-journal (boom "jfail")})))
        code (caught-code (fn [] (credit! s c "m85-blocked" 1)))]
    {:scenario :maintenance-blocks
     :ok? (= :durability_maintenance code)}))

(defn scenario-save-ab-separation!
  []
  (let [c (ctx "save-ab")
        s (open! c)
        _ (seed! s)
        fa (fence s)
        ra (coins/credit-coins!
            s (durable-cfg c)
            {:chat-id chat :player-id player :save-id "a"
             :fence-generation fa :operation-id "m85-a" :coins-delta 15})
        sw (tx/activate-save! s {:chat-id chat :player-id player :save-id "b"})
        fb (:fence-generation sw)
        stale (caught-code
               #(coins/credit-coins!
                 s (durable-cfg c)
                 {:chat-id chat :player-id player :save-id "a"
                  :fence-generation fa :operation-id "m85-stale-a" :coins-delta 1}))
        rb (coins/credit-coins!
            s (durable-cfg c)
            {:chat-id chat :player-id player :save-id "b"
             :fence-generation fb :operation-id "m85-b" :coins-delta 20})]
    {:scenario :save-ab
     :ok? (and (false? (:duplicate ra))
               (= :stale_writer_fence stale)
               (false? (:duplicate rb))
               (= 1015 (coins-of s "a"))
               (= 20 (coins-of s "b")))}))

(defn scenario-bridge-http!
  []
  (let [c (ctx "bridge")
        s (open! c)
        _ (seed! s)
        f (fence s)
        durable (durable-cfg c)
        srv (bridge/start!
             {:host "127.0.0.1"
              :port 0
              :token "m85-token"
              :store s
              :credit-coins! (coins/make-credit-fn durable)})
        url (str (:base-url srv) "/v1/rewards/coins")
        body (str "{\"chatId\":\"" chat "\",\"playerId\":\"" player
                  "\",\"saveId\":\"a\",\"fenceGeneration\":" f
                  ",\"operationId\":\"m85-bridge\",\"coinsDelta\":12}")
        conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setRequestProperty "Authorization" "Bearer m85-token")
               (.setRequestProperty "Content-Type" "application/json"))
        _ (with-open [os (.getOutputStream conn)]
            (.write os (.getBytes body StandardCharsets/UTF_8)))
        status (.getResponseCode conn)
        _ (bridge/stop! srv)]
    {:scenario :bridge-http
     :status status
     :coins (coins-of s "a")
     :durable? (true? (:durable-coins? srv))
     :ok? (and (= 200 status)
               (= 1012 (coins-of s "a"))
               (true? (:durable-coins? srv)))}))

(defn run-all!
  [{:keys [out-dir] :or {out-dir "target/m8-5"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        results
        [(scenario-normal!)
         (scenario-retry-same-op!)
         (scenario-payload-mismatch!)
         (scenario-stale-fence!)
         (scenario-concurrent!)
         (scenario-before-prepare!)
         (scenario-after-prepare-before-datomic!)
         (scenario-datomic-ok-journal-fail!)
         (scenario-before-http!)
         (scenario-jvm-reopen!)
         (scenario-volume-loss-replay!)
         (scenario-journal-corrupt!)
         (scenario-maintenance-blocks!)
         (scenario-save-ab-separation!)
         (scenario-bridge-http!)]
        ok? (every? :ok? results)
        report
        {:milestone "M8.5"
         :at (str (Instant/now))
         :coverage coins/coverage
         :results (mapv #(select-keys % [:scenario :ok? :code :status]) results)
         :all-ok? ok?
         :rollback-recon-available? false
         :cutover-ready? false
         :exactly-once-claimed? false
         :rpo-zero-claimed? false
         :ok? ok?}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
