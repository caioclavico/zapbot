(ns odisseu.smoke.backup-quiescence
  "Provas descartáveis: writer ativo recusa; quiescência libera; timeout aborta;
   falha no meio não publica; restore confere manifesto e checksum."
  (:require [clojure.java.io :as io]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.http.maintenance-gate :as gate]
            [pokemon.migration.backup :as backup])
  (:import [java.nio.file Files LinkOption]))

(defn- ensure! [ok message data]
  (when-not ok (throw (ex-info message (or data {})))))

(defn- code-of [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (.getMessage e))
       (catch Throwable e (str (class e) " " (.getMessage e)))))

(defn- path [root name]
  (.getAbsolutePath (io/file root name)))

(defn- seed! [storage]
  (let [system (str "quiescence-" (System/nanoTime))
        client (persistence/client {:system system
                                    :storage-dir storage})
        _ (d/create-database client {:db-name "q"})
        conn (d/connect client {:db-name "q"})]
    (d/transact conn {:tx-data [{:db/ident :q/id
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}
                                {:db/ident :q/n
                                 :db/valueType :db.type/long
                                 :db/cardinality :db.cardinality/one}]})
    (d/transact conn {:tx-data [{:q/id "row" :q/n 77}]})
    system))

(defn- read-n! [storage system]
  (let [client (persistence/client {:system system
                                    :storage-dir storage})
        conn (d/connect client {:db-name "q"})
        row (d/pull (d/db conn) '[:q/n] [:q/id "row"])]
    (:q/n row)))

(defn -main [& _]
  (let [root (.toFile (Files/createTempDirectory "a6-quiescence-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        live (path root "live")
        _ (.mkdirs (io/file live))
        refused (path root "refused-backup")
        system (seed! live)
        held (code-of #(backup/snapshot-storage! live refused))]
    (ensure! (= "backup_writer_active_in_process" held) "writer-active-not-refused" {:code held})
    (ensure! (not (.exists (io/file refused))) "writer-active-published-backup" {})
    (persistence/release-storage-writers! live)
    (backup/release-refused-lock-probes!)
    (let [closed-only (code-of #(backup/snapshot-storage! live (path root "closed-only")))]
      (ensure! (= "backup_quiescence_unproven" closed-only)
               (str "closed-connection-was-treated-as-quiescence " closed-only)
               {:code closed-only}))
    (let [dir (io/file live ".a6-maintenance")
          g (gate/create (.getAbsolutePath dir))
          _ (ensure! (gate/acquire-write! g) "admission-failed" {})
          timed (code-of #(backup/prepare-cold-snapshot! live g {:timeout-ms 40}))
          timed-dest (io/file root "timed-out")]
      (ensure! (= "backup_quiescence_timeout" timed) "timeout-did-not-abort" {:code timed})
      (ensure! (not (.exists (io/file live ".a6-maintenance" "backup-quiescence.edn")))
               "timeout-wrote-certificate" {})
      (ensure! (not (.exists timed-dest)) "timeout-published-backup" {})
      (gate/release-write! g))
    (let [published (io/file root "published")
          snap (backup/cold-snapshot! live (.getAbsolutePath published))
          hook (fn [event _]
                 (when (= event :after-copy-file)
                   (throw (ex-info "injected-disk-failure" {}))))
          failed (io/file root "partial")]
      (ensure! (:ok snap) "quiescent-snapshot-rejected" snap)
      (ensure! (:ok (backup/verify-backup! (.getAbsolutePath published)))
               "manifest-checksum-rejected" {})
      (persistence/release-storage-writers! live)
      (let [fail-code (code-of #(with-redefs-fn {(ns-resolve 'pokemon.migration.backup '*test-hook*) hook}
                                  (fn [] (backup/snapshot-storage! live (.getAbsolutePath failed)))))]
        (ensure! (= "injected-disk-failure" fail-code)
                 (str "copy-failure-was-swallowed " fail-code)
                 {:code fail-code}))
      (ensure! (not (Files/exists (.toPath failed) (make-array LinkOption 0)))
               "partial-backup-was-published" {})
      (let [restored (io/file root "restored")
            _ (backup/restore-storage! (.getAbsolutePath restored) (.getAbsolutePath published))
            _ (backup/resume-storage! (.getAbsolutePath restored))]
        (ensure! (= 77 (read-n! (.getAbsolutePath restored) system)) "restored-value-mismatch" {})))
    (println "BACKUP_QUIESCENCE_OK")
    (shutdown-agents)
    (System/exit 0)))
