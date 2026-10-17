(ns pokemon.application.joy
  "Centro Pokémon / Enfermeira Joy — estado no save ativo."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn list-beds
  [store chat-id player-id save-id]
  (ports/list-enfermaria store chat-id player-id save-id))

(defn send!
  ([store chat-id player-id request]
   (send! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/joy-send! store writer-ctx full))))

(defn collect!
  ([store chat-id player-id request]
   (collect! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/joy-collect! store writer-ctx full))))
