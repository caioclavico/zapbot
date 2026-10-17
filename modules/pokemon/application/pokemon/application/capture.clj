(ns pokemon.application.capture
  "Captura no save ativo — fence + operation-id + payload-hash."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]))

(defn attempt-capture!
  "Tenta capturar no save congelado. Consome a bola sempre; adiciona à
  coleção só se capturou. writer-ctx ou freeze automático via chat/player."
  ([store chat-id player-id request]
   (attempt-capture! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/attempt-capture! store writer-ctx full))))
