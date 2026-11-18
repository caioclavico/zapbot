(ns odisseu.cutover.durability-drill
  "M8.3 — ensaio sintético: backup + journal independente + perda total do volume.
   Storages m83-* isolados por cenário. Sem produção / WhatsApp / dual-write."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.durability :as dur]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.op-journal :as journal]
            [odisseu.cutover.recovery :as recovery]
            [odisseu.platform.application.ports :as pports]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.migration.backup :as backup])
  (:import [java.time Instant]))

(def chat "chat-m83-syn@g.us")
(def player "player-m83-syn@c.us")
(def platform-db "m83-platform")
(def pokemon-db "m83-pokemon")

(defn- ms-now [] (System/currentTimeMillis))

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m83_path_blocked" {:path p}))))

(defn- wipe-dir! [path]
  (let [f (io/file (persistence/resolve-storage-dir path))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- ctx
  "Paths isolados por cenário — evita cliente Datomic Local sobre storage wiped."
  [scenario-id]
  (let [base (str ".datomic-local/m83-" scenario-id)
        c {:id scenario-id
           :system (str "odisseu-m83-" scenario-id)
           :poke-live (str base "-poke")
           :plat-live (str base "-plat")
           :poke-bak (str base "-poke-bak")
           :plat-bak (str base "-plat-bak")
           :poke-rec (str base "-poke-rec")
           :plat-rec (str base "-plat-rec")
           :journal (str base "-journal")}]
    (doseq [p (vals (dissoc c :id :system))]
      (assert-disposable! p)
      (.mkdirs (io/file (persistence/resolve-storage-dir p))))
    (journal/wipe! (:journal c))
    c))

(defn- coins [store]
  (long (or (:coins (ports/get-save store chat player
                                    (or (ports/active-save-id store chat player) "a")))
            0)))

(defn- snapshot [poke platform]
  (let [block (pports/get-chat-block platform chat)]
    {:coins-a (coins poke)
     :rank-pontos (long (or (get-in (pports/get-chat-rank platform chat) [player "pontos"]) 0))
     :blocked (set (map str (or (:comandos block) #{})))
     :admin? (boolean (pports/known-admin? platform chat [player]))}))

(defn- open-fresh! [c]
  {:poke (cut-import/open-pokemon-store! (:poke-live c) (:system c) pokemon-db)
   :platform (platform-pipe/open-disposable-store! (:plat-live c) (:system c) platform-db)
   :ctx c})

(defn- reopen-recovered! [c]
  {:poke (cut-import/reopen-pokemon-store! (:poke-rec c) (:system c) pokemon-db)
   :platform (let [client (persistence/client {:system (:system c)
                                               :storage-dir (:plat-rec c)})
                   conn (persistence/ensure-db! client platform-db)]
               (odisseu.platform.infrastructure.datomic.store/make-store conn))
   :ctx c})

(defn- seed! [poke platform jdir]
  (trainer/create-trainer! poke {:chat-id chat :player-id player})
  (let [r (reward/apply-gameplay-reward-on-active!
           poke chat player {:operation-id "m83-seed-coins" :coins-delta 1000})]
    (when-not (:duplicate r)
      (journal/append! jdir
                       {:operation-id "m83-seed-coins"
                        :domain :pokemon :kind :coins-delta
                        :scope {:chat chat :player player :save "a"}
                        :payload {:coins-delta 1000}
                        :datomic-confirmed? true})))
  (pports/set-bot-blocked! platform chat false "m83-seed-bot")
  (pports/remember-admin! platform chat player "m83-seed-admin")
  (journal/append! jdir
                   {:operation-id "m83-seed-admin"
                    :domain :platform :kind :remember-admin
                    :scope {:chat chat :player player}
                    :payload {:player player}
                    :datomic-confirmed? true})
  {:coins (coins poke)})

(defn- confirm+journal!
  [jdir datomic-thunk entry-map]
  (let [dres (datomic-thunk)
        dup? (boolean (:duplicate dres))
        jres (when-not dup?
               (journal/append! jdir (assoc entry-map :datomic-confirmed? true)))]
    {:datomic dres
     :duplicate? dup?
     :journal (or jres {:ok true :skipped-duplicate true})}))

(defn- apply-pre-backup! [poke platform jdir]
  (let [c (confirm+journal!
           jdir
           #(reward/apply-gameplay-reward-on-active!
             poke chat player {:operation-id "m83-pre-coins" :coins-delta 50})
           {:operation-id "m83-pre-coins" :domain :pokemon :kind :coins-delta
            :scope {:chat chat :player player :save "a"}
            :payload {:coins-delta 50}})
        s (confirm+journal!
           jdir
           #(pports/score-point! platform chat player "Ash" "quiz" "m83-pre-score")
           {:operation-id "m83-pre-score" :domain :platform :kind :score-point
            :scope {:chat chat :player player}
            :payload {:name "Ash" :game "quiz"}})
        b (confirm+journal!
           jdir
           #(pports/block-commands! platform chat ["naval"] "m83-pre-block")
           {:operation-id "m83-pre-block" :domain :platform :kind :block-commands
            :scope {:chat chat}
            :payload {:commands ["naval"]}})]
    {:ops [c s b] :state (snapshot poke platform) :watermark (journal/watermark jdir)}))

(defn- apply-post-backup! [poke platform jdir]
  (let [c (confirm+journal!
           jdir
           #(reward/apply-gameplay-reward-on-active!
             poke chat player {:operation-id "m83-post-coins" :coins-delta 77})
           {:operation-id "m83-post-coins" :domain :pokemon :kind :coins-delta
            :scope {:chat chat :player player :save "a"}
            :payload {:coins-delta 77}})
        s (confirm+journal!
           jdir
           #(pports/score-point! platform chat player "Ash" "velha" "m83-post-score")
           {:operation-id "m83-post-score" :domain :platform :kind :score-point
            :scope {:chat chat :player player}
            :payload {:name "Ash" :game "velha"}})
        b (confirm+journal!
           jdir
           #(pports/block-commands! platform chat ["quiz"] "m83-post-block")
           {:operation-id "m83-post-block" :domain :platform :kind :block-commands
            :scope {:chat chat}
            :payload {:commands ["quiz"]}})]
    {:ops [c s b]
     :op-ids ["m83-post-coins" "m83-post-score" "m83-post-block"]
     :state (snapshot poke platform)
     :at-ms (ms-now)}))

(defn- replay-entry!
  [poke platform entry]
  (let [op (:operation-id entry)
        scope (:scope entry)
        payload (:payload entry)]
    (when-not (= chat (:chat scope))
      (throw (ex-info "replay_scope_mismatch" {:expected chat :got (:chat scope)})))
    (case (:kind entry)
      :coins-delta
      (reward/apply-gameplay-reward-on-active!
       poke chat player
       {:operation-id op :coins-delta (long (:coins-delta payload))})

      :score-point
      (pports/score-point! platform chat player
                           (:name payload) (:game payload) op)

      :block-commands
      (pports/block-commands! platform chat (:commands payload) op)

      :remember-admin
      (pports/remember-admin! platform chat (:player payload player) op)

      (throw (ex-info "replay_kind_unsupported" {:kind (:kind entry)})))))

(defn- replay-after!
  [poke platform jdir after-seq]
  (mapv (fn [e]
          (let [r (replay-entry! poke platform e)]
            {:operation-id (:operation-id e)
             :seq (:seq e)
             :duplicate? (boolean (:duplicate r))}))
        (journal/entries-after jdir after-seq)))

(defn- backup-live! [c]
  (backup/snapshot-storage! (:plat-live c) (:plat-bak c))
  (backup/snapshot-storage! (:poke-live c) (:poke-bak c)))

(defn- restore-to-rec! [c]
  (wipe-dir! (:plat-rec c))
  (wipe-dir! (:poke-rec c))
  (.mkdirs (io/file (persistence/resolve-storage-dir (:plat-rec c))))
  (.mkdirs (io/file (persistence/resolve-storage-dir (:poke-rec c))))
  (backup/restore-storage! (:plat-rec c) (:plat-bak c))
  (backup/restore-storage! (:poke-rec c) (:poke-bak c)))

(defn- scenario-happy-path!
  []
  (let [c (ctx "happy")
        j (:journal c)
        {:keys [poke platform]} (open-fresh! c)
        _ (seed! poke platform j)
        pre (apply-pre-backup! poke platform j)
        wm (:watermark pre)
        backup-at (ms-now)
        _ (backup-live! c)
        post (apply-post-backup! poke platform j)
        state-live (:state post)
        failure-at (ms-now)
        _ (wipe-dir! (:plat-live c))
        _ (wipe-dir! (:poke-live c))
        t0 (ms-now)
        _ (restore-to-rec! c)
        rec (reopen-recovered! c)
        poke2 (:poke rec)
        platform2 (:platform rec)
        state-after-restore (snapshot poke2 platform2)
        replay (replay-after! poke2 platform2 j wm)
        state-after-replay (snapshot poke2 platform2)
        recovered-at (ms-now)
        again (replay-after! poke2 platform2 j wm)]
    {:name :happy-backup-journal-replay
     :watermark wm
     :post-op-ids (:op-ids post)
     :state-live state-live
     :state-after-restore state-after-restore
     :state-after-replay state-after-replay
     :post-lost-before-replay?
     (and (< (:coins-a state-after-restore) (:coins-a state-live))
          (< (:rank-pontos state-after-restore) (:rank-pontos state-live)))
     :reconstructed?
     (and (= (:coins-a state-live) (:coins-a state-after-replay))
          (= (:rank-pontos state-live) (:rank-pontos state-after-replay))
          (= (:blocked state-live) (:blocked state-after-replay)))
     :all-replay-duplicates? (every? :duplicate? again)
     :rpo (recovery/compute-rpo
           {:backup-at-ms backup-at
            :last-confirmed-tx-at-ms (:at-ms post)
            :lost-op-ids []})
     :rto (recovery/compute-rto {:failure-at-ms failure-at :recovered-at-ms recovered-at})
     :restore-ms (- recovered-at t0)
     :ok? (and (< (:coins-a state-after-restore) (:coins-a state-live))
               (= (:coins-a state-live) (:coins-a state-after-replay))
               (= (:rank-pontos state-live) (:rank-pontos state-after-replay))
               (every? :duplicate? again))}))

(defn- scenario-journal-lag!
  []
  (let [c (ctx "lag")
        j (:journal c)
        {:keys [poke platform]} (open-fresh! c)
        _ (seed! poke platform j)
        pre (apply-pre-backup! poke platform j)
        wm (:watermark pre)
        _ (backup-live! c)
        dres (reward/apply-gameplay-reward-on-active!
              poke chat player {:operation-id "m83-lag-coins" :coins-delta 33})
        jres (journal/append-best-effort!
              "/nonexistent/m83-journal-fail"
              {:operation-id "m83-lag-coins"
               :domain :pokemon :kind :coins-delta
               :scope {:chat chat :player player :save "a"}
               :payload {:coins-delta 33}
               :datomic-confirmed? true})
        live-coins (coins poke)
        _ (wipe-dir! (:plat-live c))
        _ (wipe-dir! (:poke-live c))
        _ (restore-to-rec! c)
        rec (reopen-recovered! c)
        _ (replay-after! (:poke rec) (:platform rec) j wm)
        after (coins (:poke rec))]
    {:name :datomic-ok-journal-fail
     :journal-ok? (boolean (:ok jres))
     :at-risk? (boolean (:at-risk jres))
     :live-coins live-coins
     :after-restore-replay-coins after
     :lost? (< after live-coins)
     :ok? (and (false? (:ok jres))
               (true? (:at-risk jres))
               (false? (boolean (:duplicate dres)))
               (< after live-coins))}))

(defn- scenario-dual-loss!
  []
  (let [c (ctx "dual")
        j (:journal c)
        {:keys [poke platform]} (open-fresh! c)
        _ (seed! poke platform j)
        pre (apply-pre-backup! poke platform j)
        wm (:watermark pre)
        _ (backup-live! c)
        post (apply-post-backup! poke platform j)
        live (:state post)
        _ (wipe-dir! (:plat-live c))
        _ (wipe-dir! (:poke-live c))
        _ (journal/wipe! j)
        _ (restore-to-rec! c)
        rec (reopen-recovered! c)
        after-restore (snapshot (:poke rec) (:platform rec))
        replay-n (count (journal/entries-after j wm))]
    {:name :dual-loss-datomic-and-journal
     :live live
     :after-restore after-restore
     :post-op-ids (:op-ids post)
     :replay-entries replay-n
     :post-lost? (< (:coins-a after-restore) (:coins-a live))
     :ok? (and (zero? replay-n)
               (< (:coins-a after-restore) (:coins-a live)))}))

(defn- scenario-http-lost!
  []
  (let [c (ctx "http")
        j (:journal c)
        {:keys [poke]} (open-fresh! c)
        _ (trainer/create-trainer! poke {:chat-id chat :player-id player})
        r1 (reward/apply-gameplay-reward-on-active!
            poke chat player {:operation-id "m83-http" :coins-delta 5})
        _ (journal/append! j
                           {:operation-id "m83-http" :domain :pokemon :kind :coins-delta
                            :scope {:chat chat :player player :save "a"}
                            :payload {:coins-delta 5}
                            :datomic-confirmed? true})
        r2 (reward/apply-gameplay-reward-on-active!
            poke chat player {:operation-id "m83-http" :coins-delta 5})]
    {:name :http-lost-retry
     :ok? (and (false? (boolean (:duplicate r1)))
               (true? (boolean (:duplicate r2))))}))

(defn- scenario-intent-datomic-fail!
  []
  (let [c (ctx "intent")
        intent (journal/write-intent!
                (:journal c)
                {:operation-id "m83-intent-only"
                 :domain :pokemon :kind :coins-delta
                 :scope {:chat chat :player player :save "a"}
                 :payload {:coins-delta 99}})]
    {:name :intent-without-datomic
     :intent-phase (:phase intent)
     :datomic-confirmed? (:datomic-confirmed? intent)
     ;; M8.4 renomeou :intent → :prepared (mesma semântica: pré-Datomic)
     :ok? (and (contains? #{:prepared :intent} (:phase intent))
               (false? (:datomic-confirmed? intent)))}))

(defn- scenario-concurrency-order!
  []
  (let [c (ctx "conc")
        j (:journal c)
        {:keys [poke platform]} (open-fresh! c)
        _ (seed! poke platform j)
        _ (dotimes [i 5]
            (let [op (str "m83-ord-" i)]
              (confirm+journal!
               j
               #(pports/score-point! platform chat player "Ash" "quiz" op)
               {:operation-id op :domain :platform :kind :score-point
                :scope {:chat chat :player player}
                :payload {:name "Ash" :game "quiz"}})))
        seqs (mapv :seq (journal/list-entries j))
        ops (->> (journal/list-entries j)
                 (filter #(re-find #"m83-ord-" (:operation-id %)))
                 (mapv :operation-id))]
    {:name :concurrency-ordering
     :monotonic? (apply < seqs)
     :ok? (and (apply < seqs)
               (= ops (mapv #(str "m83-ord-" %) (range 5))))}))

(defn- scenario-scope-isolation!
  []
  (let [c (ctx "scope")
        {:keys [poke platform]} (open-fresh! c)
        _ (seed! poke platform (:journal c))
        threw? (try
                 (replay-entry! poke platform
                                {:operation-id "m83-wrong-chat"
                                 :kind :score-point
                                 :scope {:chat "other@g.us" :player player}
                                 :payload {:name "X" :game "quiz"}})
                 false
                 (catch Exception _ true))]
    {:name :scope-isolation :ok? threw?}))

(defn- scenario-corrupt-journal!
  []
  (let [c (ctx "corrupt")
        j (:journal c)
        _ (journal/append! j
                           {:operation-id "m83-ok" :domain :platform :kind :score-point
                            :scope {:chat chat :player player}
                            :payload {:name "A" :game "quiz"}
                            :datomic-confirmed? true})
        corr (journal/corrupt-last! j)
        read-fail? (try (journal/list-entries j) false (catch Exception _ true))
        maint (recovery/enter-maintenance!
               "target/m8-3/maint-corrupt" "journal-corrupt")]
    {:name :journal-corruption
     :corrupted (:corrupted corr)
     :read-fail? read-fail?
     :maintenance? (:maintenance? maint)
     :ok? (and read-fail? (:maintenance? maint))}))

(defn run-drill!
  [{:keys [out-dir] :or {out-dir "target/m8-3"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        t0 (ms-now)
        happy (scenario-happy-path!)
        lag (scenario-journal-lag!)
        dual (scenario-dual-loss!)
        http (scenario-http-lost!)
        intent (scenario-intent-datomic-fail!)
        conc (scenario-concurrency-order!)
        scope (scenario-scope-isolation!)
        corrupt (scenario-corrupt-journal!)
        gates (dur/evaluate-m83-gates
               {:journal-recon-proven? (:ok? happy)
                :dual-loss-still-fatal? (:ok? dual)
                :journal-lag-honest? (:ok? lag)
                :whatsapp-staging-validated? false
                :production-journal-deployed? false})
        report
        {:milestone "M8.3"
         :at (str (Instant/now))
         :elapsed-ms (- (ms-now) t0)
         :audit dur/datomic-local-audit
         :strategies dur/strategy-comparison
         :chosen dur/chosen-strategy
         :atomicity dur/journal-atomicity
         :policy dur/operational-policy
         :scenarios
         {:happy happy
          :journal-lag lag
          :dual-loss dual
          :http-lost http
          :intent intent
          :concurrency conc
          :scope scope
          :corrupt corrupt}
         :recovered-with-journal (:post-op-ids happy)
         :lost-when-journal-lags ["m83-lag-coins"]
         :lost-when-dual-loss (:post-op-ids dual)
         :rpo-happy (:rpo happy)
         :rto-happy (:rto happy)
         :gates gates
         :journal-recon-proven? (:ok? happy)
         :rollback-recon-available? (:rollback-recon-available? gates)
         :cutover-ready? false
         :exactly-once-claimed? false
         :ok? (and (:ok? happy) (:ok? lag) (:ok? dual) (:ok? http)
                   (:ok? intent) (:ok? conc) (:ok? scope) (:ok? corrupt)
                   (true? (:journal-recon-proven? gates))
                   (false? (:rollback-recon-available? gates))
                   (false? (:cutover-ready? gates)))}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
