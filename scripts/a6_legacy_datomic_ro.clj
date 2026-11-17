(ns a6-legacy-datomic-ro
  "Inventário RO do volume Datomic legado (cópia local descartável).

  --storage deve ser o diretório pai do system (ex.: …/legacy-…/),
  com system=odisseu-m90-core e db=core. Não toca volumes live."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.domain.identities :as id]
            [pokemon.domain.reward :as reward]))

(defn- poke->map
  [p]
  {:id-pokemon (:pokemon/id-pokemon p)
   :nome (or (:pokemon/species p) "unknown")
   :nivel (or (:pokemon/level p) 1)
   :hp-atual (or (:pokemon/hp-atual p) (:pokemon/hp p) 0)})

(defn- inventory->map
  [db save-eid]
  (->> (d/q '[:find (pull ?i [*])
              :in $ ?s
              :where [?i :inventory-item/save ?s]]
            db save-eid)
       (map first)
       (map (fn [i]
              [(or (:inventory-item/item-id i) "item")
               (long (:inventory-item/qty i 0))]))
       (into {})))

(defn- active-save
  [db player]
  (let [active-id (:player/active-save-id player)
        rows (d/q '[:find (pull ?s [*])
                    :in $ ?p
                    :where [?s :save/player ?p]]
                  db (:db/id player))
        saves (mapv first rows)]
    (or (first (filter #(= active-id (:save/slot-id %)) saves))
        (first saves))))

(defn- collection-for-save
  [db save-eid]
  (->> (d/q '[:find (pull ?p [*])
              :in $ ?s
              :where [?p :pokemon/save ?s]]
            db save-eid)
       (map first)
       vec))

(defn- player-rows
  [db]
  (->> (d/q '[:find (pull ?p [*])
              :where [?p :player/key]]
            db)
       (map first)
       vec))

(defn -main [& args]
  (let [opts (apply hash-map args)
        storage (get opts "--storage")
        system (or (get opts "--system") "odisseu-m90-core")
        db-name (or (get opts "--db") "core")
        out (get opts "--out")]
    (when-not (and storage out)
      (println "Uso: --storage DIR --out file.edn [--system odisseu-m90-core] [--db core]")
      (System/exit 2))
    (let [client (persistence/client {:system system :storage-dir storage})
          ;; ensure-db! conecta; DB já existe na cópia RO — não cria schema novo
          conn (persistence/ensure-db! client db-name)
          db (d/db conn)
          trainers
          (vec
           (keep
            (fn [p]
              (let [parsed (try (id/parse-player-key (:player/key p))
                                (catch Exception _ nil))
                    chat (:chat-id parsed)
                    player-id (:player-id parsed)
                    save (when parsed (active-save db p))]
                (when (and chat player-id save)
                  (let [pokes (collection-for-save db (:db/id save))
                        inv (inventory->map db (:db/id save))
                        coins (long (:save/coins save 0))
                        account {:equipe (mapv poke->map pokes)
                                 :pc []
                                 :enfermaria []
                                 :loja {:moedas coins :inventario inv}
                                 :ginasios {}}]
                    {:player-id player-id
                     :chat-id chat
                     :save-id (:save/slot-id save)
                     :account account}))))
            (player-rows db)))
          payload {:ok true
                   :source :datomic-legacy-ro-copy
                   :system system
                   :db db-name
                   :trainer-count (count trainers)
                   :trainers trainers}]
      (io/make-parents out)
      (spit out (with-out-str (pp/pprint payload)))
      (pp/pprint {:ok true
                  :out out
                  :trainer-count (count trainers)
                  :player-hashes
                  (mapv (fn [t]
                          (subs (reward/payload-hash {:id (:player-id t)}) 0 12))
                        trainers)}))))
