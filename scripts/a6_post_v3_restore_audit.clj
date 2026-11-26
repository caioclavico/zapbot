(ns a6.post-v3-restore-audit
  "Audita apenas uma cópia isolada do restore; nunca cria banco ou transaciona."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.xp-reconciliation :as reconciliation]
            [pokemon.infrastructure.http.json :as json]))

(defn audit!
  [storage system db-name pokemon-id]
  (when (some str/blank? [storage system db-name pokemon-id])
    (throw (ex-info "restore_audit_arguments_required" {})))
  (let [file (io/file storage)
        canonical (.getCanonicalPath file)]
    (when-not (and (.isAbsolute file) (.isDirectory file)
                   (= canonical (.getAbsolutePath file))
                   (not (str/includes? canonical "odisseu-a6-cutover-datomic"))
                   (not (str/includes? canonical "/var/lib/docker/volumes/"))
                   (not= canonical "/data/datomic-local")
                   (not (str/starts-with? canonical "/data/datomic-local/")))
      (throw (ex-info "restore_audit_storage_refused" {})))
    (let [client (persistence/client {:system system :storage-dir canonical
                                      :maintenance-control? true})]
      (when-not (contains? (set (d/list-databases client {})) db-name)
        (throw (ex-info "restore_audit_database_missing" {})))
      (reconciliation/audit-post-v3 (d/connect client {:db-name db-name})
                                    pokemon-id pokemon-id))))

(defn -main [& args]
  (when-not (= 4 (count args))
    (throw (ex-info "restore_audit_arguments_required" {})))
  (let [result (apply audit! args)]
    (println (json/write-str result))
    (when-not (= "PASS" (:status result))
      (System/exit 2))))
