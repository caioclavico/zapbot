(ns pokemon.infrastructure.datomic.pvp-store
  "TX helpers A3 — desafio/aceitação/turnos/desistência PvP."
  (:require [clojure.edn :as edn]
            [datomic.client.api :as d]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.pvp :as pvp]
            [pokemon.domain.xp :as xp]))

(defn- present? [e] (boolean (:db/id e)))

(defn clear-chat-pvp-tx
  [db chat-id battle-id]
  (let [g (d/pull db '[*] [:group/chat-id chat-id])]
    (when (and (present? g) (= battle-id (:group/active-pvp-battle-id g)))
      [[:db/retract [:group/chat-id chat-id] :group/active-pvp-battle-id battle-id]])))

(defn assert-player-idle!
  "Jogador não pode ter outra batalha/caça ativa."
  [db chat-id player-id]
  (when-let [bid (:player/active-battle-id
                  (d/pull db '[:player/active-battle-id]
                          [:player/key (id/player-key chat-id player-id)]))]
    (let [be (d/pull db '[*] [:battle/id bid])]
      (when (present? be)
        (let [st (when-let [raw (:battle/state-edn be)]
                   (edn/read-string raw))]
          (when (and st (or (:active? st)
                            (= "active" (:battle/phase be))
                            (= "waiting" (:battle/phase be))
                            (:aguardando-captura? st)))
            (throw (err/error :battle_already_active {:battle-id bid}))))))))

(defn pokemon-sync-tx
  "Sincroniza HP/status do registro de coleção a partir do estado PvP."
  [poke hp status]
  (when poke
    (let [lookup [:pokemon/id-pokemon (:id-pokemon poke)]
          st-str (xp/status->str status)]
      (cond-> [[:db/add lookup :pokemon/hp-atual (long hp)]]
        st-str (conj [:db/add lookup :pokemon/status st-str])
        (and (nil? st-str) (:status poke))
        (conj [:db/retract lookup :pokemon/status (:status poke)])))))

(defn winner-rewards-tx
  "Moedas + bola de nocaute + XP do Pokémon no save do vencedor."
  [db sk save poke kos desafiante-pokemon]
  (let [coins (long (:save/coins save 0))
        xp-amt (pvp/xp-por-nocautes kos)
        bola (pvp/bola-premio desafiante-pokemon)
        next-coins (+ coins pvp/moedas-por-vitoria)
        vitorias (inc (long (:save/vitorias-treinador save 0)))
        applied (when (and poke (pos? xp-amt))
                  (xp/aplicar-xp-pokemon poke xp-amt))
        r (when applied (:registro applied))
        lookup (when poke [:pokemon/id-pokemon (:id-pokemon poke)])
        inv-key (id/inventory-item-key sk bola)
        cur (d/pull db '[:inventory-item/qty] [:inventory-item/key inv-key])
        old-qty (long (:inventory-item/qty cur 0))]
    (cond-> [[:db/cas [:save/key sk] :save/coins coins next-coins]
             [:db/add [:save/key sk] :save/vitorias-treinador vitorias]
             {:inventory-item/key inv-key
              :inventory-item/save [:save/key sk]
              :inventory-item/item-id bola
              :inventory-item/qty (inc old-qty)}]
      r (into [[:db/add lookup :pokemon/level (long (:nivel r))]
               [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
               [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
               [:db/add lookup :pokemon/hp (long (:hp r))]
               [:db/add lookup :pokemon/ataque (long (:ataque r))]
               [:db/add lookup :pokemon/defesa (long (:defesa r))]
               [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
               [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
               [:db/add lookup :pokemon/veloc (long (:veloc r))]
               [:db/add lookup :pokemon/amizade (long (:amizade r))]]))))
