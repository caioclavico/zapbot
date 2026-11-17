(ns pokemon.migration.receipt-a433
  "Auditoria somente-leitura para a migration 019 de recibos globais."
  (:require [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def migration-id "pokemon/019-operation-receipt-unique-value")

(defn report
  "Não transaciona. Relata duplicações que impedem trocar identity por value."
  [conn]
  (let [db (d/db conn)
        attr (d/pull db '[{:db/unique [:db/ident]}] :operation-receipt/id)
        ids (d/q '[:find ?id ?receipt
                   :where [?receipt :operation-receipt/id ?id]] db)
        duplicates (->> ids
                        (group-by first)
                        (keep (fn [[operation-id entries]]
                                (when (> (count entries) 1)
                                  {:operation-id operation-id
                                   :receipt-eids (mapv second entries)})))
                        (sort-by :operation-id)
                        vec)
        applied? (some? (d/pull db '[:odisseu.schema/migration-id]
                                  [:odisseu.schema/migration-id migration-id]))]
    {:migration migration-id
     :receipt-uniqueness (some-> attr :db/unique :db/ident)
     :receipt-count (count ids)
     :duplicates duplicates
     :migration-applied applied?
     :safe-to-apply? (and (empty? duplicates)
                          (not applied?))
     :safe-state? (and (empty? duplicates)
                       (or applied? (= :db.unique/value
                                         (some-> attr :db/unique :db/ident))))}))

(defn -main
  "Uso somente leitura: clojure -M -m pokemon.migration.receipt-a433 <storage-dir> <system> <db-name>."
  [& [storage-dir system db-name]]
  (when-not (every? some? [storage-dir system db-name])
    (throw (ex-info "usage: <storage-dir> <system> <db-name>" {})))
  (let [client (persistence/client {:storage-dir storage-dir :system system})]
    (when-not (some #{db-name} (d/list-databases client {}))
      (throw (ex-info "database_not_found" {:db-name db-name})))
    (prn (report (d/connect client {:db-name db-name})))))
