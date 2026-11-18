(ns odisseu.persistence.live-guard
  "Recusa o volume live antes de abrir um cliente Datomic.
   A barreira física é a ausência do mount. Este predicado impede a abertura
   acidental quando o caminho ou o mountinfo já apontam para o volume live.
   Administrador do host e quem controla o Docker continuam capazes de montar
   o volume por fora."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def live-volume "odisseu-a6-cutover-datomic")

(def proof-file "/run/odisseu-exclusive/proof")

(def ^:dynamic *proof-file* proof-file)

(defn- mountinfo []
  (let [source (io/file "/proc/self/mountinfo")]
    (if (.isFile source)
      (slurp source)
      "")))

(defn live-visible?
  [storage]
  (or (str/includes? (str storage) live-volume)
      (str/includes? (mountinfo) live-volume)))

(defn- exclusive-proof
  [storage]
    (let [file (io/file *proof-file*)]
    (when (and (.isFile file) (not (.canWrite file)))
      (let [proof (try (edn/read-string (slurp file)) (catch Throwable _ nil))
            volume (str (:volume proof))
            storage-file (.getCanonicalFile (io/file (str storage)))]
        (when (and (= true (:exclusive proof))
                   (= 0 (:other-writers proof))
                   (str/includes? volume live-volume)
                   (str/includes? (str storage) volume)
                   (not (str/starts-with? (.getCanonicalPath file)
                                          (str (.getCanonicalPath storage-file)))))
          proof)))))

(defn assert-external!
  "Lança antes de qualquer cliente. Variável de ambiente não autoriza o live."
  [storage]
  (when (live-visible? storage)
    (when-not (exclusive-proof storage)
      (throw (ex-info "live_storage_refused"
                      {:client-opened? false
                       :storage (str storage)}))))
  nil)
