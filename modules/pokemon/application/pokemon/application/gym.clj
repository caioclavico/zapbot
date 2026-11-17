(ns pokemon.application.gym
  "Ginásios no save ativo — fence + operation-id; ocupação no grupo."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.gym :as gym]
            [pokemon.domain.ops :as ops]))

(defn catalog
  []
  gym/ginasios)

(defn get-occupation
  [store chat-id gym-id]
  (ports/get-gym-occupation store chat-id gym-id))

(defn set-gym-team!
  ([store chat-id player-id indices]
   (set-gym-team! store (writer/freeze-writer store chat-id player-id) indices))
  ([store writer-ctx indices]
   (ports/set-gym-team! store writer-ctx (mapv long indices))))

(defn start-challenge!
  "Inicia combate 3×3 no save ativo. NPC via :candidates; ocupado via slot.
  request: {:operation-id :gym-id :candidates? :nome? :agora? :dia?}"
  ([store chat-id player-id request]
   (start-challenge! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/start-gym-challenge! store writer-ctx full))))

(defn resolve-challenge!
  "Finaliza desafio: recompensas + (se vitória) ocupa ginásio.
  Harness/testes apenas — WhatsApp não envia :venceu?.
  request: {:operation-id :gym-id :venceu? :participacao :dia :indices :pedra-roll :nome}"
  ([store chat-id player-id request]
   (resolve-challenge! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/resolve-gym-challenge! store writer-ctx full))))
(defn heal-motivation!
  ([store chat-id player-id request]
   (heal-motivation! store (writer/freeze-writer store chat-id player-id) request))
  ([store writer-ctx request]
   (let [full (merge request
                     (select-keys writer-ctx
                                  [:chat-id :player-id :save-id :fence-generation]))]
     (ops/validate-mutation! full)
     (ports/heal-gym-motivation! store writer-ctx full))))

(defn occupations-for-player
  [store chat-id player-id]
  (ports/list-gym-occupations-for-player store chat-id player-id))
