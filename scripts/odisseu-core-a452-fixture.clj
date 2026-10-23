(ns odisseu.core-a452-fixture
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def migrations (map #(format "migrations/pokemon/%03d-%s.edn" %1 %2)
                     (range 1 20)
                     ["trainer-saves" "collection-hp-xp" "reward-payload-hash"
                      "capture-moves-evolution" "battle-engine" "gyms" "raids"
                      "adventure-joy" "hunt-encounters" "shop" "pokemon-location-legado"
                      "enfermaria-bed-origin" "onboarding" "pvp" "pokedex-favorito"
                      "missions" "mission-events" "mission-events-processed"
                      "operation-receipt-unique-value"]))

(def schema-meta-attr
  {:db/ident       :odisseu.schema/migration-id
   :db/valueType   :db.type/string
   :db/cardinality :db.cardinality/one
   :db/unique      :db.unique/identity
   :db/doc         "Id de migration já aplicada"})

(defn- install-baseline! [conn]
  ;; Reproduz o registro oficial de schema/install!: cada migration e seu
  ;; identificador são gravados na mesma transação, sem incluir a 020.
  (d/transact conn {:tx-data [schema-meta-attr]})
  (doseq [path migrations]
      (let [migration-id (-> path
                           (str/replace #"^migrations/" "")
                           (str/replace #"\.edn$" ""))]
      (d/transact conn {:tx-data
                        (conj (vec (persistence/read-edn-resource path))
                              {:odisseu.schema/migration-id migration-id})}))))

(defn -main [& [storage system db-name]]
  (let [client (persistence/client {:storage-dir storage :system system})]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (install-baseline! conn)
      (d/transact conn {:tx-data
                        [{:db/id "p-a" :player/key "chat-a|player-a"
                          :player/chat-id "chat-a" :player/player-id "player-a"
                          :player/active-save-id "a" :player/fence-generation 1}
                         {:db/id "s-a" :save/key "chat-a|player-a|a"
                          :save/slot-id "a" :save/player "p-a" :save/revision 0}
                         {:db/id "p-b" :player/key "chat-a|player-b"
                          :player/chat-id "chat-a" :player/player-id "player-b"
                          :player/active-save-id "a" :player/fence-generation 1}
                         {:db/id "s-b" :save/key "chat-a|player-b|a"
                          :save/slot-id "a" :save/player "p-b" :save/revision 0}
                         {:db/id "pk-a" :pokemon/key "pokemon-a"
                          :pokemon/id-pokemon "pokemon-a" :pokemon/save "s-a"
                          :pokemon/species "Bulbasaur" :pokemon/level 5
                          :pokemon/hp 45 :pokemon/hp-atual 45 :pokemon/shiny false}
                         {:db/id "pk-b" :pokemon/key "pokemon-b"
                          :pokemon/id-pokemon "pokemon-b" :pokemon/save "s-b"
                          :pokemon/species "Charmander" :pokemon/level 5
                          :pokemon/hp 39 :pokemon/hp-atual 39 :pokemon/shiny false}]})
      (println "FIXTURE_READY"))))
