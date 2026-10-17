(ns pokemon.infrastructure.datomic.raid-store
  "TX builders de raid (fora do store monolítico)."
  (:require [pokemon.domain.capture :as capture]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.raid :as raid]))

(defn participant->map
  [e edn-read present?]
  (when (present? e)
    {:key (:raid-participant/key e)
     :raid-id (:raid-participant/raid-id e)
     :player-key (:raid-participant/player-key e)
     :player-id (when-let [pk (:raid-participant/player-key e)]
                  (:player-id (id/parse-player-key pk)))
     :save-id (:raid-participant/save-id e)
     :fence-generation (long (:raid-participant/fence-generation e 0))
     :nome (:raid-participant/nome e)
     :pokemon (or (edn-read (:raid-participant/pokemon-edn e)) {})
     :hp (long (:raid-participant/hp e 0))
     :dano (long (:raid-participant/dano e 0))
     :collection-index (long (:raid-participant/collection-index e 0))}))

(defn raid->map
  [e participantes capturas edn-read present?]
  (when (present? e)
    (let [parts (into {}
                      (map (fn [p]
                             [(:player-id p) p])
                           participantes))
          caps (into {}
                     (map (fn [[pid c]]
                            [pid {:tentativas (long (:tentativas c 0))
                                  :capturou (boolean (:capturou c))}])
                          capturas))]
      {:raid-id (:raid/id e)
       :chat-id (:raid/chat-id e)
       :ginasio (:raid/ginasio e)
       :nome-ginasio (:raid/nome-ginasio e)
       :nivel-ginasio (long (:raid/nivel-ginasio e 1))
       :liga (:raid/liga e)
       :fase (:raid/fase e)
       :expira (:raid/expira e)
       :captura-expira (:raid/captura-expira e)
       :proxima (:raid/proxima e)
       :hp-chefe (long (:raid/hp-chefe e 0))
       :hp-max (long (:raid/hp-max e 0))
       :defesa (double (:raid/defesa e 50))
       :def-esp (double (:raid/def-esp e 50))
       :vez (:raid/vez e)
       :ordem (or (edn-read (:raid/ordem-edn e)) [])
       :chefe (or (edn-read (:raid/chefe-edn e)) {})
       :criador (:raid/criador e)
       :turn-index (long (:raid/turn-index e 0))
       :revision (long (:raid/revision e 0))
       :rewards-paid? (boolean (:raid/rewards-paid? e))
       :participantes parts
       :capturas caps})))

(defn- raid-entity-tx
  [r]
  (cond-> {:raid/id (:raid-id r)
           :raid/chat-id (:chat-id r)
           :raid/ginasio (:ginasio r)
           :raid/nome-ginasio (:nome-ginasio r)
           :raid/nivel-ginasio (long (:nivel-ginasio r 1))
           :raid/liga (:liga r)
           :raid/fase (str (:fase r))
           :raid/expira (long (:expira r))
           :raid/ordem-edn (pr-str (:ordem r))
           :raid/chefe-edn (pr-str (:chefe r))
           :raid/turn-index (long (:turn-index r 0))
           :raid/revision (long (:revision r 0))
           :raid/rewards-paid? (boolean (:rewards-paid? r))}
    (:captura-expira r) (assoc :raid/captura-expira (long (:captura-expira r)))
    (:proxima r) (assoc :raid/proxima (long (:proxima r)))
    (:hp-chefe r) (assoc :raid/hp-chefe (long (:hp-chefe r)))
    (:hp-max r) (assoc :raid/hp-max (long (:hp-max r)))
    (:defesa r) (assoc :raid/defesa (double (:defesa r)))
    (:def-esp r) (assoc :raid/def-esp (double (:def-esp r)))
    (:vez r) (assoc :raid/vez (:vez r))
    (:criador r) (assoc :raid/criador (:criador r))))

(defn- participant-entity
  [raid-id player-id chat-id p]
  {:raid-participant/key (id/raid-participant-key raid-id player-id)
   :raid-participant/raid-id raid-id
   :raid-participant/player-key (id/player-key chat-id player-id)
   :raid-participant/save-id (:save-id p)
   :raid-participant/fence-generation (long (:fence-generation p))
   :raid-participant/nome (:nome p)
   :raid-participant/pokemon-edn (pr-str (:pokemon p))
   :raid-participant/hp (long (:hp p))
   :raid-participant/dano (long (:dano p 0))
   :raid-participant/collection-index (long (:collection-index p 0))})

(defn build-open-tx
  [{:keys [request]}]
  (let [r (raid/nova-raid request)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        pk (id/player-key (:chat-id r) (or (:criador r) "system"))]
    {:tx [(raid-entity-tx r)
          {:chat-active-raid/chat-id (:chat-id r)
           :chat-active-raid/raid-id (:raid-id r)}
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-open"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key pk
           :operation-receipt/save-id (or (:save-id request) "a")
           :operation-receipt/summary (pr-str {:raid-id (:raid-id r)})}]
     :result {:operation-id operation-id
              :duplicate false
              :raid r}}))

(defn build-join-tx
  [{:keys [db save writer-ctx request raid colecao]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        fence (:fence-generation writer-ctx)
        idx (long (:collection-index request))
        poke (when (< idx (count colecao)) (nth colecao idx))
        _ (when-not poke (throw (err/error :pokemon_not_found {:idx idx})))
        agora (long (or (:agora request) (System/currentTimeMillis)))
        nome (or (:nome request) player-id)
        raid' (raid/entrar raid
                           {:player-id player-id
                            :nome nome
                            :save-id save-id
                            :fence-generation fence
                            :pokemon poke
                            :collection-index idx}
                           agora)
        p (get-in raid' [:participantes player-id])
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        expected-rev (long (:revision raid 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx [(raid-entity-tx (assoc raid' :revision (inc expected-rev)))
          [:db/cas [:raid/id (:raid-id raid)] :raid/revision expected-rev (inc expected-rev)]
          (participant-entity (:raid-id raid) player-id chat-id p)
          [:db/cas [:save/key sk] :save/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-join"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key chat-id player-id)
           :operation-receipt/save-id save-id
           :operation-receipt/summary (pr-str {:raid-id (:raid-id raid) :idx idx})}]
     :result {:operation-id operation-id
              :duplicate false
              :raid raid'
              :participant p}}))

(defn build-leave-tx
  [{:keys [save writer-ctx request raid]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        raid' (raid/sair raid player-id agora)
        expected-rev (long (:revision raid 0))
        sk (id/save-key chat-id player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        part-key (id/raid-participant-key (:raid-id raid) player-id)]
    {:tx [[:db/add [:raid/id (:raid-id raid)] :raid/fase "inscricoes"]
          [:db/add [:raid/id (:raid-id raid)] :raid/ordem-edn (pr-str (:ordem raid'))]
          [:db/cas [:raid/id (:raid-id raid)] :raid/revision expected-rev (inc expected-rev)]
          [:db/retractEntity [:raid-participant/key part-key]]
          [:db/cas [:save/key sk] :save/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-leave"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key chat-id player-id)
           :operation-receipt/save-id (:save-id writer-ctx)
           :operation-receipt/summary (pr-str {:raid-id (:raid-id raid)})}]
     :result {:operation-id operation-id :duplicate false :raid raid'}}))

(defn build-start-tx
  [{:keys [save writer-ctx request raid]}]
  (let [player-id (:player-id writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        raid' (raid/iniciar raid player-id agora)
        expected-rev (long (:revision raid 0))
        expected-turn (long (:turn-index raid 0))
        sk (id/save-key (:chat-id writer-ctx) player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx [(raid-entity-tx (assoc raid' :revision (inc expected-rev)))
          [:db/cas [:raid/id (:raid-id raid)] :raid/revision expected-rev (inc expected-rev)]
          [:db/cas [:raid/id (:raid-id raid)] :raid/turn-index expected-turn 0]
          [:db/cas [:save/key sk] :save/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-start"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) player-id)
           :operation-receipt/save-id (:save-id writer-ctx)
           :operation-receipt/summary (pr-str {:raid-id (:raid-id raid) :n (count (:ordem raid'))})}]
     :result {:operation-id operation-id :duplicate false :raid raid'}}))

(defn- reward-tx-for-participant
  [db raid player-id part apply-pokemon-xp-tx save-entity query-collection]
  (let [chat-id (:chat-id raid)
        save-id (:save-id part)
        sk (id/save-key chat-id player-id save-id)
        save (save-entity db chat-id player-id save-id)
        coins (long (:save/coins save 0))
        pe (long (:save/pe-raids save 0))
        rev (long (:save/revision save 0))
        receipt-id (str "raid-reward|" (:raid-id raid) "|" player-id)
        id-poke (or (get-in part [:pokemon :id-pokemon])
                    (get-in part [:pokemon "id-pokemon"]))
        live (when id-poke
               (some #(when (= (:id-pokemon %) id-poke) %)
                     (query-collection db sk)))
        xp-part (when live
                  (apply-pokemon-xp-tx live raid/recompensa-xp-pokemon))]
    {:tx (concat
          [[:db/cas [:save/key sk] :save/coins coins
            (model/apply-coin-delta coins raid/recompensa-moedas)]
           [:db/add [:save/key sk] :save/pe-raids (+ pe raid/recompensa-pe)]
           [:db/cas [:save/key sk] :save/revision rev (inc rev)]
           {:operation-receipt/id receipt-id
            :operation-receipt/kind "raid-reward"
            :operation-receipt/channel "gameplay"
            :operation-receipt/payload-hash
            (ops/payload-hash
             (ops/raid-reward-payload
              {:raid-id (:raid-id raid) :player-id player-id}))
            :operation-receipt/player-key (id/player-key chat-id player-id)
            :operation-receipt/save-id save-id
            :operation-receipt/summary
            (pr-str {:moedas raid/recompensa-moedas :pe raid/recompensa-pe})}]
          (or (:tx xp-part) []))
     :player-id player-id
     :moedas raid/recompensa-moedas
     :pe raid/recompensa-pe}))

(defn build-attack-tx
  [{:keys [db save writer-ctx request raid
           apply-pokemon-xp-tx save-entity query-collection]}]
  (let [player-id (:player-id writer-ctx)
        part (get-in raid [:participantes player-id])
        _ (raid/assert-participant-fence! part writer-ctx)
        expected-turn (long (or (:expected-turn-index request)
                                (:turn-index raid 0)))
        _ (when (not= expected-turn (long (:turn-index raid 0)))
            (throw (err/error :stale_turn_index
                              {:expected expected-turn
                               :actual (:turn-index raid)})))
        agora (long (or (:agora request) (System/currentTimeMillis)))
        move-index (long (:move-index request))
        outcome (raid/atacar raid player-id move-index agora)
        raid' (:raid outcome)
        expected-rev (long (:revision raid 0))
        sk (id/save-key (:chat-id writer-ctx) player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        pay-rewards? (and (:venceu? outcome) (not (:rewards-paid? raid)))
        reward-bundles (when pay-rewards?
                         (mapv (fn [[pid p]]
                                 (reward-tx-for-participant
                                  db raid' pid p
                                  apply-pokemon-xp-tx save-entity query-collection))
                               (raid/participantes-elegiveis raid')))
        part' (get-in raid' [:participantes player-id])
        ;; Atualiza HP/dano dos participantes vivos no snapshot
        other-parts (for [[pid p] (:participantes raid')
                          :when (not= pid player-id)]
                      (participant-entity (:raid-id raid) pid (:chat-id writer-ctx) p))
        raid-tx (concat
                 [(raid-entity-tx (assoc raid' :revision (inc expected-rev)
                                         :rewards-paid? (or (:rewards-paid? raid)
                                                            pay-rewards?)))
                  [:db/cas [:raid/id (:raid-id raid)] :raid/revision
                   expected-rev (inc expected-rev)]
                  [:db/cas [:raid/id (:raid-id raid)] :raid/turn-index
                   expected-turn (long (:turn-index raid'))]
                  (participant-entity (:raid-id raid) player-id
                                      (:chat-id writer-ctx) part')
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                  {:operation-receipt/id operation-id
                   :operation-receipt/kind "raid-attack"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash phash
                   :operation-receipt/player-key
                   (id/player-key (:chat-id writer-ctx) player-id)
                   :operation-receipt/save-id (:save-id writer-ctx)
                   :operation-receipt/summary
                   (pr-str {:dano (:dano outcome) :venceu? (:venceu? outcome)})}]
                 other-parts)]
    {:tx (into (vec raid-tx) (mapcat :tx reward-bundles))
     :result {:operation-id operation-id
              :duplicate false
              :dano (:dano outcome)
              :revide (:revide outcome)
              :venceu? (:venceu? outcome)
              :derrota? (:derrota? outcome)
              :raid raid'
              :rewards (mapv #(select-keys % [:player-id :moedas :pe])
                             (or reward-bundles []))}}))

(defn build-capture-tx
  [{:keys [db save writer-ctx request raid colecao
           pokemon-entity-map inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        part (get-in raid [:participantes player-id])
        _ (raid/assert-participant-fence! part writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        _ (when-not (raid/captura-pendente? raid player-id agora)
            (throw (err/error :raid_capture_unavailable {:player-id player-id})))
        bola (:bola request)
        chefe (:chefe raid)
        chance-base (raid/chance-captura (or (:raridade chefe) (get chefe "raridade")))
        chance (capture/chance-com-bola chance-base bola)
        roll (long (or (:roll request) 100))
        sucesso? (< roll chance)
        tentativas (long (get-in raid [:capturas player-id :tentativas] 0))
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        cap-key (id/raid-capture-key (:raid-id raid) player-id)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        item-tx (inventory-adjust-tx db sk bola -1)
        add-tx (when sucesso?
                 (let [reg (-> chefe
                               (dissoc :id-pokemon)
                               (assoc :nivel 1
                                      :hp-atual (or (:hp chefe) (get chefe "hp") 50)
                                      :status nil
                                      :id-pokemon (coll/new-id-pokemon)
                                      :nome (or (:nome chefe) (get chefe "nome") "Chefe")
                                      :species (or (:species chefe) (:nome chefe)
                                                   (get chefe "nome"))))]
                   [(pokemon-entity-map sk reg (count colecao))]))]
    {:tx (concat item-tx
                 (or add-tx [])
                 [{:raid-capture/key cap-key
                   :raid-capture/raid-id (:raid-id raid)
                   :raid-capture/player-id player-id
                   :raid-capture/tentativas (inc tentativas)
                   :raid-capture/capturou (or sucesso?
                                              (boolean (get-in raid [:capturas player-id :capturou])))}
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                  {:operation-receipt/id operation-id
                   :operation-receipt/kind "raid-capture"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash phash
                   :operation-receipt/player-key (id/player-key chat-id player-id)
                   :operation-receipt/save-id save-id
                   :operation-receipt/summary
                   (pr-str {:sucesso? sucesso? :chance chance :roll roll})}])
     :result {:operation-id operation-id
              :duplicate false
              :sucesso? sucesso?
              :chance chance
              :roll roll
              :tentativas (inc tentativas)}}))
