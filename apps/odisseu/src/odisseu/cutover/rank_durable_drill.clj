(ns odisseu.cutover.rank-durable-drill
  "M8.6 — ensaio score-point + durable-op em Platform Datomic descartável."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.coins-durable :as coins]
            [odisseu.cutover.durability-policy :as policy]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.cutover.rank-durable :as rank]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [pokemon.application.ports :as pports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.migration.backup :as backup])
  (:import [java.net HttpURLConnection URL]
           [java.nio.charset StandardCharsets]
           [java.time Instant]))

(def chat "chat-m86-syn@g.us")
(def player "player-m86-syn@c.us")
(def player2 "player2-m86-syn@c.us")
(def platform-db "m86-platform")
(def pokemon-db "m86-pokemon")

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m86_path_blocked" {:path p}))))

(defn- wipe-dir! [path]
  (let [f (io/file (persistence/resolve-storage-dir path))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- ctx [id]
  (let [base (str ".datomic-local/m86-" id)
        c {:id id
           :system (str "odisseu-m86-" id)
           :plat-live (str base "-plat")
           :plat-bak (str base "-plat-bak")
           :plat-rec (str base "-plat-rec")
           :poke-live (str base "-poke")
           :journal (str base "-journal")
           :poke-journal (str base "-poke-journal")
           :maint (str base "-maint")}]
    (doseq [p (vals (dissoc c :id :system))]
      (assert-disposable! p)
      (.mkdirs (io/file (persistence/resolve-storage-dir p))))
    (journal/wipe! (:journal c))
    (journal/wipe! (:poke-journal c))
    (wipe-dir! (:maint c))
    (.mkdirs (io/file (:maint c)))
    c))

(defn- durable-cfg
  ([c] (durable-cfg c {}))
  ([c extra]
   (merge {:mode :optional
           :journal-dir (:journal c)
           :maint-dir (:maint c)
           :policy :strict}
          extra)))

(defn- open-plat! [c]
  (platform-pipe/open-disposable-store! (:plat-live c) (:system c) platform-db))

(defn- reopen-plat! [c]
  (let [client (persistence/client {:system (:system c) :storage-dir (:plat-live c)})
        conn (persistence/ensure-db! client platform-db)]
    (odisseu.platform.infrastructure.datomic.store/make-store conn)))

(defn- reopen-plat-rec! [c]
  (let [client (persistence/client {:system (:system c) :storage-dir (:plat-rec c)})
        conn (persistence/ensure-db! client platform-db)]
    (odisseu.platform.infrastructure.datomic.store/make-store conn)))

(defn- open-poke! [c]
  (cut-import/open-pokemon-store! (:poke-live c) (:system c) pokemon-db))

(defn- pts [store cid pid]
  (long (or (get-in (ports/get-chat-rank store cid) [pid "pontos"]) 0)))

(defn- score!
  ([store c op nome jogo]
   (score! store c op nome jogo chat player nil))
  ([store c op nome jogo cid pid]
   (score! store c op nome jogo cid pid nil))
  ([store c op nome jogo cid pid extra]
   (rank/score-point!
    store (durable-cfg c (or extra {}))
    cid pid nome jogo op)))

(defn- caught-code [thunk]
  (try (thunk) nil
       (catch Exception ex
         (or (err/error-code ex) (.getMessage ex)))))

(defn- boom [msg]
  (fn [] (throw (ex-info msg {}))))

(defn scenario-normal!
  []
  (let [c (ctx "normal")
        s (open-plat! c)
        r (score! s c "m86-n1" "Ash" "quiz")]
    {:scenario :normal
     :ok? (and (= 1 (pts s chat player))
               (false? (:duplicate r))
               (= :ok (get-in r [:durable :status])))}))

(defn scenario-retry!
  []
  (let [c (ctx "retry")
        s (open-plat! c)
        _ (score! s c "m86-r1" "Ash" "velha")
        r2 (score! s c "m86-r1" "Ash" "velha")]
    {:scenario :retry
     :ok? (and (true? (:duplicate r2))
               (= 1 (pts s chat player)))}))

(defn scenario-two-events!
  []
  (let [c (ctx "two-ev")
        s (open-plat! c)
        _ (score! s c "m86-e1" "Ash" "quiz")
        _ (score! s c "m86-e2" "Ash" "naval")]
    {:scenario :two-events
     :ok? (= 2 (pts s chat player))}))

(defn scenario-payload-mismatch!
  []
  (let [c (ctx "mismatch")
        s (open-plat! c)
        _ (score! s c "m86-same" "Ash" "quiz")
        code (caught-code
              (fn [] (score! s c "m86-same" "Ash" "velha")))]
    {:scenario :payload-mismatch
     :code code
     :ok? (and (= 1 (pts s chat player))
               (or (= "platform_receipt_payload_mismatch" code)
                   (= "journal_operation_id_payload_mismatch" code)
                   (re-find #"payload_mismatch|journal_operation_id" (str code))))}))

(defn scenario-concurrent!
  []
  (let [c (ctx "conc")
        s (open-plat! c)
        _ (score! s c "m86-c1" "Ash" "quiz" chat player)
        _ (score! s c "m86-c2" "Misty" "quiz" chat player2)]
    {:scenario :concurrent
     :ok? (and (= 1 (pts s chat player))
               (= 1 (pts s chat player2)))}))

(defn scenario-two-chats-games!
  []
  (let [c (ctx "chats")
        s (open-plat! c)
        chat-b "chat-b-m86@g.us"
        _ (score! s c "m86-a-quiz" "Ash" "quiz" chat player)
        _ (score! s c "m86-b-velha" "Ash" "velha" chat-b player)]
    {:scenario :two-chats-games
     :ok? (and (= 1 (pts s chat player))
               (= 1 (pts s chat-b player))
               (= 1 (get-in (ports/get-chat-rank s chat) [player "jogos" "quiz"]))
               (= 1 (get-in (ports/get-chat-rank s chat-b) [player "jogos" "velha"])))}))

(defn scenario-before-prepare!
  []
  (let [c (ctx "before-prep")
        s (open-plat! c)
        code (caught-code
              (fn [] (score! s c "m86-bp" "Ash" "quiz" chat player
                             {:hooks {:before-prepare (boom "crash")}})))]
    {:scenario :before-prepare
     :ok? (and (zero? (pts s chat player))
               (empty? (journal/list-entries (:journal c)))
               (some? code))}))

(defn scenario-after-commit-before-journal!
  []
  (let [c (ctx "jfail")
        s (open-plat! c)
        code (caught-code
              (fn [] (score! s c "m86-jfail" "Ash" "quiz" chat player
                             {:hooks {:before-journal (boom "crash_j")}})))
        recon (rank/reconcile-uncertain! s (:journal c) (:maint c) "m86-jfail")]
    {:scenario :after-commit-before-journal
     :code code
     :recon recon
     :ok? (and (= :durability_compromised code)
               (= 1 (pts s chat player))
               (= :reconciled (:status recon))
               (false? (:points-reapplied? recon))
               (true? (policy/maintenance? (:maint c))))}))

(defn scenario-journal-unavailable!
  []
  (let [c (ctx "junavail")
        s (open-plat! c)
        code (caught-code
              (fn []
                (rank/score-point!
                 s {:mode :optional
                    :journal-dir "/nonexistent/m86-journal-fail"
                    :maint-dir (:maint c)
                    :policy :strict}
                 chat player "Ash" "quiz" "m86-unavail")))]
    {:scenario :journal-unavailable
     ;; prepare já falha (dir inexistente) → sem pontos
     :ok? (and (some? code)
               (zero? (pts s chat player)))}))

(defn scenario-http-lost!
  []
  (let [c (ctx "http-lost")
        s (open-plat! c)
        code (caught-code
              (fn [] (score! s c "m86-http" "Ash" "quiz" chat player
                             {:hooks {:before-http (boom "http_lost")}})))
        r2 (score! s c "m86-http" "Ash" "quiz")]
    {:scenario :http-lost
     :ok? (and (some? code)
               (= 1 (pts s chat player))
               (true? (:duplicate r2)))}))

(defn scenario-reconcile!
  []
  (let [c (ctx "recon")
        s (open-plat! c)
        _ (caught-code
           (fn [] (score! s c "m86-recon" "Ash" "adedonha" chat player
                          {:hooks {:before-journal (boom "j")}})))
        recon (rank/reconcile-uncertain! s (:journal c) (:maint c) "m86-recon")
        phases (mapv :phase (journal/list-entries (:journal c)))]
    {:scenario :reconcile
     :ok? (and (= :reconciled (:status recon))
               (some #{:journal-durable} phases)
               (= 1 (pts s chat player)))}))

(defn scenario-volume-loss-replay!
  []
  (let [c (ctx "volume")
        s (open-plat! c)
        _ (score! s c "m86-pre" "Ash" "quiz")
        wm (journal/watermark (:journal c))
        _ (backup/snapshot-storage! (:plat-live c) (:plat-bak c))
        _ (score! s c "m86-post" "Ash" "velha")
        live (pts s chat player)
        _ (wipe-dir! (:plat-live c))
        _ (wipe-dir! (:plat-rec c))
        _ (.mkdirs (io/file (persistence/resolve-storage-dir (:plat-rec c))))
        _ (backup/restore-storage! (:plat-rec c) (:plat-bak c))
        s2 (reopen-plat-rec! c)
        after-restore (pts s2 chat player)
        durable (->> (journal/entries-after (:journal c) wm)
                     (filter #(and (= :journal-durable (:phase %))
                                   (= :score-point (:kind %))
                                   (true? (:datomic-confirmed? %)))))
        replay (mapv #(rank/replay-score-entry! s2 %) durable)
        after-replay (pts s2 chat player)
        again (mapv #(rank/replay-score-entry! s2 %) durable)]
    {:scenario :volume-loss-replay
     :ok? (and (< after-restore live)
               (= live after-replay)
               (every? :duplicate again)
               (some #(false? (:duplicate %)) replay))}))

(defn scenario-jvm-reopen!
  []
  (let [c (ctx "reopen")
        s1 (open-plat! c)
        _ (score! s1 c "m86-reopen" "Ash" "naval")
        s2 (reopen-plat! c)
        r (score! s2 c "m86-reopen" "Ash" "naval")]
    {:scenario :jvm-reopen
     :ok? (and (= 1 (pts s2 chat player))
               (true? (:duplicate r)))}))

(defn scenario-replay-repeated!
  []
  (let [c (ctx "replay-rep")
        s (open-plat! c)
        _ (score! s c "m86-rr" "Ash" "quiz")
        entry (->> (journal/list-entries (:journal c))
                   (filter #(= :journal-durable (:phase %)))
                   last)
        r1 (rank/replay-score-entry! s entry)
        r2 (rank/replay-score-entry! s entry)]
    {:scenario :replay-repeated
     :ok? (and (true? (:duplicate r1))
               (true? (:duplicate r2))
               (= 1 (pts s chat player)))}))

(defn scenario-required-journal-missing!
  []
  (let [c (ctx "req-missing")
        s (open-plat! c)
        code (caught-code
              (fn []
                (rank/score-point!
                 s {:mode :required-strict
                    :maint-dir (:maint c)
                    :policy :strict}
                 chat player "Ash" "quiz" "m86-req")))]
    {:scenario :required-journal-missing
     :code code
     :ok? (and (= :durability_journal_required code)
               (zero? (pts s chat player)))}))

(defn scenario-pokemon-platform-id-isolation!
  "Mesmo operation-id string em DBs/journals distintos → sem colisão."
  []
  (let [c (ctx "iso")
        plat (open-plat! c)
        poke (open-poke! c)
        _ (trainer/create-trainer! poke {:chat-id chat :player-id player})
        op "shared-op-id-m86"
        fence (long (:fence-generation (pports/get-player poke chat player) 0))
        pr (rank/score-point!
            plat (durable-cfg c)
            chat player "Ash" "quiz" op)
        cr (coins/credit-coins!
            poke {:mode :optional
                  :journal-dir (:poke-journal c)
                  :maint-dir (str (:maint c) "-poke")
                  :policy :strict}
            {:chat-id chat :player-id player :save-id "a"
             :fence-generation fence
             :operation-id op
             :coins-delta 7})]
    {:scenario :pokemon-platform-isolation
     :ok? (and (false? (:duplicate pr))
               (false? (:duplicate cr))
               (= 1 (pts plat chat player))
               (= 7 (:coins cr))
               ;; journals separados: ambos têm a op
               (some? (journal/find-by-operation-id (:journal c) op))
               (some? (journal/find-by-operation-id (:poke-journal c) op)))}))

(defn scenario-bridge-http!
  []
  (let [c (ctx "bridge")
        plat (open-plat! c)
        poke (open-poke! c)
        _ (trainer/create-trainer! poke {:chat-id chat :player-id player})
        durable (durable-cfg c)
        srv (bridge/start!
             {:host "127.0.0.1" :port 0 :token "m86-token"
              :store poke :platform-store plat
              :score-point! (rank/make-score-fn durable)})
        url (str (:base-url srv) "/v1/platform/rank/score")
        body (str "{\"chatId\":\"" chat "\",\"playerId\":\"" player
                  "\",\"name\":\"Ash\",\"game\":\"quiz\","
                  "\"operationId\":\"m86-bridge\"}")
        conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setRequestProperty "Authorization" "Bearer m86-token")
               (.setRequestProperty "Content-Type" "application/json"))
        _ (with-open [os (.getOutputStream conn)]
            (.write os (.getBytes body StandardCharsets/UTF_8)))
        status (.getResponseCode conn)
        _ (bridge/stop! srv)]
    {:scenario :bridge-http
     :status status
     :ok? (and (= 200 status)
               (= 1 (pts plat chat player))
               (true? (:durable-rank? srv)))}))

(defn run-all!
  [{:keys [out-dir] :or {out-dir "target/m8-6"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        results
        [(scenario-normal!)
         (scenario-retry!)
         (scenario-two-events!)
         (scenario-payload-mismatch!)
         (scenario-concurrent!)
         (scenario-two-chats-games!)
         (scenario-before-prepare!)
         (scenario-after-commit-before-journal!)
         (scenario-journal-unavailable!)
         (scenario-http-lost!)
         (scenario-reconcile!)
         (scenario-volume-loss-replay!)
         (scenario-jvm-reopen!)
         (scenario-replay-repeated!)
         (scenario-required-journal-missing!)
         (scenario-pokemon-platform-id-isolation!)
         (scenario-bridge-http!)]
        ok? (every? :ok? results)
        report
        {:milestone "M8.6"
         :at (str (Instant/now))
         :coverage rank/coverage
         :results (mapv #(select-keys % [:scenario :ok? :code :status]) results)
         :all-ok? ok?
         :rollback-recon-available? false
         :cutover-ready? false
         :exactly-once-claimed? false
         :rpo-zero-claimed? false
         :ok? ok?}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
