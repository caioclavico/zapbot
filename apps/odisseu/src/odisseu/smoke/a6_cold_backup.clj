(ns odisseu.smoke.a6-cold-backup
  "Disposable physical-process check for cold Datomic snapshot/restore and failures."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.migration.backup :as backup])
  (:import [java.io BufferedReader InputStreamReader]
           [java.nio.channels FileChannel OverlappingFileLockException]
           [java.nio.file Files Path LinkOption StandardOpenOption]
           [java.util UUID]))

(defn- ensure! [ok message]
  (when-not ok (throw (ex-info message {}))))

(defn- client-at [storage]
  (persistence/client {:system "odisseu-a6-cold-smoke" :storage-dir storage}))

(defn- create-data! [storage]
  (let [client (client-at storage)
        db-name "cold-fixture"
        _ (when (some #{db-name} (d/list-databases client {}))
            (d/delete-database client {:db-name db-name}))
        _ (d/create-database client {:db-name db-name})
        conn (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data [{:db/ident :cold-test/id
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}
                                {:db/ident :cold-test/value
                                 :db/valueType :db.type/long
                                 :db/cardinality :db.cardinality/one}]})
    (d/transact conn {:tx-data [{:cold-test/id "fixture" :cold-test/value 77}]})
    (println "CHILD_DATA_CREATED")
    (flush)
    (System/exit 0)))

(defn- hold-writer! [storage release-file]
  (let [client (client-at storage)
        conn-ref (atom (d/connect client {:db-name "cold-fixture"}))]
    (println "CHILD_WRITER_READY")
    (flush)
    (loop [n 0]
      (when (and @conn-ref (< n 600) (not (.exists (io/file release-file))))
        (Thread/sleep 100)
        (recur (inc n))))
    (System/exit 0)))

(defn- external-write! [storage]
  (try
    (let [client (client-at storage)
          conn (d/connect client {:db-name "cold-fixture"})]
      (println "EXTERNAL_STORAGE" (persistence/resolve-storage-dir storage)
               (contains? (set (d/list-databases client {})) "cold-fixture"))
      (d/transact conn {:tx-data [[:db/add [:cold-test/id "fixture"] :cold-test/value 78]]})
      (println "EXTERNAL_WRITE_ALLOWED")
      (flush)
      (System/exit 3))
    (catch Throwable error
      (let [{:keys [messages classes]}
            (loop [error error messages [] classes []]
              (if error
                (recur (.getCause error) (conj messages (or (.getMessage error) ""))
                       (conj classes (.getName (class error))))
                {:messages messages :classes classes}))
            lock-failure? (some #(re-find #"(?i)lock|in use|already open" %) messages)]
        (if lock-failure?
          (do (println "EXTERNAL_WRITE_DENIED" (str/join "," classes)) (flush) (System/exit 0))
          (do (println "EXTERNAL_WRITE_FAILED_UNEXPECTEDLY" (str/join " | " messages))
              (flush) (System/exit 4)))))))

(defn- read-data! [storage]
  (let [conn (d/connect (client-at storage) {:db-name "cold-fixture"})
        row (d/pull (d/db conn) '[:cold-test/id :cold-test/value]
                    [:cold-test/id "fixture"])]
    (ensure! (= 77 (:cold-test/value row)) "restored-state-mismatch")
    (println "RESTORED_CANONICAL_STATE_PASS value=77")
    (flush)
    (System/exit 0)))

(defn- lock-probe! [lock-path]
  (let [channel (FileChannel/open (java.nio.file.Paths/get lock-path (make-array String 0))
                                 (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
    (try
      (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
        (if lock
          (do (.release lock) (println "EXTERNAL_FILE_LOCK_ALLOWED") (flush) (System/exit 5))
          (do (println "EXTERNAL_FILE_LOCK_DENIED") (flush) (System/exit 0))))
      (catch java.io.IOException _
        (println "EXTERNAL_FILE_LOCK_DENIED") (flush) (System/exit 0)))))

(defn- child! [mode storage release]
  (let [command (cond-> ["clojure" "-M" "-m" "odisseu.smoke.a6-cold-backup" mode storage]
                  release (conj release))
        process (-> (ProcessBuilder. ^java.util.List command)
                    (.redirectErrorStream true)
                    .start)
        reader (BufferedReader. (InputStreamReader. (.getInputStream process)))]
    {:process process :reader reader}))

(defn- await-child! [{:keys [^Process process ^BufferedReader reader]} expected]
  (let [output (slurp reader)
        exit (.waitFor process)]
    (ensure! (zero? exit) (str "child-exit-" exit ":" output))
    (ensure! (clojure.string/includes? output expected) (str "child-output-missing:" expected))
    output))

(defn- staging-paths [parent prefix]
  (with-open [stream (Files/list ^Path parent)]
    (->> (.iterator stream)
         iterator-seq
         (filter #(clojure.string/starts-with? (str (.getFileName ^Path %)) prefix))
         vec)))

(defn- expect-throw! [f]
  (try (f) false (catch Throwable _ true)))

(defn- assert-maintenance-admission! [root]
  (let [storage (str (io/file root "admission-storage"))
        marker-dir (io/file storage ".a6-maintenance")
        marker (io/file marker-dir "bridge-maintenance.edn")
        temp (io/file marker-dir ".bridge-maintenance-interrupted.tmp")
        opts {:system "odisseu-a6-admission-smoke" :storage-dir storage}]
    (.mkdirs marker-dir)
    (spit marker "{:phase :draining}")
    (ensure! (expect-throw! #(persistence/client opts))
             "external-client-admitted-during-maintenance")
    (ensure! (some? (persistence/client (assoc opts :maintenance-control? true)))
             "maintenance-control-client-not-admitted")
    (spit marker "not-valid-edn")
    (ensure! (expect-throw! #(persistence/client opts))
             "malformed-maintenance-marker-failed-open")
    (spit marker "{:phase :open}")
    (spit temp "partial")
    (ensure! (expect-throw! #(persistence/client opts))
             "abandoned-maintenance-marker-failed-open")
    (.delete temp)
    (spit marker "{:phase :open}")
    (ensure! (some? (persistence/client opts)) "open-marker-rejected-client")
    true))

(defn- smoke! []
  (let [root (.toFile (Files/createTempDirectory "a6-cold-backup-"
                 (make-array java.nio.file.attribute.FileAttribute 0)))
        storage (str (io/file root "storage"))
        backup-path (str (io/file root "published-backup"))
        restore-path (str (io/file root "restored"))
        corrupt-path (str (io/file root "corrupt-backup"))
        release-file (str (io/file root "release-writer"))
        admission-guard (assert-maintenance-admission! root)
        created (child! "--create" storage nil)
        _ (await-child! created "CHILD_DATA_CREATED")
        holder (child! "--hold" storage release-file)
        ready (.readLine ^BufferedReader (:reader holder))]
    (ensure! (= "CHILD_WRITER_READY" ready) "child-writer-not-ready")
    (ensure! (expect-throw! #(backup/snapshot-storage! storage backup-path))
             "active-writer-snapshot-was-accepted")
    (let [attempt (await-child! (child! "--write" storage nil) "EXTERNAL_WRITE_DENIED")]
      (ensure! (not (clojure.string/includes? attempt "EXTERNAL_WRITE_ALLOWED"))
               "external-writer-bypassed-datomic-lock"))
    (spit release-file "release")
    (let [tail (slurp ^BufferedReader (:reader holder))
          exit (.waitFor ^Process (:process holder))]
      (ensure! (and (zero? exit) (empty? tail)) "writer-child-shutdown-failed"))

    (let [hook-var (ns-resolve 'pokemon.migration.backup '*test-hook*)
          attempted (atom false)
          hook (fn [event _]
                 (when (and (= event :before-copy-file)
                            (compare-and-set! attempted false true))
                   (let [lock-path (first ((ns-resolve 'pokemon.migration.backup 'datomic-lock-paths)
                                           (java.nio.file.Paths/get storage (make-array String 0))))
                         lock-check (child! "--lock-probe" (str lock-path) nil)
                         lock-output (await-child! lock-check "EXTERNAL_FILE_LOCK_DENIED")
                         {:keys [^Process process ^BufferedReader reader]} (child! "--write" storage nil)
                         output (slurp reader)
                         exit (.waitFor process)]
                     (println "COLD_COPY_WRITER_PROBE" {:external-exit exit
                                                        :external-lock-denied (str/includes? lock-output "EXTERNAL_FILE_LOCK_DENIED")
                                                        :external-denied (str/includes? output "EXTERNAL_WRITE_DENIED")})
                     (ensure! (and (zero? exit) (str/includes? output "EXTERNAL_WRITE_DENIED"))
                              "writer-started-during-cold-copy"))))
          snapshot (with-redefs-fn {hook-var hook}
                     #(backup/snapshot-storage! storage backup-path))]
      (ensure! (:ok snapshot) "snapshot-not-published")
      (ensure! @attempted "external-writer-probe-did-not-run-during-copy")
      (ensure! (:ok (backup/verify-backup! backup-path)) "snapshot-verification-failed"))
    ;; A valid prior backup is never replaced or removed.
    (ensure! (expect-throw! #(backup/snapshot-storage! storage backup-path))
             "existing-backup-was-overwritten")
    (ensure! (:ok (backup/verify-backup! backup-path)) "prior-backup-was-damaged")

    ;; Inject failure after copied bytes but before manifest/publication.
    (let [hook-var (ns-resolve 'pokemon.migration.backup '*test-hook*)
          fail-copy (fn [event _] (when (= event :after-copy-file)
                                    (throw (ex-info "injected-disk-failure" {}))))]
      (ensure! (expect-throw! #(with-redefs-fn {hook-var fail-copy}
                                 (fn [] (backup/snapshot-storage! storage (str (io/file root "copy-failed"))))))
               "copy-failure-was-not-propagated")
      (ensure! (not (Files/exists (.toPath (io/file root "copy-failed")) (make-array LinkOption 0)))
               "incomplete-backup-was-published")
      (doseq [stage (staging-paths (.toPath root) ".copy-failed.staging-")]
        (backup/discard-staging! (str root) (str stage))))

    ;; Inject interruption after manifest bytes, before atomic manifest rename.
    (let [hook-var (ns-resolve 'pokemon.migration.backup '*test-hook*)
          fail-manifest (fn [event _] (when (= event :after-manifest-temp-write)
                                        (throw (ex-info "injected-manifest-interruption" {}))))]
      (ensure! (expect-throw! #(with-redefs-fn {hook-var fail-manifest}
                                 (fn [] (backup/snapshot-storage! storage (str (io/file root "manifest-failed"))))))
               "manifest-interruption-was-not-propagated")
      (ensure! (not (Files/exists (.toPath (io/file root "manifest-failed")) (make-array LinkOption 0)))
               "manifest-failure-was-published")
      (doseq [stage (staging-paths (.toPath root) ".manifest-failed.staging-")]
        (backup/discard-staging! (str root) (str stage))))

    ;; A destination appearing during copy is rejected at publication time.
    (let [surprise (str (io/file root "surprise-destination"))
          hook-var (ns-resolve 'pokemon.migration.backup '*test-hook*)
          create-surprise (fn [event _]
                            (when (= event :after-manifest-write)
                              (.mkdirs (io/file surprise))
                              (spit (io/file surprise "operator-file") "preserve-me")))]
      (ensure! (expect-throw! #(with-redefs-fn {hook-var create-surprise}
                                 (fn [] (backup/snapshot-storage! storage surprise))))
               "unexpected-publication-destination-was-replaced")
      (ensure! (= "preserve-me" (slurp (io/file surprise "operator-file")))
               "unexpected-publication-destination-was-modified")
      (doseq [stage (staging-paths (.toPath root) ".surprise-destination.staging-")]
        (backup/discard-staging! (str root) (str stage))))

    (let [hook-var (ns-resolve 'pokemon.migration.backup '*test-hook*)
          fail-restore (fn [event _] (when (= event :after-restore-copy-file)
                                       (throw (ex-info "injected-restore-interruption" {}))))]
      (ensure! (expect-throw! #(with-redefs-fn {hook-var fail-restore}
                                 (fn [] (backup/restore-storage! restore-path backup-path))))
               "restore-interruption-was-not-propagated")
      (ensure! (not (Files/exists (.toPath (io/file restore-path)) (make-array LinkOption 0)))
               "partial-restore-was-published")
      (doseq [stage (staging-paths (.toPath root) ".restored.staging-")]
        (backup/discard-staging! (str root) (str stage))))

    (backup/restore-storage! restore-path backup-path)
    (await-child! (child! "--read" restore-path nil) "RESTORED_CANONICAL_STATE_PASS")
    (ensure! (expect-throw! #(backup/restore-storage! restore-path backup-path))
             "non-empty-restore-destination-overwritten")
    (ensure! (= 77 (:cold-test/value
                    (d/pull (d/db (d/connect (client-at restore-path) {:db-name "cold-fixture"}))
                            '[:cold-test/value] [:cold-test/id "fixture"])))
             "restore-destination-changed-after-rejection")

    ;; Corrupt a separate published copy and prove checksum mismatch is rejected.
    (backup/snapshot-storage! storage corrupt-path)
    (let [files (->> (file-seq (io/file corrupt-path))
                     (filter #(.isFile %))
                     (remove #(= ".a6-datomic-backup.edn" (.getName %))))
          target (first files)]
      (spit target "corrupt")
      (ensure! (expect-throw! #(backup/verify-backup! corrupt-path))
               "invalid-checksum-was-accepted")
      (ensure! (expect-throw! #(backup/restore-storage! (str (io/file root "corrupt-restore")) corrupt-path))
               "corrupt-backup-restore-was-accepted")
      (ensure! (not (.exists (io/file root "corrupt-restore")))
               "corrupt-restore-created-destination"))

    {:ok true :scenario-count 12 :admission-guard admission-guard
     :backup-entries (:entry-count (backup/verify-backup! backup-path))}))

(defn -main [& args]
  (case (first args)
    "--create" (create-data! (second args))
    "--hold" (hold-writer! (second args) (nth args 2))
    "--write" (external-write! (second args))
    "--lock-probe" (lock-probe! (second args))
    "--read" (read-data! (second args))
    (let [result (smoke!)]
      (println "A6_COLD_BACKUP_SMOKE_PASS" (pr-str result))
      (shutdown-agents)
      (System/exit 0))))
