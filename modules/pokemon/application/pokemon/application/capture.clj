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

(defn attempt-encounter-capture!
  "Captura pós-vitória no encontro ativo (M4.4). Pokémon e chance-base
  vêm da batalha persistida — o cliente só envia bola + rolls + identidade."
  ([store chat-id player-id request]
   (attempt-encounter-capture! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/attempt-encounter-capture! store writer-ctx full))))

(defn encounter-release!
  "Libera o selvagem após vitória — sem bola, sem captura; zera sequência."
  ([store chat-id player-id request]
   (encounter-release! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/encounter-release! store writer-ctx full))))

(defn encounter-frambo-prepare!
  ([store chat-id player-id request]
   (encounter-frambo-prepare! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/encounter-frambo-prepare! store writer-ctx full))))
