(ns pokemon.infrastructure.datomic.store
  "Adapter Datomic Local para TrainerStore.
  Efeitos externos (WhatsApp, HTTP, timers) NÃO entram nas transações."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.evolution :as evo]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.onboarding :as onboard]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.raid :as raid-domain]
            [pokemon.domain.reward :as reward]
            [pokemon.domain.xp :as xp]
            [pokemon.domain.status :as status]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]
            [pokemon.infrastructure.datomic.gym-store :as gym-store]
            [pokemon.infrastructure.datomic.onboarding-store :as onboarding-store]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]
            [pokemon.infrastructure.datomic.shop-store :as shop-store]
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

(defn- edn-read
  [s]
  (when (and s (not (empty? s)))
    (edn/read-string s)))

(defn- ->player
  [e]
  (when (present? e)
    {:chat-id (:player/chat-id e)
     :player-id (:player/player-id e)
     :player-key (:player/key e)
     :active-save-id (:player/active-save-id e)
     :fence-generation (:player/fence-generation e)
     :active-battle-id (:player/active-battle-id e)
     :gym-defense-count (long (:player/gym-defense-count e 0))}))

(defn- battle-entity
  [db battle-id]
  (d/pull db '[*] [:battle/id battle-id]))

(defn- active-battle-id-for
  [db chat-id player-id]
  (:player/active-battle-id (player-entity db chat-id player-id)))

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
             :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada e 0))
             :expansoes-mochila-50 (long (:save/expansoes-mochila-50 e 0))
             :capacidade-mochila-legada (long (:save/capacidade-mochila-legada e 50))
             :ultima-cacada-at (long (:save/ultima-cacada-at e 0))
             :kit-inicial-resgatado (boolean (:save/kit-inicial-resgatado e))
             :inicial-escolhido (boolean (:save/inicial-escolhido e))
             :ginasios (or (edn-read (:save/ginasios-edn e)) {})
             :time-ginasio (or (edn-read (:save/time-ginasio-edn e)) [])))))

(defn- ->battle
  [e]
  (when (present? e)
    (merge (or (edn-read (:battle/state-edn e)) {})
           {:battle-id (:battle/id e)
            :kind (keyword (:battle/kind e "wild"))
            :active? (boolean (:battle/active? e))
            :turn-index (long (:battle/turn-index e 0))
            :phase (keyword (:battle/phase e "active"))
            :save-id (:battle/save-id e)
            :collection-index (:battle/collection-index e 0)})))

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
             :location (or (:pokemon/location e)
                           (when (:pokemon/in-party e) "equipe")
                           "equipe")
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
      (:pokemon/ivs-edn e) (assoc :ivs (edn-read (:pokemon/ivs-edn e)))
      (:pokemon/ofertas-golpes-edn e)
      (assoc :golpes-oferecidos (edn-read (:pokemon/ofertas-golpes-edn e)))
      (some? (:pokemon/pronto-em e)) (assoc :pronto-em (:pokemon/pronto-em e))
      (:pokemon/item e) (assoc :item (:pokemon/item e))
      (some? (:pokemon/altura e)) (assoc :altura (:pokemon/altura e))
      (some? (:pokemon/peso e)) (assoc :peso (:pokemon/peso e))
      (some? (:pokemon/taxa-captura e)) (assoc :taxa-captura (:pokemon/taxa-captura e))
      (:pokemon/legado-edn e) (assoc :legado (edn-read (:pokemon/legado-edn e)))
      (:pokemon/origin-edn e) (assoc :origin-edn (:pokemon/origin-edn e)))))

(defn- pokemon-entity-map
  "Mapa de atributos Datomic a partir do registro de domínio.
   Joy collect / readmit: location=equipe; não copia pronto-em."
  [sk registro idx]
  (let [id-poke (:id-pokemon registro)
        poke-key (or id-poke (str sk "|poke|" idx))]
    (cond-> {:pokemon/key poke-key
             :pokemon/id-pokemon id-poke
             :pokemon/save [:save/key sk]
             :pokemon/species (or (:species registro) (:nome registro))
             :pokemon/nickname (or (:nome registro) (:nickname registro)
                                   (:species registro))
             :pokemon/shiny (boolean (:shiny registro))
             :pokemon/xp (long (:xp-desde-nivel registro 0))
             :pokemon/level (long (or (:nivel registro) (:level registro) 1))
             :pokemon/in-party true
             :pokemon/location "equipe"
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
      (:ivs registro) (assoc :pokemon/ivs-edn (pr-str (:ivs registro)))
      (:origin-edn registro) (assoc :pokemon/origin-edn (:origin-edn registro))
      (seq (:legado registro)) (assoc :pokemon/legado-edn (pr-str (:legado registro)))
      (number? (:altura registro)) (assoc :pokemon/altura (double (:altura registro)))
      (number? (:peso registro)) (assoc :pokemon/peso (double (:peso registro)))
      (number? (:taxa-captura registro))
      (assoc :pokemon/taxa-captura (long (:taxa-captura registro)))
      (and (contains? registro :item) (some? (:item registro)))
      (assoc :pokemon/item (str (:item registro))))))

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

(defn- pokemon-hp-status-tx
  [poke hp-atual st]
  (when poke
    (let [lookup [:pokemon/id-pokemon (:id-pokemon poke)]
          st-str (xp/status->str st)]
      (cond-> [[:db/add lookup :pokemon/hp-atual (long hp-atual)]]
        st-str (conj [:db/add lookup :pokemon/status st-str])
        (and (nil? st-str) (:status poke))
        (conj [:db/retract lookup :pokemon/status (:status poke)])))))

(defn- sync-colecao-from-gym-battle
  "Atualiza HP/status em memória a partir do estado 3×3 (antes do occupy)."
  [colecao battle]
  (let [fainted (set (map long (or (:fainted-x battle) #{})))
        active-idx (when-let [i (get-in battle [:indices-ativos :x])] (long i))
        hp-x (long (get-in battle [:hp :x] 0))
        st-x (get-in battle [:status :x])]
    (mapv (fn [poke]
            (let [idx (long (:collection-index poke))]
              (cond
                (contains? fainted idx)
                (assoc poke :hp-atual 0 :status nil)
                (= idx active-idx)
                (assoc poke :hp-atual hp-x :status (status/->kw st-x))
                :else poke)))
          colecao)))

(defn- gym-battle-hp-txs
  [colecao battle]
  (let [synced (sync-colecao-from-gym-battle colecao battle)]
    (mapcat (fn [before after]
              (when (not= (:hp-atual before) (:hp-atual after))
                (pokemon-hp-status-tx after (:hp-atual after) (:status after))))
            colecao
            synced)))

(defn- scale-npc-team
  [candidates gym]
  (mapv (fn [raw]
          (let [base (-> raw
                         (update :golpes #(or % []))
                         (assoc :status nil))
                scaled (gym/escalar-nivel base (:nivel gym))]
            (assoc scaled :hp-atual (:hp scaled))))
        candidates))

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
            active-bid (:player/active-battle-id player)
            _ (when (and active-bid
                         (not= (:player/active-save-id player) save-id))
                (let [b (battle-entity db active-bid)
                      state (when (present? b) (->battle b))]
                  (when (and state (or (:active? state) (:aguardando-captura? state)))
                    (throw (err/error :battle_pending
                                      {:battle-id active-bid
                                       :save-id (:battle/save-id b)
                                       :aguardando-captura? (boolean (:aguardando-captura? state))})))))
            _ (when (and (pos? (long (:player/gym-defense-count player 0)))
                         (not= (:player/active-save-id player) save-id))
                (throw (err/error :gym_defense_pending
                                  {:count (:player/gym-defense-count player)
                                   :active-save (:player/active-save-id player)})))
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

(defn- load-raid
  [db raid-id]
  (let [e (d/pull db '[*] [:raid/id raid-id])]
    (when (present? e)
      (let [parts (->> (d/q '[:find (pull ?p [*])
                              :in $ ?rid
                              :where [?p :raid-participant/raid-id ?rid]]
                            db raid-id)
                       (map (comp #(raid-store/participant->map % edn-read present?)
                                  first))
                       (filter some?)
                       vec)
            caps (->> (d/q '[:find ?pid ?t ?c
                             :in $ ?rid
                             :where
                             [?x :raid-capture/raid-id ?rid]
                             [?x :raid-capture/player-id ?pid]
                             [?x :raid-capture/tentativas ?t]
                             [?x :raid-capture/capturou ?c]]
                           db raid-id)
                      (map (fn [[pid t c]]
                             [pid {:tentativas t :capturou c}])))]
        (raid-store/raid->map e parts caps edn-read present?)))))

(defn- load-eggs-for-account
  [db account-key]
  (->> (d/q '[:find (pull ?e [*])
              :in $ ?ak
              :where [?e :egg/account-key ?ak]]
            db account-key)
       (map (comp #(adventure-store/egg-entity->map % edn-read present?) first))
       (filter some?)
       vec))

(defn- load-adventure
  [db chat-id player-id]
  (let [ak (id/adventure-account-key chat-id player-id)
        e (d/pull db '[*] [:adventure-account/key ak])]
    (when (present? e)
      (adventure-store/merge-estado
       (adventure-store/account-shell e edn-read present?)
       (load-eggs-for-account db ak)))))

(defn- load-enfermaria
  [db save-key*]
  (->> (d/q '[:find (pull ?b [*])
              :in $ ?sk
              :where [?b :enfermaria-bed/save-key ?sk]]
            db save-key*)
       (map (fn [[e]]
              (let [poke (or (edn-read (:enfermaria-bed/pokemon-edn e)) {})
                    poke (cond-> poke
                           (:enfermaria-bed/origin-edn e)
                           (assoc :origin-edn (:enfermaria-bed/origin-edn e))
                           (:enfermaria-bed/legado-edn e)
                           (assoc :legado (edn-read (:enfermaria-bed/legado-edn e))))]
                {:bed-id (:enfermaria-bed/bed-id e)
                 :pokemon poke
                 :pronto-em (:enfermaria-bed/pronto-em e)})))
       vec))

(defn- clear-chat-wild-tx
  "Remove trava de caça do chat se ainda apontar para esta batalha."
  [db chat-id battle-id]
  (let [g (d/pull db '[*] [:group/chat-id chat-id])]
    (when (and (present? g) (= battle-id (:group/active-wild-battle-id g)))
      (cond-> []
        (:group/active-wild-battle-id g)
        (conj [:db/retract [:group/chat-id chat-id] :group/active-wild-battle-id battle-id])
        (:group/active-wild-player-id g)
        (conj [:db/retract [:group/chat-id chat-id] :group/active-wild-player-id
               (:group/active-wild-player-id g)])))))

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
                                       :player/fence-generation 0
                                       :player/gym-defense-count 0}]})
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

  (attempt-encounter-capture! [_ writer-ctx request]
    (let [payload (ops/encounter-capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     pk (id/player-key chat-id player-id)
                     battle-id (or (:battle-id request)
                                   (active-battle-id-for db chat-id player-id))
                     _ (when-not battle-id
                         (throw (err/error :encounter_not_found
                                           {:chat-id chat-id :player-id player-id})))
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :encounter_not_found {:battle-id battle-id})))
                     _ (when-not (= (:battle/save-id be) (:save-id writer-ctx))
                         (throw (err/error :battle_save_mismatch
                                           {:battle (:battle/save-id be)
                                            :writer (:save-id writer-ctx)})))
                     state (->battle be)
                     _ (when-not (= player-id (:player-id state))
                         (throw (err/error :encounter_owner_mismatch
                                           {:owner (:player-id state)
                                            :player-id player-id})))
                     _ (when-not (:aguardando-captura? state)
                         (throw (err/error :encounter_not_ready
                                           {:battle-id battle-id
                                            :phase (:phase state)
                                            :active? (:active? state)})))
                     save-m (->save save)
                     colecao (query-collection db sk)
                     bola (:bola request)
                     pokemon (get-in state [:pokemons :o])
                     _ (when-not (capacity/cabe-pokemon? (count colecao) save-m)
                         (throw (err/error :collection_full
                                           {:count (count colecao)
                                            :nivel (:trainer-level save-m)})))
                     ball-tx (inventory-adjust-tx db sk bola -1)
                     tentativas-ant (long (:tentativas-captura state 0))
                     chance-base (or (:chance-base-captura state)
                                     (capture/chance-base-pos-batalha
                                      pokemon
                                      (get-in state [:status :o])
                                      (:sequencia-capturas save-m 0)))
                     attempt (capture/attempt-capture
                              {:pokemon pokemon
                               :status (get-in state [:status :o])
                               :sequencia-capturas (:sequencia-capturas save-m 0)
                               :chance-base chance-base
                               :bola bola
                               :tentativas-anteriores tentativas-ant
                               :roll (:roll request)
                               :fuga-roll (:fuga-roll request)})
                     rev (long (:save/revision save 0))
                     seq-atual (long (:sequencia-capturas save-m 0))
                     maior (long (:maior-sequencia-capturas save-m 0))
                     coins (long (:save/coins save 0))
                     encerrado? (or (:capturou? attempt) (:fugiu? attempt))
                     turn-now (long (:turn-index state 0))
                     next-state (cond-> (assoc state
                                               :tentativas-captura (:tentativa attempt)
                                               :chance-base-captura chance-base
                                               :turn-index (inc turn-now))
                                  encerrado? (assoc :aguardando-captura? false
                                                    :phase :finished
                                                    :active? false
                                                    :capture-outcome
                                                    (cond
                                                      (:capturou? attempt) :caught
                                                      (:fugiu? attempt) :fled)))
                     ;; CAS serializa tentativas concorrentes (espelha turn-index da batalha).
                     battle-tx [[:db/cas [:battle/id battle-id] :battle/turn-index
                                 turn-now (inc turn-now)]
                                [:db/add [:battle/id battle-id] :battle/active?
                                 (boolean (:active? next-state))]
                                [:db/add [:battle/id battle-id] :battle/phase
                                 (name (:phase next-state))]
                                [:db/add [:battle/id battle-id] :battle/state-edn
                                 (pr-str next-state)]]
                     clear-active (when encerrado?
                                    (into [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
                                          (or (clear-chat-wild-tx db chat-id battle-id) [])))
                     base-tx (into (into ball-tx battle-tx)
                                   (concat
                                    (or clear-active [])
                                    [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                                     {:operation-receipt/id operation-id
                                      :operation-receipt/kind "encounter-capture"
                                      :operation-receipt/channel "gameplay"
                                      :operation-receipt/payload-hash phash
                                      :operation-receipt/player-key pk
                                      :operation-receipt/save-id (:save-id writer-ctx)
                                      :operation-receipt/summary
                                      (pr-str {:capturou? (:capturou? attempt)
                                               :fugiu? (:fugiu? attempt)
                                               :bola bola
                                               :chance (:chance attempt)
                                               :battle-id battle-id
                                               :tentativa (:tentativa attempt)})}]))
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
                                   :primeira-tentativa? (zero? tentativas-ant)
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
                                   :battle-id battle-id
                                   :capturou? true
                                   :fugiu? false
                                   :chance (:chance attempt)
                                   :chance-base chance-base
                                   :tentativa (:tentativa attempt)
                                   :bola bola
                                   :indice idx
                                   :id-pokemon (:id-pokemon registro)
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :registro (assoc registro :collection-index idx)
                                   :encounter next-state}})

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
                                   :battle-id battle-id
                                   :capturou? false
                                   :fugiu? true
                                   :chance (:chance attempt)
                                   :chance-base chance-base
                                   :tentativa (:tentativa attempt)
                                   :bola bola
                                   :recompensa rew
                                   :subida (:subida xp-part)
                                   :encounter next-state}})

                       :else
                       {:tx base-tx
                        :result {:operation-id operation-id
                                 :duplicate false
                                 :channel "gameplay"
                                 :payload-hash phash
                                 :battle-id battle-id
                                 :capturou? false
                                 :fugiu? false
                                 :chance (:chance attempt)
                                 :chance-base chance-base
                                 :tentativa (:tentativa attempt)
                                 :bola bola
                                 :encounter next-state}})]
                 resultado)))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (start-hunt! [_ writer-ctx request]
    (let [payload (ops/hunt-start-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [chat-id (:chat-id writer-ctx)
                     player-id (:player-id writer-ctx)
                     sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     pk (id/player-key chat-id player-id)
                     save-m (->save save)
                     agora (long (or (:agora-ms request) (System/currentTimeMillis)))
                     _ (when-not (hunt/pode-cacar? (:ultima-cacada-at save-m 0) agora)
                         (throw (err/error :hunt_cooldown
                                           {:restante-ms (hunt/cooldown-restante-ms
                                                          (:ultima-cacada-at save-m 0) agora)})))
                     prev (active-battle-id-for db chat-id player-id)
                     _ (when prev
                         (let [st (->battle (battle-entity db prev))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     group-e (d/pull db '[*] [:group/chat-id chat-id])
                     _ (when (and (present? group-e)
                                  (:group/active-wild-battle-id group-e)
                                  (not= player-id (:group/active-wild-player-id group-e)))
                         (throw (err/error :chat_hunt_busy
                                           {:battle-id (:group/active-wild-battle-id group-e)
                                            :owner (:group/active-wild-player-id group-e)})))
                     colecao (query-collection db sk)
                     _ (when (empty? colecao)
                         (throw (err/error :no_active_pokemon {})))
                     ativo-idx (long (:ativo-index save-m 0))
                     pokemon-x (nth colecao (min ativo-idx (dec (count colecao))))
                     _ (when-not (pos? (long (:hp-atual pokemon-x 0)))
                         (throw (err/error :active_pokemon_fainted
                                           {:idx ativo-idx})))
                     candidates (mapv hunt/validar-candidato-basico!
                                      (or (:candidates request) []))
                     tipos (set (:tipos-encontro request))
                     surto? (boolean (:surto? request))
                     selvagem
                     (if surto?
                       (let [c (first candidates)]
                         (when-not c
                           (throw (err/error :hunt_no_candidates {:surto? true})))
                         c)
                       (hunt/selecionar-selvagem
                        candidates
                        (long (:poder-alvo request (hunt/poder-alvo-caca
                                                   (:trainer-level save-m 1))))
                        tipos))
                     selvagem (-> selvagem
                                  hunt/com-raridade
                                  (hunt/sortear-shiny (:shiny-roll request))
                                  (update :golpes #(moves/garantir-ataque-do-tipo
                                                    (or % []) (:tipos selvagem))))
                     somente-batalha? (not (capacity/cabe-pokemon? (count colecao) save-m))
                     battle-id (or (:battle-id request) operation-id)
                     state (battle/create-wild-battle
                            {:battle-id battle-id
                             :chat-id chat-id
                             :player-id player-id
                             :save-id (:save-id writer-ctx)
                             :fence-generation (:fence-generation writer-ctx)
                             :pokemon-x pokemon-x
                             :pokemon-o selvagem
                             :hp-x (:hp-atual pokemon-x)
                             :hp-o (:hp selvagem)
                             :status-x (:status pokemon-x)
                             :collection-index ativo-idx})
                     state (assoc state
                                  :bioma (:area-id request)
                                  :clima (:clima-id request)
                                  :surto? surto?
                                  :somente-batalha? somente-batalha?
                                  :tipos-encontro (vec tipos))
                     rev (long (:save/revision save 0))
                     species (or (:species selvagem) (:slug-especie selvagem)
                                 (:nome selvagem))
                     group-tx (if (present? group-e)
                                [[:db/add [:group/chat-id chat-id] :group/active-wild-battle-id battle-id]
                                 [:db/add [:group/chat-id chat-id] :group/active-wild-player-id player-id]]
                                [{:group/chat-id chat-id
                                  :group/label "grupo"
                                  :group/active-wild-battle-id battle-id
                                  :group/active-wild-player-id player-id}])
                     tx (into group-tx
                              [{:battle/id battle-id
                                :battle/save [:save/key sk]
                                :battle/player-key pk
                                :battle/save-id (:save-id writer-ctx)
                                :battle/kind "wild"
                                :battle/active? true
                                :battle/turn-index 0
                                :battle/phase "active"
                                :battle/collection-index (long ativo-idx)
                                :battle/state-edn (pr-str state)}
                               [:db/add [:player/key pk] :player/active-battle-id battle-id]
                               [:db/add [:save/key sk] :save/ultima-cacada-at agora]
                               [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                               {:pokedex-entry/key (id/pokedex-entry-key sk species)
                                :pokedex-entry/save [:save/key sk]
                                :pokedex-entry/species species
                                :pokedex-entry/seen true}
                               {:operation-receipt/id operation-id
                                :operation-receipt/kind "hunt-start"
                                :operation-receipt/channel "gameplay"
                                :operation-receipt/payload-hash phash
                                :operation-receipt/player-key pk
                                :operation-receipt/save-id (:save-id writer-ctx)
                                :operation-receipt/summary
                                (pr-str {:battle-id battle-id
                                         :species species
                                         :raridade (:raridade selvagem)
                                         :shiny? (boolean (:shiny? selvagem))
                                         :somente-batalha? somente-batalha?})}])]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle-id battle-id
                           :battle state
                           :selvagem selvagem
                           :somente-batalha? somente-batalha?
                           :area-id (:area-id request)
                           :surto? surto?}})))
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

  (start-wild-battle! [_ writer-ctx request]
    (let [payload (ops/start-battle-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          battle-id (or (:battle-id request) operation-id)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     prev (active-battle-id-for db (:chat-id writer-ctx) (:player-id writer-ctx))
                     _ (when prev
                         (let [b (battle-entity db prev)
                               st (when (present? b) (->battle b))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     state (battle/create-wild-battle
                            (merge request
                                   {:battle-id battle-id
                                    :chat-id (:chat-id writer-ctx)
                                    :player-id (:player-id writer-ctx)
                                    :save-id (:save-id writer-ctx)
                                    :fence-generation (:fence-generation writer-ctx)}))
                     rev (long (:save/revision save 0))]
                 {:tx [{:battle/id battle-id
                        :battle/save [:save/key sk]
                        :battle/player-key pk
                        :battle/save-id (:save-id writer-ctx)
                        :battle/kind "wild"
                        :battle/active? true
                        :battle/turn-index 0
                        :battle/phase "active"
                        :battle/collection-index (long (:collection-index state 0))
                        :battle/state-edn (pr-str state)}
                       [:db/add [:player/key pk] :player/active-battle-id battle-id]
                       [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                       {:operation-receipt/id operation-id
                        :operation-receipt/kind "start-battle"
                        :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key pk
                        :operation-receipt/save-id (:save-id writer-ctx)
                        :operation-receipt/summary (pr-str {:battle-id battle-id})}]
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle state}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (confirm-battle-turn! [_ writer-ctx request]
    (let [payload (ops/battle-turn-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [battle-id (:battle-id request)
                     be (battle-entity db battle-id)
                     _ (when-not (present? be)
                         (throw (err/error :battle_not_found {:battle-id battle-id})))
                     _ (when-not (= (:battle/save-id be) (:save-id writer-ctx))
                         (throw (err/error :battle_save_mismatch
                                           {:battle (:battle/save-id be)
                                            :writer (:save-id writer-ctx)})))
                     state (->battle be)
                     expected (long (:expected-turn-index request (:turn-index state)))
                     _ (when-not (= expected (long (:turn-index state)))
                         (throw (err/error :stale_turn_index
                                           {:expected expected
                                            :actual (:turn-index state)})))
                     action (keyword (:action request))
                     next-state (battle/apply-player-turn
                                 state
                                 {:action action :move-index (:move-index request)}
                                 (:rng request))
                     pk (id/player-key (:chat-id writer-ctx) (:player-id writer-ctx))
                     sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
                     rev (long (:save/revision save 0))
                     colecao (query-collection db sk)
                     gym? (= :gym (:kind next-state))
                     gym-finished? (and gym? (not (:active? next-state)))
                     idx (long (or (get-in next-state [:indices-ativos :x])
                                   (:collection-index next-state 0)))
                     poke (when (and (seq colecao) (< idx (count colecao)))
                            (nth colecao idx))
                     save-m (->save save)
                     next-state (if (:aguardando-captura? next-state)
                                  (assoc next-state
                                         :tentativas-captura 0
                                         :chance-base-captura
                                         (capture/chance-base-pos-batalha
                                          (get-in next-state [:pokemons :o])
                                          (get-in next-state [:status :o])
                                          (:sequencia-capturas save-m 0)))
                                  next-state)
                     rew (battle/reward-for-outcome (:outcome next-state))
                     xp-part (when (and (not gym?) poke (pos? (long (:pokemon-xp rew 0)))
                                        (not (:active? next-state))
                                        (not (:aguardando-captura? next-state)))
                               (apply-pokemon-xp-tx
                                (assoc poke :hp-atual (get-in next-state [:hp :x]))
                                (long (:pokemon-xp rew 0))))
                     hp-sync (if gym?
                               (gym-battle-hp-txs colecao next-state)
                               (pokemon-hp-status-tx
                                poke (get-in next-state [:hp :x])
                                (get-in next-state [:status :x])))
                     clear-active (when (and (not (:active? next-state))
                                             (not (:aguardando-captura? next-state)))
                                    (into [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
                                          (or (clear-chat-wild-tx db (:chat-id writer-ctx) battle-id) [])))
                     colecao' (if gym-finished?
                                (sync-colecao-from-gym-battle colecao next-state)
                                colecao)
                     venceu? (= :win (:outcome next-state))
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     dia (or (:dia request)
                             (mundo/dia-atual (java.time.Instant/ofEpochMilli agora)))
                     pedra-roll (or (:pedra-roll request)
                                    (get-in request [:rng :pedra])
                                    (rand-int 100))
                     gk (when gym-finished?
                          (id/gym-occupation-key (:chat-id writer-ctx) (:gym-id next-state)))
                     slot (when gym-finished?
                            (d/pull db '[*] [:gym-occupation/key gk]))
                     ocup (when gym-finished?
                            (gym-store/occupation->map slot edn-read present?))
                     resolve-part
                     (when gym-finished?
                       (gym-store/build-resolve-tx
                        {:db db :save save :writer-ctx writer-ctx
                         :omit-receipt? true
                         :request {:operation-id operation-id
                                   :payload-hash phash
                                   :gym-id (:gym-id next-state)
                                   :venceu? venceu?
                                   :dia dia
                                   :agora agora
                                   :pedra-roll pedra-roll
                                   :nome (:desafiante-nome next-state)
                                   :participacao (or (get-in next-state [:participacao :x]) {})
                                   :ginasios (:ginasios save-m {})
                                   :indices (when venceu? (:team-x-indices next-state))
                                   :expected-revision (:expected-revision next-state 0)}
                         :ocupacao ocup :colecao colecao'
                         :slot-revision (long (or (:gym-occupation/revision slot) 0))
                         :query-collection query-collection
                         :save-entity save-entity
                         :player-entity player-entity
                         :pokemon-entity-map pokemon-entity-map
                         :apply-pokemon-xp-tx apply-pokemon-xp-tx
                         :inventory-adjust-tx inventory-adjust-tx}))
                     battle-core [[:db/cas [:battle/id battle-id] :battle/turn-index
                                   expected (long (:turn-index next-state))]
                                  [:db/add [:battle/id battle-id] :battle/active?
                                   (boolean (:active? next-state))]
                                  [:db/add [:battle/id battle-id] :battle/phase
                                   (name (:phase next-state))]
                                  [:db/add [:battle/id battle-id] :battle/state-edn
                                   (pr-str next-state)]
                                  {:operation-receipt/id operation-id
                                   :operation-receipt/kind "battle-turn"
                                   :operation-receipt/channel "gameplay"
                                   :operation-receipt/payload-hash phash
                                   :operation-receipt/player-key pk
                                   :operation-receipt/save-id (:save-id writer-ctx)
                                   :operation-receipt/summary
                                   (pr-str {:battle-id battle-id
                                            :turn (:turn-index next-state)
                                            :outcome (:outcome next-state)
                                            :kind (:kind next-state)
                                            :gym-id (:gym-id next-state)})}]
                     ;; Ginásio finalizado: revision CAS vem do resolve.
                     rev-tx (when-not gym-finished?
                              [[:db/cas [:save/key sk] :save/revision rev (inc rev)]])
                     tx (into (into battle-core (or rev-tx []))
                              (concat (or hp-sync [])
                                      (or clear-active [])
                                      (or (:tx xp-part) [])
                                      (or (:tx resolve-part) [])))]
                 {:tx tx
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle next-state
                           :subida (:subida xp-part)
                           :reward (if gym-finished? (:reward (:result resolve-part)) rew)
                           :gym-resolve (when gym-finished? (:result resolve-part))}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (get-battle [_ battle-id]
    (->battle (battle-entity (d/db conn) battle-id)))

  (active-battle [_ chat-id player-id]
    (when-let [bid (active-battle-id-for (d/db conn) chat-id player-id)]
      (let [b (->battle (battle-entity (d/db conn) bid))]
        (when (or (:active? b) (:aguardando-captura? b))
          b))))

  (get-gym-occupation [_ chat-id gym-id]
    (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key (id/gym-occupation-key chat-id gym-id)])]
      (gym-store/occupation->map e edn-read present?)))

  (list-gym-occupations-for-player [_ chat-id player-id]
    (let [pk (id/player-key chat-id player-id)
          rows (d/q '[:find (pull ?g [*])
                      :in $ ?pk
                      :where [?g :gym-occupation/player-key ?pk]]
                    (d/db conn) pk)]
      (mapv (fn [[e]] (gym-store/occupation->map e edn-read present?)) rows)))

  (set-gym-team! [_ writer-ctx indices]
    (transact-with-writer!
     conn writer-ctx
     (fn [db save]
       (let [sk (id/save-key (:chat-id writer-ctx) (:player-id writer-ctx) (:save-id writer-ctx))
             idxs (mapv long indices)
             _ (when-not (and (= 3 (count idxs)) (= 3 (count (set idxs))))
                 (throw (err/error :invalid_gym_team {:indices idxs})))
             colecao (query-collection db sk)
             _ (doseq [i idxs] (coll/validate-index! i (count colecao)))
             rev (long (:save/revision save 0))]
         {:tx [[:db/add [:save/key sk] :save/time-ginasio-edn (pr-str idxs)]
               [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
          :result {:time-ginasio idxs}}))))

  (start-gym-challenge! [_ writer-ctx request]
    (let [payload (ops/gym-challenge-start-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          gym-id (str (:gym-id request))
          chat-id (:chat-id writer-ctx)
          gk (id/gym-occupation-key chat-id gym-id)
          _ (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key gk])]
              (when-not (present? e)
                (try
                  (d/transact conn {:tx-data [{:gym-occupation/key gk
                                              :gym-occupation/chat-id chat-id
                                              :gym-occupation/gym-id gym-id
                                              :gym-occupation/revision 0}]})
                  (catch Exception _))))
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [g (or (gym/obter-ginasio gym-id)
                           (throw (err/error :unknown_gym {:gym-id gym-id})))
                     player-id (:player-id writer-ctx)
                     save-id (:save-id writer-ctx)
                     pk (id/player-key chat-id player-id)
                     sk (id/save-key chat-id player-id save-id)
                     save-m (->save save)
                     insignias (:ginasios save-m {})
                     agora (long (or (:agora request) (System/currentTimeMillis)))
                     dia (or (:dia request)
                             (mundo/dia-atual (java.time.Instant/ofEpochMilli agora)))
                     _ (when-not (gym/desbloqueado? insignias gym-id)
                         (throw (err/error :gym_locked {:gym-id gym-id})))
                     prev (active-battle-id-for db chat-id player-id)
                     _ (when prev
                         (let [st (->battle (battle-entity db prev))]
                           (when (and st (or (:active? st) (:aguardando-captura? st)))
                             (throw (err/error :battle_already_active {:battle-id prev})))))
                     slot (d/pull db '[*] [:gym-occupation/key gk])
                     ocup (gym-store/occupation->map slot edn-read present?)
                     _ (when (and ocup (= (:player-key ocup) pk)
                                  (= (:save-id ocup) save-id))
                         (throw (err/error :cannot_challenge_own_gym
                                           {:gym-id gym-id :save-id save-id})))
                     _ (when (and (nil? ocup)
                                  (= dia (get insignias gym-id)))
                         (throw (err/error :gym_daily_limit
                                           {:gym-id gym-id :dia dia})))
                     idxs (vec (or (:time-ginasio save-m) []))
                     colecao (query-collection db sk)
                     _ (when-not (and (= 3 (count idxs)) (= 3 (count (set idxs))))
                         (throw (err/error :invalid_gym_team {:indices idxs})))
                     team-regs (mapv (fn [i]
                                       (coll/validate-index! i (count colecao))
                                       (nth colecao i))
                                     idxs)
                     _ (when-not (every? #(pos? (long (:hp-atual % 0))) team-regs)
                         (throw (err/error :gym_team_unhealthy {:indices idxs})))
                     team-x (mapv (fn [i poke]
                                    (assoc poke :collection-index (long i)))
                                  idxs team-regs)
                     team-o (if ocup
                              (gym/time-defensor ocup agora)
                              (let [cands (or (:candidates request) [])]
                                (when-not (= 3 (count cands))
                                  (throw (err/error :invalid_gym_npc_team
                                                    {:count (count cands)
                                                     :expected (:time g)})))
                                (scale-npc-team cands g)))
                     expected-rev (long (:gym-occupation/revision slot 0))
                     battle-id (or (:battle-id request) operation-id)
                     state (battle/create-gym-battle
                            {:battle-id battle-id
                             :chat-id chat-id
                             :player-id player-id
                             :save-id save-id
                             :fence-generation (:fence-generation writer-ctx)
                             :gym-id gym-id
                             :gym-nome (:nome g)
                             :lider-nome (or (:nome ocup) (:lider g))
                             :desafiante-nome (or (:nome request) player-id)
                             :team-x team-x
                             :team-o team-o
                             :expected-revision expected-rev})
                     rev (long (:save/revision save 0))]
                 {:tx [{:battle/id battle-id
                        :battle/save [:save/key sk]
                        :battle/player-key pk
                        :battle/save-id save-id
                        :battle/kind "gym"
                        :battle/active? true
                        :battle/turn-index 0
                        :battle/phase "active"
                        :battle/collection-index (long (first idxs))
                        :battle/state-edn (pr-str state)}
                       [:db/add [:player/key pk] :player/active-battle-id battle-id]
                       [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                       {:operation-receipt/id operation-id
                        :operation-receipt/kind "gym-challenge-start"
                        :operation-receipt/channel "gameplay"
                        :operation-receipt/payload-hash phash
                        :operation-receipt/player-key pk
                        :operation-receipt/save-id save-id
                        :operation-receipt/summary
                        (pr-str {:battle-id battle-id :gym-id gym-id})}]
                  :result {:operation-id operation-id
                           :duplicate false
                           :channel "gameplay"
                           :payload-hash phash
                           :battle-id battle-id
                           :battle state
                           :gym-id gym-id}})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (resolve-gym-challenge! [_ writer-ctx request]
    (let [payload (ops/gym-resolve-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          gym-id (:gym-id request)
          chat-id (:chat-id writer-ctx)
          gk (id/gym-occupation-key chat-id gym-id)
          ;; Stub rev=0 para CAS na primeira ocupação (corrida segura).
          _ (let [e (d/pull (d/db conn) '[*] [:gym-occupation/key gk])]
              (when-not (present? e)
                (try
                  (d/transact conn {:tx-data [{:gym-occupation/key gk
                                              :gym-occupation/chat-id chat-id
                                              :gym-occupation/gym-id gym-id
                                              :gym-occupation/revision 0}]})
                  (catch Exception _))))
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id (:player-id writer-ctx) (:save-id writer-ctx))
                     save-m (->save save)
                     slot (d/pull db '[*] [:gym-occupation/key gk])
                     ocup (gym-store/occupation->map slot edn-read present?)
                     slot-rev (long (:gym-occupation/revision slot 0))
                     colecao (query-collection db sk)]
                 (gym-store/build-resolve-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request
                                   :ginasios (:ginasios save-m {})
                                   :payload-hash phash)
                   :ocupacao ocup :colecao colecao
                   :slot-revision slot-rev
                   :query-collection query-collection
                   :save-entity save-entity
                   :player-entity player-entity
                   :pokemon-entity-map pokemon-entity-map
                   :apply-pokemon-xp-tx apply-pokemon-xp-tx
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (heal-gym-motivation! [_ writer-ctx request]
    (let [payload (reward/canonicalize
                   {:gym-id (:gym-id request)
                    :indice (long (:indice request))
                    :item-id (:item-id request)
                    :pontos (long (:pontos request))})
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [ocup (let [e (d/pull db '[*] [:gym-occupation/key
                                                   (id/gym-occupation-key (:chat-id writer-ctx)
                                                                          (:gym-id request))])]
                            (gym-store/occupation->map e edn-read present?))]
                 (gym-store/build-heal-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :ocupacao ocup
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (get-raid [_ raid-id]
    (load-raid (d/db conn) raid-id))

  (active-raid [_ chat-id]
    ;; Retorna o ponteiro do chat; expiração/visibilidade ficam na bridge
    ;; com o agora do request (testes usam timestamps sintéticos).
    (let [ptr (d/pull (d/db conn) '[*] [:chat-active-raid/chat-id chat-id])]
      (when (present? ptr)
        (load-raid (d/db conn) (:chat-active-raid/raid-id ptr)))))

  (open-raid! [_ writer-ctx request]
    (let [payload (ops/raid-open-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [_db _save]
               (raid-store/build-open-tx
                {:request (assoc request
                                 :chat-id (:chat-id writer-ctx)
                                 :payload-hash phash
                                 :agora (or (:agora request)
                                            (System/currentTimeMillis)))})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (join-raid! [_ writer-ctx request]
    (let [payload (ops/raid-join-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))
                     sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (raid-store/build-join-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid :colecao colecao}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (leave-raid! [_ writer-ctx request]
    (let [payload (ops/raid-join-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))]
                 (raid-store/build-leave-tx
                  {:save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (start-raid! [_ writer-ctx request]
    (let [payload (reward/canonicalize
                   {:raid-id (:raid-id request) :action "start"})
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))]
                 (raid-store/build-start-tx
                  {:save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (raid-attack! [_ writer-ctx request]
    (let [payload (ops/raid-attack-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))]
                 (raid-store/build-attack-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid
                   :apply-pokemon-xp-tx apply-pokemon-xp-tx
                   :save-entity save-entity
                   :query-collection query-collection}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (raid-capture! [_ writer-ctx request]
    (let [payload (ops/raid-capture-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [raid (or (load-raid db (:raid-id request))
                              (throw (err/error :raid_not_found
                                                {:raid-id (:raid-id request)})))
                     sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (raid-store/build-capture-tx
                  {:db db :save save :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :raid raid :colecao colecao
                   :pokemon-entity-map pokemon-entity-map
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (ensure-adventure-account! [_ chat-id player-id]
    (let [ak (id/adventure-account-key chat-id player-id)
          e (d/pull (d/db conn) '[*] [:adventure-account/key ak])]
      (if (present? e)
        (load-adventure (d/db conn) chat-id player-id)
        (do
          (d/transact conn {:tx-data (:tx (adventure-store/build-ensure-account-tx
                                           chat-id player-id))})
          (load-adventure (d/db conn) chat-id player-id)))))

  (get-adventure-account [_ chat-id player-id]
    (load-adventure (d/db conn) chat-id player-id))

  (get-egg [_ egg-id]
    (adventure-store/egg-entity->map
     (d/pull (d/db conn) '[*] [:egg/id egg-id]) edn-read present?))

  (grant-egg! [_ writer-ctx request]
    (let [payload (ops/egg-grant-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db _save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     _ (when-not (present? e)
                         (throw (err/error :adventure_account_missing {})))
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)]
                 (adventure-store/build-grant-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (incubate-egg! [_ writer-ctx request]
    (let [payload (ops/egg-incubate-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db _save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)]
                 (adventure-store/build-incubate-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (hatch-egg! [_ writer-ctx request]
    (let [payload (ops/egg-hatch-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [ak (id/adventure-account-key (:chat-id writer-ctx)
                                                  (:player-id writer-ctx))
                     e (d/pull db '[*] [:adventure-account/key ak])
                     shell (adventure-store/account-shell e edn-read present?)
                     eggs (load-eggs-for-account db ak)
                     sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (adventure-store/build-hatch-tx
                  {:db db :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :account-shell shell :egg-maps eggs
                   :save save :colecao colecao
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (list-enfermaria [_ chat-id player-id save-id]
    (load-enfermaria (d/db conn) (id/save-key chat-id player-id save-id)))

  (joy-send! [_ writer-ctx request]
    (let [payload (ops/joy-send-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (adventure-store/build-joy-send-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :enfermaria (load-enfermaria db sk)
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (joy-collect! [_ writer-ctx request]
    (let [payload (ops/joy-collect-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (adventure-store/build-joy-collect-tx
                  {:writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :enfermaria (load-enfermaria db sk)
                   :pokemon-entity-map pokemon-entity-map}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (buy-item! [_ writer-ctx request]
    (let [payload (ops/shop-buy-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (shop-store/build-buy-tx
                  {:db db
                   :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :inventory (query-inventory db sk)
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (use-item! [_ writer-ctx request]
    (let [payload (ops/shop-use-payload request)
          phash (ops/payload-hash payload)
          operation-id (:operation-id request)
          existing (receipt-entity (d/db conn) operation-id)]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key (:chat-id writer-ctx)
                                     (:player-id writer-ctx)
                                     (:save-id writer-ctx))]
                 (shop-store/build-use-tx
                  {:db db
                   :writer-ctx writer-ctx
                   :request (assoc request :payload-hash phash)
                   :save save
                   :colecao (query-collection db sk)
                   :inventory-adjust-tx inventory-adjust-tx}))))
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
        {:chat-id (:group/chat-id e)
         :label (:group/label e)
         :active-wild-battle-id (:group/active-wild-battle-id e)
         :active-wild-player-id (:group/active-wild-player-id e)})))

  (get-receipt [_ operation-id]
    (let [e (receipt-entity (d/db conn) operation-id)]
      (when (present? e)
        (dissoc (->receipt e) :duplicate))))

  (claim-starter-kit! [_ writer-ctx request]
    (let [operation-id (:operation-id request)
          payload (reward/reward-payload
                   {:item {:item-id "pokebola" :qty 10}})
          phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) operation-id)
          chat-id (:chat-id writer-ctx)
          player-id (:player-id writer-ctx)
          kit-any? (boolean
                    (some (fn [slot]
                            (:kit-inicial-resgatado
                             (->save (save-entity (d/db conn) chat-id player-id slot))))
                          model/default-slots))]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (onboarding-store/build-claim-kit-tx
                {:db db :writer-ctx writer-ctx :request request :save save
                 :kit-claimed-any? kit-any?
                 :inventory-adjust-tx inventory-adjust-tx})))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex))))))))

  (choose-starter! [_ writer-ctx request]
    (let [operation-id (:operation-id request)
          starter (onboard/resolve-starter (:choice request))
          payload (reward/reward-payload
                   {:pokemon {:key (str operation-id ":starter")
                              :species (:species starter)
                              :nickname (:nome starter)
                              :shiny false :xp 0 :level 1 :in-party true}})
          phash (reward/payload-hash payload)
          existing (receipt-entity (d/db conn) operation-id)
          chat-id (:chat-id writer-ctx)
          player-id (:player-id writer-ctx)
          kit-any? (boolean
                    (some (fn [slot]
                            (:kit-inicial-resgatado
                             (->save (save-entity (d/db conn) chat-id player-id slot))))
                          model/default-slots))]
      (or (resolve-receipt existing phash operation-id)
          (try
            (transact-with-writer!
             conn writer-ctx
             (fn [db save]
               (let [sk (id/save-key chat-id player-id (:save-id writer-ctx))
                     colecao (query-collection db sk)]
                 (onboarding-store/build-choose-starter-tx
                  {:db db :writer-ctx writer-ctx
                   :request (assoc request :kit-claimed-any? kit-any?)
                   :save save
                   :colecao colecao
                   :pokemon-entity-map pokemon-entity-map
                   :inventory-adjust-tx inventory-adjust-tx}))))
            (catch Exception ex
              (let [dup (receipt-entity (d/db conn) operation-id)]
                (if (present? dup)
                  (resolve-receipt dup phash operation-id)
                  (throw ex)))))))))

(defn make-store
  "Constrói store a partir de uma conexão Datomic já aberta."
  [conn]
  (->DatomicTrainerStore conn))
