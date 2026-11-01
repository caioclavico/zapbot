(ns a6-recovery-reconcile
  "Monta matriz de reconciliação a partir de export SELECT-only privado.

  Sem Cassandra, sem Datomic de produção, sem confirm!/import.
  Entrada: private-accounts.edn (+ opcional datomic-legacy.edn) e dump.edn.
  Saída: matriz anonimizada + plano (EDN privado)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [pokemon.application.recovery-reconcile :as rr]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.migration.export :as export]))

(defn- read-edn [path]
  (with-open [r (io/reader path)]
    (edn/read {:readers *data-readers*} (java.io.PushbackReader. r))))

(defn- write-edn! [path data]
  (io/make-parents path)
  (spit path (with-out-str (pp/pprint data)))
  path)

(defn- g
  [m k]
  (or (get m k)
      (get m (keyword (name k)))
      (get m (name k))))

(defn- records-from-private-accounts
  [data]
  (mapv (fn [row]
          (let [source (g row :source)
                chat (g row :chatId)
                player (g row :playerId)
                account (g row :account)]
            (rr/source-record {:source (keyword (name source))
                               :chat-id chat
                               :player-id player
                               :account account})))
        (or (g data :accounts) [])))

(defn- datomic-legacy-records
  [path]
  (when (and path (.exists (io/file path)))
    (let [data (read-edn path)]
      (mapv (fn [t]
              (rr/source-record {:source :datomic-legacy
                                 :player-id (g t :player-id)
                                 :chat-id (g t :chat-id)
                                 :account (g t :account)}))
            (or (g data :trainers) [])))))

(defn- discover-rehearsal
  "Ensaio read-only: catalog/discover. Sem confirm!/import."
  [dump-path sample-limit]
  (let [dump (export/load-edn-file dump-path)
        exp (export/from-authorized-offline-dump dump)
        cat {:export exp :path dump-path :loaded-at (System/currentTimeMillis)
             :import-token nil :requests (atom {})}
        snaps (:historical_snapshots exp)
        samples (->> snaps
                     (map (juxt #(g % :chat-id) #(g % :player-id)))
                     distinct
                     (take sample-limit))]
    {:catalog-ready (catalog/available? cat)
     :snapshot-count (count snaps)
     :sample-count (count samples)
     :samples
     (mapv (fn [[chat player]]
             (let [d (catalog/discover cat chat player)]
               {:player-hash (rr/anonymize-id player)
                :chat-hash (rr/anonymize-id chat)
                :catalog-ready (:catalog-ready d)
                :alias-applied? (:alias-applied? d)
                :versions (:versions d)
                :available-public (:available d)}))
           samples)
     :confirm-invoked false
     :import-invoked false}))

(defn -main [& args]
  (let [opts (apply hash-map args)
        private-accounts (get opts "--private-accounts")
        datomic-legacy (get opts "--datomic-legacy")
        dump-path (get opts "--dump")
        out-dir (get opts "--out")]
    (when-not (and out-dir private-accounts)
      (println "Uso: load-file + (-main \"--out\" DIR \"--private-accounts\" f.edn [--datomic-legacy f.edn] [--dump f.edn])")
      (System/exit 2))
    (let [cassandra-recs (records-from-private-accounts (read-edn private-accounts))
          legacy-recs (or (datomic-legacy-records datomic-legacy) [])
          records (into cassandra-recs legacy-recs)
          matrix (rr/reconcile records)
          anon (rr/anonymize-matrix matrix)
          plan (rr/plan-import-batch matrix)
          source-counts (frequencies (map :source records))
          rehearsal (when dump-path (discover-rehearsal dump-path 5))
          report {:ok true
                  :at (str (java.time.Instant/now))
                  :read-only true
                  :writes-performed []
                  :source-counts source-counts
                  :player-count (:player-count matrix)
                  :status-counts (:status-counts matrix)
                  :plan-counts (:counts plan)
                  :matrix anon
                  :plan {:counts (:counts plan)
                         :eligible (:eligible plan)
                         :await-human
                         (mapv #(select-keys % [:player-hash :status :action :reason :sources])
                               (:await-human plan))
                         :rollback
                         {:before-import ["cold-backup A5.6 existente"
                                          "nova cópia descartável do volume a56"
                                          "migration 025 só na cópia"]
                          :during ["import admin token por jogador elegível"
                                   "não ativar slot sem confirmação WA"]
                          :after-failure ["descartar volume de ensaio"
                                          "manter a56 live intocado"
                                          "não escrever Cassandra"]}}
                  :discover-rehearsal rehearsal}]
      (.mkdirs (io/file out-dir))
      (write-edn! (str out-dir "/reconcile-matrix-anonymized.edn") report)
      (write-edn! (str out-dir "/import-plan-anonymized.edn")
                  (select-keys report [:ok :at :plan-counts :plan :source-counts
                                       :player-count :status-counts]))
      (pp/pprint {:ok true
                  :out out-dir
                  :player-count (:player-count matrix)
                  :status-counts (:status-counts matrix)
                  :source-counts source-counts
                  :plan-counts (:counts plan)
                  :discover-rehearsal (select-keys (or rehearsal {})
                                                  [:catalog-ready :snapshot-count
                                                   :sample-count :confirm-invoked])}))))
