(ns pokemon.infrastructure.datomic.schema
  "Instala schemas Datomic versionados em migrations/pokemon/."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def migration-files
  ["migrations/pokemon/001-trainer-saves.edn"
   "migrations/pokemon/002-collection-hp-xp.edn"
   "migrations/pokemon/003-reward-payload-hash.edn"
   "migrations/pokemon/004-capture-moves-evolution.edn"
   "migrations/pokemon/005-battle-engine.edn"
   "migrations/pokemon/006-gyms.edn"
   "migrations/pokemon/007-raids.edn"
   "migrations/pokemon/008-adventure-joy.edn"
   "migrations/pokemon/009-hunt-encounters.edn"])

(def schema-meta-attr
  {:db/ident       :odisseu.schema/migration-id
   :db/valueType   :db.type/string
   :db/cardinality :db.cardinality/one
   :db/unique      :db.unique/identity
   :db/doc         "Id de migration já aplicada (ex.: pokemon/001-trainer-saves)"})

(defn- applied?
  [db migration-id]
  (some? (d/pull db '[:odisseu.schema/migration-id]
                 [:odisseu.schema/migration-id migration-id])))

(defn- ensure-meta!
  [conn]
  (let [db (d/db conn)]
    (when-not (d/pull db '[:db/ident] :odisseu.schema/migration-id)
      (d/transact conn {:tx-data [schema-meta-attr]}))))

(defn install!
  "Aplica migrations pendentes em ordem. Idempotente."
  [conn]
  (ensure-meta! conn)
  (doseq [path migration-files]
    (let [migration-id (-> path
                           (str/replace #"^migrations/" "")
                           (str/replace #"\.edn$" ""))
          db (d/db conn)]
      (when-not (applied? db migration-id)
        (let [tx (persistence/read-edn-resource path)]
          (d/transact conn {:tx-data (conj (vec tx)
                                           {:odisseu.schema/migration-id migration-id})})))))
  conn)
