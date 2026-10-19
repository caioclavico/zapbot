(ns odisseu.cutover.failure-gates
  "Cenários de falha M8.1 — cada gate deve impedir ativação.
   Somente sintético / dumps autorizados; sem produção."
  (:require [clojure.java.io :as io]
            [odisseu.cutover.export :as export]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.reconcile :as reconcile]
            [odisseu.cutover.rollback :as rollback]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [pokemon.application.ports :as ports]
            [pokemon.migration.fixtures :as poke-fix]
            [pokemon.migration.import :as poke-import]
            [pokemon.migration.recovery :as recovery]
            [pokemon.migration.transform :as poke-transform]
            [pokemon.application.bridge-tx :as bridge-tx]))

(defn- threw-code
  [thunk]
  (try
    (thunk)
    {:threw? false}
    (catch Exception ex
      (let [data (ex-data ex)]
        {:threw? true
         :type (type ex)
         :message (.getMessage ex)
         :data data
         :code (or (:pokemon/error data)
                   (:code data)
                   (when (re-find #"checksum" (str (.getMessage ex))) :checksum_mismatch)
                   (when (re-find #"export_" (str (.getMessage ex))) :export_meta)
                   :exception)}))))

(defn gate-invalid-checksum
  "Tampera checksums.edn → verify deve falhar."
  [export-path out-dir]
  (let [bad (str (io/file out-dir "checksums-tampered.edn"))
        m (recovery/checksum-manifest [export-path])
        tampered (update m :files
                         (fn [fs]
                           (mapv #(assoc % :sha256 "0deadbeef") fs)))]
    (spit bad (pr-str tampered))
    (let [r (threw-code #(recovery/verify-checksums! tampered))]
      {:gate :invalid-checksum
       :blocked? (:threw? r)
       :detail r})))

(defn gate-incomplete-dump
  "Dump sem meta synthetic/authorized → load bloqueado."
  [out-dir]
  (let [f (str (io/file out-dir "incomplete.edn"))]
    (spit f "{:meta {:note \"sem flag\"} :modules {\"bloqueio\" {}}}")
    (let [r (threw-code #(export/load-dump! f))]
      {:gate :incomplete-meta
       :blocked? (:threw? r)
       :detail r})))

(defn gate-identity-collision
  "Proveniência ambígua entre snapshots → reconcile block."
  [older newer]
  (let [poisoned (update newer :historical_snapshots
                         (fn [snaps]
                           (mapv (fn [s]
                                   (if (:snapshot-id s)
                                     (assoc-in s [:loja "moedas"] 999999)
                                     s))
                                 (or snaps []))))
        r (reconcile/reconcile-dumps older poisoned)]
    {:gate :identity-collision
     :blocked? (boolean (:block-import? r))
     :detail {:collisions (:collisions r)}}))

(defn- wipe-dir!
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))
    (.mkdirs f)))

(defn gate-partial-batch-resume
  "Journal com só :platform; resume não reexecuta platform (duplicate)."
  [platform-dump storage system db out-dir batch-id]
  (wipe-dir! out-dir)
  (let [store (platform-pipe/open-disposable-store! storage system db)
        interrupted (cut-import/interrupt-after-platform!
                     store platform-dump {:import-batch-id batch-id :out-dir out-dir})
        resumed (cut-import/resume-platform!
                 store platform-dump {:import-batch-id batch-id :out-dir out-dir})]
    {:gate :partial-batch-resume
     :blocked-activation? true
     :interrupted-status (:status interrupted)
     :resume-status (:status resumed)
     :ok? (and (= :imported (:status interrupted))
               (= :duplicate (:status resumed)))}))

(defn gate-reimport-same-batch
  [platform-dump storage system db out-dir batch-id]
  (wipe-dir! out-dir)
  (let [store (platform-pipe/open-disposable-store! storage system db)
        a (cut-import/import-platform! store platform-dump
                                       {:import-batch-id batch-id :out-dir out-dir})
        b (cut-import/import-platform! store platform-dump
                                       {:import-batch-id batch-id :out-dir out-dir})]
    {:gate :reimport-idempotent
     :first (:status a)
     :second (:status b)
     :ok? (and (= :imported (:status a)) (= :duplicate (:status b)))}))

(defn gate-destination-not-empty
  "Storage Pokémon com jogador pré-existente (ensure-trainer) → import aborta.
   Usa fixture sintética (não o dump real) para o ensaio de falha."
  [storage system db batch-id]
  (let [poke-dump (poke-fix/build-cassandra-dump)
        store (cut-import/open-pokemon-store! storage system db)
        export-data (export/pokemon-export-from-dump poke-dump)
        ir (poke-transform/transform-export export-data)
        chat (:chat-id ir)
        player (:player-id ir)
        _ (bridge-tx/ensure-trainer! store {:chat-id chat :player-id player})
        r (threw-code
           #(poke-import/import-ir! store ir {:import-batch-id batch-id}))]
    {:gate :destination-not-empty
     :blocked? (:threw? r)
     :code (:code r)
     :player-existed-before? (boolean (ports/get-player store chat player))
     :ok? (and (:threw? r)
               (= :import_player_already_exists (:code r)))}))

(defn gate-equivalence-divergence
  "IR platform vs estado vazio → não equivalente → impede go."
  [platform-dump storage system db]
  (let [ir (cut-import/platform-ir-for-import platform-dump)
        store (platform-pipe/open-disposable-store! storage system db)
        empty-imported (platform-pipe/import-ir!
                        store
                        {:bloqueio {} :admins {} :enquetes {} :quiz-historico {}
                         :rank {} :lembretes {} :bugs {} :participantes {}})
        ;; empty import overwrote — compare original IR vs emptied store read
        emptied (platform-pipe/import-ir!
                 (platform-pipe/open-disposable-store! storage system (str db "-eq"))
                 {:bloqueio {} :admins {} :enquetes {} :quiz-historico {}
                  :rank {} :lembretes {} :bugs {} :participantes {}})
        eq (platform-pipe/equivalence-report ir emptied)
        all-ok? (every? #(true? (:equivalent? %)) (vals eq))]
    {:gate :equivalence-divergence
     :blocked? (not all-ok?)
     :all-equivalent? all-ok?
     :domains (into {} (map (fn [[k v]] [k (:equivalent? v)]) eq))
     :ok? (not all-ok?)}))

(defn gate-abort-before-writer
  [out-dir]
  (let [abort (rollback/abort-before-release! out-dir "m81-failure-drill")
        gates (rollback/evaluate-gates
               {:writers-frozen true
                :snapshot-checksum-ok true
                :reconcile-unblocked true
                :import-equivalent true
                :single-writer-plan true
                :whatsapp-staging-validated false
                :rollback-recon-available false
                :authorization false})]
    {:gate :abort-before-writer
     :aborted? (:aborted? abort)
     :datomic-writer-activated? (:datomic-writer-activated? abort)
     :go? (:go? gates)
     :cutover-ready? (:cutover-ready? gates)
     :ok? (and (:aborted? abort)
               (false? (:datomic-writer-activated? abort))
               (false? (:go? gates))
               (false? (:cutover-ready? gates)))}))

(defn run-all!
  "Executa todos os gates de falha. Retorna ok? se todos bloquearam corretamente."
  [{:keys [older newer platform-dump out-dir storage system]
    :or {storage ".datomic-local/m81-failure-gates"
         system "odisseu-m81-gates"}}]
  (let [out (io/file out-dir)
        _ (.mkdirs out)
        export-bundle (export/write-export-bundle!
                       platform-dump (str (io/file out "export-for-gates"))
                       {:label "m81-gates" :source-path "synthetic"})
        results
        [(gate-invalid-checksum (:export-path export-bundle) (str out))
         (gate-incomplete-dump (str out))
         (gate-identity-collision older newer)
         (gate-partial-batch-resume platform-dump storage system "m81-partial"
                                    (str (io/file out "partial")) "m81-partial-batch")
         (gate-reimport-same-batch platform-dump storage system "m81-reimp"
                                   (str (io/file out "reimp")) "m81-reimp-batch")
         (gate-destination-not-empty ".datomic-local/m81-failure-poke"
                                     system "m81-nonempty" "m81-nonempty-batch")
         (gate-equivalence-divergence platform-dump storage system "m81-eqdiv")
         (gate-abort-before-writer (str (io/file out "abort")))]
        ok? (every? (fn [r]
                      (or (true? (:ok? r))
                          (and (true? (:blocked? r))
                               (contains? #{:invalid-checksum :incomplete-meta
                                            :identity-collision :equivalence-divergence
                                            :destination-not-empty}
                                          (:gate r)))))
                    results)]
    {:milestone "M8.1-failure-gates"
     :ok? ok?
     :results (mapv #(select-keys % [:gate :ok? :blocked? :code :first :second
                                     :resume-status :go? :cutover-ready?])
                    results)
     :activation-permitted? false}))
