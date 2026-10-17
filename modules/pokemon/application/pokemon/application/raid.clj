(ns pokemon.application.raid
  "Raids no grupo — fence por participante + operation-id + turn-index."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.ops :as ops]
            [pokemon.domain.raid :as raid]))

(defn get-raid
  [store raid-id]
  (ports/get-raid store raid-id))

(defn active-raid
  [store chat-id]
  (ports/active-raid store chat-id))

(defn open-raid!
  "Abre inscrições (criação manual/harness — auto-spawn removido no legado)."
  ([store chat-id player-id request]
   (open-raid! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation])
                     {:criador (:player-id writer-ctx)})]
     (ops/validate-mutation! full)
     (ports/open-raid! store writer-ctx full))))

(defn join-raid!
  ([store chat-id player-id request]
   (join-raid! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/join-raid! store writer-ctx full))))

(defn leave-raid!
  ([store chat-id player-id request]
   (leave-raid! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/leave-raid! store writer-ctx full))))

(defn start-raid!
  ([store chat-id player-id request]
   (start-raid! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/start-raid! store writer-ctx full))))

(defn attack!
  ([store chat-id player-id request]
   (attack! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/raid-attack! store writer-ctx full))))

(defn capture!
  ([store chat-id player-id request]
   (capture! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/raid-capture! store writer-ctx full))))

(defn no-ginasio?
  [store chat-id gym-id agora]
  (when-let [r (active-raid store chat-id)]
    (raid/no-ginasio? r gym-id agora)))
