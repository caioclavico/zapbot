(ns pokemon.application.evolution
  "Evolução no save ativo — fence + operation-id + payload-hash.
  Consumo de pedra/catalisador opcional via :item-id."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn evolve!
  ([store chat-id player-id request]
   (evolve! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/evolve-pokemon! store writer-ctx full))))
