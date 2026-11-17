(ns pokemon.application.active
  "Seleção do Pokémon ativo no save — fence + operation-id + payload-hash."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn set-active!
  ([store chat-id player-id request]
   (set-active! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/set-active-pokemon! store writer-ctx full))))
