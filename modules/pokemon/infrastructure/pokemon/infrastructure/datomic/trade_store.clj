(ns pokemon.infrastructure.datomic.trade-store
  "Repositório A4.4.1. Transições usam CAS de revisão; integração ao adapter
   principal e operações de escolha entram nas etapas seguintes."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.reward :as reward]
            [pokemon.domain.trade :as trade]
            [pokemon.domain.trade-evolution :as trade-evolution]))

(defn- capitalize-species [s]
  (when (and (string? s) (not (str/blank? s)))
    (str (str/upper-case (subs s 0 1)) (subs s 1))))

(defn- display-name [e]
  (or (when-not (str/blank? (str (:pokemon/nickname e))) (:pokemon/nickname e))
      (capitalize-species (:pokemon/species e))))

(defn- enrich-pokemon-labels [db trade]
  (let [ctx (:context trade)
        pa (when-let [id (:pokemon-a ctx)]
             (d/pull db '[:pokemon/nickname :pokemon/species] [:pokemon/id-pokemon id]))
        pb (when-let [id (:pokemon-b ctx)]
             (d/pull db '[:pokemon/nickname :pokemon/species] [:pokemon/id-pokemon id]))
        name-a (or (:pokemon-a-name ctx) (display-name pa))
        name-b (or (:pokemon-b-name ctx) (display-name pb))]
    (cond-> trade
      name-a (assoc :pokemon-a-name name-a)
      name-b (assoc :pokemon-b-name name-b)
      (:pokemon-a ctx) (assoc :pokemon-a (:pokemon-a ctx))
      (:pokemon-b ctx) (assoc :pokemon-b (:pokemon-b ctx))
      (:public-id trade) (assoc :public-label (trade/format-public-id (:public-id trade))))))

(defn- ->trade [e]
  (when (:db/id e)
    {:id (:trade/id e) :chat-id (:trade/chat-id e) :player-a (:trade/player-a e)
     :player-b (:trade/player-b e) :save-a (:trade/save-a e) :save-b (:trade/save-b e)
     :state (keyword (:trade/state e)) :created-at (:trade/created-at e)
     :expires-at (:trade/expires-at e) :revision (:trade/revision e)
     :fence-a (:trade/fence-a e) :fence-b (:trade/fence-b e)
     :save-revision-a (:trade/save-revision-a e)
     :save-revision-b (:trade/save-revision-b e)
     :public-id (:trade/public-id e)
     :context (edn/read-string (or (:trade/context-edn e) "{}"))}))

(defn get-trade [conn id]
  (when-let [t (->trade (d/pull (d/db conn) '[*] [:trade/id id]))]
    (enrich-pokemon-labels (d/db conn) t)))

(defn- public-key [chat-id public-id]
  (str chat-id "|" (long public-id)))

(defn resolve-trade-id
  "Resolve referência pública (`84`/`#84`) ou id canônico legado no chat."
  [conn chat-id trade-ref]
  (when (and (string? trade-ref) (not (str/blank? trade-ref))
             (string? chat-id) (not (str/blank? chat-id)))
    (let [db (d/db conn)
          by-id (->trade (d/pull db '[:db/id :trade/id :trade/chat-id] [:trade/id trade-ref]))]
      (cond
        (and by-id (= chat-id (:chat-id by-id))) (:id by-id)
        :else
        (when-let [n (trade/parse-public-token trade-ref)]
          (:trade/id (d/pull db '[:trade/id] [:trade/public-key (public-key chat-id n)])))))))

(defn- next-public-allocation
  "Retorna {:public-id N :tx [...]} com CAS do contador do chat."
  [db chat-id]
  (let [e (d/pull db '[*] [:trade-seq/chat-id chat-id])
        cur (long (or (:trade-seq/next e) 1))]
    {:public-id cur
     :tx (if (:db/id e)
           [[:db/cas [:trade-seq/chat-id chat-id] :trade-seq/next cur (inc cur)]]
           [{:trade-seq/chat-id chat-id :trade-seq/next (inc cur)}])}))

(defn- cas-conflict? [^Exception ex]
  (or (err/conflict? ex)
      (boolean (re-find #"(?i)cas|unique|conflict" (or (.getMessage ex) "")))))

(defn- transact! [conn tx]
  (d/transact conn tx))

(defn- receipt [db op] (d/pull db '[*] [:operation-receipt/id op]))
(defn- result-receipt [e hash op]
  (when (:db/id e)
    (reward/assert-receipt-payload! (:operation-receipt/payload-hash e) hash op)
    (let [summary (edn/read-string (:operation-receipt/summary e))]
      (cond-> (assoc summary :duplicate true)
        (:trade-id summary) (assoc :id (:trade-id summary))))))

(defn- context-from-request [request]
  (merge (select-keys request [:pokemon-a :pokemon-b])
         (select-keys request [:pokemon-a-name :pokemon-b-name])))

(defn create! [conn request]
  (let [{:keys [operation-id chat-id player-a player-b save-a save-b fence-a fence-b now]} request
        payload (reward/canonicalize (select-keys request [:chat-id :player-a :player-b :save-a :save-b :fence-a :fence-b :now]))
        hash (reward/payload-hash payload)]
    (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id)
        (loop [attempt 0]
          (let [outcome
                (try
                  (let [db (d/db conn)
                        a (d/pull db '[*] [:player/key (str chat-id "|" player-a)])
                        b (d/pull db '[*] [:player/key (str chat-id "|" player-b)])
                        sa (d/pull db '[*] [:save/key (str chat-id "|" player-a "|" save-a)])
                        sb (d/pull db '[*] [:save/key (str chat-id "|" player-b "|" save-b)])
                        _ (when (or (= player-a player-b) (not (:db/id a)) (not (:db/id b))
                                    (not= save-a (:player/active-save-id a)) (not= save-b (:player/active-save-id b))
                                    (not= fence-a (:player/fence-generation a)) (not= fence-b (:player/fence-generation b)))
                            (throw (err/error :invalid_trade {})))
                        alloc (next-public-allocation db chat-id)
                        public-id (:public-id alloc)
                        t (assoc (trade/create! {:id operation-id :chat-id chat-id :player-a player-a :player-b player-b
                                                 :save-a save-a :save-b save-b :now now})
                                 :context (context-from-request request)
                                 :public-id public-id)
                        summary {:trade-id operation-id :public-id public-id :state :invited}]
                    (d/transact conn {:tx-data (vec
                                               (concat
                                                (:tx alloc)
                                                [[:db/cas [:player/key (:player/key a)] :player/active-save-id save-a save-a]
                                                 [:db/cas [:player/key (:player/key a)] :player/fence-generation fence-a fence-a]
                                                 [:db/cas [:player/key (:player/key b)] :player/active-save-id save-b save-b]
                                                 [:db/cas [:player/key (:player/key b)] :player/fence-generation fence-b fence-b]
                                                 [:db/cas [:save/key (:save/key sa)] :save/revision (:save/revision sa) (:save/revision sa)]
                                                 [:db/cas [:save/key (:save/key sb)] :save/revision (:save/revision sb) (:save/revision sb)]
                                                 (-> t
                                                     (assoc :trade/id (:id t) :trade/chat-id (:chat-id t)
                                                            :trade/player-a (:player-a t) :trade/player-b (:player-b t)
                                                            :trade/save-a (:save-a t) :trade/save-b (:save-b t)
                                                            :trade/state (name (:state t)) :trade/created-at (:created-at t)
                                                            :trade/expires-at (:expires-at t) :trade/revision (:revision t)
                                                            :trade/context-edn (pr-str (:context t))
                                                            :trade/public-id public-id
                                                            :trade/public-key (public-key chat-id public-id))
                                                     (assoc :trade/fence-a fence-a :trade/fence-b fence-b
                                                            :trade/save-revision-a (:save/revision sa)
                                                            :trade/save-revision-b (:save/revision sb))
                                                     (dissoc :id :chat-id :player-a :player-b :save-a :save-b :state
                                                             :created-at :expires-at :revision :context :public-id))
                                                 {:operation-receipt/id operation-id :operation-receipt/kind "trade-create"
                                                  :operation-receipt/channel "gameplay"
                                                  :operation-receipt/payload-hash hash
                                                  :operation-receipt/player-key (:player/key a)
                                                  :operation-receipt/save-id save-a
                                                  :operation-receipt/summary (pr-str summary)}]))})
                    (assoc (get-trade conn operation-id) :duplicate false))
                  (catch Exception ex
                    (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id)
                        (if (and (< attempt 8) (cas-conflict? ex))
                          ::retry
                          (throw ex)))))]
            (if (= ::retry outcome)
              (recur (inc attempt))
              outcome))))))

(defn transition! [conn before after]
  (d/transact conn {:tx-data [[:db/cas [:trade/id (:id before)] :trade/revision (:revision before) (:revision after)]
                              [:db/add [:trade/id (:id before)] :trade/state (name (:state after))]
                              [:db/add [:trade/id (:id before)] :trade/context-edn (pr-str (:context after))]]})
  (get-trade conn (:id before)))

(defn- participant-entity [db chat-id player-id]
  (d/pull db '[*] [:player/key (str chat-id "|" player-id)]))

(defn- save-entity [db chat-id player-id save-id]
  (d/pull db '[*] [:save/key (str chat-id "|" player-id "|" save-id)]))

(defn- transition-request [conn request action target-state required-actor]
  (let [{:keys [operation-id chat-id player-id save-id fence-generation now]} request
        trade-id (or (resolve-trade-id conn chat-id (:trade-id request)) (:trade-id request))
        request (assoc request :trade-id trade-id)
        payload (reward/canonicalize (select-keys request [:trade-id :chat-id :player-id :save-id :fence-generation :now :action]))
        hash (reward/payload-hash payload)
        db (d/db conn)]
    (or (result-receipt (receipt db operation-id) hash operation-id)
        (let [before (get-trade conn trade-id)
              actor (participant-entity db chat-id player-id)
              a (participant-entity db chat-id (:player-a before))
              b (participant-entity db chat-id (:player-b before))
              sa (save-entity db chat-id (:player-a before) (:save-a before))
              sb (save-entity db chat-id (:player-b before) (:save-b before))
              expired? (and before (>= (long now) (long (:expires-at before))))
              required-player (when required-actor
                                (if (= required-actor :player-b)
                                  (:player-b before)
                                  (:player-a before)))]
          (when (nil? before) (throw (err/error :trade_not_found {})))
          ;; JIDs são literais por segurança: @lid e @c.us não são aliases
          ;; intercambiáveis sem vínculo explícito e confiável.
          (when-not (and (= chat-id (:chat-id before))
                         (trade/participant? before player-id))
            (throw (err/error :trade_forbidden {})))
          (when (and required-player (not= player-id required-player))
            (throw (err/error :trade_action_forbidden {})))
          (when-not (= save-id (:player/active-save-id actor))
            (throw (err/error :trade_save_mismatch {})))
          (when-not (= fence-generation (:player/fence-generation actor))
            (throw (err/error :stale_writer_fence {})))
          (when-not (and a b sa sb
                         (= (:save-a before) (:player/active-save-id a))
                         (= (:save-b before) (:player/active-save-id b))
                         (= (:fence-a before) (:player/fence-generation a))
                         (= (:fence-b before) (:player/fence-generation b)))
            (throw (err/error :invalid_trade {})))
          (when (contains? #{:completed :refused :cancelled :expired :invalidated} (:state before))
            (throw (err/error :invalid_trade_transition {})))
          (when (and expired? (not= action :expire))
            (throw (err/error :trade_expired {})))
          (let [after (if expired?
                        (assoc before :state :expired :revision (inc (:revision before)))
                        (assoc before :state target-state :revision (inc (:revision before))))
                summary {:trade-id trade-id :state (:state after)}
                tx [[:db/cas [:trade/id trade-id] :trade/revision (:revision before) (:revision after)]
                    [:db/cas [:player/key (:player/key a)] :player/active-save-id (:save-a before) (:save-a before)]
                    [:db/cas [:player/key (:player/key a)] :player/fence-generation (:fence-a before) (:fence-a before)]
                    [:db/cas [:player/key (:player/key b)] :player/active-save-id (:save-b before) (:save-b before)]
                    [:db/cas [:player/key (:player/key b)] :player/fence-generation (:fence-b before) (:fence-b before)]
                    [:db/add [:trade/id trade-id] :trade/state (name (:state after))]
                    [:db/add [:trade/id trade-id] :trade/revision (:revision after)]
                    {:operation-receipt/id operation-id :operation-receipt/kind (str "trade-" (name action))
                     :operation-receipt/channel "gameplay" :operation-receipt/payload-hash hash
                     :operation-receipt/player-key (:player/key actor) :operation-receipt/save-id save-id
                     :operation-receipt/summary (pr-str summary)}]]
            (try
              (transact! conn {:tx-data tx})
              (assoc (get-trade conn trade-id) :duplicate false)
              (catch Exception ex
                (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id)
                    (throw ex)))))))))

(defn accept! [conn request]
  (transition-request conn (assoc request :action :accept)
                      :accept :accepted :player-b))

(defn refuse! [conn request]
  (transition-request conn (assoc request :action :refuse)
                      :refuse :refused :player-b))

(defn cancel! [conn request]
  (transition-request conn (assoc request :action :cancel)
                      :cancel :cancelled nil))

(defn expire! [conn request]
  (transition-request conn (assoc request :action :expire)
                      :expire :expired nil))

(defn- pokemon-by-id [db id]
  (d/pull db '[*] [:pokemon/id-pokemon id]))

(defn- pokemon-record [e]
  (when (:db/id e)
    {:id-pokemon (:pokemon/id-pokemon e)
     :species (:pokemon/species e)
     :nome (or (:pokemon/nickname e) (:pokemon/species e))
     :shiny (boolean (:pokemon/shiny e))
     :nivel (long (:pokemon/level e 1))
     :hp (long (:pokemon/hp e 0)) :hp-atual (long (:pokemon/hp-atual e 0))
     :ataque (long (:pokemon/ataque e 0)) :defesa (long (:pokemon/defesa e 0))
     :atq-esp (long (:pokemon/atq-esp e 0)) :def-esp (long (:pokemon/def-esp e 0))
     :veloc (long (:pokemon/veloc e 0)) :tipos (vec (:pokemon/tipos e))
     :golpes (vec (or (some-> (:pokemon/golpes-edn e) edn/read-string) []))
     :item (:pokemon/item e) :amizade (long (:pokemon/amizade e 70))}))

(defn- evolution-tx [lookup original evolved prepared]
  (let [old-types (set (:tipos original)) new-types (set (:tipos evolved))]
    (vec
     (concat
      (map (fn [t] [:db/retract lookup :pokemon/tipos t]) (remove new-types old-types))
      (map (fn [t] [:db/add lookup :pokemon/tipos t]) (remove old-types new-types))
      [[:db/add lookup :pokemon/species (:species evolved)]
       [:db/add lookup :pokemon/nickname (:nome evolved)]
       [:db/add lookup :pokemon/hp (long (:hp evolved))]
       [:db/add lookup :pokemon/hp-atual (long (:hp-atual evolved))]
       [:db/add lookup :pokemon/ataque (long (:ataque evolved))]
       [:db/add lookup :pokemon/defesa (long (:defesa evolved))]
       [:db/add lookup :pokemon/atq-esp (long (:atq-esp evolved))]
       [:db/add lookup :pokemon/def-esp (long (:def-esp evolved))]
       [:db/add lookup :pokemon/veloc (long (:veloc evolved))]
       [:db/add lookup :pokemon/golpes-edn (pr-str (:golpes evolved))]]
      (when (:habilidade evolved)
        [[:db/add lookup :pokemon/habilidade (:habilidade evolved)]])
      (when (:imagem evolved)
        [[:db/add lookup :pokemon/imagem (:imagem evolved)]])
      (when (:consumed-item prepared)
        [[:db/retract lookup :pokemon/item (:consumed-item prepared)]])))))

(defn- joy-holds-pokemon? [db save-key pokemon-id]
  (boolean
   (some (fn [eid]
           (let [raw (:enfermaria-bed/pokemon-edn (d/pull db '[*] eid))]
             (try (let [poke (or (battle/decode-compact-edn raw) {})]
                    (= pokemon-id (or (:id-pokemon poke)
                                      (:pokemon/id-pokemon poke))))
                  (catch Exception _ false))))
         (d/q '[:find ?e
                :in $ ?sk
                :where [?e :enfermaria-bed/save-key ?sk]] db save-key))))

(defn- unavailable? [db p save-key player pokemon-id]
  (or (:player/active-battle-id player)
      (joy-holds-pokemon? db save-key pokemon-id)))

(defn complete! [conn request]
  "Confirma a troca e move as duas instâncias em uma única transação Datomic."
  (let [{:keys [operation-id chat-id player-id save-id fence-generation now]} request
        trade-id (or (resolve-trade-id conn chat-id (:trade-id request)) (:trade-id request))
        tr0 (get-trade conn trade-id)
        pokemon-a (or (:pokemon-a request) (get-in tr0 [:context :pokemon-a]))
        pokemon-b (or (:pokemon-b request) (get-in tr0 [:context :pokemon-b]))
        request (assoc request :trade-id trade-id :pokemon-a pokemon-a :pokemon-b pokemon-b)
        payload (reward/canonicalize (select-keys request [:trade-id :chat-id :player-id :save-id :fence-generation :pokemon-a :pokemon-b :now]))
        hash (reward/payload-hash payload)
        db (d/db conn)]
    (or (result-receipt (receipt db operation-id) hash operation-id)
        (let [tr tr0
              actor (participant-entity db chat-id player-id)
              a (participant-entity db chat-id (:player-a tr))
              b (participant-entity db chat-id (:player-b tr))
              sa (save-entity db chat-id (:player-a tr) (:save-a tr))
              sb (save-entity db chat-id (:player-b tr) (:save-b tr))
              pa (pokemon-by-id db pokemon-a)
              pb (pokemon-by-id db pokemon-b)
              pa-record (pokemon-record pa)
              pb-record (pokemon-record pb)
              prepared-a (trade-evolution/prepare pa-record pb-record)
              prepared-b (trade-evolution/prepare pb-record pa-record)
              evolved-a (when prepared-a
                          (trade-evolution/apply-prepared pa-record prepared-a))
              evolved-b (when prepared-b
                          (trade-evolution/apply-prepared pb-record prepared-b))
              sk-a (:save/key sa)
              sk-b (:save/key sb)]
          (when (nil? tr) (throw (err/error :trade_not_found {})))
          (when-not (and (= chat-id (:chat-id tr))
                         (trade/participant? tr player-id))
            (throw (err/error :trade_forbidden {})))
          (when-not (= player-id (:player-a tr))
            (throw (err/error :trade_action_forbidden {})))
          (when-not (and (= :accepted (:state tr)) (= save-id (:save-a tr))
                         (= fence-generation (:fence-a tr))
                         (= save-id (:player/active-save-id actor))
                         (= fence-generation (:player/fence-generation actor))
                         (< (long now) (long (:expires-at tr)))
                         pa pb (not (unavailable? db pa sk-a a pokemon-a))
                         (not (unavailable? db pb sk-b b pokemon-b))
                         (= (:db/id sa) (or (-> pa :pokemon/save :db/id) (:pokemon/save pa)))
                         (= (:db/id sb) (or (-> pb :pokemon/save :db/id) (:pokemon/save pb))))
            (throw (err/error :invalid_trade {})))
          (let [base-tx [[:db/cas [:trade/id trade-id] :trade/revision (:revision tr) (inc (:revision tr))]
                    [:db/cas [:player/key (:player/key a)] :player/active-save-id (:save-a tr) (:save-a tr)]
                    [:db/cas [:player/key (:player/key b)] :player/active-save-id (:save-b tr) (:save-b tr)]
                    [:db/cas [:player/key (:player/key a)] :player/fence-generation (:fence-a tr) (:fence-a tr)]
                    [:db/cas [:player/key (:player/key b)] :player/fence-generation (:fence-b tr) (:fence-b tr)]
                    [:db/cas [:save/key (:save/key sa)] :save/revision (:save/revision sa) (inc (:save/revision sa))]
                    [:db/cas [:save/key (:save/key sb)] :save/revision (:save/revision sb) (inc (:save/revision sb))]
                    [:db/cas [:pokemon/id-pokemon pokemon-a] :pokemon/save (:db/id sa) (:db/id sb)]
                    [:db/cas [:pokemon/id-pokemon pokemon-b] :pokemon/save (:db/id sb) (:db/id sa)]
                    [:db/add [:pokemon/id-pokemon pokemon-a] :pokemon/save (:db/id sb)]
                    [:db/add [:pokemon/id-pokemon pokemon-b] :pokemon/save (:db/id sa)]]
                tx (vec (concat base-tx
                    (or (when evolved-a
                          (evolution-tx [:pokemon/id-pokemon pokemon-a] pa-record evolved-a prepared-a)) [])
                    (or (when evolved-b
                          (evolution-tx [:pokemon/id-pokemon pokemon-b] pb-record evolved-b prepared-b)) [])
                    [
                    [:db/add [:trade/id trade-id] :trade/state "completed"]
                    [:db/add [:trade/id trade-id] :trade/revision (inc (:revision tr))]
                    {:operation-receipt/id operation-id :operation-receipt/kind "trade-complete"
                     :operation-receipt/channel "gameplay" :operation-receipt/payload-hash hash
                     :operation-receipt/player-key (:player/key actor) :operation-receipt/save-id save-id
                     :operation-receipt/summary (pr-str {:trade-id trade-id :state :completed
                                                         :evolutions (vec (keep :target [prepared-a prepared-b]))})}]))]
            (try
              (transact! conn {:tx-data tx})
              (assoc (get-trade conn trade-id) :duplicate false
                     :evolutions (vec (keep :target [prepared-a prepared-b])))
              (catch Exception ex
                (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id)
                    (throw ex)))))))))

(defn list-trades [conn chat-id player-id]
  (let [db (d/db conn)]
    (->> (concat
          (d/q '[:find ?e :in $ ?chat ?player
                 :where [?e :trade/chat-id ?chat] [?e :trade/player-a ?player]]
               db chat-id player-id)
          (d/q '[:find ?e :in $ ?chat ?player
                 :where [?e :trade/chat-id ?chat] [?e :trade/player-b ?player]]
               db chat-id player-id))
         (map (fn [[eid]]
                (enrich-pokemon-labels db (->trade (d/pull db '[*] eid)))))
         (remove nil?)
         vec)))
