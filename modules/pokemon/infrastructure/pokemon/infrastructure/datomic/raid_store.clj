(ns pokemon.infrastructure.datomic.raid-store
  "TX builders de raid (fora do store monolítico)."
  (:require [pokemon.domain.battle :as battle]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.raid :as raid]
            [pokemon.domain.raid-auto :as raid-auto]
            [pokemon.infrastructure.datomic.battle-idle-store :as battle-idle-store]
            [pokemon.infrastructure.datomic.economy-store :as economy-store]))

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
                                  :capturou (boolean (:capturou c))
                                  :frambo-preparada (boolean (:frambo-preparada c))}])
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
       :criada-em (:raid/criada-em e)
       :min-jogadores (when (:raid/min-jogadores e) (long (:raid/min-jogadores e)))
       :max-jogadores (when (:raid/max-jogadores e) (long (:raid/max-jogadores e)))
       :turn-index (long (:raid/turn-index e 0))
       :revision (long (:raid/revision e 0))
       :rewards-paid? (boolean (:raid/rewards-paid? e))
       :origem (:raid/origem e)
       :last-activity-at (:raid/last-activity-at e)
       :idle-expires-at (:raid/idle-expires-at e)
       :idle-expired? (boolean (:raid/idle-expired? e))
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
    (:criador r) (assoc :raid/criador (:criador r))
    (:criada-em r) (assoc :raid/criada-em (long (:criada-em r)))
    (:min-jogadores r) (assoc :raid/min-jogadores (long (:min-jogadores r)))
    (:max-jogadores r) (assoc :raid/max-jogadores (long (:max-jogadores r)))
    (:origem r) (assoc :raid/origem (str (:origem r)))
    (:last-activity-at r) (assoc :raid/last-activity-at (long (:last-activity-at r)))
    (:idle-expires-at r) (assoc :raid/idle-expires-at (long (:idle-expires-at r)))
    (some? (:idle-expired? r)) (assoc :raid/idle-expired? (boolean (:idle-expired? r)))))

(defn schedule->map
  [e present?]
  (when (present? e)
    {:chat-id (:raid-auto-schedule/chat-id e)
     :enabled (boolean (:raid-auto-schedule/enabled e))
     :interval-ms (long (:raid-auto-schedule/interval-ms e
                                                         raid-auto/default-interval-ms))
     :next-at (long (:raid-auto-schedule/next-at e 0))
     :ginasio-index (long (:raid-auto-schedule/ginasio-index e 0))
     :revision (long (:raid-auto-schedule/revision e 0))
     :last-spawn-id (:raid-auto-schedule/last-spawn-id e)
     :last-raid-id (:raid-auto-schedule/last-raid-id e)}))

(defn outbox->map
  [e present?]
  (when (and (present? e) (nil? (:pokemon-outbox/acked-at e)))
    {:id (:pokemon-outbox/id e)
     :chat-id (:pokemon-outbox/chat-id e)
     :kind (:pokemon-outbox/kind e)
     :texto (:pokemon-outbox/texto e)
     :created-at (long (:pokemon-outbox/created-at e 0))}))

(defn- outbox-tx
  [{:keys [id chat-id kind texto created-at]}]
  {:pokemon-outbox/id id
   :pokemon-outbox/chat-id chat-id
   :pokemon-outbox/kind (str kind)
   :pokemon-outbox/texto (str texto)
   :pokemon-outbox/created-at (long created-at)})

(defn- schedule-entity-tx
  [s]
  (cond-> {:raid-auto-schedule/chat-id (:chat-id s)
           :raid-auto-schedule/enabled (boolean (:enabled s))
           :raid-auto-schedule/interval-ms (long (:interval-ms s))
           :raid-auto-schedule/next-at (long (:next-at s))
           :raid-auto-schedule/ginasio-index (long (:ginasio-index s 0))
           :raid-auto-schedule/revision (long (:revision s 0))}
    (:last-spawn-id s) (assoc :raid-auto-schedule/last-spawn-id (:last-spawn-id s))
    (:last-raid-id s) (assoc :raid-auto-schedule/last-raid-id (:last-raid-id s))))

(defn- encode-participant-pokemon-edn
  "Snapshot de combate no participante — sem historico/ofertas/legado.
   Mesma estratégia de caça/ginásio/Joy: slim + gzip se plain > limite Local (~4096).
   Coleção canônica permanece em :pokemon/*; não truncamos identificadores."
  [pokemon]
  (battle/encode-value-edn
   :raid-participant/pokemon-edn
   (battle/slim-pokemon-for-battle pokemon {:keep-images? true})))

(defn- participant-entity
  [raid-id player-id chat-id p]
  {:raid-participant/key (id/raid-participant-key raid-id player-id)
   :raid-participant/raid-id raid-id
   :raid-participant/player-key (id/player-key chat-id player-id)
   :raid-participant/save-id (:save-id p)
   :raid-participant/fence-generation (long (:fence-generation p))
   :raid-participant/nome (:nome p)
   :raid-participant/pokemon-edn (encode-participant-pokemon-edn (:pokemon p))
   :raid-participant/hp (long (:hp p))
   :raid-participant/dano (long (:dano p 0))
   :raid-participant/collection-index (long (:collection-index p 0))})

(defn build-open-tx
  "Abre lobby. `active-raid` = snapshot do ponteiro (já eager-expired se preciso)."
  [{:keys [request active-raid]}]
  (let [agora (long (or (:agora request) (System/currentTimeMillis)))
        _ (when (and active-raid (raid/lobby-ou-combate-vivo? active-raid agora))
            (throw (err/error :raid_chat_busy
                              {:raid-id (:raid-id active-raid)
                               :fase (:fase active-raid)})))
        r (assoc (raid/nova-raid request) :origem (or (:origem request) "manual"))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        pk (id/player-key (:chat-id r) (or (:criador r) "system"))
        chat-id (:chat-id r)
        ;; Ponteiro deve existir (ensure no store). CAS nil|old → novo id.
        expected-id (when active-raid (:raid-id active-raid))
        pointer-tx (if (= expected-id (:raid-id r))
                     []
                     [[:db/cas [:chat-active-raid/chat-id chat-id]
                       :chat-active-raid/raid-id expected-id (:raid-id r)]])]
    {:tx (into [(raid-entity-tx r)
                {:operation-receipt/id operation-id
                 :operation-receipt/kind "raid-open"
                 :operation-receipt/channel "gameplay"
                 :operation-receipt/payload-hash phash
                 :operation-receipt/player-key pk
                 :operation-receipt/save-id (or (:save-id request) "a")
                 :operation-receipt/summary (pr-str {:raid-id (:raid-id r)})}]
               pointer-tx)
     :result {:operation-id operation-id
              :duplicate false
              :raid r}}))

(defn build-upsert-schedule-tx
  [{:keys [schedule expected-revision]}]
  (let [s schedule
        expected (long (or expected-revision (:revision s 0)))
        next-rev (inc expected)
        s' (assoc s :revision next-rev)]
    {:tx (cond-> [(schedule-entity-tx s')]
           (pos? expected)
           (conj [:db/cas [:raid-auto-schedule/chat-id (:chat-id s)]
                  :raid-auto-schedule/revision expected next-rev]))
     :result {:schedule s'}}))

(defn build-auto-spawn-tx
  "Spawn automático atômico: receipt spawn-id + raid + ponteiro + agenda + outbox."
  [{:keys [plan schedule active-raid announce-texto agora]}]
  (let [acao (:acao plan)
        chat-id (:chat-id schedule)
        expected-rev (long (:revision schedule 0))
        next-rev (inc expected-rev)
        agora (long agora)]
    (case acao
      (:noop :skip-disabled)
      {:tx [] :result {:acao acao :schedule schedule :duplicate false}}

      :skip-busy
      (let [s' (assoc schedule
                      :next-at (:next-at plan)
                      :revision next-rev
                      :last-spawn-id (:spawn-id plan))
            rev-tx (when (pos? expected-rev)
                     [[:db/cas [:raid-auto-schedule/chat-id chat-id]
                       :raid-auto-schedule/revision expected-rev next-rev]])]
        {:tx (into [(schedule-entity-tx s')
                    {:operation-receipt/id (:spawn-id plan)
                     :operation-receipt/kind "raid-auto-skip"
                     :operation-receipt/channel "system"
                     :operation-receipt/payload-hash (:spawn-id plan)
                     :operation-receipt/player-key (id/player-key chat-id "system")
                     :operation-receipt/save-id "a"
                     :operation-receipt/summary
                     (pr-str {:acao :skip-busy :active (:active-raid-id plan)})}]
                   (or rev-tx []))
         :result {:acao :skip-busy :schedule s' :duplicate false}})

      :spawn
      (let [_ (when (raid-auto/ocupa-slot-contra-spawn? active-raid)
                (throw (err/error :raid_chat_busy
                                  {:raid-id (:raid-id active-raid)
                                   :fase (:fase active-raid)
                                   :origem (:origem active-raid)})))
            r (-> (raid/nova-raid {:raid-id (:raid-id plan)
                                   :chat-id chat-id
                                   :ginasio (:ginasio plan)
                                   :nome-ginasio (:nome-ginasio plan)
                                   :nivel-ginasio (:nivel-ginasio plan)
                                   :liga (:liga plan)
                                   :chefe (:chefe plan)
                                   :agora agora
                                   :criador nil})
                  (assoc :origem "auto"))
            expected-ptr (when active-raid (:raid-id active-raid))
            s' (assoc schedule
                      :next-at (:next-at plan)
                      :ginasio-index (:next-ginasio-index plan)
                      :revision next-rev
                      :last-spawn-id (:spawn-id plan)
                      :last-raid-id (:raid-id plan))
            event-id (raid-auto/outbox-opened-id (:raid-id plan))
            pointer-tx [[:db/cas [:chat-active-raid/chat-id chat-id]
                         :chat-active-raid/raid-id expected-ptr (:raid-id r)]]
            rev-tx (when (pos? expected-rev)
                     [[:db/cas [:raid-auto-schedule/chat-id chat-id]
                       :raid-auto-schedule/revision expected-rev next-rev]])]
        {:tx (into [(raid-entity-tx r)
                    (schedule-entity-tx s')
                    {:operation-receipt/id (:spawn-id plan)
                     :operation-receipt/kind "raid-auto-spawn"
                     :operation-receipt/channel "system"
                     :operation-receipt/payload-hash (:spawn-id plan)
                     :operation-receipt/player-key (id/player-key chat-id "system")
                     :operation-receipt/save-id "a"
                     :operation-receipt/summary
                     (pr-str {:raid-id (:raid-id r) :due-at (:due-at plan)})}
                    (outbox-tx {:id event-id
                                :chat-id chat-id
                                :kind "raid-opened"
                                :texto announce-texto
                                :created-at agora})]
                   (concat pointer-tx (or rev-tx [])))
         :result {:acao :spawn :schedule s' :raid r :duplicate false
                  :due-at (:due-at plan)
                  :outbox-id event-id}}))))

(defn build-auto-expire-announce-tx
  "Após expire de lobby auto: anuncia combate ou cancelamento via outbox.
   IDs estáveis por raid-id + expira do lobby (reenvio WA não recria jogo)."
  [{:keys [raid acao agora texto]}]
  (when (and (= "auto" (str (:origem raid)))
             (contains? #{:combate :cancelada} acao)
             (string? texto) (seq texto))
    (let [lobby-expira (long (:expira raid 0))
          event-id (if (= :combate acao)
                     (raid-auto/outbox-started-id (:raid-id raid) lobby-expira)
                     (raid-auto/outbox-cancelled-id (:raid-id raid) lobby-expira))
          kind (if (= :combate acao) "raid-started" "raid-cancelled")]
      {:tx [(outbox-tx {:id event-id
                        :chat-id (:chat-id raid)
                        :kind kind
                        :texto texto
                        :created-at (long agora)})]
       :result {:outbox-id event-id :kind kind}})))

(defn build-expire-lobby-tx
  "Materializa expiração de inscrições (cancelada ou combate)."
  [{:keys [raid agora announce-texto]}]
  (let [exp (raid/expirar-inscricoes raid agora)
        raid' (:raid exp)
        acao (:acao exp)
        expected-rev (long (:revision raid 0))
        chat-id (:chat-id raid)
        rid (:raid-id raid)
        announce (when (and announce-texto
                            (= "auto" (str (:origem raid)))
                            (contains? #{:combate :cancelada} acao))
                   (build-auto-expire-announce-tx
                    {:raid raid :acao acao :agora agora :texto announce-texto}))]
    (case acao
      :noop {:tx [] :result {:raid raid' :acao :noop}}
      :cancelada
      ;; Mantém entidade do ponteiro; só limpa raid-id (CAS nil na próxima open).
      {:tx (into [(raid-entity-tx (assoc raid' :revision (inc expected-rev)))
                  [:db/cas [:raid/id rid] :raid/revision
                   expected-rev (inc expected-rev)]
                  [:db/retract [:chat-active-raid/chat-id chat-id]
                   :chat-active-raid/raid-id rid]]
                 (or (:tx announce) []))
       :result {:raid raid' :acao :cancelada :outbox-id (:outbox-id (:result announce))}}
      :combate
      {:tx (into [(raid-entity-tx (assoc raid' :revision (inc expected-rev)))
                  [:db/cas [:raid/id rid] :raid/revision
                   expected-rev (inc expected-rev)]]
                 (or (:tx announce) []))
       :result {:raid raid' :acao :combate :outbox-id (:outbox-id (:result announce))}})))

(defn build-cancel-tx
  [{:keys [save writer-ctx request raid]}]
  (let [player-id (:player-id writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        raid' (raid/cancelar raid player-id agora)
        expected-rev (long (:revision raid 0))
        sk (id/save-key (:chat-id writer-ctx) player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        chat-id (:chat-id raid)
        rid (:raid-id raid)]
    {:tx [(raid-entity-tx (assoc raid' :revision (inc expected-rev)))
          [:db/cas [:raid/id rid] :raid/revision expected-rev (inc expected-rev)]
          [:db/retract [:chat-active-raid/chat-id chat-id]
           :chat-active-raid/raid-id rid]
          [:db/cas [:save/key sk] :save/revision rev (inc rev)]
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-cancel"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key (:chat-id writer-ctx) player-id)
           :operation-receipt/save-id (:save-id writer-ctx)
           :operation-receipt/summary (pr-str {:raid-id rid})}]
     :result {:operation-id operation-id :duplicate false :raid raid'}}))

(defn build-join-tx
  [{:keys [db save writer-ctx request raid colecao]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        fence (:fence-generation writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        already? (contains? (:participantes raid) player-id)
        old (when already? (get-in raid [:participantes player-id]))
        idx (long (:collection-index request))
        same-idx? (and already? (= idx (long (:collection-index old -1))))]
    (cond
      ;; Já inscrito com o mesmo mon → idempotente.
      (and already? same-idx?)
      {:tx [{:operation-receipt/id operation-id
             :operation-receipt/kind "raid-join"
             :operation-receipt/channel "gameplay"
             :operation-receipt/payload-hash phash
             :operation-receipt/player-key (id/player-key chat-id player-id)
             :operation-receipt/save-id save-id
             :operation-receipt/summary
             (pr-str {:raid-id (:raid-id raid) :already true :idx idx})}]
       :result {:operation-id operation-id
                :duplicate true
                :refreshed false
                :raid raid
                :participant old}}

      ;; Combate: combatente travado — não troca.
      (and already? (not (raid/inscricoes-abertas? raid agora)))
      (throw (err/error :raid_combatant_locked
                        {:fase (:fase raid)
                         :collection-index (:collection-index old)
                         :requested idx}))

      :else
      (let [poke (when (< idx (count colecao)) (nth colecao idx))
            _ (when-not poke (throw (err/error :pokemon_not_found {:idx idx})))
            _ (when-not (raid/pokemon-elegivel-liga? (:liga raid) poke)
                (throw (err/error :raid_liga_mismatch
                                  {:liga (:liga raid)
                                   :nivel (:nivel poke)
                                   :idx idx})))
            nome (or (:nome request) (:nome old) player-id)
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
            expected-rev (long (:revision raid 0))]
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
               :operation-receipt/summary
               (pr-str {:raid-id (:raid-id raid) :idx idx :refreshed already?})}]
         :result {:operation-id operation-id
                  :duplicate false
                  :refreshed (boolean already?)
                  :raid raid'
                  :participant p}}))))

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
        idle (battle-idle-store/raid-touch-attrs agora)
        raid' (-> (raid/iniciar raid player-id agora)
                  (assoc :last-activity-at (:raid/last-activity-at idle)
                         :idle-expires-at (:raid/idle-expires-at idle)
                         :idle-expired? false))
        expected-rev (long (:revision raid 0))
        expected-turn (long (:turn-index raid 0))
        sk (id/save-key (:chat-id writer-ctx) player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)]
    {:tx [(merge (raid-entity-tx (assoc raid' :revision (inc expected-rev))) idle)
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
        pe (long (:save/pe-raids save 0))
        rev (long (:save/revision save 0))
        receipt-id (str "raid-reward|" (:raid-id raid) "|" player-id)
        id-poke (or (get-in part [:pokemon :id-pokemon])
                    (get-in part [:pokemon "id-pokemon"]))
        live (when id-poke
               (some #(when (= (:id-pokemon %) id-poke) %)
                     (query-collection db sk)))
        xp-qtd (if live raid/recompensa-xp-pokemon 0)
        xp-part (when live
                  (apply-pokemon-xp-tx live xp-qtd))
        coin-part (economy-store/apply-delta-tx
                   {:sk sk :save save :delta raid/recompensa-moedas
                    :operation-id receipt-id :source "raid" :role "primary"})]
    {:tx (concat
          [[:db/add [:save/key sk] :save/pe-raids (+ pe raid/recompensa-pe)]
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
            (pr-str {:moedas raid/recompensa-moedas
                     :pe raid/recompensa-pe
                     :xp-pokemon xp-qtd})}]
          (:tx coin-part)
          (or (:tx xp-part) []))
     :player-id player-id
     :moedas raid/recompensa-moedas
     :pe raid/recompensa-pe
     :xp-pokemon xp-qtd
     :pokemon-id id-poke}))

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
        _ (battle-idle-store/assert-battle-not-idle-expired! raid agora)
        move-index (long (:move-index request))
        outcome (raid/atacar raid player-id move-index agora)
        raid' (:raid outcome)
        still-combat? (= "combate" (str (:fase raid')))
        idle (when still-combat? (battle-idle-store/raid-touch-attrs agora))
        raid' (cond-> raid'
                idle (assoc :last-activity-at (:raid/last-activity-at idle)
                            :idle-expires-at (:raid/idle-expires-at idle)
                            :idle-expired? false))
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
                 [(cond-> (raid-entity-tx (assoc raid' :revision (inc expected-rev)
                                                 :rewards-paid? (or (:rewards-paid? raid)
                                                                    pay-rewards?)))
                    idle (merge idle))
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
              :rewards (mapv #(select-keys % [:player-id :moedas :pe :xp-pokemon :pokemon-id])
                             (or reward-bundles []))}}))

(defn build-frambo-prepare-tx
  "Prepara Frambo para a próxima tentativa — não consome ainda."
  [{:keys [db save writer-ctx request raid inventory-adjust-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        part (get-in raid [:participantes player-id])
        _ (raid/assert-participant-fence! part writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        _ (when-not (raid/captura-pendente? raid player-id agora)
            (throw (err/error :raid_capture_unavailable {:player-id player-id})))
        cap (get-in raid [:capturas player-id])
        _ (when (:frambo-preparada cap)
            (throw (err/error :frambo_already_prepared {:player-id player-id})))
        sk (id/save-key chat-id player-id save-id)
        ;; Valida qty>=1 sem incluir o débito no TX (consumo só na captura).
        _ (inventory-adjust-tx db sk capture/frambo-item-id -1)
        rev (long (:save/revision save 0))
        cap-key (id/raid-capture-key (:raid-id raid) player-id)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        tentativas (long (:tentativas cap 0))]
    {:tx [[:db/cas [:save/key sk] :save/revision rev (inc rev)]
          {:raid-capture/key cap-key
           :raid-capture/raid-id (:raid-id raid)
           :raid-capture/player-id player-id
           :raid-capture/tentativas tentativas
           :raid-capture/capturou (boolean (:capturou cap))
           :raid-capture/frambo-preparada true}
          {:operation-receipt/id operation-id
           :operation-receipt/kind "raid-frambo-prep"
           :operation-receipt/channel "gameplay"
           :operation-receipt/payload-hash phash
           :operation-receipt/player-key (id/player-key chat-id player-id)
           :operation-receipt/save-id save-id
           :operation-receipt/summary
           (pr-str {:raid-id (:raid-id raid) :frambo true})}]
     :result {:operation-id operation-id
              :duplicate false
              :frambo-preparada true
              :tentativas tentativas}}))

(defn build-capture-tx
  [{:keys [db save writer-ctx request raid colecao save-m
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
        prepared? (boolean (get-in raid [:capturas player-id :frambo-preparada]))
        ;; `:frambo true` no request permite operação combinada (prepare+uso).
        frambo? (or prepared? (boolean (:frambo request)))
        chance (capture/chance-com-bola-frambo chance-base bola frambo?)
        ;; Produção: RNG local. Testes/smoke injetam `:roll` (0–99).
        ;; Bug 12.6: default antigo 100 ⇒ (< roll chance) sempre falso.
        roll (long (or (:roll request) (rand-int 100)))
        sucesso? (< roll chance)
        tentativas (long (get-in raid [:capturas player-id :tentativas] 0))
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        cap-key (id/raid-capture-key (:raid-id raid) player-id)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        storage (capacity/resumo-armazenamento-pokemon (count colecao) (or save-m save))
        _ (when (:cheio? storage)
            (throw (err/error :collection_full
                              {:count (:ocupacao storage)
                               :capacidade (:capacidade storage)
                               :excesso (:excesso storage)
                               :nivel (:nivel storage)})))
        item-tx (inventory-adjust-tx db sk bola -1)
        frambo-tx (when frambo?
                    (inventory-adjust-tx db sk capture/frambo-item-id -1))
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
                 (or frambo-tx [])
                 (or add-tx [])
                 [{:raid-capture/key cap-key
                   :raid-capture/raid-id (:raid-id raid)
                   :raid-capture/player-id player-id
                   :raid-capture/tentativas (inc tentativas)
                   :raid-capture/capturou (or sucesso?
                                              (boolean (get-in raid [:capturas player-id :capturou])))
                   :raid-capture/frambo-preparada false}
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                  {:operation-receipt/id operation-id
                   :operation-receipt/kind "raid-capture"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash phash
                   :operation-receipt/player-key (id/player-key chat-id player-id)
                   :operation-receipt/save-id save-id
                   :operation-receipt/summary
                   (pr-str {:sucesso? sucesso? :chance chance :roll roll
                            :frambo? frambo?})}])
     :result {:operation-id operation-id
              :duplicate false
              :sucesso? sucesso?
              :chance chance
              :chance-base chance-base
              :roll roll
              :frambo? frambo?
              :bola bola
              :tentativas (inc tentativas)}}))
