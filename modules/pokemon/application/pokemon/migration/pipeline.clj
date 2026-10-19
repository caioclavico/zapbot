(ns pokemon.migration.pipeline
  "Orquestra export → transform → import → equivalência → A/B → backup (M5.0)."
  (:require [clojure.java.io :as io]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.backup :as backup]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.import :as mig-import]
            [pokemon.migration.report :as report]
            [pokemon.migration.transform :as transform]))

(def default-storage ".datomic-local/m50-migration")
(def default-backup ".datomic-local/m50-migration-backup")
(def default-system "odisseu-m50-migration")
(def default-db "m50-harness")
(def default-out "target/m5-0")

(defn- fresh-store!
  [storage-rel system db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [storage-rel system db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- owned-ids
  "UUIDs na coleção ativa + leitos Joy (total histórico do save)."
  [store chat player slot]
  (set (concat (map :id-pokemon (ports/list-collection store chat player slot))
               (map #(get-in % [:pokemon :id-pokemon])
                    (ports/list-enfermaria store chat player slot)))))

(defn- switch-roundtrip!
  [store chat player]
  (let [before-a (owned-ids store chat player "a")
        before-b (owned-ids store chat player "b")
        to-b (trainer/switch-save! store chat player "b")
        mid-a (owned-ids store chat player "a")
        mid-b (owned-ids store chat player "b")
        to-a (trainer/switch-save! store chat player "a")
        after-a (owned-ids store chat player "a")
        after-b (owned-ids store chat player "b")]
    {:active-after (get-in to-a [:player :active-save-id])
     :fence (get-in to-a [:player :fence-generation])
     :counts {:a-before (count before-a) :b-before (count before-b)
              :a-mid (count mid-a) :b-mid (count mid-b)
              :a-after (count after-a) :b-after (count after-b)
              :collection-a (count (ports/list-collection store chat player "a"))
              :joy-beds-a (count (ports/list-enfermaria store chat player "a"))}
     :merged? (or (not= before-a after-a)
                  (not= before-b after-b)
                  (not= before-a mid-a)
                  (not= before-b mid-b))
     :switched-via-b (get-in to-b [:player :active-save-id])}))

(defn run-pipeline!
  "Pipeline completo em storage descartável. Retorna relatório."
  [{:keys [storage-rel backup-rel system db-name out-dir import-batch-id fixture-path]
    :or {storage-rel default-storage
         backup-rel default-backup
         system default-system
         db-name default-db
         out-dir default-out
         import-batch-id "m50-batch-syn-001"}}]
  (let [out (io/file out-dir)
        _ (.mkdirs out)
        export-data (if fixture-path
                      (export/from-path fixture-path)
                      (export/synthetic-default))
        export-path (export/write-export! export-data (str (io/file out "export.edn")))
        ir (transform/transform-export export-data)
        ir-path (transform/write-ir! ir (str (io/file out "ir.edn")))
        store (fresh-store! storage-rel system db-name)
        ;; Garantia: não existia jogador antes (fresh db)
        _ (when (ports/get-player store (:chat-id ir) (:player-id ir))
            (throw (ex-info "precondition_player_exists" {})))
        import-result (mig-import/import-ir! store ir {:import-batch-id import-batch-id})
        reimport (mig-import/reimport-same-batch! store ir import-batch-id)
        equivalence (eq/compare-ir store ir)
        switch-result (switch-roundtrip! store (:chat-id ir) (:player-id ir))
        backup-result (backup/snapshot-storage! storage-rel backup-rel)
        ;; Simula falha: apaga db e restaura do backup
        _ (let [client (persistence/client {:system system :storage-dir storage-rel})]
            (persistence/delete-db! client db-name))
        restore-result (backup/restore-storage! storage-rel backup-rel)
        store2 (reopen-store storage-rel system db-name)
        equivalence-after-restore (eq/compare-ir store2 ir)
        report-data (report/build-report
                     {:ir ir
                      :import-result (assoc import-result :reimport reimport)
                      :equivalence (assoc equivalence
                                          :after-restore equivalence-after-restore)
                      :backup backup-result
                      :restore restore-result
                      :switch-result switch-result})
        edn-path (report/write-edn! report-data (str (io/file out "report.edn")))
        md-path (report/write-markdown! report-data (str (io/file out "report.md")))]
    (assoc report-data
           :paths {:export export-path
                   :ir ir-path
                   :report-edn edn-path
                   :report-md md-path
                   :storage storage-rel
                   :backup backup-rel}
           :ok? (and (:ok? equivalence)
                     (:ok? equivalence-after-restore)
                     (not (:merged? switch-result))
                     (every? #(= :duplicate (:status %)) reimport)))))
