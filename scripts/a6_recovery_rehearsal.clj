(ns a6-recovery-rehearsal
  "Ensaio discover → confirm! → admin-import em Datomic Local descartável.

  Guardas: storage sob ~/odisseu-recovery/.../rehearsal-*; sem Cassandra;
  sem HTTP; confirm! só no store passado. Interrompe se isolamento falhar."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(def ^:private import-token "a64-rehearsal-import-token-not-for-prod")

(defn- assert-isolation!
  [storage-dir]
  (let [abs (.getCanonicalPath (io/file storage-dir))
        home (.getCanonicalPath (io/file (System/getProperty "user.home")))
        recovery (.getCanonicalPath (io/file home "odisseu-recovery"))]
    (when-not (str/starts-with? abs recovery)
      (throw (ex-info "rehearsal_storage_outside_odisseu_recovery" {:storage abs})))
    (when-not (str/includes? abs "rehearsal")
      (throw (ex-info "rehearsal_storage_must_include_rehearsal" {:storage abs})))
    (when (re-find #"(?i)odisseu-core-a56-datomic$|/data/datomic-local|production|prod-live" abs)
      (throw (ex-info "rehearsal_storage_looks_like_live" {:storage abs})))
    ;; Sem ponte Cassandra neste processo
    (doseq [k ["CASSANDRA_CONTACT_POINTS" "CASSANDRA_HOST" "RECOVERY_CASSANDRA_WRITE"]]
      (when (System/getenv k)
        (throw (ex-info "rehearsal_cassandra_env_present"
                        {:env k :hint "Ensaio deve rodar sem env Cassandra"}))))
    {:ok true :storage abs :cassandra-env-cleared true}))

(defn- inventory
  [s chat player]
  (let [active (ports/active-save-id s chat player)
        saves (ports/list-saves s chat player)
        coll (try (ports/list-collection s chat player active) (catch Exception _ []))
        recovery-slots (filter #(str/starts-with? (str (:slot-id %)) "recovery") saves)
        recovery-colls
        (into {}
              (for [slot recovery-slots
                    :let [id (:slot-id slot)]]
                [id (try (count (ports/list-collection s chat player id))
                         (catch Exception _ -1))]))]
    {:active-save-id active
     :save-ids (mapv :slot-id saves)
     :active-collection-count (count coll)
     :recovery-collection-counts recovery-colls}))

(defn -main [& args]
  (let [opts (apply hash-map args)
        storage (get opts "--storage")
        system (or (get opts "--system") "odisseu-m90-core")
        db-name (or (get opts "--db") "core")
        dump-path (get opts "--dump")
        pick-path (get opts "--pick")
        out-path (get opts "--out")]
    (when-not (and storage dump-path pick-path out-path)
      (println "Uso: --storage DIR --dump authorized-dump.edn --pick rehearsal-pick-private.edn --out report.edn")
      (System/exit 2))
    (let [iso (assert-isolation! storage)
          pick (edn/read-string (slurp pick-path))
          chat (:_chat pick)
          player (:_player pick)
          _ (when (or (str/blank? chat) (str/blank? player))
              (throw (ex-info "rehearsal_pick_missing_identity" {})))
          dump (export/load-edn-file dump-path)
          exp (export/from-authorized-offline-dump dump)
          available (recovery/discover exp chat player)
          _ (when (empty? available)
              (throw (ex-info "rehearsal_no_snapshots" {:player-hash (:player-hash pick)})))
          ;; Conflito: se eligible exige fingerprint único V1/V2 — já filtrado no pick
          client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          s (doto (store/make-store conn) trainer/bootstrap!)
          before-trainer? (boolean (ports/get-player s chat player))
          _ (when-not before-trainer?
              (trainer/create-trainer! s {:chat-id chat :player-id player}))
          before (inventory s chat player)
          cat (catalog/load-catalog {:dump-path dump-path :import-token import-token})
          disc (catalog/discover cat chat player)
          req (catalog/register-request! cat {:chat-id chat :player-id player
                                              :choice (if (= 2 (count (:versions disc))) :both :v1)})
          confirmed (catalog/player-confirm!
                     cat {:chat-id chat :player-id player
                          :request-id (:request-id req)
                          :confirmation-token (:confirmation-token req)})
          imported (catalog/admin-import!
                    cat s {:request-id (:request-id req)
                           :import-token import-token
                           :import-batch-id (str "a64-rehearsal|" (:player-hash pick))})
          after (inventory s chat player)
          ;; Idempotência: mesmo batch + mesmos snapshots → :duplicate, sem novos slots
          retry
          (let [entry (get @(:requests cat) (:request-id req))
                selected-full (:selected-full entry)
                snaps (catalog/snapshots-for-request cat entry)
                token (:confirmation-token entry)]
            (recovery/confirm!
             s snaps
             {:chat-id chat :player-id player
              :selected selected-full :token token
              :import-batch-id (str "a64-rehearsal|" (:player-hash pick))}))
          after-retry (inventory s chat player)
          report {:ok true
                  :at (str (java.time.Instant/now))
                  :isolation iso
                  :player-hash (:player-hash pick)
                  :chat-hash (:chat-hash pick)
                  :versions (:versions disc)
                  :discover-public (:available disc)
                  :before before
                  :import {:status (:status imported)
                           :import-batch-id (:import-batch-id imported)
                           :active-save-id (:active-save-id imported)
                           :results
                           (mapv #(select-keys % [:status :slot-id :content-hash
                                                  :pokemon-count :collection-count])
                                 (:results imported))}
                  :after after
                  :retry-results
                  (mapv #(select-keys % [:status :slot-id :content-hash])
                        (:results retry))
                  :after-retry after-retry
                  :idempotent?
                  (and (every? #(= :duplicate (:status %)) (:results retry))
                       (= (:save-ids after) (:save-ids after-retry))
                       (= (:recovery-collection-counts after)
                          (:recovery-collection-counts after-retry))
                       (= (:active-save-id before) (:active-save-id after)
                          (:active-save-id after-retry)))
                  :active-preserved?
                  (= (:active-save-id before) (:active-save-id after))
                  :cassandra-writes []
                  :live-datomic-writes []
                  :confirm-target :disposable-local-only
                  :pii-redacted true}]
      (io/make-parents out-path)
      (spit out-path (with-out-str (pp/pprint report)))
      (pp/pprint (dissoc report :discover-public))
      (when-not (:idempotent? report)
        (System/exit 1)))))
