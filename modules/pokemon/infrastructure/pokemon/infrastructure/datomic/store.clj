(ns pokemon.infrastructure.datomic.store
  "Adapter Datomic Local para TrainerStore.
  Efeitos externos (WhatsApp, HTTP, timers) NÃO entram nas transações."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.evolution :as evo]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.reward :as reward]
            [pokemon.domain.xp :as xp]
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
    (let [base {:save-key (:save/key e)
                :slot-id (:save/slot-id e)
                :coins (:save/coins e 0)
                :revision (:save/revision e 0)
                :ativo-index (:save/ativo-index e 0)
                :vitorias-treinador (:save/vitorias-treinador e 0)
                :pe-ginasios (:save/pe-ginasios e 0)
                :pe-raids (:save/pe-raids e 0)
                :xp-insignias (:save/xp-insignias e 0)
                :xp-missoes (:save/xp-missoes e 0)}
          computed (xp/xp-treinador-total base)
          ;; M3.1: componentes são a fonte. M2 apply-reward ainda grava
          ;; :save/trainer-xp|level direto quando não há vitórias/PE.
          stored-xp (long (:save/trainer-xp e 0))
          stored-level (long (:save/trainer-level e 1))
          xp (if (pos? computed) computed stored-xp)
          perfil (xp/progresso-treinador xp)
          nivel (if (pos? computed) (:nivel perfil) stored-level)]
      (assoc base
             :trainer-xp xp
             :trainer-level nivel
             :xp-atual (:xp-atual perfil)
             :xp-necessario (:xp-necessario perfil)
             :sequencia-capturas (long (:save/sequencia-capturas e 0))
             :maior-sequencia-capturas (long (:save/maior-sequencia-capturas e 0))
             :expansoes-pokemon-50 (long (:save/expansoes-pokemon-50 e 0))
             :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada e 0))))))

(defn- edn-read
  [s]
  (when (and s (not (empty? s)))
    (edn/read-string s)))

(defn- ->pokemon
  [e]
  (when (present? e)
    (cond-> {:pokemon-key (:pokemon/key e)
             :id-pokemon (:pokemon/id-pokemon e)
             :species (:pokemon/species e)
             :nome (or (:pokemon/nickname e) (:pokemon/species e))
             :nickname (:pokemon/nickname e)
             :shiny (boolean (:pokemon/shiny e))
             :xp (:pokemon/xp e)
             :level (:pokemon/level e)
             :nivel (:pokemon/level e)
             :in-party (boolean (:pokemon/in-party e))
             :collection-index (:pokemon/collection-index e)
             :hp (:pokemon/hp e)
             :hp-atual (:pokemon/hp-atual e)
             :status (:pokemon/status e)
             :xp-desde-nivel (:pokemon/xp-desde-nivel e 0)
             :amizade (:pokemon/amizade e 70)
             :ataque (:pokemon/ataque e 0)
             :defesa (:pokemon/defesa e 0)
             :atq-esp (:pokemon/atq-esp e 0)
             :def-esp (:pokemon/def-esp e 0)
             :veloc (:pokemon/veloc e 0)
             :tipos (vec (:pokemon/tipos e))
             :raridade (:pokemon/raridade e "comum")
             :golpes (vec (or (edn-read (:pokemon/golpes-edn e)) []))
             :golpes-removidos (vec (or (edn-read (:pokemon/golpes-removidos-edn e)) []))
             :historico-golpes (or (edn-read (:pokemon/historico-golpes-edn e)) {})}
      (:pokemon/habilidade e) (assoc :habilidade (:pokemon/habilidade e))
      (:pokemon/imagem e) (assoc :imagem (:pokemon/imagem e))
      (:pokemon/imagem-shiny e) (assoc :imagem-shiny (:pokemon/imagem-shiny e))
      (:pokemon/ivs-edn e) (assoc :ivs (edn-read (:pokemon/ivs-edn e))))))

(defn- pokemon-entity-map
  "Mapa de atributos Datomic a partir do registro de domínio."
  [sk registro idx]
  (let [id-poke (:id-pokemon registro)
        poke-key (or id-poke (str sk "|poke|" idx))]
    (cond-> {:pokemon/key poke-key
             :pokemon/id-pokemon id-poke
             :pokemon/save [:save/key sk]
             :pokemon/species (or (:species registro) (:nome registro))
             :pokemon/nickname (:nome registro)
             :pokemon/shiny (boolean (:shiny registro))
             :pokemon/xp (long (:xp-desde-nivel registro 0))
             :pokemon/level (long (:nivel registro 1))
             :pokemon/in-party true
             :pokemon/collection-index idx
             :pokemon/hp (long (:hp registro 0))
             :pokemon/hp-atual (long (:hp-atual registro 0))
             :pokemon/xp-desde-nivel (long (:xp-desde-nivel registro 0))
             :pokemon/amizade (long (:amizade registro 70))
             :pokemon/ataque (long (:ataque registro 0))
             :pokemon/defesa (long (:defesa registro 0))
             :pokemon/atq-esp (long (:atq-esp registro 0))
             :pokemon/def-esp (long (:def-esp registro 0))
             :pokemon/veloc (long (:veloc registro 0))
             :pokemon/raridade (or (:raridade registro) "comum")
             :pokemon/golpes-edn (pr-str (or (:golpes registro) []))}
      (seq (:tipos registro)) (assoc :pokemon/tipos (vec (:tipos registro)))
      (:status registro) (assoc :pokemon/status (:status registro))
      (:habilidade registro) (assoc :pokemon/habilidade (:habilidade registro))
      (:imagem registro) (assoc :pokemon/imagem (:imagem registro))
      (:imagem-shiny registro) (assoc :pokemon/imagem-shiny (:imagem-shiny registro))
      (seq (:golpes-removidos registro))
      (assoc :pokemon/golpes-removidos-edn (pr-str (:golpes-removidos registro)))
      (seq (:historico-golpes registro))
      (assoc :pokemon/historico-golpes-edn (pr-str (:historico-golpes registro)))
      (:ivs registro) (assoc :pokemon/ivs-edn (pr-str (:ivs registro))))))

(defn- inventory-adjust-tx
  "Retorna tx-data para delta de item; lança se qty ficaria negativa."
  [db sk item-id delta]
  (let [ik (id/inventory-item-key sk item-id)
        cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])
        old-qty (long (:inventory-item/qty cur 0))
        next-qty (+ old-qty (long delta))]
    (when (neg? next-qty)
      (throw (err/error :insufficient_item
                        {:item-id item-id :qty old-qty :delta delta})))
    [{:inventory-item/key ik
      :inventory-item/save [:save/key sk]
      :inventory-item/item-id item-id
      :inventory-item/qty next-qty}]))

(defn- apply-pokemon-xp-tx
  "TX fragments para conceder XP a um Pokémon (por id-pokemon)."
  [poke quantidade]
  (let [applied (xp/aplicar-xp-pokemon poke quantidade)
        r (:registro applied)
        lookup [:pokemon/id-pokemon (:id-pokemon poke)]]
    {:tx [[:db/add lookup :pokemon/level (long (:nivel r))]
          [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
          [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
          [:db/add lookup :pokemon/hp (long (:hp r))]
          [:db/add lookup :pokemon/hp-atual (long (:hp-atual r))]
          [:db/add lookup :pokemon/ataque (long (:ataque r))]
          [:db/add lookup :pokemon/defesa (long (:defesa r))]
          [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
          [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
          [:db/add lookup :pokemon/veloc (long (:veloc r))]
          [:db/add lookup :pokemon/amizade (long (:amizade r))]]
     :subida (when (pos? (:subidas applied))
               {:nome (:nome applied)
                :nivel (:nivel-final applied)
                :niveis-subidos (:subidas applied)})}))

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
       (sort-by :collection-index)
       vec))

(defn- query-collection
  [db save-key*]
  (->> (d/q '[:find (pull ?p [*])
              :in $ ?sk
              :where
              [?s :save/key ?sk]
              [?p :pokemon/save ?s]
              [?p :pokemon/collection-index]]
            db save-key*)
       (map (comp ->pokemon first))
       (sort-by :collection-index)
       vec))

(defn- writer-fence-tx
  "CAS que exige save ativo + fence congelados (operações iniciadas em A
  falham após alternância para B)."
  [chat-id player-id writer-ctx]
  (let [pk (id/player-key chat-id player-id)]
    [[:db/cas [:player/key pk] :player/fence-generation
      (:fence-generation writer-ctx) (:fence-generation writer-ctx)]
     [:db/cas [:player/key pk] :player/active-save-id
      (:save-id writer-ctx) (:save-id writer-ctx)]]))

(defn- transact-with-writer!
  [conn writer-ctx build-tx]
  (let [{:keys [chat-id player-id save-id]} writer-ctx]
    (loop [attempt 0]
      (let [db (d/db conn)
            player (player-entity db chat-id player-id)
            save (save-entity db chat-id player-id save-id)]
        (when-not (present? player)
          (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
        (when-not (present? save)
          (throw (err/error :save_not_found {:save-id save-id})))
        (when (or (not= (:player/active-save-id player) save-id)
                  (not= (:player/fence-generation player) (:fence-generation writer-ctx)))
          (throw (err/error :stale_writer_fence
                            {:expected writer-ctx
                             :active (:player/active-save-id player)
                             :fence (:player/fence-generation player)})))
        (let [built (build-tx db save)
              tx (into (writer-fence-tx chat-id player-id writer-ctx) (:tx built))
              result (try
                       (d/transact conn {:tx-data tx})
                       {:ok true :value (:result built)}
                       (catch Exception ex
                         {:ok false :ex ex}))]
          (if (:ok result)
            (:value result)
            (let [ex (:ex result)]
              (if (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                (recur (inc attempt))
                (throw (err/error :writer_fence_conflict
                                  {:cause (ex-message ex)
                                   :attempt attempt
                                   :writer writer-ctx}))))))))))

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
     :channel (:operation-receipt/channel e)
     :payload-hash (:operation-receipt/payload-hash e)
     :player-key (:operation-receipt/player-key e)
     :save-id (:operation-receipt/save-id e)
     :summary (when-let [s (:operation-receipt/summary e)]
                (edn/read-string s))
     :duplicate true}))

(defn- resolve-receipt
  "Idempotência: mesmo hash → duplicate; hash diferente → erro."
  [e expected-hash operation-id]
  (when (present? e)
    (reward/assert-receipt-payload!
     (:operation-receipt/payload-hash e) expected-hash operation-id)
    (->receipt e)))

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
  [db request save channel payload-hash]
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
               :operation-receipt/channel channel
               :operation-receipt/payload-hash payload-hash
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
        with-item (if-not (and item-id (not (zero? item-qty)))
                    with-poke
                    (let [ik (id/inventory-item-key sk item-id)
                          cur (d/pull db '[:inventory-item/qty] [:inventory-item/key ik])
                          old-qty (long (:inventory-item/qty cur 0))
                          next-qty (+ old-qty item-qty)]
                      (when (neg? next-qty)
                        (throw (err/error :insufficient_item
                                          {:item-id item-id :qty old-qty :delta item-qty})))
                      (conj with-poke
                            {:inventory-item/key ik
                             :inventory-item/save [:save/key sk]
                             :inventory-item/item-id item-id
                             :inventory-item/qty next-qty})))]
    {:tx with-item :summary summary}))

(defn- admin-seed-reward!*
  "Seed/harness: sem fence; com payload-hash."
  [conn request]
  (let [payload (reward/reward-payload request)
        phash (reward/payload-hash payload)
        {:keys [operation-id chat-id player-id save-id]} request
        existing (receipt-entity (d/db conn) operation-id)]
    (or (resolve-receipt existing phash operation-id)
        (loop [attempt 0]
          (let [db (d/db conn)
                again (receipt-entity db operation-id)]
            (or (resolve-receipt again phash operation-id)
                (let [save (save-entity db chat-id player-id save-id)
                      _ (when-not (present? save)
                          (throw (err/error :save_not_found {:save-id save-id})))
                      {:keys [tx summary]} (reward-tx-data db request save "admin" phash)
                      result (try
                               (d/transact conn {:tx-data tx})
                               {:ok true
                                :value {:operation-id operation-id
                                        :duplicate false
                                        :channel "admin"
                                        :payload-hash phash
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
                        (resolve-receipt dup phash operation-id)

                        (and (anomaly-conflict? ex) (< attempt max-cas-attempts))
                        (recur (inc attempt))

                        :else
                        (throw (err/error :reward_conflict
                                          {:cause (ex-message ex)
                                           :operation-id operation-id}))))))))))))

(defn- apply-gameplay-reward!*
  "Gameplay: fence+ativo na mesma TX; payload-hash no recibo."
  [conn writer-ctx request]
  (let [full (merge request
                    (select-keys writer-ctx
                                 [:chat-id :player-id :save-id :fence-generation]))
        payload (reward/reward-payload full)
        phash (reward/payload-hash payload)
        operation-id (:operation-id full)
        existing (receipt-entity (d/db conn) operation-id)]
    (or (resolve-receipt existing phash operation-id)
        (try
          (transact-with-writer!
           conn writer-ctx
           (fn [db save]
             (let [{:keys [tx summary]} (reward-tx-data db full save "gameplay" phash)]
               {:tx tx
                :result {:operation-id operation-id
                         :duplicate false
                         :channel "gameplay"
                         :payload-hash phash
                         :summary summary}})))
          (catch Exception ex
            (let [dup (receipt-entity (d/db conn) operation-id)]
              (if (present? dup)
                (resolve-receipt dup phash operation-id)
                (throw ex))))))))

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
                                   :save/revision (:revision init)
                                   :save/ativo-index 0
                                   :save/vitorias-treinador 0
                                   :save/pe-ginasios 0
                                   :save/pe-raids 0
                                   :save/xp-insignias 0
                                   :save/xp-missoes 0}]})
      (->save (save-entity (d/db conn) chat-id player-id save-id))))

  (get-player [_ chat-id player-id]
    (->player (player-entity (d/db conn) chat-id player-id)))

  (get-save [_ chat-id player-id save-id]
    (->save (save-entity (d/db conn) chat-id player-id save-id)))

  (list-party [_ chat-id player-id save-id]
    (query-party (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-collection [_ chat-id player-id save-id]
    (query-collection (d/db conn) (id/save-key chat-id player-id save-id)))

  (list-inventory [_ chat-id player-id save-id]
    (query-inventory (d/db conn) (id/save-key chat-id player-id save-id)))

  (active-save-id [_ chat-id player-id]
    (:player/active-save-id (player-entity (d/db conn) chat-id player-id)))

  (activate-save! [_ chat-id player-id save-id]
    (activate-save!* conn chat-id player-id save-id))

  (apply-gameplay-reward! [_ writer-ctx request]
    (let [result (apply-gameplay-reward!* conn writer-ctx request)]
      (if (:duplicate result)
        result
        (assoc result
               :save (->save (save-entity (d/db conn)
                                          (:chat-id writer-ctx)
                                          (:player-id writer-ctx)
                                          (:save-id writer-ctx)))))))

  (admin-seed-reward! [_ request]
    (admin-seed-reward!* conn request))

  (add-to-collection! [_ writer-ctx registro]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             idx (coll/next-index colecao)
             entity (pokemon-entity-map sk registro idx)
             rev (long (:save/revision save 0))]
         {:tx [entity
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:destino :equipe
                   :indice idx
                   :id-pokemon (:id-pokemon registro)
                   :registro (assoc registro :collection-index idx)}}))))

  (update-hp-status! [_ writer-ctx idx hp-atual status]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             _ (coll/validate-index! idx (count colecao))
             poke (nth colecao idx)
             status-str (xp/status->str status)
             lookup [:pokemon/id-pokemon (:id-pokemon poke)]
             rev (long (:save/revision save 0))
             tx (cond-> [[:db/add lookup :pokemon/hp-atual (long hp-atual)]
                         [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
                  status-str (conj [:db/add lookup :pokemon/status status-str])
                  (and (nil? status-str) (:status poke))
                  (conj [:db/retract lookup :pokemon/status (:status poke)]))]
         {:tx tx
          :result {:ok true :idx idx :hp-atual (long hp-atual) :status status-str}}))))

  (grant-pokemon-xp! [_ writer-ctx idx quantidade]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             colecao (query-collection db sk)
             _ (coll/validate-index! idx (count colecao))
             poke (nth colecao idx)
             applied (xp/aplicar-xp-pokemon poke quantidade)
             r (:registro applied)
             lookup [:pokemon/id-pokemon (:id-pokemon poke)]
             rev (long (:save/revision save 0))
             tx [[:db/add lookup :pokemon/level (long (:nivel r))]
                 [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
                 [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
                 [:db/add lookup :pokemon/hp (long (:hp r))]
                 [:db/add lookup :pokemon/hp-atual (long (:hp-atual r))]
                 [:db/add lookup :pokemon/ataque (long (:ataque r))]
                 [:db/add lookup :pokemon/defesa (long (:defesa r))]
                 [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
                 [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
                 [:db/add lookup :pokemon/veloc (long (:veloc r))]
                 [:db/add lookup :pokemon/amizade (long (:amizade r))]
                 [:db/cas [:save/key sk] :save/revision rev (inc rev)]]]
         {:tx tx
          :result (when (pos? (:subidas applied))
                    {:nome (:nome applied)
                     :nivel (:nivel-final applied)
                     :niveis-subidos (:subidas applied)})}))))

  (register-trainer-victory! [_ writer-ctx]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             v (long (:save/vitorias-treinador save 0))
             rev (long (:save/revision save 0))]
         {:tx [[:db/add [:save/key sk] :save/vitorias-treinador (inc v)]
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:vitorias (inc v)}}))))

  (attempt-capture! [_ writer-ctx request]
    (let [payload (ops/capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     save-m (->save save)
                     colecao (query-collection db sk)
                     bola (:bola request)
                     pokemon (:pokemon request)
                     _ (when-not (capacity/cabe-pokemon? (count colecao) save-m)
                         (throw (err/error :collection_full
                                           {:count (count colecao)
                                            :nivel (:trainer-level save-m)})))
                     ball-tx (inventory-adjust-tx db sk bola -1)
                     attempt (capture/attempt-capture
                              {:pokemon pokemon
                               :status (:status request)
                               :sequencia-capturas (:sequencia-capturas save-m 0)
                               :bola bola
                               :tentativas-anteriores (:tentativas-anteriores request 0)
                               :roll (:roll request)
                               :fuga-roll (:fuga-roll request)})
                     rev (long (:save/revision save 0))
                     seq-atual (long (:sequencia-capturas save-m 0))
                     maior (long (:maior-sequencia-capturas save-m 0))
                     coins (long (:save/coins save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     base-tx (into ball-tx
                                   [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                    {:operation-receipt/id operation-id
                                     :operation-receipt/kind "capture"
                                     :operation-receipt/channel "gameplay"
                                     :operation-receipt/payload-hash phash
                                     :operation-receipt/player-key pk
                                     :operation-receipt/save-id (:save-id writer-ctx)
                                     :operation-receipt/summary
                                     (pr-str {:capturou? (:capturou? attempt)
                                              :fugiu? (:fugiu? attempt)
                                              :bola bola
                                              :chance (:chance attempt)})}])
                     resultado
                     (cond
                       (:capturou? attempt)
                       (let [registro (coll/pokemon->registro pokemon
                                                              (or (:hp pokemon) (:hp-atual pokemon) 0)
                                                              nil)
                             idx (coll/next-index colecao)
                             entity (pokemon-entity-map sk registro idx)
                             seq-nova (inc seq-atual)
                             rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova seq-nova
                                   :primeira-tentativa? (zero? (long (:tentativas-anteriores request 0)))
                                   :capturou? true})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             tx (cond-> (into base-tx
                                              [entity
                                               [:db/add [:save/key sk] :save/sequencia-capturas seq-nova]
                                               [:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                (max maior seq-nova)]
                                               [:db/cas [:save/key sk] :save/coins coins
                                                (model/apply-coin-delta coins (:moedas rew))]
                                               {:pokedex-entry/key (id/pokedex-entry-key sk (:species registro))
                                                :pokedex-entry/save [:save/key sk]
                                                :pokedex-entry/species (:species registro)
                                                :pokedex-entry/seen true
                                                :pokedex-entry/caught true}])
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :capturou? true
                                   :fugiu? false
                                   :chance (:chance attempt)
                                   :indice idx
                                   :id-pokemon (:id-pokemon registro)
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :registro (assoc registro :collection-index idx)}})

                       (:fugiu? attempt)
                       (let [rew (capture/recompensa-pos-captura
                                  {:raridade (or (:raridade pokemon) "comum")
                                   :sequencia-nova 0
                                   :primeira-tentativa? false
                                   :capturou? false})
                             ativo-idx (min (long (:ativo-index save-m 0))
                                            (max 0 (dec (count colecao))))
                             ativo (when (seq colecao) (nth colecao ativo-idx))
                             xp-part (when (and ativo (pos? (:xp rew)))
                                       (apply-pokemon-xp-tx ativo (:xp rew)))
                             tx (cond-> (into base-tx
                                              [[:db/add [:save/key sk] :save/maior-sequencia-capturas
                                                (max maior seq-atual)]
                                               [:db/add [:save/key sk] :save/sequencia-capturas 0]])
                                  xp-part (into (:tx xp-part)))]
                         {:tx tx
                          :result {:operation-id operation-id
                                   :duplicate false
                                   :channel "gameplay"
                                   :payload-hash phash
                                   :capturou? false
                                   :fugiu? true
                                   :chance (:chance attempt)
                                   :recompensa rew
                                   :subida (:subida xp-part)}})

                       :else
                       {:tx base-tx
                        :result {:operation-id operation-id
                                 :duplicate false
                                 :channel "gameplay"
                                 :payload-hash phash
                                 :capturou? false
                                 :fugiu? false
                                 :chance (:chance attempt)
                                 :tentativa (:tentativa attempt)}})]
                 resultado)))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (evolve-pokemon! [_ writer-ctx request]
    (let [payload (ops/evolve-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     evoluido (evo/aplicar-evolucao poke (:dados request))
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     item-tx (when-let [item-id (:item-id request)]
                               (inventory-adjust-tx db sk item-id -1))
                     old-tipos (set (:tipos poke))
                     new-tipos (set (:tipos evoluido))
                     tipo-tx (concat
                              (map (fn [t] [:db/retract lookup :pokemon/tipos t])
                                   (remove new-tipos old-tipos))
                              (map (fn [t] [:db/add lookup :pokemon/tipos t])
                                   (remove old-tipos new-tipos)))
                     tx (into (or item-tx [])
                              (concat
                               tipo-tx
                               [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                [:db/add lookup :pokemon/species (:species evoluido)]
                                [:db/add lookup :pokemon/nickname (:nome evoluido)]
                                [:db/add lookup :pokemon/hp (long (:hp evoluido))]
                                [:db/add lookup :pokemon/hp-atual (long (:hp-atual evoluido))]
                                [:db/add lookup :pokemon/ataque (long (:ataque evoluido))]
                                [:db/add lookup :pokemon/defesa (long (:defesa evoluido))]
                                [:db/add lookup :pokemon/atq-esp (long (:atq-esp evoluido))]
                                [:db/add lookup :pokemon/def-esp (long (:def-esp evoluido))]
                                [:db/add lookup :pokemon/veloc (long (:veloc evoluido))]
                                [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes evoluido))]
                                {:operation-receipt/id operation-id
                                 :operation-receipt/kind "evolve"
                                 :operation-receipt/channel "gameplay"
                                 :operation-receipt/payload-hash phash
                                 :operation-receipt/player-key pk
                                 :operation-receipt/save-id (:save-id writer-ctx)
                                 :operation-receipt/summary
                                 (pr-str {:idx idx :nome-novo (:nome evoluido)})}]))
                     tx (cond-> tx
                          (:habilidade evoluido)
                          (conj [:db/add lookup :pokemon/habilidade (:habilidade evoluido)])
                          (:imagem evoluido)
                          (conj [:db/add lookup :pokemon/imagem (:imagem evoluido)])
                          (:imagem-shiny evoluido)
                          (conj [:db/add lookup :pokemon/imagem-shiny (:imagem-shiny evoluido)]))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :registro evoluido}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (learn-move! [_ writer-ctx request]
    (let [payload (ops/learn-move-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     colecao (query-collection db sk)
                     idx (long (:idx request))
                     _ (coll/validate-index! idx (count colecao))
                     poke (nth colecao idx)
                     atualizado (moves/preparar-aprendizado poke (:golpe request) (:slot request))
                     rev (long (:save/revision save 0))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     lookup [:pokemon/id-pokemon (:id-pokemon poke)]
                     tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                         [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes atualizado))]
                         [:db/add lookup :pokemon/historico-golpes-edn
                          (pr-str (:historico-golpes atualizado))]
                         [:db/add lookup :pokemon/golpes-removidos-edn
                          (pr-str (:golpes-removidos atualizado))]
                         {:operation-receipt/id operation-id
                          :operation-receipt/kind "learn-move"
                          :operation-receipt/channel "gameplay"
                          :operation-receipt/payload-hash phash
                          :operation-receipt/player-key pk
                          :operation-receipt/save-id (:save-id writer-ctx)
                          :operation-receipt/summary
                          (pr-str {:idx idx :slug (moves/chave (:golpe request))})}]]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :idx idx
                           :registro atualizado}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

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
