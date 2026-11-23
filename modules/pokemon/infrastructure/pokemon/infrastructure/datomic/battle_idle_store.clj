(ns pokemon.infrastructure.datomic.battle-idle-store
  "Persistência + expiração idempotente do idle timeout de batalhas/raids."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.raid :as raid-domain]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.pvp-store :as pvp-store]
            [pokemon.infrastructure.datomic.trainer-progression :as trainer-progression]))

(defn- present? [e] (boolean (:db/id e)))

(defn- edn-read [s]
  (battle/decode-compact-edn s))

(defn touch-attrs
  "Attrs Datomic para criar/renovar janela de idle."
  ([agora] (touch-attrs agora (idle/idle-timeout-ms)))
  ([agora timeout-ms]
   (let [t (idle/touch agora timeout-ms)]
     {:battle/last-activity-at (:last-activity-at t)
      :battle/idle-expires-at (:idle-expires-at t)
      :battle/idle-expired? false})))

(defn raid-touch-attrs
  ([agora] (raid-touch-attrs agora (idle/idle-timeout-ms)))
  ([agora timeout-ms]
   (let [t (idle/touch agora timeout-ms)]
     {:raid/last-activity-at (:last-activity-at t)
      :raid/idle-expires-at (:idle-expires-at t)
      :raid/idle-expired? false})))

(defn touch-tx
  [battle-id agora]
  (let [a (touch-attrs agora)]
    [[:db/add [:battle/id battle-id] :battle/last-activity-at (:battle/last-activity-at a)]
     [:db/add [:battle/id battle-id] :battle/idle-expires-at (:battle/idle-expires-at a)]
     [:db/add [:battle/id battle-id] :battle/idle-expired? false]]))

(defn raid-touch-tx
  [raid-id agora]
  (let [a (raid-touch-attrs agora)]
    [[:db/add [:raid/id raid-id] :raid/last-activity-at (:raid/last-activity-at a)]
     [:db/add [:raid/id raid-id] :raid/idle-expires-at (:raid/idle-expires-at a)]
     [:db/add [:raid/id raid-id] :raid/idle-expired? false]]))

(defn idle-fields-from-entity
  "Campos de idle a mesclar em ->battle."
  [e]
  (cond-> {}
    (some? (:battle/last-activity-at e))
    (assoc :last-activity-at (:battle/last-activity-at e))
    (some? (:battle/idle-expires-at e))
    (assoc :idle-expires-at (:battle/idle-expires-at e))
    (some? (:battle/idle-expired? e))
    (assoc :idle-expired? (boolean (:battle/idle-expired? e)))))

(defn- notify-tx
  [battle-id chat-id player-id payload agora]
  (let [nid (idle/notify-id battle-id player-id)]
    {:battle-notify/id nid
     :battle-notify/kind "battle-idle-timeout"
     :battle-notify/battle-id (str battle-id)
     :battle-notify/chat-id (str chat-id)
     :battle-notify/player-id (str player-id)
     :battle-notify/payload-edn (pr-str payload)
     :battle-notify/created-at (long agora)
     :battle-notify/acked? false}))

(defn- player-ids-of
  [state]
  (->> (vals (or (:jogadores state) {}))
       (cons (:player-id state))
       (remove nil?)
       distinct
       vec))

(defn- clear-wild-pointers-tx
  [db chat-id battle-id player-id]
  (let [pk (when player-id (id/player-key chat-id player-id))
        g (d/pull db '[*] [:group/chat-id chat-id])
        clear-player (when pk
                       [[:db/retract [:player/key pk] :player/active-battle-id battle-id]])
        clear-group (when (and (present? g)
                               (= battle-id (:group/active-wild-battle-id g)))
                      [[:db/retract [:group/chat-id chat-id] :group/active-wild-battle-id battle-id]
                       (when (= player-id (:group/active-wild-player-id g))
                         [:db/retract [:group/chat-id chat-id] :group/active-wild-player-id player-id])])]
    (into [] (concat clear-player (remove nil? clear-group)))))

(defn- clear-pvp-pointers-tx
  [db chat-id battle-id state]
  (let [pids (player-ids-of state)
        clear-players (mapcat (fn [pid]
                                [[:db/retract [:player/key (id/player-key chat-id pid)]
                                  :player/active-battle-id battle-id]])
                              pids)
        clear-chat (or (pvp-store/clear-chat-pvp-tx db chat-id battle-id) [])]
    (into [] (concat clear-players clear-chat))))

(defn- clear-raid-pointer-tx
  [db chat-id raid-id]
  (let [e (d/pull db '[*] [:chat-active-raid/chat-id chat-id])]
    (when (and (present? e) (= raid-id (:chat-active-raid/raid-id e)))
      [[:db/retractEntity (:db/id e)]])))

(defn- sequencia-break-tx
  [db chat-id player-id save-id]
  (when (and chat-id player-id save-id)
    (let [sk (id/save-key chat-id player-id save-id)
          save (d/pull db '[:save/sequencia-capturas :save/maior-sequencia-capturas]
                       [:save/key sk])]
      (when (present? save)
        (let [seq-atual (long (:save/sequencia-capturas save 0))
              maior (long (:save/maior-sequencia-capturas save 0))]
          [[:db/add [:save/key sk] :save/sequencia-capturas 0]
           [:db/add [:save/key sk] :save/maior-sequencia-capturas
            (max maior seq-atual)]])))))

(defn build-expire-battle-tx
  "TX idempotente de expiração. Sem recompensas/rank/derrota de ginásio.
  Sem `:force?`, recusa se `idle-expires-at` ainda não venceu — protege contra
  scheduler atrasado após renovação (CAS sozinho não basta se o pull já vê o
  novo deadline)."
  ([db battle-id agora] (build-expire-battle-tx db battle-id agora nil))
  ([db battle-id agora {:keys [force?]}]
  (let [be (d/pull db '[*] [:battle/id battle-id])]
    (when-not (present? be)
      (throw (err/error :battle_not_found {:battle-id battle-id})))
    (let [op-id (idle/receipt-operation-id battle-id)
          existing (d/pull db '[:operation-receipt/id] [:operation-receipt/id op-id])]
      (if (present? existing)
        {:tx []
         :result {:operation-id op-id :duplicate true :battle-id battle-id
                  :already-expired? true}}
        (let [state0 (or (edn-read (:battle/state-edn be)) {})
              state0 (merge state0 (idle-fields-from-entity be)
                            {:battle-id (:battle/id be)
                             :kind (keyword (:battle/kind be "wild"))
                             :active? (boolean (:battle/active? be))
                             :phase (keyword (:battle/phase be "active"))})
              already? (or (true? (:battle/idle-expired? be))
                           (= :idle-timeout (:outcome state0))
                           (and (false? (:active? state0))
                                (not (:aguardando-captura? state0))
                                (= :finished (:phase state0))))
              observed-expires (:battle/idle-expires-at be)
              still-fresh? (and (not force?)
                                (not already?)
                                (some? observed-expires)
                                (not (idle/expired? observed-expires agora)))]
          (if still-fresh?
            {:tx []
             :result {:operation-id op-id
                      :duplicate false
                      :battle-id battle-id
                      :skipped "deadline-not-due"
                      :already-expired? false
                      :idle-expires-at observed-expires}}
          (let [state' (idle/expire-battle state0)
              kind (:kind state0)
              chat-id (or (:chat-id state0) (:battle/chat-id be))
              ;; Fallback: player-key "chat|player"
              pk (:battle/player-key be)
              fallback-player (when (and (string? pk) (re-find #"\|" pk))
                                (second (str/split pk #"\|" 2)))
              player-id (or (:player-id state0) fallback-player
                            (get-in state0 [:jogadores :x]))
              save-id (or (:save-id state0) (:battle/save-id be)
                          (get-in state0 [:saves :x]))
              pids (player-ids-of state0)
              pids (if (seq pids) pids (cond-> [] player-id (conj player-id)))
              timeout-s (idle/idle-timeout-segundos)
              wild-name (get-in state0 [:pokemons :o :nome])
              payload (idle/notification-payload
                       {:battle-id battle-id
                        :kind kind
                        :chat-id chat-id
                        :player-ids pids
                        :timeout-seconds timeout-s
                        :wild-name wild-name})
              notify (mapv #(notify-tx battle-id chat-id % payload agora) pids)
              clear (case kind
                      :pvp (clear-pvp-pointers-tx db chat-id battle-id state0)
                      (clear-wild-pointers-tx db chat-id battle-id player-id))
              seq-break (when (and (= :wild kind) (not already?))
                          (sequencia-break-tx db chat-id player-id save-id))
              core [{:battle/id battle-id
                     :battle/active? false
                     :battle/phase "finished"
                     :battle/idle-expired? true
                     :battle/state-edn (battle/encode-state-edn state')}
                    {:operation-receipt/id op-id
                     :operation-receipt/kind "battle-idle-expire"
                     :operation-receipt/channel "gameplay"
                     :operation-receipt/payload-hash
                     (str "idle-expire|" battle-id)
                     :operation-receipt/player-key (or pk (str chat-id "|" player-id))
                     :operation-receipt/save-id (or save-id "a")
                     :operation-receipt/summary
                     (pr-str {:battle-id battle-id :outcome :idle-timeout :kind kind})}]
              ;; CAS: idle-expired? + idle-expires-at observado.
              ;; Se um turno válido renovou expires-at, este expire aborta (conflito)
              ;; e não encerra batalha já prolongada.
              cas (cond-> []
                    (not (true? (:battle/idle-expired? be)))
                    (conj [:db/cas [:battle/id battle-id] :battle/idle-expired?
                           (:battle/idle-expired? be) true])
                    (some? observed-expires)
                    (conj [:db/cas [:battle/id battle-id] :battle/idle-expires-at
                           observed-expires observed-expires]))]
          {:tx (into [] (concat core cas clear (or seq-break []) notify))
           :result {:operation-id op-id
                    :duplicate false
                    :battle-id battle-id
                    :battle state'
                    :notifications notify
                    :already-expired? already?}}))))))))

(defn build-expire-raid-tx
  "Encerra combate por idle. Lobby (`inscricoes`) é no-op — o tick de raid-auto
   já materializa cancelada/combate no prazo do lobby.
   CAS em idle-expires-at observado: sweeper atrasado não mata raid renovada."
  ([db raid-id agora] (build-expire-raid-tx db raid-id agora nil))
  ([db raid-id agora {:keys [force?]}]
  (let [re (d/pull db '[*] [:raid/id raid-id])]
    (when-not (present? re)
      (throw (err/error :raid_not_found {:raid-id raid-id})))
    (let [fase (str (:raid/fase re))
          op-id (str "raid-idle-expire|" raid-id)
          existing (d/pull db '[:operation-receipt/id] [:operation-receipt/id op-id])]
      (cond
        (present? existing)
        {:tx []
         :result {:operation-id op-id :duplicate true :raid-id raid-id
                  :already-expired? true}}

        ;; Nunca encerrar vitória/derrota/raid já finalizada como idle.
        (= "inscricoes" fase)
        {:tx []
         :result {:operation-id op-id :duplicate false :raid-id raid-id
                  :skipped "lobby" :already-expired? false}}

        (not= "combate" fase)
        {:tx []
         :result {:operation-id op-id :duplicate false :raid-id raid-id
                  :skipped "not-combat" :already-expired? true}}

        :else
        (let [chat-id (:raid/chat-id re)
              already? (or (true? (:raid/idle-expired? re))
                           (contains? #{"vitoria" "derrota" "expirada" "idle-timeout"} fase))
              observed-expires (:raid/idle-expires-at re)
              still-fresh? (and (not force?)
                                (not already?)
                                (some? observed-expires)
                                (not (idle/expired? observed-expires agora)))]
          (if still-fresh?
            {:tx []
             :result {:operation-id op-id
                      :duplicate false
                      :raid-id raid-id
                      :skipped "deadline-not-due"
                      :already-expired? false
                      :idle-expires-at observed-expires}}
            (let [parts (d/q '[:find ?pid ?sid
                               :in $ ?rid
                               :where
                               [?p :raid-participant/raid-id ?rid]
                               [?p :raid-participant/player-key ?pk]
                               [?p :raid-participant/save-id ?sid]
                               [?pl :player/key ?pk]
                               [?pl :player/player-id ?pid]]
                             db raid-id)
                  pids (mapv first parts)
                  save-by-pid (into {} (map (fn [[pid sid]] [pid sid]) parts))
                  culprit (:raid/vez re)
                  culprit-save (get save-by-pid culprit)
                  chefe (or (edn-read (:raid/chefe-edn re)) {})
                  boss-name (or (:nome chefe) (get chefe "nome"))
                  timeout-s (idle/idle-timeout-segundos)
                  payload (idle/notification-payload
                           {:battle-id raid-id
                            :kind :raid
                            :chat-id chat-id
                            :player-ids pids
                            :timeout-seconds timeout-s
                            :boss-name boss-name
                            :mentions (cond-> [] culprit (conj culprit))})
                  ;; Uma mensagem por raid (chat), não por participante.
                  notify [(assoc (notify-tx raid-id chat-id "chat" payload agora)
                                 :battle-notify/id (idle/raid-chat-notify-id raid-id)
                                 :battle-notify/player-id (or culprit "chat"))]
                  clear (let [e (d/pull db '[*] [:chat-active-raid/chat-id chat-id])]
                          (when (and (present? e) (= raid-id (:chat-active-raid/raid-id e)))
                            [[:db/retract [:chat-active-raid/chat-id chat-id]
                              :chat-active-raid/raid-id raid-id]]))
                  seq-break (when (and culprit culprit-save (not already?))
                              (sequencia-break-tx db chat-id culprit culprit-save))
                  core [{:raid/id raid-id
                         :raid/idle-expired? true}
                        {:operation-receipt/id op-id
                         :operation-receipt/kind "raid-idle-expire"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash (str "raid-idle-expire|" raid-id)
                         :operation-receipt/player-key (str chat-id "|raid")
                         :operation-receipt/save-id "a"
                         :operation-receipt/summary
                         (pr-str {:raid-id raid-id
                                  :fase :idle-timeout
                                  :culprit-present (boolean culprit)
                                  :rule "global-idle"
                                  :timeout-seconds timeout-s})}]
                  ;; CAS de fase protege vitória/derrota que finalizou após o read.
                  ;; Não converter uma raid vencida em timeout, nem zerar streak.
                  cas (cond-> [[:db/cas [:raid/id raid-id] :raid/fase "combate" "idle-timeout"]]
                        (not (true? (:raid/idle-expired? re)))
                        (conj [:db/cas [:raid/id raid-id] :raid/idle-expired?
                               (:raid/idle-expired? re) true])
                        (some? observed-expires)
                        (conj [:db/cas [:raid/id raid-id] :raid/idle-expires-at
                               observed-expires observed-expires]))]
              {:tx (into [] (concat core cas (or clear []) (or seq-break []) notify))
               :result {:operation-id op-id
                        :duplicate false
                        :raid-id raid-id
                        :already-expired? already?
                        :culprit culprit
                        :notifications notify}}))))))))

(defn- open-battle-entity?
  "Ativa, aguardando captura, ou desafio PvP waiting — ainda 'pendente'."
  [e]
  (when (and (:battle/id e) (not (true? (:battle/idle-expired? e))))
    (let [st (or (edn-read (:battle/state-edn e)) {})
          phase (keyword (or (:battle/phase e) (:phase st)))]
      (boolean
       (or (true? (:battle/active? e))
           (true? (:aguardando-captura? st))
           (= :waiting phase))))))

(defn find-open-battle-ids
  "Todas as batalhas ainda abertas (independente do prazo idle)."
  [db]
  (->> (d/q '[:find (pull ?e [:db/id :battle/id :battle/active? :battle/idle-expired?
                              :battle/phase :battle/state-edn])
              :where [?e :battle/id]]
            db)
       (map first)
       (filter open-battle-entity?)
       (mapv :battle/id)))

(defn find-open-raid-ids
  "Raids em inscrição ou combate (não idle-expired).
   Uso: force-expire admin. Idle sweeper NÃO mata lobby — ver find-combat-raid-ids."
  [db]
  (->> (d/q '[:find ?rid ?fase
              :where
              [?e :raid/id ?rid]
              [?e :raid/fase ?fase]]
            db)
       (filter (fn [[rid fase]]
                 (and (contains? #{"inscricoes" "combate"} (str fase))
                      (not (true? (:raid/idle-expired?
                                   (d/pull db '[:raid/idle-expired?]
                                           [:raid/id rid])))))))
       (mapv first)))

(defn find-combat-raid-ids
  "Só combate ativo. Idle de batalha não se aplica ao lobby (`inscricoes`).
   Bug pré-fix: backfill/expire usavam find-open-raid-ids e matavam lobbies
   de 20–30 min com timeout de 5 min, limpando o ponteiro → RAID_NOT_FOUND."
  [db]
  (->> (d/q '[:find ?rid ?fase
              :where
              [?e :raid/id ?rid]
              [?e :raid/fase ?fase]]
            db)
       (filter (fn [[rid fase]]
                 (and (= "combate" (str fase))
                      (not (true? (:raid/idle-expired?
                                   (d/pull db '[:raid/idle-expired?]
                                           [:raid/id rid])))))))
       (mapv first)))


(defn find-turn-expired-raid-ids
  "Raids em combate com prazo de turno vencido (≥2 aptos gravado via turn-expires-at)."
  [db agora]
  (->> (d/q '[:find ?rid ?texp
              :where
              [?e :raid/id ?rid]
              [?e :raid/fase "combate"]
              [?e :raid/turn-expires-at ?texp]]
            db)
       (filter (fn [[rid texp]]
                 (and (some? texp)
                      (idle/expired? texp agora)
                      (not (true? (:raid/idle-expired?
                                   (d/pull db '[:raid/idle-expired?]
                                           [:raid/id rid])))))))
       (mapv first)))

(defn build-skip-turn-tx
  "Passagem automática de turno — sem revide/dano/itens. Não renova idle global.
   CAS: turn-index + turn-expires-at observados."
  [db raid-id agora]
  (let [re (d/pull db '[*] [:raid/id raid-id])]
    (when-not (present? re)
      (throw (err/error :raid_not_found {:raid-id raid-id})))
    (let [fase (str (:raid/fase re))
          turn-index (long (:raid/turn-index re 0))
          observed-texp (:raid/turn-expires-at re)
          op-id (idle/turn-skip-receipt-id raid-id turn-index)
          existing (d/pull db '[:operation-receipt/id] [:operation-receipt/id op-id])]
      (cond
        (present? existing)
        {:tx []
         :result {:operation-id op-id :duplicate true :raid-id raid-id
                  :turn-index turn-index}}

        (not= "combate" fase)
        {:tx []
         :result {:operation-id op-id :duplicate false :raid-id raid-id
                  :skipped "not-combat"}}

        (nil? observed-texp)
        {:tx []
         :result {:operation-id op-id :duplicate false :raid-id raid-id
                  :skipped "no-turn-deadline"}}

        (not (idle/expired? observed-texp agora))
        {:tx []
         :result {:operation-id op-id :duplicate false :raid-id raid-id
                  :skipped "deadline-not-due"
                  :turn-expires-at observed-texp}}

        :else
        ;; Carrega mapa mínimo via attrs da entidade + participantes query
        (let [parts (->> (d/q '[:find (pull ?p [*])
                                :in $ ?rid
                                :where [?p :raid-participant/raid-id ?rid]]
                              db raid-id)
                         (map first)
                         (map (fn [p]
                                (let [pk (:raid-participant/player-key p)
                                      pl (d/pull db '[:player/player-id] [:player/key pk])
                                      pid (:player/player-id pl)
                                      snap (or (edn-read (:raid-participant/pokemon-edn p)) {})
                                      ;; O snapshot v2 guarda :active/:party no bundle.
                                      ;; Não passar o bundle inteiro como Pokémon para o painel.
                                      bundle? (boolean (:__raid-party-v1 snap))
                                      party (if bundle? (vec (or (:party snap) [])) [])
                                      active-index (long (if bundle? (:active-idx snap 0) 0))
                                      pokemon (if bundle?
                                                (or (:active snap)
                                                    (:pokemon (nth party active-index nil))
                                                    {})
                                                snap)
                                      hp (long (or (:raid-participant/hp p)
                                                   (:hp snap) 0))]
                                  [pid {:hp hp
                                        :nome (:raid-participant/nome p)
                                        :pokemon pokemon
                                        :save-id (:raid-participant/save-id p)
                                        :fence-generation (:raid-participant/fence-generation p)
                                        :party party
                                        :active-party-idx active-index
                                        :collection-index (long (:raid-participant/collection-index p 0))}])))
                         (into {}))
              ordem (or (edn-read (:raid/ordem-edn re)) [])
              raid {:raid-id raid-id
                    :chat-id (:raid/chat-id re)
                    :fase fase
                    :vez (:raid/vez re)
                    :ordem ordem
                    :turn-index turn-index
                    :expira (:raid/expira re)
                    :idle-expires-at (:raid/idle-expires-at re)
                    :idle-expired? (boolean (:raid/idle-expired? re))
                    :turn-expires-at observed-texp
                    :participantes parts
                    :chefe (or (edn-read (:raid/chefe-edn re)) {})
                    :hp-chefe (long (:raid/hp-chefe re 0))
                    :hp-max (long (:raid/hp-max re 0))}
              outcome (raid-domain/pular-turno raid agora)
              raid' (:raid outcome)
              aptos (raid-domain/treinadores-aptos raid')
              window (raid-domain/turn-window agora aptos (idle/turn-timeout-ms))
              raid' (merge raid' window)
              skipped (:skipped-player outcome)
              next-pid (:next-player outcome)
              timeout-s (idle/turn-timeout-segundos)
              chat-id (:raid/chat-id re)
              ;; Snapshot canônico do próximo turno para o painel WhatsApp.
              next-part (get-in raid' [:participantes next-pid])
              raid-dto {:raid-id raid-id
                        :fase "combate"
                        :turn-index (:turn-index raid')
                        :vez next-pid
                        :vez-nome (:nome next-part)
                        :vez-hp (:hp next-part)
                        :vez-pokemon (:pokemon next-part)
                        :hp-chefe (:hp-chefe raid')
                        :hp-max (:hp-max raid')
                        :chefe (:chefe raid')
                        :skipped-player skipped
                        :timeout-seconds timeout-s}
              payload {:kind "raid-turn-skip"
                       :battle-id raid-id
                       :battle-kind "raid"
                       :chat-id chat-id
                       :player-ids [skipped next-pid]
                       :mentions (filterv some? [skipped next-pid])
                       :timeout-seconds timeout-s
                       :skipped-player skipped
                       :next-player next-pid
                       :raid raid-dto
                       :text (str "⏳ *TURNO ENCERRADO POR INATIVIDADE*\n\n"
                                  "@" (or (some-> skipped (str/split #"@") first) "treinador")
                                  " não realizou uma ação em " timeout-s " segundos.\n\n"
                                  "⏭️ A vez passou para @"
                                  (or (some-> next-pid (str/split #"@") first) "treinador")
                                  ".\n\n"
                                  "⚔️ *RAID — TURNO " (:turn-index raid') "*")}
              notify [{:battle-notify/id (idle/turn-skip-notify-id raid-id turn-index)
                       :battle-notify/kind "raid-turn-skip"
                       :battle-notify/battle-id (str raid-id)
                       :battle-notify/chat-id (str chat-id)
                       :battle-notify/player-id (str next-pid)
                       :battle-notify/payload-edn (pr-str payload)
                       :battle-notify/created-at (long agora)
                       :battle-notify/acked? false}]
              receipt {:operation-receipt/id op-id
                       :operation-receipt/kind "raid-turn-skip"
                       :operation-receipt/channel "gameplay"
                       :operation-receipt/payload-hash
                       (str "raid-turn-skip|" raid-id "|" turn-index)
                       :operation-receipt/player-key (str chat-id "|raid")
                       :operation-receipt/save-id "a"
                       :operation-receipt/summary
                       (pr-str {:raid-id raid-id
                                :skipped skipped
                                :next next-pid
                                :from-turn turn-index
                                :to-turn (:turn-index raid')
                                :idle-expires-at (:idle-expires-at raid)
                                :rule "turn-skip-60s"})}
              ;; CAS turn-expires: observed → novo (ou retract). Não misturar
              ;; :db/add com CAS observed→observed no mesmo attr (datoms-conflict).
              ;; NÃO toca idle-expires-at.
              new-texp (:turn-expires-at window)
              cas (cond-> [[:db/cas [:raid/id raid-id] :raid/turn-index turn-index
                            (long (:turn-index raid'))]]
                    (some? new-texp)
                    (conj [:db/cas [:raid/id raid-id] :raid/turn-expires-at
                           observed-texp (long new-texp)])
                    (nil? new-texp)
                    (into [[:db/cas [:raid/id raid-id] :raid/turn-expires-at
                            observed-texp observed-texp]
                           [:db/retract [:raid/id raid-id] :raid/turn-expires-at]]))]
          {:tx (into [] (concat
                         [{:raid/id raid-id
                           :raid/vez next-pid
                           :raid/turn-started-at (long (:turn-started-at window))}]
                         cas
                         [receipt]
                         notify))
           :result {:operation-id op-id
                    :duplicate false
                    :raid-id raid-id
                    :skipped-player skipped
                    :next-player next-pid
                    :turn-index (:turn-index raid')
                    :raid raid'
                    :notifications notify}})))))

(defn skip-turn!
  [conn raid-id agora]
  (let [agora (long (or agora (System/currentTimeMillis)))
        built (build-skip-turn-tx (d/db conn) raid-id agora)]
    (when (seq (:tx built))
      (d/transact conn {:tx-data (:tx built)}))
    (:result built)))

(defn find-expired-battle-ids
  "Vencidas por idle: ativas OU aguardando captura / waiting."
  [db agora]
  (->> (d/q '[:find (pull ?e [:db/id :battle/id :battle/active? :battle/idle-expired?
                              :battle/idle-expires-at :battle/phase :battle/state-edn])
              :where
              [?e :battle/idle-expires-at _]
              [?e :battle/id]]
            db)
       (map first)
       (filter open-battle-entity?)
       (filter (fn [e] (idle/expired? (:battle/idle-expires-at e) agora)))
       (mapv :battle/id)))

(defn find-expired-raid-ids
  "Só combate com idle vencido. Lobby (`inscricoes`) nunca entra aqui."
  [db agora]
  (->> (find-combat-raid-ids db)
       (filter (fn [rid]
                 (let [re (d/pull db '[:raid/idle-expires-at :raid/idle-expired?]
                                  [:raid/id rid])]
                   (and (some? (:raid/idle-expires-at re))
                        (idle/expired? (:raid/idle-expires-at re) agora)
                        (not (true? (:raid/idle-expired? re)))))))
       vec))

(defn expire-battle!
  "Aplica expiração. Idempotente via receipt único + CAS.
  Conflito no CAS de idle-expires-at = prazo foi renovado por ação válida —
  retorna skipped (não encerra).
  `opts`: `:force?` ignora prazo (admin / force-expire)."
  ([conn battle-id agora] (expire-battle! conn battle-id agora nil))
  ([conn battle-id agora {:keys [force?] :as opts}]
  (let [agora (long (or agora (System/currentTimeMillis)))
        built (trainer-progression/enrich
               (d/db conn)
               (build-expire-battle-tx (d/db conn) battle-id agora opts))]
    (if (or (empty? (:tx built)) (:duplicate (:result built)) (:skipped (:result built)))
      (:result built)
      (try
        (d/transact conn {:tx-data (:tx built)})
        (:result built)
        (catch Exception ex
          (if (err/conflict? ex)
            (let [dup (d/pull (d/db conn) '[*]
                              [:operation-receipt/id
                               (idle/receipt-operation-id battle-id)])
                  be2 (d/pull (d/db conn) '[:battle/idle-expired? :battle/idle-expires-at]
                              [:battle/id battle-id])]
              (cond
                (present? dup)
                {:operation-id (:operation-receipt/id dup)
                 :duplicate true
                 :battle-id battle-id
                 :already-expired? true}

                ;; Prazo renovado ou já expirado por outra TX — não rethrow loop.
                (or (true? (:battle/idle-expired? be2))
                    (and (some? (:battle/idle-expires-at be2))
                         (not (idle/expired? (:battle/idle-expires-at be2) agora))))
                {:operation-id (idle/receipt-operation-id battle-id)
                 :duplicate false
                 :battle-id battle-id
                 :skipped "deadline-renewed-or-raced"
                 :already-expired? (boolean (:battle/idle-expired? be2))}

                :else
                (throw ex)))
            (throw ex))))))))

(defn expire-raid!
  ([conn raid-id agora] (expire-raid! conn raid-id agora nil))
  ([conn raid-id agora opts]
   (let [agora (long (or agora (System/currentTimeMillis)))
         built (build-expire-raid-tx (d/db conn) raid-id agora opts)]
     (if (or (empty? (:tx built)) (:duplicate (:result built)))
       (:result built)
       (try
         (d/transact conn {:tx-data (:tx built)})
         (:result built)
         (catch Exception ex
           (if (err/conflict? ex)
             {:operation-id (str "raid-idle-expire|" raid-id)
              :duplicate true
              :raid-id raid-id
              :already-expired? true}
             (throw ex))))))))

(defn find-active-battles-missing-idle
  "Abertas (ativa/aguardando/waiting) sem idle-expires-at."
  [db]
  (->> (find-open-battle-ids db)
       (remove (fn [bid]
                 (let [be (d/pull db '[:battle/idle-expires-at :battle/idle-expired?]
                                  [:battle/id bid])]
                   (or (some? (:battle/idle-expires-at be))
                       (true? (:battle/idle-expired? be))))))
       vec))

(defn find-combat-raids-missing-idle
  "Backfill de idle só em combate — nunca em lobby."
  [db]
  (->> (find-combat-raid-ids db)
       (remove (fn [rid]
                 (let [re (d/pull db '[:raid/idle-expires-at :raid/idle-expired?]
                                  [:raid/id rid])]
                   (or (some? (:raid/idle-expires-at re))
                       (true? (:raid/idle-expired? re))))))
       vec))

(defn backfill-idle-window!
  "Política segura: abertas sem timestamp ganham janela completa a partir de
   `agora` (não usam idade pré-migration). Sem expire."
  [conn agora limit]
  (let [db (d/db conn)
        agora (long agora)
        battles (take limit (find-active-battles-missing-idle db))
        raids (take limit (find-combat-raids-missing-idle db))
        n-b (atom 0)
        n-r (atom 0)]
    (doseq [bid battles]
      (try
        (d/transact conn {:tx-data (touch-tx bid agora)})
        (swap! n-b inc)
        (catch Exception _)))
    (doseq [rid raids]
      (try
        (d/transact conn {:tx-data (raid-touch-tx rid agora)})
        (swap! n-r inc)
        (catch Exception _)))
    {:backfilled-battles @n-b
     :backfilled-raids @n-r}))

(defn force-expire-open!
  "Encerra imediatamente todas as batalhas/raids abertas (ops/admin).
   Sem recompensas — mesmo outcome :idle-timeout. Idempotente."
  [conn {:keys [agora limit] :or {limit 64}}]
  (let [agora (long (or agora (System/currentTimeMillis)))
        db (d/db conn)
        battles (take limit (find-open-battle-ids db))
        raids (take limit (find-open-raid-ids db))
        battle-results (mapv #(expire-battle! conn % agora {:force? true}) battles)
        raid-results (mapv #(expire-raid! conn % agora {:force? true}) raids)]
    {:agora agora
     :force true
     :backfill {:backfilled-battles 0 :backfilled-raids 0}
     :battles battle-results
     :raids raid-results
     :open-battles (count battles)
     :open-raids (count raids)
     :expired-battles (count (remove #(or (:duplicate %) (:skipped %)) battle-results))
     :expired-raids (count (remove :duplicate raid-results))}))

(defn- chat-id-for-battle
  [db battle-id]
  (let [e (d/pull db '[:battle/chat-id] [:battle/id battle-id])]
    (when e (str (:battle/chat-id e)))))

(defn- chat-id-for-raid
  [db raid-id]
  (let [e (d/pull db '[:raid/chat-id] [:raid/id raid-id])]
    (when e (str (:raid/chat-id e)))))

(defn expire-due!
  "Sweeper: (1) backfill janela em legado sem attrs; (2) encerra vencidos.
   Com `:force true`, encerra todas as abertas agora (ignora prazo).
   `:blocked-chat-ids` — chats com jogos/pokemon bloqueados: não expira idle
   nem skip de turno (evita penalidade administrativa); renova a janela."
  [conn {:keys [agora limit force blocked-chat-ids] :or {limit 32}}]
  (if force
    (force-expire-open! conn {:agora agora :limit (or limit 64)})
    (let [agora (long (or agora (System/currentTimeMillis)))
          blocked (set (map str (or blocked-chat-ids [])))
          blocked? (fn [cid] (contains? blocked (str cid)))
          backfill (backfill-idle-window! conn agora limit)
          db (d/db conn)
          battles (->> (find-expired-battle-ids db agora)
                       (remove #(blocked? (chat-id-for-battle db %)))
                       (take limit))
          raids-due (find-expired-raid-ids db agora)
          raids-blocked (filter #(blocked? (chat-id-for-raid db %)) raids-due)
          raids (->> raids-due
                     (remove #(blocked? (chat-id-for-raid db %)))
                     (take limit))
          ;; Renova idle em raids bloqueadas para não penalizar ao desbloquear.
          deferred (mapv (fn [rid]
                           (try
                             (d/transact conn {:tx-data (raid-touch-tx rid agora)})
                             {:raid-id rid :deferred-admin-block true}
                             (catch Exception _
                               {:raid-id rid :deferred-admin-block true :error true})))
                         (take limit raids-blocked))
          battle-results (mapv #(expire-battle! conn % agora) battles)
          raid-results (mapv #(expire-raid! conn % agora) raids)
          turn-raids (->> (find-turn-expired-raid-ids db agora)
                          (remove #(blocked? (chat-id-for-raid db %)))
                          (take limit))
          skip-results (mapv #(skip-turn! conn % agora) turn-raids)]
      {:agora agora
       :force false
       :backfill backfill
       :battles battle-results
       :raids raid-results
       :turn-skips skip-results
       :deferred-admin-block deferred
       :expired-battles (count (remove #(or (:duplicate %) (:skipped %)) battle-results))
       :expired-raids (count (remove #(or (:duplicate %) (:skipped %)) raid-results))
       :skipped-turns (count (remove #(or (:duplicate %) (:skipped %)) skip-results))})))

(defn assert-battle-not-idle-expired!
  "Bloqueia mutação se prazo passou. Eager-expire fica a cargo do caller."
  [be-or-state agora]
  (let [expires (or (:battle/idle-expires-at be-or-state)
                    (:idle-expires-at be-or-state))
        expired-flag (or (:battle/idle-expired? be-or-state)
                         (:idle-expired? be-or-state))]
    (when (or (true? expired-flag) (idle/expired? expires agora))
      (throw (err/error :battle_idle_expired
                        {:expires-at expires :agora agora})))))

(defn list-pending-notifications
  "Outbox: idle-timeout, leave (PvP) e skip de turno de raid."
  [db {:keys [limit] :or {limit 50}}]
  (->> (d/q '[:find (pull ?e [*])
              :where
              [?e :battle-notify/acked? false]
              [?e :battle-notify/kind ?k]]
            db)
       (map first)
       (filter (fn [e]
                 (contains? #{"battle-idle-timeout" "battle-leave" "raid-turn-skip"}
                            (:battle-notify/kind e))))
       (sort-by :battle-notify/created-at)
       (take limit)
       (mapv (fn [e]
               (let [payload (or (edn-read (:battle-notify/payload-edn e)) {})
                     kind (:battle-notify/kind e)
                     bk (or (:battle-kind payload) "wild")
                     text (or (:text payload)
                              (when (= "battle-leave" kind)
                                "🏳️ Seu adversário abandonou a batalha. Você venceu por desistência.")
                              (when (= "raid-turn-skip" kind)
                                (:text payload))
                              (idle/format-idle-message
                               {:battle-kind bk
                                :timeout-seconds (:timeout-seconds payload)
                                :wild-name (:wild-name payload)
                                :boss-name (:boss-name payload)}))
                     mentions (or (:mentions payload)
                                  (when-let [pid (:battle-notify/player-id e)]
                                    (when (and (string? pid) (str/includes? pid "@"))
                                      [pid]))
                                  [])]
                 (cond-> {:id (:battle-notify/id e)
                          :kind kind
                          :chatId (:battle-notify/chat-id e)
                          :battleId (:battle-notify/battle-id e)
                          :createdAt (:battle-notify/created-at e)
                          :payload payload
                          :texto text
                          :text text}
                   (and (string? (:battle-notify/player-id e))
                        (not= "chat" (:battle-notify/player-id e)))
                   (assoc :playerId (:battle-notify/player-id e))
                   (seq mentions) (assoc :mentions (vec mentions))
                   (map? (:raid payload)) (assoc :raid (:raid payload))))))))

(defn ack-notification!
  "Ack idempotente — segunda chamada é no-op seguro."
  [conn notify-id]
  (let [db (d/db conn)
        e (d/pull db '[*] [:battle-notify/id notify-id])]
    (cond
      (not (present? e))
      (throw (err/error :notification_not_found {:id notify-id}))

      (true? (:battle-notify/acked? e))
      {:id notify-id :acked true :duplicate true}

      :else
      (do
        (d/transact conn {:tx-data
                          [[:db/add [:battle-notify/id notify-id]
                            :battle-notify/acked? true]
                           [:db/add [:battle-notify/id notify-id]
                            :battle-notify/acked-at (System/currentTimeMillis)]]})
        {:id notify-id :acked true :duplicate false}))))
