(ns pokemon.infrastructure.datomic.store
  "Adapter Datomic Local para TrainerStore.
  Efeitos externos (WhatsApp, HTTP, timers) NÃO entram nas transações."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.infrastructure.datomic.schema :as schema]))

(def ^:private max-cas-attempts 12)

(defn- anomaly-conflict?
  [ex]
  (err/conflict? ex))

(defn- player-entity
  [db chat-id player-id]
  (d/pull db '[*] [:player/key (id/player-key chat-id player-id)]))

(defn- save-entity
  [db chat-id player-id save-id]
  (d/pull db '[*] [:save/key (id/save-key chat-id player-id save-id)]))

(defn- present?
  [entity]
  (boolean (:db/id entity)))

(defn- ->player
  [e]
  (when (present? e)
    {:chat-id (:player/chat-id e)
     :player-id (:player/player-id e)
     :player-key (:player/key e)
     :active-save-id (:player/active-save-id e)
     :fence-generation (:player/fence-generation e)}))

(defn- ->save
  [e]
  (when (present? e)
    {:save-key (:save/key e)
     :slot-id (:save/slot-id e)
     :coins (:save/coins e)
     :trainer-xp (:save/trainer-xp e)
     :trainer-level (:save/trainer-level e)
     :revision (:save/revision e)}))

(defn- ->pokemon
  [e]
  {:pokemon-key (:pokemon/key e)
   :species (:pokemon/species e)
   :nickname (:pokemon/nickname e)
   :shiny (boolean (:pokemon/shiny e))
   :xp (:pokemon/xp e)
   :level (:pokemon/level e)
   :in-party (boolean (:pokemon/in-party e))})

(defn- ->item
  [e]
  {:item-id (:inventory-item/item-id e)
   :qty (:inventory-item/qty e)})

(defn- query-party
  [db save-key*]
  (->> (d/q '[:find (pull ?p [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?p :pokemon/save ?s]
              [?p :pokemon/in-party true]]
            db save-key*)
       (map (comp ->pokemon first))
       vec))

(defn- query-inventory
  [db save-key*]
  (->> (d/q '[:find (pull ?i [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?i :inventory-item/save ?s]]
            db save-key*)
       (map (comp ->item first))
       vec))

(defn- receipt-entity
  [db operation-id]
  (d/pull db '[*] [:operation-receipt/id operation-id]))

(defn- ->receipt
  [e]
  (when (present? e)
    {:operation-id (:operation-receipt/id e)
     :kind (:operation-receipt/kind e)
     :player-key (:operation-receipt/player-key e)
     :save-id (:operation-receipt/save-id e)
     :summary (when-let [s (:operation-receipt/summary e)]
                (edn/read-string s))
     :duplicate true}))

(defn- activate-save!*
  [conn chat-id player-id save-id]
  (model/validate-save-slot! save-id)
  (let [pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)]
    (loop [attempt 0]
      (let [db (d/db conn)
            player (player-entity db chat-id player-id)
            save (save-entity db chat-id player-id save-id)
            _ (when-not (present? player)
                (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
            _ (when-not (present? save)
                (throw (err/error :save_not_found {:save-id save-id})))
            decision (model/assert-activate
                      {:active-save-id (:player/active-save-id player)
                       :fence-generation (:player/fence-generation player)}
                      save-id)]
        (if (= :noop (:action decision))
          {:status :already-active
           :active-save-id save-id
           :fence-generation (:fence-generation decision)}
          (let [expected-fence (if (= :set-initial (:action decision))
                                 0
                                 (:expected-fence decision))
                next-fence (:next-fence decision)
                tx [[:db/cas [:player/key pk] :player/fence-generation
                     expected-fence next-fence]
                    [:db/add [:player/key pk] :player/active-save-id save-id]]
                result (try
                         {:ok true
                          :value (do
                                   (d/transact conn {:tx-data tx})
                                   {:status (if (= :set-initial (:action decision))
                                              :activated :switched)
                                    :active-save-id save-id
                                    :from (:from decision)
                                    :fence-generation next-fence
                                    :save-key sk})}
                         (catch Exception ex
                           {:ok false :ex ex}))]
            (if (:ok result)
              (:value result)
              (let [ex (:ex result)]
                (if (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                  (recur (inc attempt))
                  (throw (err/error :activate_conflict
                                    {:cause (ex-message ex)
                                     :attempt attempt
                                     :target save-id})))))))))))

(defn- reward-tx-data
  [db request save]
  (let [{:keys [operation-id chat-id player-id save-id
                coins-delta pokemon item]} request
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)
        coins (long (:save/coins save 0))
        rev (long (:save/revision save 0))
        xp (long (:save/trainer-xp save 0))
        level (long (:save/trainer-level save 1))
        delta (long (or coins-delta 0))
        xp-delta (long (or (:trainer-xp-delta request) 0))
        next-coins (model/apply-coin-delta coins delta)
        next-xp (+ xp xp-delta)
        next-level (long (or (:trainer-level request) level))
        poke pokemon
        poke-key (or (:key poke) (when poke (str operation-id ":pokemon")))
        item-id (:item-id item)
        item-qty (long (or (:qty item) 0))
        summary (model/reward-summary
                 {:coins-delta delta
                  :pokemon-key poke-key
                  :species (:species poke)
                  :shiny (:shiny poke)
                  :item-id item-id
                  :item-qty item-qty})
        base [{:operation-receipt/id operation-id
               :operation-receipt/kind "reward"
               :operation-receipt/player-key pk
               :operation-receipt/save-id save-id
               :operation-receipt/summary (pr-str summary)}
              [:db/cas [:save/key sk] :save/coins coins next-coins]
              [:db/cas [:save/key sk] :save/revision rev (inc rev)]
              [:db/add [:save/key sk] :save/trainer-xp next-xp]
              [:db/add [:save/key sk] :save/trainer-level next-level]]
        with-poke (if-not poke
                    base
                    (conj base
                          {:pokemon/key poke-key
                           :pokemon/save [:save/key sk]
                           :pokemon/species (:species poke)
                           :pokemon/nickname (or (:nickname poke) (:species poke))
                           :pokemon/shiny (boolean (:shiny poke))
                           :pokemon/xp (long (or (:xp poke) 0))
                           :pokemon/level (long (or (:level poke) 1))
                           :pokemon/in-party (boolean (get poke :in-party true))}
                          {:pokedex-entry/key (id/pokedex-entry-key sk (:species poke))
                           :pokedex-entry/save [:save/key sk]
                           :pokedex-entry/species (:species poke)
                           :pokedex-entry/seen true
                           :pokedex-entry/caught true}))
        with-item (if-not (and item-id (pos? item-qty))
                    with-poke
                    (let [ik (id/inventory-item-key sk item-id)
                          cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])
                          old-qty (long (:inventory-item/qty cur 0))]
                      (conj with-poke
                            {:inventory-item/key ik
                             :inventory-item/save [:save/key sk]
                             :inventory-item/item-id item-id
                             :inventory-item/qty (+ old-qty item-qty)})))]
    {:tx with-item :summary summary}))

(defn- apply-reward!*
  [conn request]
  (let [{:keys [operation-id chat-id player-id save-id]} request
        existing (receipt-entity (d/db conn) operation-id)]
    (if (present? existing)
      (->receipt existing)
      (loop [attempt 0]
        (let [db (d/db conn)
              again (receipt-entity db operation-id)]
          (if (present? again)
            (->receipt again)
            (let [save (save-entity db chat-id player-id save-id)
                  _ (when-not (present? save)
                      (throw (err/error :save_not_found {:save-id save-id})))
                  {:keys [tx summary]} (reward-tx-data db request save)
                  result (try
                           (d/transact conn {:tx-data tx})
                           {:ok true
                            :value {:operation-id operation-id
                                    :duplicate false
                                    :summary summary
                                    :save (->save (save-entity (d/db conn) chat-id player-id save-id))}}
                           (catch Exception ex
                             {:ok false :ex ex}))]
              (if (:ok result)
                (:value result)
                (let [ex (:ex result)
                      dup (receipt-entity (d/db conn) operation-id)]
                  (cond
                    (present? dup)
                    (->receipt dup)

                    (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                    (recur (inc attempt))

                    :else
                    (throw (err/error :reward_conflict
                                      {:cause (ex-message ex)
                                       :operation-id operation-id}))))))))))))

(defrecord DatomicTrainerStore [conn]
  ports/TrainerStore

  (ensure-schema! [_]
    (schema/install! conn)
    :ok)

  (create-player! [_ chat-id player-id]
    (let [pk (id/player-key chat-id player-id)
          db (d/db conn)]
      (if (present? (player-entity db chat-id player-id))
        (->player (player-entity (d/db conn) chat-id player-id))
        (do
          (d/transact conn {:tx-data [{:player/key pk
                                       :player/chat-id chat-id
                                       :player/player-id player-id
                                       :player/fence-generation 0}]})
          (->player (player-entity (d/db conn) chat-id player-id))))))

  (create-save! [_ chat-id player-id save-id]
    (model/validate-save-slot! save-id)
    (let [pk (id/player-key chat-id player-id)
          sk (id/save-key chat-id player-id save-id)
          init (model/initial-save save-id)
          db (d/db conn)]
      (when-not (present? (player-entity db chat-id player-id))
        (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
      (when (present? (save-entity db chat-id player-id save-id))
        (throw (err/error :save_already_exists {:save-id save-id})))
      (d/transact conn {:tx-data [{:save/key sk
                                   :save/slot-id save-id
                                   :save/player [:player/key pk]
                                   :save/coins (:coins init)
                                   :save/trainer-xp (:trainer-xp init)
                                   :save/trainer-level (:trainer-level init)
                                   :save/revision (:revision init)}]})
      (->save (save-entity (d/db conn) chat-id player-id save-id))))

  (get-player [_ chat-id player-id]
    (->player (player-entity (d/db conn) chat-id player-id)))

  (get-save [_ chat-id player-id save-id]
    (->save (save-entity (d/db conn) chat-id player-id save-id)))

  (list-party [_ chat-id player-id save-id]
    (query-party (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-inventory [_ chat-id player-id save-id]
    (query-inventory (d/db conn) (id/save-key chat-id player-id save-id)))

  (active-save-id [_ chat-id player-id]
    (:player/active-save-id (player-entity (d/db conn) chat-id player-id)))

  (activate-save! [_ chat-id player-id save-id]
    (activate-save!* conn chat-id player-id save-id))

  (apply-reward! [_ request]
    (apply-reward!* conn request))

  (ensure-group! [_ chat-id label]
    (let [db (d/db conn)
          e (d/pull db '[*] [:group/chat-id chat-id])]
      (if (present? e)
        {:chat-id (:group/chat-id e) :label (:group/label e)}
        (do
          (d/transact conn {:tx-data [{:group/chat-id chat-id
                                       :group/label (or label "grupo-ficticio")}]})
          {:chat-id chat-id :label (or label "grupo-ficticio")}))))

  (get-group [_ chat-id]
    (let [e (d/pull (d/db conn) '[*] [:group/chat-id chat-id])]
      (when (present? e)
        {:chat-id (:group/chat-id e) :label (:group/label e)}))))

(defn make-store
  "Constrói store a partir de uma conexão Datomic já aberta."
  [conn]
  (->DatomicTrainerStore conn))
