(ns odisseu.smoke.a6-restore-aggregate
  "Read-only aggregate inspection for an isolated, restored A6 Datomic volume."
  (:require [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.gym-audit :as gym-audit]))

(defn- attr-stats [db attr]
  (let [schema (set (map first (d/q '[:find ?ident :where [_ :db/ident ?ident]] db)))]
    (if-not (contains? schema attr)
      {:status :unknown}
      (let [entities (d/q '[:find ?e
                            :in $ ?attr
                            :where [?e ?attr _]] db attr)
            values (d/q '[:find ?v
                          :in $ ?attr
                          :where [_ ?attr ?v]] db attr)]
        {:status :available
         :entity-count (count entities)
         :numeric-total (let [numbers (filter number? (map first values))]
                          (when (seq numbers) (long (reduce + numbers))))}))))

(defn- db-summary [db]
  (let [stats (d/db-stats db)]
    {:datoms (:datoms stats)
     :saves (attr-stats db :save/key)
     :pokemon (attr-stats db :pokemon/id-pokemon)
     :inventory-items (attr-stats db :inventory-item/key)
     :inventory-quantity (attr-stats db :inventory-item/qty)
     :legacy-coins (attr-stats db :save/coins)
     :operational-coins (attr-stats db :save/coins-operational)
     :ledger-entries (attr-stats db :coin-ledger/key)
     :operation-receipts (attr-stats db :operation-receipt/id)
     :gym-audit (gym-audit/summarize db nil)}))

(defn -main [& _]
  (let [storage (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local")
        system (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-a6-restore-readonly")
        client (persistence/client {:system system :storage-dir storage
                                    :maintenance-control? true})
        available (set (d/list-databases client {}))
        db-name (or (System/getenv "ODISSEU_BRIDGE_DB") "core")
        platform-name (or (System/getenv "ODISSEU_PLATFORM_DB") "platform")
        result {:database-count (count available)
                :core-present? (contains? available db-name)
                :platform-present? (contains? available platform-name)
                :core (when (contains? available db-name)
                        (db-summary (d/db (d/connect client {:db-name db-name}))))
                :platform (when (contains? available platform-name)
                            (let [conn (d/connect client {:db-name platform-name})
                                  db (d/db conn)]
                              {:datoms (:datoms (d/db-stats db))
                               :release-deliveries (attr-stats db :platform.delivery/idempotency-key)}))}]
    ;; Deliberately aggregate-only: no save keys, player IDs, values, or payloads.
    (println (pr-str result))
    (shutdown-agents)))
