(ns pokemon.application.pvp
  "Casos de uso A3 — desafio/aceitação/turnos PvP 1×1."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn open-challenge!
  ([store chat-id player-id request]
   (open-challenge! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/open-pvp-challenge! store writer-ctx full))))

(defn accept-challenge!
  ([store chat-id player-id request]
   (accept-challenge! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/accept-pvp-challenge! store writer-ctx full))))

(defn cancel!
  ([store chat-id player-id request]
   (cancel! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/cancel-pvp! store writer-ctx full))))

(defn confirm-turn!
  ([store chat-id player-id request]
   (confirm-turn! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/confirm-pvp-turn! store writer-ctx full))))

(defn active-in-chat
  [store chat-id]
  (ports/active-pvp-battle store chat-id))
