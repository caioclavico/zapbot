(ns pokemon.infrastructure.datomic.battle-leave-store
  "TX builders de !pk sair — abandono voluntário wild/gym/raid (combate)."
  (:require [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.pvp :as pvp]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.pvp-store :as pvp-store]))

(defn- present? [e] (boolean (:db/id e)))

(defn- leave-notify-id
  [battle-id player-id]
  (str "battle-leave-notify|" battle-id "|" player-id))

(defn- notify-opponent-tx
  [battle-id chat-id opponent-id payload agora]
  (when opponent-id
    {:battle-notify/id (leave-notify-id battle-id opponent-id)
     :battle-notify/kind "battle-leave"
     :battle-notify/battle-id (str battle-id)
     :battle-notify/chat-id (str chat-id)
     :battle-notify/player-id (str opponent-id)
     :battle-notify/payload-edn (pr-str payload)
     :battle-notify/created-at (long agora)
     :battle-notify/acked? false}))

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

(defn- clear-wild-pointers-tx
  [db chat-id battle-id player-id]
  (let [pk (id/player-key chat-id player-id)
        g (d/pull db '[*] [:group/chat-id chat-id])
        clear-player [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
        clear-group (when (and (present? g)
                               (= battle-id (:group/active-wild-battle-id g)))
                      (cond-> [[:db/retract [:group/chat-id chat-id]
                                :group/active-wild-battle-id battle-id]]
                        (= player-id (:group/active-wild-player-id g))
                        (conj [:db/retract [:group/chat-id chat-id]
                               :group/active-wild-player-id player-id])))]
    (into [] (concat clear-player (or clear-group [])))))

(defn- assert-owner!
  [battle player-id]
  (let [owner (or (:player-id battle)
                  (get-in battle [:jogadores :x]))
        pvp? (= :pvp (:kind battle))
        side (when pvp? (pvp/side-of-player battle player-id))]
    (cond
      pvp?
      (when-not side
        (throw (err/error :pvp_not_participant {:player-id player-id})))

      (and owner (not= (str owner) (str player-id)))
      (throw (err/error :battle_not_yours
                        {:owner owner :player-id player-id})))))

(defn build-leave-wild-or-gym-tx
  "Abandona batalha wild/gym. Sem recompensas."
  [{:keys [db save writer-ctx request battle pokemon-hp-status-tx gym-battle-hp-txs]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        battle-id (:battle-id battle)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        _ (assert-owner! battle player-id)
        _ (when (= :pvp (:kind battle))
            (throw (err/error :not_wild_or_gym {:kind (:kind battle)})))
        next (battle/abandon battle)
        pk (id/player-key chat-id player-id)
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        colecao (:colecao request)
        idx (long (or (get-in battle [:indices-ativos :x])
                      (:collection-index battle 0)))
        poke (when (and (seq colecao) (< idx (count colecao)))
               (nth colecao idx))
        gym? (= :gym (:kind battle))
        hp-sync (cond
                  gym? (when gym-battle-hp-txs
                         (gym-battle-hp-txs colecao next))
                  (and poke pokemon-hp-status-tx)
                  (pokemon-hp-status-tx
                   poke (get-in next [:hp :x]) (get-in next [:status :x])
                   (:status poke)))
        clear (if gym?
                [[:db/retract [:player/key pk] :player/active-battle-id battle-id]]
                (clear-wild-pointers-tx db chat-id battle-id player-id))
        seq-break (when (= :wild (:kind battle))
                    (sequencia-break-tx db chat-id player-id save-id))
        ;; CAS só se ainda ativa — aguardando-captura já tem active?=false.
        cas-active (when (true? (:active? battle))
                     [[:db/cas [:battle/id battle-id] :battle/active? true false]])]
    {:tx (into (or cas-active [])
               (concat [[:db/add [:battle/id battle-id] :battle/active? false]
                        [:db/add [:battle/id battle-id] :battle/phase "finished"]
                        [:db/add [:battle/id battle-id] :battle/state-edn (battle/encode-state-edn next)]
                        [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "battle-leave"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key pk
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:battle-id battle-id
                                  :modality (name (:kind battle))
                                  :outcome :abandoned
                                  :status "left"})}]
                       clear (or hp-sync []) (or seq-break [])))
     :result {:operation-id operation-id
              :duplicate false
              :status "left"
              :modality (name (:kind battle))
              :battle next
              :payload-hash phash}}))

(defn build-leave-pvp-tx
  "Desistência PvP (ou cancelamento de desafio aguardando). Notifica oponente."
  [{:keys [db save writer-ctx request battle pokemon-sync-tx]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        save-id (:save-id writer-ctx)
        battle-id (:battle-id battle)
        operation-id (:operation-id request)
        phash (:payload-hash request)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        side (pvp/side-of-player battle player-id)
        _ (when-not side
            (throw (err/error :pvp_not_participant {})))
        _ (when (and (pvp/aguardando? battle) (not= side :x))
            (throw (err/error :pvp_cancel_forbidden {})))
        next (battle/forfeit-pvp battle side)
        sk (id/save-key chat-id player-id save-id)
        rev (long (:save/revision save 0))
        pk (id/player-key chat-id player-id)
        colecao-x (:colecao-x request)
        colecao-o (:colecao-o request)
        poke-x (when (seq colecao-x)
                 (nth colecao-x (get-in battle [:indices-ativos :x] 0) nil))
        poke-o (when (and colecao-o (get-in battle [:indices-ativos :o]))
                 (nth colecao-o (get-in battle [:indices-ativos :o]) nil))
        sync-x (when (and poke-x pokemon-sync-tx (get-in next [:hp :x]))
                 (pokemon-sync-tx poke-x (get-in next [:hp :x]) (get-in next [:status :x])))
        sync-o (when (and poke-o pokemon-sync-tx (get-in next [:hp :o]))
                 (pokemon-sync-tx poke-o (get-in next [:hp :o]) (get-in next [:status :o])))
        clear-x [[:db/retract
                  [:player/key (id/player-key chat-id (get-in battle [:jogadores :x]))]
                  :player/active-battle-id battle-id]]
        clear-o (when (get-in battle [:jogadores :o])
                  [[:db/retract
                    [:player/key (id/player-key chat-id (get-in battle [:jogadores :o]))]
                    :player/active-battle-id battle-id]])
        clear-chat (or (pvp-store/clear-chat-pvp-tx db chat-id battle-id) [])
        opponent (when-not (pvp/aguardando? battle)
                   (get-in battle [:jogadores (if (= side :x) :o :x)]))
        notify (when opponent
                 [(notify-opponent-tx
                   battle-id chat-id opponent
                   {:kind "battle-leave"
                    :modality "pvp"
                    :battle-id battle-id
                    :leaver player-id
                    :outcome (:outcome next)
                    :text "🏳️ Seu adversário abandonou a batalha. Você venceu por desistência."}
                   agora)])
        cas-active (when (true? (:active? battle))
                     [[:db/cas [:battle/id battle-id] :battle/active? true false]])]
    {:tx (into (or cas-active [])
               (concat [[:db/add [:battle/id battle-id] :battle/active? false]
                        [:db/add [:battle/id battle-id] :battle/phase "finished"]
                        [:db/add [:battle/id battle-id] :battle/state-edn (battle/encode-state-edn next)]
                        [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "battle-leave"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key pk
                         :operation-receipt/save-id save-id
                         :operation-receipt/summary
                         (pr-str {:battle-id battle-id
                                  :modality "pvp"
                                  :outcome (:outcome next)
                                  :status "left"})}]
                       clear-x (or clear-o []) clear-chat
                       (or sync-x []) (or sync-o []) (or notify [])))
     :result {:operation-id operation-id
              :duplicate false
              :status "left"
              :modality "pvp"
              :battle next
              :opponent-notified (boolean opponent)
              :payload-hash phash}}))

(defn build-leave-raid-combat-tx
  "Remove só o jogador do combate. Encerra raid só se ninguém restar."
  [{:keys [db save writer-ctx request raid]}]
  (let [chat-id (:chat-id writer-ctx)
        player-id (:player-id writer-ctx)
        agora (long (or (:agora request) (System/currentTimeMillis)))
        raid' (raid/sair-combate raid player-id agora)
        expected-rev (long (:revision raid 0))
        sk (id/save-key chat-id player-id (:save-id writer-ctx))
        rev (long (:save/revision save 0))
        operation-id (:operation-id request)
        phash (:payload-hash request)
        part-key (id/raid-participant-key (:raid-id raid) player-id)
        ended? (= "abandonada" (str (:fase raid')))
        clear-ptr (when ended?
                    (let [e (d/pull db '[*] [:chat-active-raid/chat-id chat-id])]
                      (when (and (present? e)
                                 (= (:raid-id raid) (:chat-active-raid/raid-id e)))
                        [[:db/retractEntity (:db/id e)]])))]
    {:tx (into (cond-> [(cond-> {:raid/id (:raid-id raid)
                                 :raid/fase (str (:fase raid'))
                                 :raid/ordem-edn (pr-str (:ordem raid'))
                                 :raid/turn-index (long (:turn-index raid' 0))}
                          (:vez raid') (assoc :raid/vez (:vez raid')))]
                 (and (nil? (:vez raid')) (:vez raid))
                 (conj [:db/retract [:raid/id (:raid-id raid)] :raid/vez (:vez raid)]))
               (concat [[:db/cas [:raid/id (:raid-id raid)] :raid/revision
                         expected-rev (inc expected-rev)]
                        [:db/retractEntity [:raid-participant/key part-key]]
                        [:db/cas [:save/key sk] :save/revision rev (inc rev)]
                        {:operation-receipt/id operation-id
                         :operation-receipt/kind "battle-leave"
                         :operation-receipt/channel "gameplay"
                         :operation-receipt/payload-hash phash
                         :operation-receipt/player-key (id/player-key chat-id player-id)
                         :operation-receipt/save-id (:save-id writer-ctx)
                         :operation-receipt/summary
                         (pr-str {:raid-id (:raid-id raid)
                                  :modality "raid"
                                  :fase (:fase raid')
                                  :status "left"})}]
                       (or clear-ptr [])))
     :result {:operation-id operation-id
              :duplicate false
              :status "left"
              :modality "raid"
              :raid (assoc raid' :revision (inc expected-rev))
              :raid-ended ended?
              :payload-hash phash}}))
