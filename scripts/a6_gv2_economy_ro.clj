(require '[datomic.client.api :as d]
         '[odisseu.persistence.datomic :as persistence])

(def client (persistence/client {:system "odisseu-m90-core"
                                 :storage-dir "/data/datomic-local"
                                 :maintenance-control? true}))
(def conn (persistence/ensure-db! client "core"))
(def db (d/db conn))

(defn count-attr [ident]
  (try (count (d/q '[:find ?e :in $ ?a :where [?e ?a]] db ident))
       (catch Exception _ -1)))

(def saves (count-attr :save/key))
(def pokes (count-attr :pokemon/id-pokemon))
(def eggs (count-attr :egg/id))
(def incs (try (count (d/q '[:find ?e :where [?e :incubator/id]] db))
               (catch Exception _
                 (try (count (d/q '[:find ?e :where [?e :save/incubator-slots]] db))
                      (catch Exception _ -1)))))
(def receipts (try (count (d/q '[:find ?e :where [?e :operation-receipt/id]] db))
                   (catch Exception _
                     (try (count (d/q '[:find ?e :where [?e :receipt/id]] db))
                          (catch Exception _ -1)))))
(def coins (->> (d/q '[:find ?c :where [?e :save/coins ?c]] db)
                (map first) (map long) (reduce + 0)))
(def coins-op (->> (d/q '[:find ?c :where [?e :save/coins-operational ?c]] db)
                   (map first) (map long) (reduce + 0)))
(def ver (->> (d/q '[:find ?v :where [?e :pokemon/xp-progress-version ?v]] db)
              (map first) frequencies))
(def sum-nivel (->> (d/q '[:find ?n :where [?e :pokemon/id-pokemon] [?e :pokemon/level ?n]] db)
                    (map first) (map long) (reduce + 0)))
(def no-ver (count (d/q '[:find ?e
                          :where [?e :pokemon/id-pokemon]
                          (not [?e :pokemon/xp-progress-version])]
                        db)))
(println (pr-str {:saves saves
                  :pokemon pokes
                  :eggs eggs
                  :incubators incs
                  :receipts receipts
                  :coins-save coins
                  :coins-operational coins-op
                  :xp-versions ver
                  :unversioned no-ver
                  :sum-nivel sum-nivel}))
