(ns pokemon.infrastructure.datomic.adventure-store
  "TX builders Adventure Sync + Joy."
  (:require [pokemon.domain.capacity :as capacity]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.joy :as joy]
            [pokemon.domain.ops :as ops]))

(defn- egg->entity
  [account-key o]
  (cond-> {:egg/id (:egg-id o)
           :egg/account-key account-key
           :egg/category (name (:category o))
           :egg/state (name (:state o))
           :egg/species (:species o)
           :egg/points (long (:points o 0))
           :egg/points-goal (long (:points-goal o 0))
           :egg/time-ms (long (:time-ms o 0))
           :egg/time-goal-ms (long (:time-goal-ms o 0))
           :egg/revision (long (:revision o 0))}
    (:due-at o) (assoc :egg/due-at (long (:due-at o)))
    (:incubated-at o) (assoc :egg/incubated-at (long (:incubated-at o)))
    (:incubator-id o) (assoc :egg/incubator-id (:incubator-id o))
    (:save-id o) (assoc :egg/save-id (:save-id o))
    (some? (:fence-generation o))
    (assoc :egg/fence-generation (long (:fence-generation o)))
    (:hatch-id o) (assoc :egg/hatch-id (:hatch-id o))
    (:resultado o) (assoc :egg/resultado-edn (pr-str (:resultado o)))))

(defn egg-entity->map
  [e edn-read present?]
  (when (present? e)
    {:egg-id (:egg/id e)
     :category (keyword (:egg/category e))
     :state (keyword (:egg/state e))
     :species (:egg/species e)
     :points (long (:egg/points e 0))
     :points-goal (long (:egg/points-goal e 0))
     :time-ms (long (:egg/time-ms e 0))
     :time-goal-ms (long (:egg/time-goal-ms e 0))
     :due-at (:egg/due-at e)
     :incubated-at (:egg/incubated-at e)
     :incubator-id (:egg/incubator-id e)
     :save-id (:egg/save-id e)
     :fence-generation (:egg/fence-generation e)
     :hatch-id (:egg/hatch-id e)
     :resultado (edn-read (:egg/resultado-edn e))
     :revision (long (:egg/revision e 0))}))

(defn account-shell
  [e edn-read present?]
  (when (present? e)
    (let [shell (or (edn-read (:adventure-account/state-edn e))
                    (eggs/estado-inicial))]
      (assoc shell
             :account-key (:adventure-account/key e)
             :revision (long (:adventure-account/revision e 0))))))

(defn merge-estado
  "Combina shell (incubadoras/receipts) + ovos das entidades."
  [shell egg-maps]
  (assoc (or shell (eggs/estado-inicial))
         :eggs (vec egg-maps)))

(defn build-ensure-account-tx
  [chat-id player-id]
  (let [ak (id/adventure-account-key chat-id player-id)
        init (eggs/estado-inicial)]
    {:tx [{:adventure-account/key ak
           :adventure-account/chat-id chat-id
           :adventure-account/player-id player-id
           :adventure-account/revision 0
           :adventure-account/state-edn
           (pr-str (select-keys init [:incubators :receipts]))}]
     :result {:account-key ak}}))

(defn build-grant-tx
  [{:keys [writer-ctx request account-shell egg-maps]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        ak (id/adventure-account-key chat-id player-id)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        ovo (eggs/novo-ovo (:egg-id request) (:category request) agora (:species request))
        estado (eggs/conceder-ovo (merge-estado account-shell egg-maps) ovo)
        rev (long (:revision account-shell 0))
        shell' (select-keys estado [:incubators :receipts])
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx [(egg->entity ak (assoc ovo :revision 0))
          [:db/add [:adventure-account/key ak]
           :adventure-account/state-edn (pr-str shell')]
          [:db/cas [:adventure-account/key ak]
           :adventure-account/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "egg-grant"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key chat-id player-id)
           :operation-receipt/save-id (:save-id writer-ctx)
           :operation-receipt/summary (pr-str {:egg-id (:egg-id ovo)})}]
     :result {:operation-id operation-id :duplicate false :egg ovo}}))

(defn build-incubate-tx
  [{:keys [writer-ctx request account-shell egg-maps]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        ak (id/adventure-account-key chat-id player-id)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        estado0 (merge-estado account-shell egg-maps)
        estado (eggs/incubar estado0
                             (:egg-id request)
                             (or (:incubator-id request) "permanente")
                             agora
                             (:save-id writer-ctx)
                             (:fence-generation writer-ctx))
        ovo (some #(when (= (:egg-id request) (:egg-id %)) %) (:eggs estado))
        prev (some #(when (= (:egg-id request) (:egg-id %)) %) egg-maps)
        expected-rev (long (:revision prev 0))
        rev (long (:revision account-shell 0))
        shell' (select-keys estado [:incubators :receipts])
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx [(egg->entity ak (assoc ovo :revision (inc expected-rev)))
          [:db/cas [:egg/id (:egg-id ovo)] :egg/revision expected-rev (inc expected-rev)]
          [:db/add [:adventure-account/key ak]
           :adventure-account/state-edn (pr-str shell')]
          [:db/cas [:adventure-account/key ak]
           :adventure-account/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "egg-incubate"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key chat-id player-id)
           :operation-receipt/save-id (:save-id writer-ctx)
           :operation-receipt/summary
           (pr-str {:egg-id (:egg-id ovo) :due-at (:due-at ovo)
                    :save-id (:save-id ovo)})}]
     :result {:operation-id operation-id :duplicate false :egg ovo}}))

(defn build-hatch-tx
  "Avança tempo se preciso, fixa resultado, adiciona à coleção do save carimbado."
  [{:keys [db writer-ctx request account-shell egg-maps save colecao
           pokemon-entity-map]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        ak (id/adventure-account-key chat-id player-id)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        estado0 (eggs/avancar-tempo (merge-estado account-shell egg-maps) agora)
        ovo0 (some #(when (= (:egg-id request) (:egg-id %)) %) (:eggs estado0))
        _ (when-not ovo0 (throw (err/error :egg_not_found {:egg-id (:egg-id request)})))
        _ (eggs/assert-hatch-save! ovo0 writer-ctx)
        hatch-id (or (:hatch-id request)
                     (:hatch-id ovo0)
                     (str "hatch-" (:egg-id ovo0)))
        pokemon-id (or (get-in ovo0 [:resultado :pokemon-id])
                       (coll/new-id-pokemon))
        ovo1 (eggs/fixar-resultado ovo0 hatch-id pokemon-id)
        ;; Capacidade do save de origem
        save-m {:expansoes-pokemon-50 (long (:save/expansoes-pokemon-50 save 0))
                :capacidade-pokemon-legada (long (:save/capacidade-pokemon-legada save 0))
                :trainer-level (long (:save/trainer-level save 1))
                :vitorias-treinador (long (:save/vitorias-treinador save 0))
                :pe-ginasios (long (:save/pe-ginasios save 0))
                :pe-raids (long (:save/pe-raids save 0))
                :xp-insignias (long (:save/xp-insignias save 0))
                :xp-missoes (long (:save/xp-missoes save 0))}
        _ (when-not (capacity/cabe-pokemon? (count colecao) save-m)
            (throw (err/error :collection_full {:count (count colecao)})))
        estado1 (eggs/concluir-hatch
                 (assoc estado0 :eggs
                        (mapv #(if (= (:egg-id %) (:egg-id ovo0)) ovo1 %)
                              (:eggs estado0)))
                 (:egg-id ovo0) agora)
        ovo-final (some #(when (= (:egg-id ovo0) (:egg-id %)) %) (:eggs estado1))
        prev (some #(when (= (:egg-id request) (:egg-id %)) %) egg-maps)
        expected-rev (long (:revision prev 0))
        rev (long (:revision account-shell 0))
        sk (id/save-key chat-id player-id (:save-id writer-ctx))
        save-rev (long (:save/revision save 0))
        reg (get-in ovo1 [:resultado :registro])
        shell' (select-keys estado1 [:incubators :receipts])
        operation-id (:operation-id request)
        phash (:payload-hash request)
        already? (= :hatched (:state prev))]
    (if already?
      {:tx []
       :result {:operation-id operation-id :duplicate true
                :egg prev :pokemon-id (get-in prev [:resultado :pokemon-id])}}
      {:tx [(egg->entity ak (assoc ovo-final :revision (inc expected-rev)))
            [:db/cas [:egg/id (:egg-id ovo0)] :egg/revision expected-rev (inc expected-rev)]
            [:db/add [:adventure-account/key ak]
             :adventure-account/state-edn (pr-str shell')]
            [:db/cas [:adventure-account/key ak]
             :adventure-account/revision rev (inc rev)]
            (pokemon-entity-map sk reg (count colecao))
            [:db/cas [:save/key sk] :save/revision save-rev (inc save-rev)]
            {:operation-receipt/id operation-id
             :operation-receipt/kind "egg-hatch"
             :operation-receipt/channel "gameplay"
             :operation-receipt/payload-hash phash
             :operation-receipt/player-key (id/player-key chat-id player-id)
             :operation-receipt/save-id (:save-id writer-ctx)
             :operation-receipt/summary
             (pr-str {:hatch-id hatch-id :pokemon-id pokemon-id})}]
       :result {:operation-id operation-id
                :duplicate false
                :egg ovo-final
                :pokemon-id pokemon-id
                :registro reg}})))

(defn build-joy-send-tx
  [{:keys [writer-ctx request save colecao enfermaria pokemon-entity-map]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        out (joy/enviar colecao enfermaria (long (:idx request)) agora)
        removed (nth colecao (long (:idx request)))
        rem-id (:id-pokemon removed)
        remaining (:colecao out)
        reindex (map-indexed
                 (fn [i poke]
                   [:db/add [:pokemon/id-pokemon (:id-pokemon poke)]
                    :pokemon/collection-index (long i)])
                 remaining)
        bed (:entrada out)
        bed-key (id/enfermaria-bed-key sk (:bed-id bed))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx (concat
          [[:db/retractEntity [:pokemon/id-pokemon rem-id]]]
          reindex
          [{:enfermaria-bed/key bed-key
            :enfermaria-bed/save-key sk
            :enfermaria-bed/bed-id (:bed-id bed)
            :enfermaria-bed/pokemon-edn (pr-str (:pokemon bed))
            :enfermaria-bed/pronto-em (:pronto-em bed)}
           [:db/cas [:save/key sk] :save/revision rev (inc rev)]
           {:operation-receipt/id operation-id
            :operation-receipt/kind "joy-send"
            :operation-receipt/channel "gameplay"
            :operation-receipt/payload-hash phash
            :operation-receipt/player-key (id/player-key chat-id player-id)
            :operation-receipt/save-id save-id
            :operation-receipt/summary
            (pr-str {:bed-id (:bed-id bed) :pronto-em (:pronto-em bed)})}])
     :result {:operation-id operation-id
              :duplicate false
              :entrada bed
              :pronto-em (:pronto-em bed)}}))

(defn build-joy-collect-tx
  [{:keys [writer-ctx request save colecao enfermaria pokemon-entity-map]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        sk (id/save-key chat-id player-id save-id)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        part (joy/curados enfermaria agora)
        start (count colecao)
        add-tx (map-indexed
                (fn [i reg]
                  (pokemon-entity-map sk
                                      (assoc reg :id-pokemon
                                             (or (:id-pokemon reg)
                                                 (coll/new-id-pokemon)))
                                      (+ start i)))
                (:registros part))
        retract-tx (map (fn [e]
                          [:db/retractEntity
                           [:enfermaria-bed/key
                            (id/enfermaria-bed-key sk (:bed-id e))]])
                        (:prontos part))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    (when (empty? (:prontos part))
      (throw (err/error :joy_nothing_ready {:agora agora})))
    {:tx (concat add-tx retract-tx
                 [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
                  {:operation-receipt/id operation-id
                   :operation-receipt/kind "joy-collect"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash phash
                   :operation-receipt/player-key (id/player-key chat-id player-id)
                   :operation-receipt/save-id save-id
                   :operation-receipt/summary
                   (pr-str {:n (count (:prontos part))})}])
     :result {:operation-id operation-id
              :duplicate false
              :curados (:registros part)
              :n (count (:prontos part))}}))
