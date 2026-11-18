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

(defn forget-move!
  ([store chat-id player-id request]
   (forget-move! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/forget-move! store writer-ctx full))))

(defn offer-move!
  ([store chat-id player-id request]
   (offer-move! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/offer-move! store writer-ctx full))))

(defn refuse-offer!
  ([store chat-id player-id request]
   (refuse-offer! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/refuse-offer! store writer-ctx full))))

(defn relearn-move!
  ([store chat-id player-id request]
   (relearn-move! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/relearn-move! store writer-ctx full))))
