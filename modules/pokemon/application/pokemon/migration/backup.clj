(ns pokemon.migration.backup
  "Fail-closed cold snapshots and non-destructive Datomic Local restore."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.io IOException]
           [java.nio ByteBuffer]
           [java.nio.channels FileChannel FileLock OverlappingFileLockException]
           [java.nio.file Files FileVisitResult LinkOption Path Paths SimpleFileVisitor
            StandardCopyOption StandardOpenOption AtomicMoveNotSupportedException]
           [java.nio.file.attribute BasicFileAttributes PosixFilePermission]
           [java.security MessageDigest]
           [java.time Instant]
           [java.math BigInteger]
           [java.util UUID]))

(def ^:private manifest-name ".a6-datomic-backup.edn")
(def ^:dynamic ^:private *test-hook*
  "Fault injection used only by disposable tests; never set by application code."
  nil)
(defonce ^:private held-lock-paths (atom #{}))
(defonce ^:private overlap-channels (atom []))

(defn- hook! [event data]
  (when *test-hook* (*test-hook* event data)))

(defn- path ^Path [s]
  (.normalize (.toAbsolutePath
               (if (instance? Path s)
                 ^Path s
                 (Paths/get (str s) (make-array String 0))))))

(defn- exists? [^Path p]
  (Files/exists p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))

(defn- fail! [code data]
  (throw (ex-info code (or data {}))))

(declare sha256-file)

(defn- delete-tree-explicit! [^Path root]
  (when (exists? root)
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (visitFile [^Path file ^BasicFileAttributes _]
         (Files/deleteIfExists file)
         FileVisitResult/CONTINUE)
       (postVisitDirectory [^Path dir ^IOException error]
         (when error (throw error))
         (Files/deleteIfExists dir)
         FileVisitResult/CONTINUE)))))

(defn- permissions [^Path p]
  (try
    (->> (Files/getPosixFilePermissions p (make-array LinkOption 0))
         (map str) sort vec)
    (catch UnsupportedOperationException _ nil)))

(defn- attrs-entry [^Path root ^Path p ^BasicFileAttributes attrs]
  (let [rel (str (.relativize root p))
        path-name (if (= rel "") "." (str/replace rel "\\" "/"))
        symlink? (.isSymbolicLink attrs)
        regular? (.isRegularFile attrs)
        directory? (.isDirectory attrs)]
    (when symlink? (fail! "backup_symlink_forbidden" {:path path-name}))
    (when-not (or regular? directory?) (fail! "backup_special_file_forbidden" {:path path-name}))
    (cond-> {:path path-name
             :kind (if directory? :directory :file)
             :size (if regular? (.size attrs) 0)
             :modified-ms (.toMillis (.lastModifiedTime attrs))
             :permissions (permissions p)}
      regular? (assoc :sha256 (sha256-file p)))))

(defn- runtime-lock-file? [^Path root ^Path p]
  (or (= ".lock" (str (.getFileName p)))
      (= (.resolve root ".odisseu-writer.lock") p)))

(defn- sha256-file [^Path p]
  (let [digest (MessageDigest/getInstance "SHA-256")
        buffer (ByteBuffer/allocate 65536)]
    (with-open [ch (FileChannel/open p (into-array StandardOpenOption [StandardOpenOption/READ]))]
      (loop []
        (.clear buffer)
        (let [n (.read ch buffer)]
          (when (pos? n)
            (.flip buffer)
            (.update digest buffer)
            (recur)))))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- tree-manifest [^Path root]
  (when-not (and (exists? root) (Files/isDirectory root (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
    (fail! "backup_directory_required" {:path (str root)}))
  (let [entries (atom [])]
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [^Path dir ^BasicFileAttributes attrs]
         (swap! entries conj (attrs-entry root dir attrs))
         FileVisitResult/CONTINUE)
       (visitFile [^Path file ^BasicFileAttributes attrs]
         (when-not (or (= file (.resolve root manifest-name))
                       (runtime-lock-file? root file))
           (swap! entries conj (attrs-entry root file attrs)))
         FileVisitResult/CONTINUE)))
    (->> @entries (sort-by :path) vec)))

(defn- force-file! [^Path p]
  (with-open [ch (FileChannel/open p (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
    (.force ch true)))

(defn- force-directory! [^Path p]
  (try
    (with-open [ch (FileChannel/open p (into-array StandardOpenOption [StandardOpenOption/READ]))]
      (.force ch true))
    (catch UnsupportedOperationException e
      (throw (ex-info "backup_directory_fsync_unsupported" {:path (str p)} e)))
    (catch IOException e
      (throw (ex-info "backup_directory_fsync_failed" {:path (str p)} e)))))

(defn- copy-tree! [^Path from ^Path to]
  (let [dirs (atom [])]
    (Files/walkFileTree
     from
     (proxy [SimpleFileVisitor] []
       (preVisitDirectory [^Path dir ^BasicFileAttributes attrs]
         (let [rel (.relativize from dir)
               target (.resolve to rel)]
           (when-not (str/blank? (str rel))
             (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0)))
           (swap! dirs conj [dir target attrs]))
         FileVisitResult/CONTINUE)
       (visitFile [^Path file ^BasicFileAttributes attrs]
         (let [rel (.relativize from file)
               target (.resolve to rel)]
           (when (.isSymbolicLink attrs)
             (fail! "backup_symlink_forbidden" {:path (str rel)}))
           (when-not (.isRegularFile attrs)
             (fail! "backup_special_file_forbidden" {:path (str rel)}))
           (when-not (runtime-lock-file? from file)
             (Files/createDirectories (.getParent target)
                                      (make-array java.nio.file.attribute.FileAttribute 0))
             (hook! :before-copy-file {:source (str file)})
             (Files/copy file target
                         (into-array StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES]))
             (force-file! target)
             (hook! :after-copy-file {:source (str file)})))
         FileVisitResult/CONTINUE)))
    ;; Directory mtimes are applied after children so the copy preserves source metadata.
    (doseq [[source target ^BasicFileAttributes attrs] (reverse @dirs)]
      (when (and (Files/getFileStore source) (Files/getFileStore target))
        (Files/setLastModifiedTime target (.lastModifiedTime attrs))
        (when (not= source from)
          (try
            (Files/setPosixFilePermissions target (Files/getPosixFilePermissions source (make-array LinkOption 0)))
            (catch UnsupportedOperationException _)))))))

(defn- write-manifest! [^Path root entries]
  (let [manifest {:format "odisseu-datomic-cold-backup"
                  :version 1
                  :created-at (str (Instant/now))
                  :entries entries}
        target (.resolve root manifest-name)
        tmp (.resolve root (str manifest-name ".tmp-" (UUID/randomUUID)))]
    (hook! :before-manifest-write {:path (str target)})
    (spit (str tmp) (pr-str manifest))
    (hook! :after-manifest-temp-write {:path (str tmp)})
    (force-file! tmp)
    (Files/move tmp target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (when-let [root-entry (some #(when (= "." (:path %)) %) entries)]
      ;; Publishing the manifest changes the root directory mtime; restore the
      ;; source metadata before verification/publication.
      (Files/setLastModifiedTime root
        (java.nio.file.attribute.FileTime/fromMillis (:modified-ms root-entry)))
      (when-let [perms (:permissions root-entry)]
        (try
          (Files/setPosixFilePermissions root
            (set (map #(PosixFilePermission/valueOf %) perms)))
          (catch UnsupportedOperationException _))))
    (force-directory! root)
    (hook! :after-manifest-write {:path (str target)})
    manifest))

(defn- read-manifest [^Path root]
  (let [p (.resolve root manifest-name)]
    (when-not (Files/isRegularFile p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
      (fail! "backup_manifest_missing" {:path (str p)}))
    (try
      (let [m (edn/read-string (slurp (str p)))
            paths (map :path (:entries m))
            safe-path? (fn [rel]
                         (and (string? rel)
                              (not (str/blank? rel))
                              (not (str/includes? rel "\\"))
                              (not (.isAbsolute (Paths/get rel (make-array String 0))))
                              (every? #(not= ".." (str %))
                                      (iterator-seq (.iterator (Paths/get rel (make-array String 0)))))
                              (not= rel manifest-name)))]
        (when-not (and (= "odisseu-datomic-cold-backup" (:format m))
                       (= 1 (:version m))
                       (vector? (:entries m))
                       (= (count paths) (count (set paths)))
                       (every? safe-path? paths)
                       (= "." (first paths)))
          (fail! "backup_manifest_invalid" {}))
        m)
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch Throwable e (throw (ex-info "backup_manifest_invalid" {} e))))))

(defn verify-backup!
  "Validates manifest, path inventory, sizes, permissions, and SHA-256 content."
  [backup-rel]
  (let [root (path (if (instance? Path backup-rel)
                     backup-rel
                     (persistence/resolve-storage-dir backup-rel)))
        manifest (read-manifest root)
        actual (tree-manifest root)
        expected (:entries manifest)]
    (when-not (= expected actual)
      (fail! "backup_manifest_mismatch"
             {:expected-count (count expected) :actual-count (count actual)}))
    {:ok true :entry-count (count actual) :manifest manifest
     :manifest-sha256 (sha256-file (.resolve root manifest-name))}))

(defn- lock-file! [^Path p]
  (let [key (str (.toAbsolutePath p))]
    (locking held-lock-paths
      (when (contains? @held-lock-paths key)
        (fail! "backup_lock_already_held_in_process" {:lock (str p)}))
      (swap! held-lock-paths conj key)
      (let [channel (try
                      (FileChannel/open p (into-array StandardOpenOption
                                                      [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
                      (catch Throwable error
                        (swap! held-lock-paths disj key)
                        (throw error)))]
        (try
          (let [lock (.tryLock channel)]
            (when-not lock
              (.close channel)
              (swap! held-lock-paths disj key)
              (fail! "backup_writer_active" {:lock (str p)}))
            {:path p :registry-key key :channel channel :lock lock})
          (catch OverlappingFileLockException e
            ;; Closing a second channel for a locked file may release JVM-wide
            ;; locks on some platforms. Keep it open and fail closed instead.
            (swap! overlap-channels conj channel)
            (throw (ex-info "backup_writer_active_in_process" {:lock (str p)} e)))
          (catch Throwable e
            (when (.isOpen channel) (.close channel))
            (swap! held-lock-paths disj key)
            (throw e)))))))

(defn- unlock-file! [{:keys [^FileLock lock ^FileChannel channel registry-key]}]
  (try (.release lock) (catch Throwable _))
  (try (.close channel) (catch Throwable _))
  (swap! held-lock-paths disj registry-key)
  nil)

(defn- datomic-lock-paths [^Path root]
  (let [found (atom [])]
    (Files/walkFileTree
     root
     (proxy [SimpleFileVisitor] []
       (visitFile [^Path file ^BasicFileAttributes attrs]
         (when (and (.isRegularFile attrs) (= ".lock" (str (.getFileName file))))
           (swap! found conj file))
         FileVisitResult/CONTINUE)))
    (vec (sort-by str @found))))

(defn- with-offline-locks [^Path root f]
  (let [supervisor (.resolve root ".odisseu-writer.lock")
        supervisor-lock (when (exists? supervisor) (lock-file! supervisor))
        locks (atom [])]
    (try
      (let [paths (datomic-lock-paths root)]
        (when (empty? paths) (fail! "backup_no_datomic_locks_found" {:root (str root)}))
        (doseq [p paths] (swap! locks conj (lock-file! p)))
        ;; Refuse a changing database inventory while acquiring the locks.
        (when-not (= paths (datomic-lock-paths root))
          (fail! "backup_database_inventory_changed" {}))
        (f paths))
      (finally
        (doseq [held (reverse @locks)] (unlock-file! held))
        (when supervisor-lock
          (unlock-file! supervisor-lock))))))

(defn- empty-directory? [^Path p]
  (and (Files/isDirectory p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
       (with-open [stream (Files/list p)]
         (not (.hasNext (.iterator stream))))))

(defn- staging-path [^Path target]
  (.resolveSibling target (str "." (.getFileName target) ".staging-" (UUID/randomUUID))))

(defn- with-publication-lock [^Path target f]
  (let [parent (.getParent target)
        publication-lock (.resolve parent ".a6-backup-publication.lock")]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (let [{:keys [^FileChannel channel ^FileLock lock]} (lock-file! publication-lock)]
      (try (f)
           (finally
             (unlock-file! {:lock lock :channel channel :registry-key (str (.toAbsolutePath publication-lock))}))))))

(defn- publish-atomic! [^Path stage ^Path target]
  (when (exists? target)
    (fail! "backup_destination_exists" {:target (str target)}))
  (try
    (Files/move stage target (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE]))
    (force-directory! (.getParent target))
    (catch AtomicMoveNotSupportedException e
      (throw (ex-info "backup_atomic_rename_unsupported" {:target (str target)} e)))))

(defn snapshot-storage!
  "Creates a cold snapshot without replacing existing backups. The Datomic JVM
   must be stopped; the helper additionally acquires the bridge and DB file locks
   so managed Datomic clients cannot start or write while bytes are copied.
   DATA: use a new backup path for each snapshot."
  [storage-rel backup-rel]
  (let [from (path (persistence/resolve-storage-dir storage-rel))
        to (path (persistence/resolve-storage-dir backup-rel))]
    (when (or (.startsWith to from) (.startsWith from to))
      (fail! "backup_paths_overlap" {}))
    (when-not (and (exists? from) (Files/isDirectory from (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
      (fail! "backup_source_missing" {:storage storage-rel}))
    (with-publication-lock
      to
      (fn []
        (when (exists? to) (fail! "backup_destination_exists" {:destination backup-rel}))
        (with-offline-locks
          from
          (fn [_locks]
            (let [source-manifest (tree-manifest from)
                  stage (staging-path to)]
              (Files/createDirectory stage (make-array java.nio.file.attribute.FileAttribute 0))
              ;; Failure leaves only a hidden staging directory. It is never considered valid.
              (copy-tree! from stage)
              (let [copied (tree-manifest stage)]
                (when-not (= source-manifest copied)
                  (fail! "backup_source_changed_or_copy_mismatch" {}))
                (write-manifest! stage copied)
                (let [verified (verify-backup! stage)]
                  (publish-atomic! stage to)
                  (assoc verified :from (str from) :to (str to) :ok true))))))))))

(defn restore-storage!
  "Validates a published backup and restores it to a new or empty target only.
   Non-empty destinations are never removed or overwritten."
  [storage-rel backup-rel]
  (let [from (path (persistence/resolve-storage-dir backup-rel))
        to (path (persistence/resolve-storage-dir storage-rel))]
    (when (or (.startsWith to from) (.startsWith from to))
      (fail! "restore_paths_overlap" {}))
    (when-not (exists? from) (fail! "backup_missing" {:backup backup-rel}))
    (with-publication-lock
      to
      (fn []
        (let [{:keys [manifest-sha256]} (verify-backup! from)
              existed-empty? (and (exists? to) (empty-directory? to))]
          (when (and (exists? to) (not existed-empty?))
            (fail! "restore_destination_not_empty" {:destination storage-rel}))
          (let [stage (staging-path to)]
            (Files/createDirectory stage (make-array java.nio.file.attribute.FileAttribute 0))
            ;; Manifest is not part of the Datomic storage tree.
            (let [entries (:entries (read-manifest from))]
              (doseq [{rel :path kind :kind} entries :when (not= rel ".")]
                (let [source (.resolve from rel)
                      target (.resolve stage rel)]
                  (if (= kind :directory)
                    (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
                    (do
                      (Files/createDirectories (.getParent target)
                                               (make-array java.nio.file.attribute.FileAttribute 0))
                      (hook! :before-restore-copy-file {:path rel})
                      (Files/copy source target (into-array StandardCopyOption [StandardCopyOption/COPY_ATTRIBUTES]))
                      (force-file! target)
                      (hook! :after-restore-copy-file {:path rel})))))
              (doseq [{rel :path kind :kind modified-ms :modified-ms perms :permissions} (reverse entries)
                      :when (= kind :directory)]
                (let [dir (if (= rel ".") stage (.resolve stage rel))]
                  (Files/setLastModifiedTime dir
                                            (java.nio.file.attribute.FileTime/fromMillis modified-ms))
                  (when perms
                    (try
                      (Files/setPosixFilePermissions dir
                        (set (map #(PosixFilePermission/valueOf %) perms)))
                      (catch UnsupportedOperationException _)))))
              (let [actual (tree-manifest stage)]
                (when-not (= entries actual) (fail! "restore_manifest_mismatch" {}))
                (when existed-empty? (Files/delete to))
                (publish-atomic! stage to)
                {:ok true :from (str from) :restored-to (str to)
                 :manifest-sha256 manifest-sha256 :entry-count (count entries)}))))))))

(defn discard-staging!
  "Explicitly removes one named staging directory after operator inspection."
  [parent-rel staging-rel]
  (let [parent (path (persistence/resolve-storage-dir parent-rel))
        target (path (persistence/resolve-storage-dir staging-rel))
        name (str (.getFileName target))]
    (when-not (and (= parent (.getParent target))
                   (re-matches #"\..+\.staging-[0-9a-fA-F-]{36}" name)
                   (exists? target))
      (fail! "staging_cleanup_path_rejected" {}))
    (delete-tree-explicit! target)
    {:ok true :discarded name}))
