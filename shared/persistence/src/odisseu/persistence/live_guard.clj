(ns odisseu.persistence.live-guard
  "Classifica o storage antes de abrir um cliente Datomic.
   :live é o volume de produção. :non-live foi lido e não é esse volume,
   ou é um diretório temporário isolado. :unknown significa que a leitura
   falhou ou não dá para saber. :unknown bloqueia. Uma exceção não autoriza.
   Administrador do host continua capaz de montar o volume por fora."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def live-volume "odisseu-a6-cutover-datomic")

(def production-container-path "/data/datomic-local")

(def proof-path "/run/odisseu-exclusive/proof")

(defn read-mountinfo
  []
  (let [source (io/file "/proc/self/mountinfo")]
    (if-not (.exists source)
      {:status :absent}
      (try
        {:status :ok
         :text (with-open [reader (java.io.BufferedReader.
                                   (java.io.InputStreamReader.
                                    (java.io.FileInputStream. source)
                                    java.nio.charset.StandardCharsets/UTF_8))]
                 (loop [lines []]
                   (if-let [line (.readLine reader)]
                     (recur (conj lines line))
                     (str/join "\n" lines))))}
        (catch java.io.IOException ex
          {:status :unreadable :error (.getMessage ex)})))))

(defn- production-path?
  [path]
  (or (= path production-container-path)
      (str/ends-with? path "/data/datomic-local")))

(defn- proven-isolated-temp?
  [path]
  (let [absolute (.getAbsolutePath (io/file path))
        tmp (.getAbsolutePath (io/file (System/getProperty "java.io.tmpdir")))]
    (and (.isAbsolute (io/file absolute))
         (not (production-path? absolute))
         (not (str/includes? absolute live-volume))
         (or (str/starts-with? absolute tmp)
             (str/starts-with? absolute "/tmp/")
             (str/starts-with? absolute "/private/tmp/")
             (str/starts-with? absolute "/var/folders/")))))

(defn- valid-mount-line?
  [line]
  (and (str/includes? line " - ")
       (>= (count (str/split line #"\s+")) 6)))

(defn classify
  "Devolve :live, :non-live ou :unknown. Nunca converte falha em :non-live
   para um caminho que possa ser o Datomic de produção."
  [storage]
  (let [path (str storage)
        mount (try
                (read-mountinfo)
                (catch java.io.IOException ex
                  {:status :unreadable :error (.getMessage ex)}))]
    (cond
      (str/includes? path live-volume) :live
      (= :unreadable (:status mount))
      (if (proven-isolated-temp? path) :non-live :unknown)
      (= :absent (:status mount))
      (cond
        (proven-isolated-temp? path) :non-live
        (production-path? path) :unknown
        :else :non-live)
      :else
      (let [lines (remove str/blank? (str/split-lines (or (:text mount) "")))
            valid (filter valid-mount-line? lines)]
        (cond
          (some #(str/includes? % live-volume) lines) :live
          (and (seq lines) (empty? valid))
          (if (proven-isolated-temp? path) :non-live :unknown)
          :else :non-live)))))

(defn read-kernel-binding
  "Relê boot id e id do container no momento da checagem."
  []
  (let [boot (io/file "/proc/sys/kernel/random/boot_id")
        cgroup (io/file "/proc/self/cgroup")]
    {:boot-id (when (.isFile boot) (str/trim (slurp boot)))
     :container-id (when (.isFile cgroup)
                     (when-let [found (re-find #"([0-9a-f]{12,64})" (slurp cgroup))]
                       (if (string? found) found (last found))))
     :mounts (let [mount (read-mountinfo)]
               (when (= :ok (:status mount)) (:text mount)))}))

(defn proof-matches?
  "A prova só vale se os campos relidos agora coincidem.
   Hash do arquivo não autentica. Boot id ou container diferentes invalidam."
  [proof observation]
  (boolean
   (and (map? proof)
        (map? observation)
        (= true (:exclusive proof))
        (= 0 (:other-writers proof))
        (str/includes? (str (:volume proof)) live-volume)
        (seq (:boot-id proof))
        (= (:boot-id proof) (:boot-id observation))
        (seq (:container-id proof))
        (= (:container-id proof) (:container-id observation))
        (str/includes? (str (:mounts observation)) live-volume))))

(defn- constant-proof
  []
  (let [file (io/file proof-path)]
    (when (and (.isFile file) (not (.canWrite file)))
      (try (edn/read-string (slurp file)) (catch Throwable _ nil)))))

(defn assert-external!
  "Lança antes de qualquer cliente. Mapa do chamador não entra nesta função.
   :unknown e :live sem prova revalidada agora bloqueiam."
  [storage]
  (case (classify storage)
    :non-live nil
    :unknown (throw (ex-info "storage_identity_unknown"
                             {:client-opened? false :storage (str storage)}))
    :live (if (proof-matches? (constant-proof) (read-kernel-binding))
            nil
            (throw (ex-info "live_storage_refused"
                            {:client-opened? false :storage (str storage)})))))
