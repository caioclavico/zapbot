(ns pokemon.application.favorite
  "Favorito do save — fence + operation-id + payload-hash (A4.2)."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn set-favorite!
  ([store chat-id player-id request]
   (set-favorite! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/set-favorite-pokemon! store writer-ctx full))))
