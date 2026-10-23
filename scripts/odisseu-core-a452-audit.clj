(ns odisseu.core-a452-audit
  "Auditoria/ensaio da migration 020 em uma cópia Datomic Local.
   Nunca aponta para o volume oficial: o wrapper exige um diretório audit-* ."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.infrastructure.datomic.schema :as schema])
  (:import [java.nio.file Files]))

(defn- assert-audit-storage! [storage]
  (let [f (io/file storage)
        s (.getCanonicalPath f)]
    (when (Files/isSymbolicLink (.toPath f))
      (throw (ex-info "audit_storage_symlink_forbidden" {:storage s})))
    (when (or (re-find #"(?i)odisseu-core-datomic|prod|staging" s)
              (not (re-find #"(?i)(audit|a451)" s)))
      (throw (ex-info "audit_storage_required" {:storage s}))))
  storage)

(defn- entities [db attr]
  (->> (d/q {:query '[:find ?e :in $ ?a :where [?e ?a]]
             :args [db attr]})
       (map first)
       sort))

(defn snapshot [conn]
  (let [db (d/db conn)
        migration (fn [id]
                    (some? (d/pull db '[:odisseu.schema/migration-id]
                                    [:odisseu.schema/migration-id id])))
        players (mapv #(d/pull db [:player/key :player/chat-id :player/player-id
                                   :player/active-save-id :player/fence-generation] %)
                       (entities db :player/key))
        saves (mapv #(d/pull db [:save/key :save/slot-id
                                 {:save/player [:player/key :player/player-id :player/chat-id]}
                                 :save/revision :save/ativo-index] %)
                     (entities db :save/key))
        pokemon (mapv #(d/pull db [:pokemon/key :pokemon/id-pokemon
                                    {:pokemon/save [:save/key :save/slot-id
                                                    {:save/player [:player/key]}]}
                                    :pokemon/species :pokemon/nickname :pokemon/shiny
                                    :pokemon/xp :pokemon/level :pokemon/hp
                                    :pokemon/hp-atual :pokemon/status :pokemon/golpes-edn
                                    :pokemon/ivs-edn] %)
                       (entities db :pokemon/key))]
    {:players players :saves saves :pokemon pokemon
     :migration-state {:019 (migration "pokemon/019-operation-receipt-unique-value")
                       :020 (migration "pokemon/020-trades")}
     :counts {:players (count players) :saves (count saves) :pokemon (count pokemon)}}))

(defn- write! [path value]
  (spit path (with-out-str (pprint value))))

(defn -main [& [phase storage system db-name snapshot-path]]
  (when-not (every? some? [phase storage system db-name snapshot-path])
    (throw (ex-info "usage: <snapshot|install> <storage> <system> <db> <snapshot.edn>" {})))
  (assert-audit-storage! storage)
  (let [client (persistence/client {:storage-dir storage :system system})]
    (when-not (some #{db-name} (d/list-databases client {}))
      (throw (ex-info "database_not_found" {:db-name db-name})))
    (let [conn (d/connect client {:db-name db-name})]
      (case phase
        "snapshot" (write! snapshot-path (snapshot conn))
        "install" (do
                      ;; O ensaio de 020 não deve mascarar migrations anteriores ausentes.
                      (when-not (some? (d/pull (d/db conn)
                                               '[:odisseu.schema/migration-id]
                                               [:odisseu.schema/migration-id
                                                "pokemon/019-operation-receipt-unique-value"]))
                        (throw (ex-info "baseline_migration_019_missing" {})))
                      (schema/install! conn)
                      (let [before (edn/read-string (slurp snapshot-path))
                            after (snapshot conn)]
                        (when-not (= (select-keys before [:players :saves :pokemon :counts])
                                     (select-keys after [:players :saves :pokemon :counts]))
                          (throw (ex-info "canonical_data_changed"
                                          {:before (:counts before)
                                           :after (:counts after)})))
                        (write! snapshot-path after)
                        (println (pr-str {:migration "pokemon/020-trades"
                                          :before-020 (get-in before [:migration-state :020])
                                          :after-020 (get-in after [:migration-state :020])
                                          :result (cond
                                                    (get-in before [:migration-state :020]) :already-installed
                                                    (get-in after [:migration-state :020]) :installed-now
                                                    :else :not-installed)}))))
        (throw (ex-info "unknown_phase" {:phase phase}))))))
