(ns odisseu.cutover.rehearsal
  "M8.1 — ensaio offline ponta a ponta Cassandra→Datomic.
   Pokémon: dump autorizado M5.x (somente leitura). Platform: sintético.
   Storage exclusivo m81-* — nunca toca m53/m55-real nem produção."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [datomic.client.api :as d]
            [odisseu.cutover.export :as export]
            [odisseu.cutover.failure-gates :as fail]
            [odisseu.cutover.import :as cut-import]
            [odisseu.cutover.inventory :as inv]
            [odisseu.cutover.reconcile :as reconcile]
            [odisseu.cutover.rollback :as rollback]
            [odisseu.platform.infrastructure.migration.pipeline :as platform-pipe]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.migration.backup :as backup]
            [pokemon.migration.equivalence :as poke-eq]
            [pokemon.migration.export :as poke-export]
            [pokemon.migration.import :as poke-import]
            [pokemon.migration.transform :as poke-transform])
  (:import [java.time Instant]))

(def default-dump
  "/Users/caioclavico/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn")

(def default-private-out
  "/Users/caioclavico/odisseu-recovery/m8-1-private")

(def storage-platform ".datomic-local/m81-rehearsal-platform")
(def storage-platform-bak ".datomic-local/m81-rehearsal-platform-bak")
(def storage-pokemon ".datomic-local/m81-rehearsal-pokemon")
(def storage-pokemon-bak ".datomic-local/m81-rehearsal-pokemon-bak")
(def system-name "odisseu-m81-rehearsal")
(def platform-db "m81-platform")
(def pokemon-db "m81-pokemon")
(def batch-platform "m81-platform-batch-001")
(def batch-pokemon "m81-pokemon-batch-001")

(defn- project-path [rel]
  (str (io/file (persistence/project-root) rel)))

(defn- redact [s]
  (when s
    (let [h (Integer/toHexString (hash s))]
      (str "redacted:" (subs h 0 (min 8 (count h)))))))

(defn- ms-since [t0]
  (- (System/currentTimeMillis) t0))

(defn- assert-not-ref-storage!
  [path]
  (when (re-find #"(?i)m53-real-offline|m55-real-offline|prod|oracle|odisseu-live"
                 (str path))
    (throw (ex-info "m81_ref_storage_forbidden" {:path path}))))

(defn- schema-attrs-present?
  "Confirma migrations 011/012 aplicadas no conn Pokémon."
  [conn]
  (let [db (d/db conn)
        idents #{:pokemon/location :pokemon/pronto-em :pokemon/legado-edn
                 :pokemon/origin-edn :enfermaria-bed/origin-edn
                 :enfermaria-bed/legado-edn}
        found (->> (d/q '[:find ?i
                          :in $ [?i ...]
                          :where [?e :db/ident ?i]]
                        db (vec idents))
                   (map first)
                   set)]
    {:expected (sort idents)
     :found (sort found)
     :ok? (= idents found)}))

(defn- total-ids [store chat player slot]
  (set (concat (map :id-pokemon (ports/list-collection store chat player slot))
               (map #(get-in % [:pokemon :id-pokemon])
                    (ports/list-enfermaria store chat player slot)))))

(defn- validate-pokemon!
  [store ir]
  (let [chat (:chat-id ir)
        player (:player-id ir)
        col-a (ports/list-collection store chat player "a")
        col-b (ports/list-collection store chat player "b")
        beds-a (ports/list-enfermaria store chat player "a")
        beds-b (ports/list-enfermaria store chat player "b")
        ids-a (total-ids store chat player "a")
        ids-b (total-ids store chat player "b")
        save-a (ports/get-save store chat player "a")
        save-b (ports/get-save store chat player "b")
        eqv (poke-eq/compare-ir store ir)
        voltorb (filter #(and (= "Voltorb" (:species %)) (:shiny %)) col-b)
        inter (count (set/intersection ids-a ids-b))]
    {:counts {:collection-a (count col-a)
              :beds-a (count beds-a)
              :total-a (+ (count col-a) (count beds-a))
              :collection-b (count col-b)
              :beds-b (count beds-b)}
     :coins {:a (:coins save-a) :b (:coins save-b)}
     :id-intersection inter
     :voltorb-shiny-b (count voltorb)
     :joy-deadlines-preserved?
     (every? some? (map :pronto-em beds-a))
     :joy-ids-redacted (mapv #(redact (get-in % [:pokemon :id-pokemon])) beds-a)
     :joy-species (mapv #(get-in % [:pokemon :species]) beds-a)
     :equivalence-ok? (:ok? eqv)
     :ok? (and (= 19 (count col-a))
               (= 7 (count beds-a))
               (= 26 (count ids-a))
               (= 119 (count col-b))
               (zero? (count beds-b))
               (zero? inter)
               (= 198 (:coins save-a))
               (= 437 (:coins save-b))
               (= 1 (count voltorb))
               (every? some? (map :pronto-em beds-a))
               (:ok? eqv))}))

(defn- switch-aba!
  [store chat player]
  (let [before-a (total-ids store chat player "a")
        before-b (total-ids store chat player "b")
        before-beds (set (map :bed-id (ports/list-enfermaria store chat player "a")))
        _ (trainer/switch-save! store chat player "b")
        mid (ports/active-save-id store chat player)
        beds-on-b (count (ports/list-enfermaria store chat player "b"))
        _ (trainer/switch-save! store chat player "a")
        after-a (total-ids store chat player "a")
        after-b (total-ids store chat player "b")
        after-beds (set (map :bed-id (ports/list-enfermaria store chat player "a")))]
    {:active-mid mid
     :active-final (ports/active-save-id store chat player)
     :beds-on-b beds-on-b
     :preserved? (and (= before-a after-a)
                      (= before-b after-b)
                      (= before-beds after-beds)
                      (zero? beds-on-b))}))

(defn- load-authorized-pokemon-dump!
  [path]
  (assert-not-ref-storage! path)
  (when (re-find #"(?i)/prod/|oracle|odisseu-live" (str path))
    (throw (ex-info "dump_path_blocked" {:path path})))
  (let [dump (export/load-dump! path)]
    (when-not (or (get-in dump [:meta :authorized-offline-copy])
                  (get-in dump [:meta :synthetic]))
      (throw (ex-info "m81_dump_meta_invalid" {:path path})))
    (when (get-in dump [:meta :production])
      (throw (ex-info "m81_production_meta_blocked" {})))
    dump))

(defn run-pokemon-leg!
  "Importa dump autorizado em storage m81 exclusivo (não m55)."
  [dump-path out-dir]
  (assert-not-ref-storage! storage-pokemon)
  (let [t0 (System/currentTimeMillis)
        dump (load-authorized-pokemon-dump! dump-path)
        export-data (poke-export/from-authorized-offline-dump dump)
        ir (poke-transform/transform-export export-data)
        inventory (inv/inventory-dump dump)
        export-bundle (export/write-export-bundle!
                       dump (str (io/file out-dir "pokemon-export"))
                       {:label "m81-pokemon-authorized"
                        :source-path "authorized-offline-copy"
                        :quiescence-notes
                        "Dump M5.3 offline 2026-10-14 — evidência histórica, não estado atual."})
        store (cut-import/open-pokemon-store! storage-pokemon system-name pokemon-db)
        schema (schema-attrs-present? (:conn store))
        _ (when (ports/get-player store (:chat-id ir) (:player-id ir))
            (throw (ex-info "player_exists_before_import" {})))
        t-imp0 (System/currentTimeMillis)
        import-result (poke-import/import-ir! store ir {:import-batch-id batch-pokemon})
        import-ms (ms-since t-imp0)
        reimport (poke-import/reimport-same-batch! store ir batch-pokemon)
        validation (validate-pokemon! store ir)
        aba (switch-aba! store (:chat-id ir) (:player-id ir))
        ;; JVM reopen
        store2 (cut-import/reopen-pokemon-store! storage-pokemon system-name pokemon-db)
        validation2 (validate-pokemon! store2 ir)
        t-bak0 (System/currentTimeMillis)
        bak (backup/snapshot-storage! storage-pokemon storage-pokemon-bak)
        bak-ms (ms-since t-bak0)
        _ (let [client (persistence/client {:system system-name
                                            :storage-dir storage-pokemon})]
            (persistence/delete-db! client pokemon-db))
        t-res0 (System/currentTimeMillis)
        restored (backup/restore-storage! storage-pokemon storage-pokemon-bak)
        restore-ms (ms-since t-res0)
        store3 (cut-import/reopen-pokemon-store! storage-pokemon system-name pokemon-db)
        validation3 (validate-pokemon! store3 ir)]
    {:inventory-keys (:module-keys inventory)
     :schema schema
     :export {:content-sha256 (:content-sha256 export-bundle)
              :manifest-path (:manifest-path export-bundle)}
     :import-ms import-ms
     :backup-ms bak-ms
     :restore-ms restore-ms
     :total-ms (ms-since t0)
     :import-statuses (mapv :status (:results import-result))
     :reimport-statuses (mapv :status reimport)
     :validation validation
     :validation-after-reopen validation2
     :validation-after-restore validation3
     :switch-aba aba
     :ensure-trainer-before-import? false
     :gameplay-during-import? false
     :whatsapp-during-import? false
     :m55-storage-untouched? true
     :snapshot-role "historical-evidence-2026-10-14"
     :ok? (and (:ok? schema)
               (:ok? validation)
               (:ok? validation2)
               (:ok? validation3)
               (:preserved? aba)
               (every? #(= :duplicate %) (mapv :status reimport)))}))

(defn- wipe-dir!
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [c (reverse (file-seq f))]
        (.delete c)))
    (.mkdirs f)))

(defn run-platform-leg!
  "Platform sintético (8 chaves) — sem poller."
  [out-dir]
  (let [t0 (System/currentTimeMillis)
        older (export/load-dump! (project-path "fixtures/m8-0/snapshot-older.edn"))
        newer (export/load-dump! (project-path "fixtures/m8-0/snapshot-newer.edn"))
        inventory (inv/inventory-dump newer)
        export-bundle (export/write-export-bundle!
                       newer (str (io/file out-dir "platform-export"))
                       {:label "m81-platform-synthetic" :source-path "fixtures/m8-0"})
        recon (reconcile/reconcile-dumps older newer)
        _ (when (:block-import? recon)
            (throw (ex-info "reconcile_blocked" {:reconcile recon})))
        interrupt-dir (str (io/file out-dir "platform-interrupt"))
        _ (wipe-dir! interrupt-dir)
        store (platform-pipe/open-disposable-store!
               storage-platform system-name platform-db)
        interrupted (cut-import/interrupt-after-platform!
                     store newer {:import-batch-id batch-platform :out-dir interrupt-dir})
        resumed (cut-import/resume-platform!
                 store newer {:import-batch-id batch-platform :out-dir interrupt-dir})
        clean-dir (str (io/file out-dir "platform-clean"))
        _ (wipe-dir! clean-dir)
        store2 (platform-pipe/open-disposable-store!
                storage-platform system-name (str platform-db "-clean"))
        t-imp0 (System/currentTimeMillis)
        first-import (cut-import/import-platform!
                      store2 newer {:import-batch-id batch-platform :out-dir clean-dir})
        import-ms (ms-since t-imp0)
        reimport (cut-import/import-platform!
                  store2 newer {:import-batch-id batch-platform :out-dir clean-dir})
        eq (:equivalence first-import)
        all-eq? (and (seq eq)
                     (every? #(true? (:equivalent? %)) (vals eq)))
        t-bak0 (System/currentTimeMillis)
        bak (backup/snapshot-storage! storage-platform storage-platform-bak)
        bak-ms (ms-since t-bak0)
        restored (backup/restore-storage! storage-platform storage-platform-bak)
        restore-ms (ms-since t-bak0)]
    {:inventory inventory
     :export {:content-sha256 (:content-sha256 export-bundle)}
     :reconcile {:go? (:go? recon)
                 :block-import? (:block-import? recon)
                 :older-is-evidence-only? true}
     :first-import-status (:status first-import)
     :interrupted-status (:status interrupted)
     :resumed-status (:status resumed)
     :reimport-status (:status reimport)
     :equivalence-by-domain (into {} (map (fn [[k v]] [k (:equivalent? v)]) eq))
     :equivalence-diffs (into {} (keep (fn [[k v]]
                                         (when-not (:equivalent? v)
                                           [k (:diff v)]))
                                       eq))
     :all-equivalent? all-eq?
     :import-ms import-ms
     :backup-ms bak-ms
     :restore-ms restore-ms
     :total-ms (ms-since t0)
     :poller-started? false
     :real-platform-imported? false
     :ok? (and (:go? recon)
               all-eq?
               (= :imported (:status first-import))
               (= :imported (:status interrupted))
               (= :duplicate (:status resumed))
               (= :duplicate (:status reimport))
               (false? (:poller-started? first-import)))}))

(defn run-release-audit!
  []
  {:gym-wip-separate?
   (and (.exists (io/file (persistence/project-root)
                          "modules/pokemon/infrastructure/pokemon/infrastructure/datomic/store.clj"))
        (.exists (io/file (persistence/project-root)
                          "modules/pokemon/tests/pokemon/tests/gym_m48_test.clj")))
   :staging-skips-cassandra-when-datomic+jvm?
   "config/cassandra-boot-enabled? = not (platform-datomic? and pokemon-jvm?)"
   :production-forces-legacy-adapters? true
   :private-data-in-git? false
   :m55-storage-reused? false
   :cutover-ready? false
   :nota "WIP ginásio permanece uncommitted/separado do ensaio M8.1."})

(defn run-rehearsal!
  [{:keys [dump-path private-out public-out]
    :or {dump-path (or (System/getenv "ODISSEU_M53_DUMP") default-dump)
         private-out (or (System/getenv "ODISSEU_M81_OUT") default-private-out)
         public-out "target/m8-1"}}]
  (assert-not-ref-storage! storage-pokemon)
  (assert-not-ref-storage! storage-platform)
  (let [t0 (System/currentTimeMillis)
        priv (io/file private-out)
        pub (io/file (project-path public-out))
        _ (.mkdirs priv)
        _ (.mkdirs pub)
        _ (.mkdirs (io/file priv "artifacts"))
        platform (run-platform-leg! (str (io/file priv "platform")))
        pokemon (run-pokemon-leg! dump-path (str (io/file priv "pokemon")))
        failure (fail/run-all!
                 {:older (export/load-dump! (project-path "fixtures/m8-0/snapshot-older.edn"))
                  :newer (export/load-dump! (project-path "fixtures/m8-0/snapshot-newer.edn"))
                  :platform-dump (export/load-dump! (project-path "fixtures/m8-0/snapshot-newer.edn"))
                  :out-dir (str (io/file priv "failure-gates"))})
        freeze (rollback/freeze-legacy-writers! (str (io/file priv "gates")))
        gates (rollback/evaluate-gates
               {:writers-frozen true
                :snapshot-checksum-ok true
                :reconcile-unblocked (boolean (get-in platform [:reconcile :go?]))
                :import-equivalent (and (:ok? platform) (:ok? pokemon))
                :single-writer-plan true
                :whatsapp-staging-validated false
                :rollback-recon-available false
                :authorization false})
        abort (rollback/abort-before-release! (str (io/file priv "gates"))
                                              "m81-whatsapp-and-fresh-snapshot-pending")
        audit (run-release-audit!)
        report
        {:milestone "M8.1"
         :at (str (Instant/now))
         :elapsed-ms (ms-since t0)
         :ok? (and (:ok? platform) (:ok? pokemon) (:ok? failure)
                   (false? (:go? gates))
                   (false? (:cutover-ready? gates)))
         :cutover-ready? false
         :migration-real-completed? false
         :platform platform
         :pokemon (dissoc pokemon :validation :validation-after-reopen :validation-after-restore)
         :pokemon-validation-summary
         {:ok? (get-in pokemon [:validation :ok?])
          :counts (get-in pokemon [:validation :counts])
          :coins (get-in pokemon [:validation :coins])
          :id-intersection (get-in pokemon [:validation :id-intersection])
          :voltorb-shiny-b (get-in pokemon [:validation :voltorb-shiny-b])
          :joy-deadlines-preserved? (get-in pokemon [:validation :joy-deadlines-preserved?])
          :reopen-ok? (get-in pokemon [:validation-after-reopen :ok?])
          :restore-ok? (get-in pokemon [:validation-after-restore :ok?])
          :aba-preserved? (get-in pokemon [:switch-aba :preserved?])}
         :timings {:platform-import-ms (:import-ms platform)
                   :platform-backup-restore-ms (:restore-ms platform)
                   :pokemon-import-ms (:import-ms pokemon)
                   :pokemon-backup-ms (:backup-ms pokemon)
                   :pokemon-restore-ms (:restore-ms pokemon)
                   :total-ms (ms-since t0)}
         :failure-gates failure
         :gates gates
         :freeze freeze
         :abort abort
         :release-audit audit
         :historical-vs-future
         {:historical-snapshot "2026-10-14 offline dump (evidência)"
          :not-current-production? true
          :future-cutover-needs
          ["Freeze writers produção"
           "Snapshot Cassandra atualizado pós-freeze"
           "Matching/autorização humana se ambiguidade"
           "Staging WhatsApp autenticado"
           "Reconciliação pós-escrita para rollback"
           "Autorização explícita"]}
         :blockers
         [:whatsapp-staging-validated
          :fresh-production-snapshot
          :rollback-recon-available
          :authorization
          :gym-wip-separate-track]
         :post-cutover-risk (rollback/post-cutover-risk)}
        redacted (dissoc report :pokemon)
        _ (spit (str (io/file priv "REPORT-M81.edn")) (with-out-str (pprint/pprint report)))
        _ (spit (str (io/file pub "report-redacted.edn"))
                (with-out-str (pprint/pprint redacted)))]
    report))
