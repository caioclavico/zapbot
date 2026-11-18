(ns odisseu.cutover.op-journal
  "Journal append-only independente do volume Datomic (M8.3/M8.4).
   M8.4: fsync, schema versionado, fases, rejeição de op-id/payload mismatch.
   NÃO declara exactly-once entre journal e Datomic."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [odisseu.cutover.recovery :as recovery])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.nio.channels FileChannel]
           [java.nio.file Files StandardCopyOption OpenOption StandardOpenOption]))

(def schema-version "m84-v1")

(def phases
  #{:prepared :datomic-committed :journal-durable :uncertain :aborted})

(defn- assert-disposable!
  [path]
  (when (re-find #"(?i)prod|oracle|live|m53-real|m55-real" (str path))
    (throw (ex-info "journal_path_blocked" {:path path}))))

(defn- sha256 [s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes (str s) StandardCharsets/UTF_8))
    (apply str (map #(format "%02x" %) (.digest md)))))

(defn payload-hash
  [payload]
  (sha256 (pr-str payload)))

(defn journal-dir
  [root]
  (assert-disposable! root)
  (let [d (io/file (str root))]
    (.mkdirs d)
    d))

(defn- fsync-file!
  "Force conteúdo + metadados para disco (melhor esforço no lab)."
  [^java.io.File f]
  (with-open [ch (FileChannel/open
                  (.toPath f)
                  (into-array OpenOption [StandardOpenOption/WRITE]))]
    (.force ch true)))

(defn- atomic-write-edn!
  "Escreve EDN via temp + fsync + move. Hook :before-fsync / :during-write para crashes."
  [target-file content hooks]
  (let [tmp (io/file (str target-file ".tmp"))
        _ (when-let [h (:before-write hooks)] (h))
        _ (spit tmp (pr-str content))
        _ (when-let [h (:during-write hooks)] (h))
        _ (fsync-file! tmp)
        _ (when-let [h (:before-fsync hooks)] (h)) ;; após force do tmp; nome legado
        _ (when-let [h (:after-fsync-before-move hooks)] (h))]
    (try
      (Files/move (.toPath tmp) (.toPath target-file)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception _
        (Files/move (.toPath tmp) (.toPath target-file)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (when-let [h (:after-move hooks)] (h))
    ;; fsync do diretório não portátil em todos FS; fsync do arquivo final
    (fsync-file! target-file)
    content))

(defn- seq-files
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile %) (re-matches #"\d{8}\.edn" (.getName %))))
       (sort-by #(.getName %))))

(defn next-seq
  [dir]
  (let [fs (seq-files dir)]
    (if (seq fs)
      (inc (Long/parseLong (subs (.getName (last fs)) 0 8)))
      1)))

(defn watermark
  [dir]
  (let [fs (seq-files dir)]
    (if (seq fs)
      (Long/parseLong (subs (.getName (last fs)) 0 8))
      0)))

(defn read-entry
  [file]
  (edn/read-string (slurp file)))

(defn list-entries
  [dir]
  (mapv read-entry (seq-files dir)))

(defn entries-after
  [dir after-seq]
  (->> (list-entries dir)
       (filter #(> (long (:seq %)) (long after-seq)))
       vec))

(defn find-by-operation-id
  [dir operation-id]
  (->> (list-entries dir)
       (filter #(= (str operation-id) (str (:operation-id %))))
       last))

(defn- assert-payload-stable!
  [dir operation-id ph]
  (when-let [prev (find-by-operation-id dir operation-id)]
    (when (and (:payload-hash prev) (not= (:payload-hash prev) ph))
      (throw (ex-info "journal_operation_id_payload_mismatch"
                      {:operation-id operation-id
                       :stored (:payload-hash prev)
                       :got ph})))))

(defn append-phase!
  "Append genérico com fase. hooks opcionais para injeção de crash."
  [dir {:keys [operation-id domain kind scope payload phase
               datomic-confirmed? replayable? side-effects fence
               schema result-summary]
        :as m}
   & [hooks]]
  (assert-disposable! dir)
  (when (str/blank? (str operation-id))
    (throw (ex-info "journal_missing_operation_id" {})))
  (when-not (contains? phases (keyword phase))
    (throw (ex-info "journal_invalid_phase" {:phase phase :allowed phases})))
  (let [d (journal-dir dir)
        ph (payload-hash payload)
        _ (assert-payload-stable! d operation-id ph)
        seq-n (next-seq d)
        entry {:schema (or schema schema-version)
               :seq seq-n
               :phase (keyword phase)
               :at-ms (System/currentTimeMillis)
               :operation-id (str operation-id)
               :domain (keyword domain)
               :kind (keyword kind)
               :scope (into {} scope)
               :fence fence
               :payload payload
               :payload-hash ph
               :datomic-confirmed? (boolean datomic-confirmed?)
               :replayable? (not= false replayable?)
               :side-effects (vec (or side-effects []))
               :result-summary result-summary
               :nota (:nota m)}
        name (format "%08d.edn" seq-n)
        target (io/file d name)]
    (atomic-write-edn! target entry (or hooks {}))
    entry))

(defn append!
  "Compat M8.3: append após Datomic confirmado → fase :journal-durable."
  [dir m]
  (when-not (true? (:datomic-confirmed? m))
    (throw (ex-info "journal_requires_datomic_confirm"
                    {:hint "Use append-phase! :prepared ou confirme Datomic primeiro."})))
  (append-phase! dir (assoc m :phase :journal-durable)))

(defn append-best-effort!
  [dir entry-map]
  (try
    {:ok true :at-risk false :entry (append! dir entry-map)}
    (catch Exception ex
      {:ok false
       :at-risk true
       :error (.getMessage ex)
       :operation-id (:operation-id entry-map)})))

(defn write-intent!
  "Fase :prepared ANTES do Datomic. Sem confirmação ao cliente."
  [dir m & [hooks]]
  (append-phase! dir (merge m {:phase :prepared
                               :datomic-confirmed? false})
                 hooks))

(defn mark-uncertain!
  "Registra incerteza (ex.: Datomic OK, journal confirm falhou)."
  [dir m]
  (append-phase! dir (merge m {:phase :uncertain
                               :datomic-confirmed? (boolean (:datomic-confirmed? m))})))

(defn prepared-without-durable
  "Entradas :prepared sem :journal-durable posterior para o mesmo op-id."
  [dir]
  (let [entries (list-entries dir)
        by-op (group-by :operation-id entries)]
    (->> by-op
         (keep (fn [[op es]]
                 (let [phases* (set (map :phase es))]
                   (when (and (contains? phases* :prepared)
                              (not (contains? phases* :journal-durable))
                              (not (contains? phases* :aborted)))
                     {:operation-id op
                      :phases phases*
                      :last (last es)}))))
         vec)))

(defn compact!
  "Rotação: move entries ≤ keep-after-seq para archive/. Crash hooks suportados."
  [dir keep-after-seq & [hooks]]
  (let [d (journal-dir dir)
        arch (journal-dir (str dir "/archive"))
        to-move (->> (seq-files d)
                     (filter #(<= (Long/parseLong (subs (.getName %) 0 8))
                                  (long keep-after-seq))))]
    (when-let [h (:before-compact hooks)] (h))
    (doseq [f to-move]
      (when-let [h (:during-compact hooks)] (h))
      (let [dest (io/file arch (.getName f))]
        (Files/move (.toPath f) (.toPath dest)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    (when-let [h (:after-compact hooks)] (h))
    {:archived (count to-move) :keep-after-seq keep-after-seq}))

(defn corrupt-last!
  [dir]
  (when-let [f (last (seq-files dir))]
    (spit f "{{{corrupt")
    {:corrupted (.getName f)}))

(defn wipe!
  [dir]
  (let [root (io/file (str dir))]
    (when (.exists root)
      (doseq [c (reverse (file-seq root))]
        (.delete c)))))

(defn fail-closed!
  "Entra em manutenção quando política de durabilidade está comprometida."
  [maint-dir reason]
  (recovery/enter-maintenance! maint-dir (str "durability:" reason)))
