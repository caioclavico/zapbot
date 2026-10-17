(ns pokemon.application.moves
  "Aprendizado/substituição de golpes no save ativo."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn learn-move!
  ([store chat-id player-id request]
   (learn-move! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/learn-move! store writer-ctx full))))