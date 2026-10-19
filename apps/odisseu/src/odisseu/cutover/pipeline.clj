(ns odisseu.cutover.pipeline
  "Orquestra M8.0: inventário → export → reconcile → import → equivalência → rollback drill.
   Somente fixtures sintéticas / offline autorizadas. Sem produção / WhatsApp / dual-write."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [odisseu.cutover.export :as export]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.inventory :as inv]
            [odisseu.cutover.reconcile :as reconcile]
            [odisseu.cutover.rollback :as rollback]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as poke-store]
            [pokemon.migration.fixtures :as poke-fix]
            [pokemon.migration.equivalence :as poke-eq]
            [pokemon.migration.backup :as backup]))

(def default-older "fixtures/m8-0/snapshot-older.edn")
(def default-newer "fixtures/m8-0/snapshot-newer.edn")
(def default-out "target/m8-0")
(def default-storage ".datomic-local/m80-cutover")
(def default-backup ".datomic-local/m80-cutover-backup")
(def default-poke-storage ".datomic-local/m80-cutover-poke")
(def default-poke-backup ".datomic-local/m80-cutover-poke-backup")
(def default-system "odisseu-m80-cutover")
(def default-db "m80-platform")
(def default-poke-db "m80-pokemon")

(defn- project-path [rel]
  (str (io/file (persistence/project-root) rel)))

(defn run-inventory!
  [dump]
  (inv/inventory-dump dump))

(defn run-export!
  [dump out-dir label]
  (export/write-export-bundle! dump out-dir {:label label :source-path label}))

(defn run-reconcile!
  [older newer]
  (reconcile/reconcile-dumps older newer))

(defn run-platform-import-cycle!
  "Import + reimport idempotente + resume após interrupção simulada."
  [newer out-dir batch-id]
  (let [storage default-storage
        system default-system
        db default-db
        store (platform-pipe/open-disposable-store! storage system db)
        interrupt-dir (str (io/file out-dir "interrupt"))
        _ (.mkdirs (io/file interrupt-dir))
        interrupted (cut-import/interrupt-after-platform!
                     store newer {:import-batch-id batch-id
                                  :out-dir interrupt-dir})
        ;; resume: mesmo batch, journal já tem :platform → duplicate
        resumed (cut-import/resume-platform!
                 store newer {:import-batch-id batch-id
                              :out-dir interrupt-dir})
        ;; ciclo limpo em outro subdir
        clean-dir (str (io/file out-dir "import-clean"))
        store2 (platform-pipe/open-disposable-store! storage system (str db "-clean"))
        first-import (cut-import/import-platform!
                      store2 newer {:import-batch-id batch-id :out-dir clean-dir})
        reimport (cut-import/import-platform!
                  store2 newer {:import-batch-id batch-id :out-dir clean-dir})]
    {:interrupted interrupted
     :resumed-status (:status resumed)
     :first-import first-import
     :reimport-status (:status reimport)
     :equivalence (get-in first-import [:equivalence])
     :all-domains-equivalent?
     (every? #(true? (get-in % [:equivalent?]))
             (vals (or (get-in first-import [:equivalence]) {})))
     :poller-started? false}))

(defn run-pokemon-import!
  "Usa dump M5 sintético embutido (dois saves) — não ensure-trainer fill."
  [out-dir batch-id]
  (let [dump (poke-fix/build-cassandra-dump)
        store (cut-import/open-pokemon-store!
               default-poke-storage default-system default-poke-db)
        result (cut-import/import-pokemon!
                store dump {:import-batch-id batch-id :out-dir out-dir})
        eq (poke-eq/compare-ir store (:ir result))
        bak (backup/snapshot-storage! default-poke-storage default-poke-backup)
        ;; wipe + restore
        _ (let [client (persistence/client {:system default-system
                                            :storage-dir default-poke-storage})]
            (persistence/delete-db! client default-poke-db))
        _ (backup/restore-storage! default-poke-storage default-poke-backup)
        store2 (let [client (persistence/client {:system default-system
                                                 :storage-dir default-poke-storage})
                     conn (persistence/ensure-db! client default-poke-db)
                     s (poke-store/make-store conn)]
                 (trainer/bootstrap! s)
                 s)
        eq2 (poke-eq/compare-ir store2 (:ir result))]
    {:import result
     :equivalence eq
     :equivalence-after-restore eq2
     :backup bak
     :ensure-trainer-fill? false}))

(defn run-pipeline!
  [{:keys [older-path newer-path out-dir import-batch-id]
    :or {older-path default-older
         newer-path default-newer
         out-dir default-out
         import-batch-id "m80-batch-syn-001"}}]
  (let [out (io/file (project-path out-dir))
        _ (.mkdirs out)
        older (export/load-dump! (project-path older-path))
        newer (export/load-dump! (project-path newer-path))
        inventory (run-inventory! newer)
        export-bundle (run-export! newer (str (io/file out "export")) "m8-newer")
        _ (run-export! older (str (io/file out "export-older-evidence")) "m8-older-evidence")
        recon (run-reconcile! older newer)
        _ (when (:block-import? recon)
            (throw (ex-info "reconcile_blocked" {:reconcile recon})))
        platform-cycle (run-platform-import-cycle! newer (str out) import-batch-id)
        pokemon-cycle (run-pokemon-import! (str (io/file out "pokemon"))
                                           (str import-batch-id "-poke"))
        freeze (rollback/freeze-legacy-writers! (str (io/file out "gates")))
        gates (rollback/evaluate-gates
               {:writers-frozen true
                :snapshot-checksum-ok true
                :reconcile-unblocked (boolean (:go? recon))
                :import-equivalent (boolean (:all-domains-equivalent? platform-cycle))
                :single-writer-plan true
                ;; bloqueante explícito — staging WhatsApp ainda não validado
                :whatsapp-staging-validated false
                :rollback-recon-available false
                :authorization false})
        abort (rollback/abort-before-release!
               (str (io/file out "gates"))
               "whatsapp-staging-not-validated")
        drill (rollback/drill-backup-restore!
               {:storage-rel default-storage
                :backup-rel default-backup
                :export-path (:export-path export-bundle)
                :out-dir (str (io/file out "gates"))})
        report
        {:milestone "M8.0"
         :at (str (java.time.Instant/now))
         :inventory inventory
         :export {:content-sha256 (:content-sha256 export-bundle)
                  :manifest-path (:manifest-path export-bundle)}
         :reconcile (dissoc recon :platform) ;; platform detalhado em arquivo
         :reconcile-platform (:platform recon)
         :platform-import {:reimport-status (:reimport-status platform-cycle)
                           :resumed-status (:resumed-status platform-cycle)
                           :all-equivalent? (:all-domains-equivalent? platform-cycle)
                           :poller-started? false}
         :pokemon-import {:reimport-statuses (get-in pokemon-cycle [:import :reimport-statuses])
                          :equivalence-ok? (boolean (get-in pokemon-cycle [:equivalence :ok?]))
                          :restore-ok? (boolean (get-in pokemon-cycle [:equivalence-after-restore :ok?]))}
         :gates gates
         :freeze freeze
         :abort abort
         :rollback-drill drill
         :post-cutover-risk (rollback/post-cutover-risk)
         :cutover-ready? false
         :ok? (and (:go? recon)
                   (:all-domains-equivalent? platform-cycle)
                   (get-in pokemon-cycle [:equivalence :ok?])
                   (get-in pokemon-cycle [:equivalence-after-restore :ok?])
                   (= :duplicate (:reimport-status platform-cycle))
                   (not (:cutover-ready? gates)))}]
    (spit (str (io/file out "report.edn")) (with-out-str (pprint/pprint report)))
    (spit (str (io/file out "reconcile.edn")) (with-out-str (pprint/pprint recon)))
    report))
