(ns odisseu.cutover.recovery-drill
  "M8.2 — ensaio sintético de falha/restauração/reprocessamento.
   Storages exclusivos m82-*. Sem produção / WhatsApp / dual-write."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.recovery :as recovery]
            [odisseu.cutover.rollback :as rollback]
            [odisseu.platform.application.ports :as pports]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward]
            [pokemon.application.trainer :as trainer]
            [pokemon.migration.backup :as backup])
  (:import [java.time Instant]))

(def storage-platform ".datomic-local/m82-recovery-platform")
(def storage-platform-bak ".datomic-local/m82-recovery-platform-bak")
(def storage-pokemon ".datomic-local/m82-recovery-pokemon")
(def storage-pokemon-bak ".datomic-local/m82-recovery-pokemon-bak")
;; Destinos NOVOS para restore (nunca sobrescrever volume live ainda aberto).
(def storage-platform-recovered ".datomic-local/m82-recovery-platform-recovered")
(def storage-pokemon-recovered ".datomic-local/m82-recovery-pokemon-recovered")
(def system-name "odisseu-m82-recovery")
;; Restore preserva o subdir do system original — reabrir com o mesmo system.
(def platform-db "m82-platform")
(def pokemon-db "m82-pokemon")

(def chat "chat-m82-syn@g.us")
(def player "player-m82-syn@c.us")

(defn- ms-now [] (System/currentTimeMillis))

(defn- assert-disposable! [p]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str p))
    (throw (ex-info "m82_path_blocked" {:path p}))))

(defn- wipe-dir! [path]
  (let [f (io/file (persistence/resolve-storage-dir path))]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))))

(defn- open-platform! [db]
  (assert-disposable! storage-platform)
  (platform-pipe/open-disposable-store! storage-platform system-name db))

(defn- reopen-platform!
  ([db] (reopen-platform! storage-platform system-name db))
  ([storage system db]
   (let [client (persistence/client {:system system :storage-dir storage})
         conn (persistence/ensure-db! client db)]
     (odisseu.platform.infrastructure.datomic.store/make-store conn))))

(defn- open-pokemon! []
  (assert-disposable! storage-pokemon)
  (cut-import/open-pokemon-store! storage-pokemon system-name pokemon-db))

(defn- reopen-pokemon!
  ([] (reopen-pokemon! storage-pokemon system-name))
  ([storage system]
   (cut-import/reopen-pokemon-store! storage system pokemon-db)))

(defn- coins [store]
  (long (or (:coins (ports/get-save store chat player
                                    (or (ports/active-save-id store chat player) "a")))
            0)))

(defn- seed-pokemon!
  [store]
  (trainer/create-trainer! store {:chat-id chat :player-id player})
  (reward/apply-gameplay-reward-on-active!
   store chat player
   {:operation-id "m82-seed-coins"
    :coins-delta 1000})
  {:coins (coins store)
   :active (ports/active-save-id store chat player)})

(defn- seed-platform!
  [store]
  (pports/set-bot-blocked! store chat false "m82-seed-bot")
  (pports/remember-admin! store chat player "m82-seed-admin")
  (pports/create-poll! store chat
                       {:pergunta "M82 café?"
                        :opcoes ["Sim" "Não"]
                        :prefix "!"}
                       "m82-seed-poll")
  (pports/create-reminder! store
                           {:id "rem-m82-1"
                            :chat chat
                            :texto "ensaio"
                            :criador player
                            :em (+ (ms-now) 3600000)}
                           "m82-seed-rem")
  {:admins (pports/known-admin? store chat [player])
   :poll (pports/get-chat-poll store chat)
   :reminder (pports/get-reminder store "rem-m82-1")})

(defn- apply-pre-backup-ops!
  "Ops confirmadas ANTES do backup — devem sobreviver ao restore."
  [poke platform]
  (let [r-coins (reward/apply-gameplay-reward-on-active!
                 poke chat player
                 {:operation-id "m82-pre-coins" :coins-delta 50})
        r-score (pports/score-point! platform chat player "Ash" "quiz" "m82-pre-score")
        r-block (pports/block-commands! platform chat ["naval"] "m82-pre-block")
        r-vote (pports/cast-vote! platform chat player 1 "m82-pre-vote")
        _ (trainer/switch-save! poke chat player "b")
        _ (reward/apply-gameplay-reward-on-active!
           poke chat player
           {:operation-id "m82-pre-coins-b" :coins-delta 10})
        _ (trainer/switch-save! poke chat player "a")]
    {:at-ms (ms-now)
     :op-ids ["m82-pre-coins" "m82-pre-score" "m82-pre-block" "m82-pre-vote"
              "m82-pre-coins-b"]
     :coins-a (coins poke)
     :duplicate-hints {:coins (boolean (:duplicate r-coins))
                       :score (boolean (:duplicate r-score))
                       :block (boolean (:duplicate r-block))
                       :vote (boolean (:duplicate r-vote))}
     :active-after (ports/active-save-id poke chat player)}))

(defn- apply-post-backup-ops!
  "Ops confirmadas DEPOIS do backup — perdidas no restore a partir do backup."
  [poke platform]
  (let [r1 (reward/apply-gameplay-reward-on-active!
            poke chat player
            {:operation-id "m82-post-coins" :coins-delta 77})
        r2 (pports/score-point! platform chat player "Ash" "velha" "m82-post-score")
        r3 (pports/block-commands! platform chat ["quiz"] "m82-post-block")]
    {:at-ms (ms-now)
     :op-ids ["m82-post-coins" "m82-post-score" "m82-post-block"]
     :coins-after (coins poke)
     :rank-pontos (get-in (pports/get-chat-rank platform chat) [player "pontos"])
     :duplicates? (or (:duplicate r1) (:duplicate r2) (:duplicate r3))}))

(defn- snapshot-state
  [poke platform]
  (let [poll (when platform (pports/get-chat-poll platform chat))
        block (when platform (pports/get-chat-block platform chat))]
    {:coins-a (if (ports/get-player poke chat player)
                (do (trainer/switch-save! poke chat player "a")
                    (coins poke))
                0)
     :active (when (ports/get-player poke chat player)
               (ports/active-save-id poke chat player))
     :rank-pontos (long (or (get-in (pports/get-chat-rank platform chat) [player "pontos"]) 0))
     :blocked (set (map str (or (:comandos block) #{})))
     :admin? (boolean (pports/known-admin? platform chat [player]))
     :poll-votes (get-in poll [:votos player])
     :reminder? (boolean (pports/get-reminder platform "rem-m82-1"))}))

(defn- reprocess-idempotent!
  "Retry mesmos op-ids pré-backup → devem ser duplicate / sem double-apply."
  [poke platform expected-coins expected-pontos]
  (let [c1 (reward/apply-gameplay-reward-on-active!
            poke chat player
            {:operation-id "m82-pre-coins" :coins-delta 50})
        s1 (pports/score-point! platform chat player "Ash" "quiz" "m82-pre-score")
        b1 (pports/block-commands! platform chat ["naval"] "m82-pre-block")
        v1 (pports/cast-vote! platform chat player 1 "m82-pre-vote")
        state (snapshot-state poke platform)]
    {:retries {:coins-dup? (boolean (:duplicate c1))
               :score-dup? (boolean (:duplicate s1))
               :block-dup? (boolean (:duplicate b1))
               :vote-dup? (boolean (:duplicate v1))}
     :state state
     :coins-unchanged? (= expected-coins (:coins-a state))
     :pontos-unchanged? (= expected-pontos (:rank-pontos state))
     :ok? (and (boolean (:duplicate c1))
               (boolean (:duplicate s1))
               (boolean (:duplicate b1))
               (= expected-coins (:coins-a state))
               (= expected-pontos (:rank-pontos state)))}))

(defn- simulate-http-lost-then-retry!
  "Commit + 'HTTP perdido' → retry mesmo op-id."
  [poke]
  (let [op "m82-http-lost-coins"
        r1 (reward/apply-gameplay-reward-on-active!
            poke chat player {:operation-id op :coins-delta 5})
        r2 (reward/apply-gameplay-reward-on-active!
            poke chat player {:operation-id op :coins-delta 5})]
    {:first-dup? (boolean (:duplicate r1))
     :retry-dup? (boolean (:duplicate r2))
     :ok? (and (false? (boolean (:duplicate r1)))
               (true? (boolean (:duplicate r2))))}))

(defn- drill-backup-restore-loss!
  "Backup → TXs pós no live → restore do bak em DESTINO NOVO → RPO (perda)."
  [out-dir]
  (assert-disposable! storage-platform)
  (assert-disposable! storage-pokemon)
  (let [_ (recovery/enter-maintenance! out-dir "m82-drill-quiesce-for-backup")
        poke (open-pokemon!)
        platform (open-platform! platform-db)
        seed-p (seed-pokemon! poke)
        seed-pl (seed-platform! platform)
        pre (apply-pre-backup-ops! poke platform)
        state-pre-backup (snapshot-state poke platform)
        backup-at (ms-now)
        _ (assert-disposable! storage-platform-bak)
        _ (assert-disposable! storage-pokemon-bak)
        ;; quiesce: backup com processo ainda open é aceitável no Local se
        ;; nenhuma TX concorrente — pós-ops só depois do snapshot retornar.
        bak-pl (backup/snapshot-storage! storage-platform storage-platform-bak)
        bak-pk (backup/snapshot-storage! storage-pokemon storage-pokemon-bak)
        ;; sair da janela de quiesce — TXs pós-backup no LIVE
        post (apply-post-backup-ops! poke platform)
        state-after-post (snapshot-state poke platform)
        last-tx-at (:at-ms post)
        failure-at (ms-now)
        ;; Falha de volume: abandonar live; restaurar bak em destino NOVO.
        _ (assert-disposable! storage-platform-recovered)
        _ (assert-disposable! storage-pokemon-recovered)
        _ (wipe-dir! storage-platform-recovered)
        _ (wipe-dir! storage-pokemon-recovered)
        t-restore0 (ms-now)
        res-pl (backup/restore-storage! storage-platform-recovered storage-platform-bak)
        res-pk (backup/restore-storage! storage-pokemon-recovered storage-pokemon-bak)
        restore-ms (- (ms-now) t-restore0)
        recovered-at (ms-now)
        poke2 (reopen-pokemon! storage-pokemon-recovered system-name)
        platform2 (reopen-platform! storage-platform-recovered system-name platform-db)
        state-restored (snapshot-state poke2 platform2)
        lost-ops (:op-ids post)
        rpo (recovery/compute-rpo
             {:backup-at-ms backup-at
              :last-confirmed-tx-at-ms last-tx-at
              :lost-op-ids lost-ops})
        rto (recovery/compute-rto
             {:failure-at-ms failure-at :recovered-at-ms recovered-at})
        pre-survived? (and (= (:coins-a state-pre-backup) (:coins-a state-restored))
                           (= (:rank-pontos state-pre-backup) (:rank-pontos state-restored))
                           (contains? (:blocked state-restored) "naval")
                           (not (contains? (:blocked state-restored) "quiz")))
        post-lost? (and (< (:coins-a state-restored) (:coins-a state-after-post))
                        (< (:rank-pontos state-restored) (:rank-pontos state-after-post))
                        (contains? (:blocked state-after-post) "quiz")
                        (not (contains? (:blocked state-restored) "quiz")))
        reprocess (reprocess-idempotent!
                   poke2 platform2
                   (:coins-a state-restored)
                   (:rank-pontos state-restored))
        http-lost (simulate-http-lost-then-retry! poke2)
        _ (recovery/enter-maintenance!
           out-dir
           "confirmed-ops-lost-after-restore; write disabled")
        exit-blocked?
        (try
          (recovery/exit-maintenance! out-dir {:divergence-resolved? false})
          false
          (catch Exception _ true))
        gates (recovery/evaluate-recovery-gates
               {:backup-consistent-validated true
                :restore-proven true
                :single-writer true
                :safe-reprocess (boolean (:ok? reprocess))
                :storage-failure-handled true
                :no-silent-divergence (boolean
                                       (get (recovery/maintenance-status out-dir)
                                            :maintenance?))
                :rpo-rto-measured (and (some? (:rpo-ms rpo)) (some? (:rto-ms rto)))
                :maintenance-procedure (and exit-blocked?
                                            (get (recovery/maintenance-status out-dir)
                                                 :maintenance?))
                :lost-confirmed-ops (count lost-ops)
                :external-durable-log? false
                :rollback-recon-available false})]
    {:seed {:pokemon seed-p :platform seed-pl}
     :pre-backup pre
     :state-pre-backup state-pre-backup
     :backup {:platform bak-pl :pokemon bak-pk :at-ms backup-at}
     :post-backup post
     :state-after-post state-after-post
     :live-abandoned? true
     :restore {:platform res-pl :pokemon res-pk :ms restore-ms
               :dest {:platform storage-platform-recovered
                      :pokemon storage-pokemon-recovered}}
     :state-restored state-restored
     :pre-survived? pre-survived?
     :post-lost? post-lost?
     :lost-op-ids lost-ops
     :reprocess reprocess
     :http-lost-retry http-lost
     :rpo rpo
     :rto rto
     :maintenance (recovery/maintenance-status out-dir)
     :exit-without-resolve-blocked? exit-blocked?
     :gates gates
     :recovered-stores {:poke poke2 :platform platform2}
     :zero-loss-proven? false
     :ok? (and pre-survived?
               post-lost?
               (:ok? reprocess)
               (:ok? http-lost)
               exit-blocked?
               (false? (:rollback-recon-available? gates))
               (false? (:zero-loss-proven? gates)))}))

(defn- drill-restore-to-new-dest!
  "Segundo restore independente do bak Pokémon (integridade do artefato)."
  []
  (let [dest ".datomic-local/m82-recovery-pokemon-verify"
        _ (assert-disposable! dest)
        _ (wipe-dir! dest)
        r (backup/restore-storage! dest storage-pokemon-bak)
        store (cut-import/reopen-pokemon-store! dest system-name pokemon-db)
        c (coins store)]
    {:ok true
     :restored-to dest
     :coins-a c
     :matches-pre-backup? (= 1050 c)
     :backup-result r}))

(defn- drill-partial-store!
  "Platform recovered ok, Pokémon 'indisponível' → manutenção acoplada."
  [out-dir platform]
  (let [rank-ok? (pos? (long (or (get-in (pports/get-chat-rank platform chat)
                                         [player "pontos"])
                                 0)))
        _ (recovery/enter-maintenance!
           out-dir "pokemon-store-unavailable; coupled maintenance")
        st (recovery/maintenance-status out-dir)]
    {:platform-readable? rank-ok?
     :maintenance? (:maintenance? st)
     :write-enabled? (:write-enabled? st)
     :ok? (and rank-ok? (:maintenance? st) (false? (:write-enabled? st)))}))

(defn- drill-jvm-reopen!
  "Reabre stores recovered (simula restart JVM)."
  []
  (let [poke (reopen-pokemon! storage-pokemon-recovered system-name)
        platform (reopen-platform! storage-platform-recovered system-name platform-db)
        st (snapshot-state poke platform)]
    ;; coins pode ser 1050+ se o drill HTTP-lost já creditou no recovered
    {:state st
     :ok? (and (>= (long (:coins-a st)) 1050)
               (= 1 (:rank-pontos st))
               (contains? (:blocked st) "naval")
               (not (contains? (:blocked st) "quiz"))
               (:admin? st)
               (:reminder? st))}))

(defn run-drill!
  [{:keys [out-dir]
    :or {out-dir "target/m8-2"}}]
  (let [root (io/file (persistence/project-root) out-dir)
        _ (.mkdirs root)
        t0 (ms-now)
        primary (drill-backup-restore-loss! (str (io/file root "maint")))
        new-dest (drill-restore-to-new-dest!)
        partial (drill-partial-store! (str (io/file root "maint-partial"))
                                      (get-in primary [:recovered-stores :platform]))
        reopen (drill-jvm-reopen!)
        ;; não serializar stores no report
        primary' (dissoc primary :recovered-stores)
        freeze (rollback/freeze-legacy-writers! (str (io/file root "gates")))
        abort (rollback/abort-before-release!
               (str (io/file root "gates"))
               "m82-recovery-not-zero-loss")
        cutover-merge
        (recovery/merge-cutover-gates
         {:writers-frozen true
          :snapshot-checksum-ok true
          :reconcile-unblocked true
          :import-equivalent true
          :single-writer-plan true
          :whatsapp-staging-validated false
          :authorization false}
         (:gates primary))
        report
        {:milestone "M8.2"
         :at (str (Instant/now))
         :elapsed-ms (- (ms-now) t0)
         :strategy recovery/recovery-strategy
         :failure-catalog recovery/failure-catalog
         :primary-drill primary'
         :restore-to-new-dest new-dest
         :partial-store partial
         :jvm-reopen reopen
         :freeze freeze
         :abort abort
         :gates-merged cutover-merge
         :recovered-ops (:op-ids (:pre-backup primary'))
         :unrecovered-ops (:lost-op-ids primary')
         :rpo (:rpo primary')
         :rto (:rto primary')
         :zero-loss-proven? false
         :rollback-recon-available? false
         :cutover-ready? false
         :ok? (and (:ok? primary')
                   (true? (:ok new-dest))
                   (true? (:matches-pre-backup? new-dest))
                   (:ok? partial)
                   (:ok? reopen)
                   (false? (get-in cutover-merge [:cutover :go?])))}]
    (spit (str (io/file root "report.edn")) (with-out-str (pprint/pprint report)))
    report))
