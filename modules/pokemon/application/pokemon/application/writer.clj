(ns pokemon.application.writer
  "Congelamento do save ativo (writer fence) compartilhado por gameplay."
  (:require [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]))

(defn freeze-writer
  "Congela identidade do save ativo no início da operação.
  Mutações posteriores CAS-am esses valores; após A→B, escritas de A falham."
  [store chat-id player-id]
  (let [player (ports/get-player store chat-id player-id)]
    (when-not player
      (throw (err/error :player_not_found {:chat-id chat-id :player-id player-id})))
    (when-not (:active-save-id player)
      (throw (err/error :no_active_save {:chat-id chat-id :player-id player-id})))
    {:chat-id chat-id
     :player-id player-id
     :save-id (:active-save-id player)
     :fence-generation (:fence-generation player)}))
