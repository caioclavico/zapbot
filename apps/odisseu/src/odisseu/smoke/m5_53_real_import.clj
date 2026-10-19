(ns odisseu.smoke.m5-53-real-import
  "Import M5.3B em Datomic Local descartável a partir de dump offline autorizado.
   Não contém dados reais: lê path via env ODISSEU_M53_DUMP (EDN privado)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.set :as set]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.backup :as backup]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.import :as mig]
            [pokemon.migration.inventory :as inv]
            [pokemon.migration.match :as match]
            [pokemon.migration.transform :as transform])
  (:import [java.time Instant]))

(def ^:private default-dump
  "/Users/caioclavico/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn")

(def ^:private default-report-dir
  "/Users/caioclavico/odisseu-recovery/m5-3-private/investigation")

(defn- load-dump [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- redact [s]
  (when s
    (let [h (Integer/toHexString (hash s))]
      (str "redacted:" (subs h 0 (min 8 (count h)))))))

(defn- bucket-counts [col]
  {"equipe" (count (filter #(= "equipe" (:location %)) col))
   "pc" (count (filter #(= "pc" (:location %)) col))
   "enfermaria" (count (filter #(= "enfermaria" (:location %)) col))
   "total" (count col)})

(defn- validate! [store ir]
  (let [chat (:chat-id ir)
        player (:player-id ir)
        col-a (ports/list-collection store chat player "a")
        col-b (ports/list-collection store chat player "b")
        ids-a (set (map :id-pokemon col-a))
        ids-b (set (map :id-pokemon col-b))
        joy (filter #(= "enfermaria" (:location %)) col-a)
        save-a (ports/get-save store chat player "a")
        save-b (ports/get-save store chat player "b")
        eqv (eq/compare-ir store ir)
        voltorb (filter #(and (= "Voltorb" (:species %)) (:shiny %)) col-b)]
    {:counts {:a (count col-a) :b (count col-b)}
     :buckets {:a (bucket-counts col-a) :b (bucket-counts col-b)}
     :ids-unique {:a (= 26 (count ids-a) (count col-a))
                  :b (= 119 (count ids-b) (count col-b))}
     :id-intersection (count (set/intersection ids-a ids-b))
     :coins {:a (:coins save-a) :b (:coins save-b)}
     :joy {:n (count joy)
           :all-have-pronto-em (every? some? (map :pronto-em joy))
           :species (mapv :species joy)
           :ids-redacted (mapv #(redact (:id-pokemon %)) joy)}
     :voltorb-shiny-b {:n (count voltorb)
                       :id-redacted (redact (:id-pokemon (first voltorb)))}
     :equivalence {:ok? (:ok? eqv)
                   :id-intersection (:id-intersection eqv)
                   :saves (mapv #(select-keys % [:slot-id :ok? :expected-count :actual-count
                                                 :expected-buckets :actual-buckets :diffs])
                                (:saves eqv))}
     :active (ports/active-save-id store chat player)
     :ok? (and (= 26 (count col-a))
               (= 119 (count col-b))
               (= 26 (count ids-a))
               (= 119 (count ids-b))
               (zero? (count (set/intersection ids-a ids-b)))
               (= 198 (:coins save-a))
               (= 437 (:coins save-b))
               (= 19 (get (bucket-counts col-a) "equipe"))
               (= 7 (get (bucket-counts col-a) "enfermaria"))
               (= 119 (get (bucket-counts col-b) "equipe"))
               (= 7 (count joy))
               (every? some? (map :pronto-em joy))
               (= 1 (count voltorb))
               (:ok? eqv))}))

(defn- switch-ab-a! [store chat player]
  (let [before-a (set (map :id-pokemon (ports/list-collection store chat player "a")))
        before-b (set (map :id-pokemon (ports/list-collection store chat player "b")))
        _ (trainer/switch-save! store chat player "b")
        mid (ports/active-save-id store chat player)
        _ (trainer/switch-save! store chat player "a")
        after-a (set (map :id-pokemon (ports/list-collection store chat player "a")))
        after-b (set (map :id-pokemon (ports/list-collection store chat player "b")))]
    {:active-mid mid
     :active-final (ports/active-save-id store chat player)
     :preserved? (and (= before-a after-a) (= before-b after-b))}))

(defn -main
  [& _]
  (let [dump-path (or (System/getenv "ODISSEU_M53_DUMP") default-dump)
        report-dir (or (System/getenv "ODISSEU_M53_REPORT_DIR") default-report-dir)
        storage (or (System/getenv "ODISSEU_M53_STORAGE") ".datomic-local/m53-real-offline")
        backup-rel (or (System/getenv "ODISSEU_M53_BACKUP") ".datomic-local/m53-real-offline-bak")
        system "odisseu-m53-real-offline"
        db-name "m53-import"
        batch-id "m53-real-20261014"
        t0 (System/currentTimeMillis)]
    (println "=== M5.3B real disposable import ===")
    (println "dump:" dump-path)
    (println "storage:" storage)
    (let [dump (load-dump dump-path)
          _ (when-not (get-in dump [:meta :authorized-offline-copy])
              (throw (ex-info "missing_authorized_offline_meta" {})))
          export-data (export/from-authorized-offline-dump dump)
          ir (transform/transform-export export-data)
          inv (inv/inventory-dump dump :path dump-path)
          m (match/match-inventory inv)
          gate-ir (gates/analyze-ir ir nil)
          _ (when-not (:ok? gate-ir)
              (throw (ex-info "ir_gates_failed" gate-ir)))
          _ (when (some #(= :missing-id-pokemon (:quarantine-flag %))
                        (mapcat :pokemon (:saves ir)))
              (throw (ex-info "missing_ids_in_ir" {})))
          client (persistence/client {:system system :storage-dir storage})
          _ (persistence/delete-db! client db-name)
          conn (persistence/ensure-db! client db-name)
          store (doto (store/make-store conn) trainer/bootstrap!)
          _ (when (ports/get-player store (:chat-id ir) (:player-id ir))
              (throw (ex-info "player_exists_before_import" {})))
          import-result (mig/import-ir! store ir {:import-batch-id batch-id
                                                  :require-match-resolved? false})
          reimport (mig/reimport-same-batch! store ir batch-id)
          v1 (validate! store ir)
          switch (switch-ab-a! store (:chat-id ir) (:player-id ir))
          bak (backup/snapshot-storage! storage backup-rel)
          _ (persistence/delete-db! client db-name)
          _ (backup/restore-storage! storage backup-rel)
          store2 (doto (store/make-store
                        (persistence/ensure-db!
                         (persistence/client {:system system :storage-dir storage})
                         db-name))
                   trainer/bootstrap!)
          v2 (validate! store2 ir)
          store3 (doto (store/make-store
                        (persistence/ensure-db!
                         (persistence/client {:system system :storage-dir storage})
                         db-name))
                   trainer/bootstrap!)
          v3 (validate! store3 ir)
          elapsed (- (System/currentTimeMillis) t0)
          report {:milestone "M5.3B"
                  :at (str (Instant/now))
                  :elapsed-ms elapsed
                  :datomic {:storage storage :system system :db db-name :batch batch-id}
                  :match-status (:status m)
                  :auto-selected? (:auto-selected? m)
                  :import {:results (:results import-result)
                           :active (:active-save-id import-result)
                           :reimport (mapv :status reimport)}
                  :validation-after-import (dissoc v1 :equivalence)
                  :equivalence-after-import (:equivalence v1)
                  :switch-a-b-a switch
                  :backup bak
                  :validation-after-restore (dissoc v2 :equivalence)
                  :validation-after-jvm-reopen (dissoc v3 :equivalence)
                  :ir-summary {:a-count (get-in ir [:saves 0 :pokemon-count])
                               :b-count (get-in ir [:saves 1 :pokemon-count])
                               :a-buckets (get-in ir [:saves 0 :buckets])
                               :b-buckets (get-in ir [:saves 1 :buckets])
                               :a-coins (get-in ir [:saves 0 :coins])
                               :b-coins (get-in ir [:saves 1 :coins])}
                  :adventure-linked? false
                  :shared-ginasios-linked? false
                  :ok? (boolean
                        (and (:ok? v1) (:ok? v2) (:ok? v3)
                             (:preserved? switch)
                             (every? #(= :duplicate %) (map :status reimport))))}]
      (.mkdirs (io/file report-dir))
      (spit (str report-dir "/m53b-import-report.edn") (pr-str report))
      (spit (str report-dir "/m53b-import-report.txt")
            (with-out-str (pprint/pprint report)))
      (println "=== RESULT ok? ===" (:ok? report) "elapsed-ms" elapsed)
      (pprint/pprint (select-keys report [:ok? :elapsed-ms :ir-summary :import
                                          :switch-a-b-a :match-status]))
      (when-not (:ok? report)
        (pprint/pprint {:v1 v1 :eqv (:equivalence v1)}))
      (shutdown-agents)
      (System/exit (if (:ok? report) 0 2)))))
