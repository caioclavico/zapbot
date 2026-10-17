(ns odisseu.persistence.datomic
  "Helpers de conexão Datomic Local (dev). Sem lógica de domínio."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]))

(defn project-root
  []
  (let [marker (io/file "deps.edn")]
    (when-not (.exists marker)
      (throw (ex-info "Execute a partir da raiz do repositório (deps.edn)." {})))
    (.getAbsoluteFile (io/file "."))))

(defn resolve-storage-dir
  [relative-or-absolute]
  (let [f (io/file relative-or-absolute)]
    (.getAbsolutePath
     (if (.isAbsolute f)
       f
       (io/file (project-root) relative-or-absolute)))))

(defn client
  [{:keys [system storage-dir]}]
  (d/client {:server-type :datomic-local
             :system system
             :storage-dir (resolve-storage-dir storage-dir)}))

(defn ensure-db!
  [client db-name]
  (let [existing (set (d/list-databases client {}))]
    (when-not (contains? existing db-name)
      (d/create-database client {:db-name db-name})))
  (d/connect client {:db-name db-name}))

(defn delete-db!
  [client db-name]
  (when (some #{db-name} (d/list-databases client {}))
    (d/delete-database client {:db-name db-name})))

(defn read-edn-resource
  "Lê EDN de path relativo à raiz do repo."
  [rel-path]
  (let [f (io/file (project-root) rel-path)]
    (when-not (.exists f)
      (throw (ex-info "migration_not_found" {:path rel-path})))
    (edn/read-string (slurp f))))
