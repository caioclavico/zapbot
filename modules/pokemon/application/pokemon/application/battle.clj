(ns pokemon.application.battle
  "Batalhas selvagens no save ativo — fence + operation-id + turn-index."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn start-wild-battle!
  "Inicia batalha selvagem no save congelado."
  ([store chat-id player-id request]
   (start-wild-battle! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/start-wild-battle! store writer-ctx full))))

(defn confirm-turn!
  "Confirma um turno (:attack|:defend). Idempotente por operation-id;
  CAS em turn-index impede turno duplicado."
  ([store chat-id player-id request]
   (confirm-turn! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/confirm-battle-turn! store writer-ctx full))))

(defn get-battle
  [store battle-id]
  (ports/get-battle store battle-id))

(defn active-battle
  [store chat-id player-id]
  (ports/active-battle store chat-id player-id))
