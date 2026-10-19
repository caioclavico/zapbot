(ns odisseu.cutover.op-journal
  "Journal append-only independente do volume Datomic (protótipo M8.3).
   NÃO declara exactly-once entre journal e Datomic.
   Protocolo: Datomic confirma → append journal → só então considerar
   'recuperável após perda de volume'. Se append falhar, a op fica em risco (RPO)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]))

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
  "Maior seq persistida (0 se vazio)."
  [dir]
  (let [fs (seq-files dir)]
    (if (seq fs)
      (Long/parseLong (subs (.getName (last fs)) 0 8))
      0)))

(defn read-entry
  [file]
  (edn/read-string (slurp file)))

(defn list-entries
  "Entradas ordenadas por :seq."
  [dir]
  (mapv read-entry (seq-files dir)))

(defn entries-after
  [dir after-seq]
  (->> (list-entries dir)
       (filter #(> (long (:seq %)) (long after-seq)))
       vec))

(defn append!
  "Append atômico: escreve temp + move. Retorna entry.
   Exige :operation-id :domain :kind :scope :payload já confirmados no Datomic."
  [dir {:keys [operation-id domain kind scope payload datomic-confirmed?]
        :as m}]
  (assert-disposable! dir)
  (when-not (true? datomic-confirmed?)
    (throw (ex-info "journal_requires_datomic_confirm"
                    {:hint "Só journalize após commit Datomic bem-sucedido."})))
  (when (str/blank? (str operation-id))
    (throw (ex-info "journal_missing_operation_id" {})))
  (let [d (journal-dir dir)
        seq-n (next-seq d)
        ph (payload-hash payload)
        entry {:seq seq-n
               :at-ms (System/currentTimeMillis)
               :operation-id (str operation-id)
               :domain (keyword domain)
               :kind (keyword kind)
               :scope (into {} scope)
               :payload payload
               :payload-hash ph
               :datomic-confirmed? true
               :replayable? (not= false (:replayable? m true))
               :side-effects (vec (or (:side-effects m) []))
               :nota (:nota m)}
        name (format "%08d.edn" seq-n)
        target (io/file d name)
        tmp (io/file d (str name ".tmp"))]
    (spit tmp (pr-str entry))
    (try
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception _
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    entry))

(defn append-best-effort!
  "Append após Datomic. Se falhar, retorna {:ok false :at-risk true} — NÃO esconde risco."
  [dir entry-map]
  (try
    {:ok true :at-risk false :entry (append! dir entry-map)}
    (catch Exception ex
      {:ok false
       :at-risk true
       :error (.getMessage ex)
       :operation-id (:operation-id entry-map)})))

(defn write-intent!
  "Registro de intenção ANTES do Datomic (fase 1). Sem confirmação ao cliente."
  [dir {:keys [operation-id domain kind scope payload]}]
  (let [d (journal-dir (str dir "/intents"))
        seq-n (next-seq d)
        entry {:seq seq-n
               :phase :intent
               :at-ms (System/currentTimeMillis)
               :operation-id (str operation-id)
               :domain (keyword domain)
               :kind (keyword kind)
               :scope (into {} scope)
               :payload payload
               :payload-hash (payload-hash payload)
               :datomic-confirmed? false}
        name (format "%08d.edn" seq-n)
        target (io/file d name)
        tmp (io/file d (str name ".tmp"))]
    (spit tmp (pr-str entry))
    (try
      (Files/move (.toPath tmp) (.toPath target)
                  (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
      (catch Exception _
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
    entry))

(defn corrupt-last!
  "Ensaio: corrompe último arquivo do journal."
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
