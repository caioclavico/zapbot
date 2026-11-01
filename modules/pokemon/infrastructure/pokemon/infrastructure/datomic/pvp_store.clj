(ns pokemon.infrastructure.datomic.pvp-store
  "TX helpers A3 — desafio/aceitação/turnos/desistência PvP."
  (:require [datomic.client.api :as d]
            [pokemon.domain.battle :as battle]
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
        (let [st (battle/decode-compact-edn (:battle/state-edn be))]
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

(defn pokemon-xp-tx
  "Aplica XP de nocaute ao registro; retorna {:tx :summary} ou nil."
  [poke kos treinador-nome]
  (when poke
    (let [xp-amt (pvp/xp-por-nocautes kos)
          applied (when (pos? xp-amt) (xp/aplicar-xp-pokemon poke xp-amt))
          r (when applied (:registro applied))
          lookup [:pokemon/id-pokemon (:id-pokemon poke)]]
      {:tx (when r
             [[:db/add lookup :pokemon/level (long (:nivel r))]
              [:db/add lookup :pokemon/xp (long (:xp-desde-nivel r))]
              [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel r))]
              [:db/add lookup :pokemon/hp (long (:hp r))]
              [:db/add lookup :pokemon/ataque (long (:ataque r))]
              [:db/add lookup :pokemon/defesa (long (:defesa r))]
              [:db/add lookup :pokemon/atq-esp (long (:atq-esp r))]
              [:db/add lookup :pokemon/def-esp (long (:def-esp r))]
              [:db/add lookup :pokemon/veloc (long (:veloc r))]
              [:db/add lookup :pokemon/amizade (long (:amizade r))]])
       :summary {:nome (or (:nome poke) (:species poke) "?")
                 :treinador (or treinador-nome "?")
                 :nocautes (long kos)
                 :xp xp-amt
                 :subidas (long (or (:subidas applied) 0))
                 :nivel-final (or (:nivel-final applied) (:nivel poke))}})))

(defn winner-bonus-tx
  "Moedas + bola + +1 vitória (PE de treinador). Sem XP de Pokémon.
   CAS direto em :save/coins (sem economy-store no runtime de produção)."
  ([db sk save desafiante-pokemon]
   (winner-bonus-tx db sk save desafiante-pokemon nil))
  ([db sk save desafiante-pokemon _operation-id]
   (let [coins (long (:save/coins save 0))
         bola (pvp/bola-premio desafiante-pokemon)
         next-coins (+ coins pvp/moedas-por-vitoria)
         vitorias (inc (long (:save/vitorias-treinador save 0)))
         inv-key (id/inventory-item-key sk bola)
         cur (d/pull db '[:inventory-item/qty] [:inventory-item/key inv-key])
         old-qty (long (:inventory-item/qty cur 0))]
     {:tx [[:db/cas [:save/key sk] :save/coins coins next-coins]
           [:db/add [:save/key sk] :save/vitorias-treinador vitorias]
           {:inventory-item/key inv-key
            :inventory-item/save [:save/key sk]
            :inventory-item/item-id bola
            :inventory-item/qty (inc old-qty)}]
      :summary {:moedas pvp/moedas-por-vitoria
                :pe 1
                :bola bola}})))

(defn winner-rewards-tx
  "Compat: moedas + bola + vitória + XP do Pokémon vencedor."
  ([db sk save poke kos desafiante-pokemon]
   (winner-rewards-tx db sk save poke kos desafiante-pokemon nil))
  ([db sk save poke kos desafiante-pokemon operation-id]
   (let [bonus (winner-bonus-tx db sk save desafiante-pokemon operation-id)
         xp-part (pokemon-xp-tx poke kos nil)]
     (into (:tx bonus) (or (:tx xp-part) [])))))
