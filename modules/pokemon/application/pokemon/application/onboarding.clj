(ns pokemon.application.onboarding
  "M9.6 — kit inicial + escolha do starter (save ativo)."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.onboarding :as onboard]))

(defn- kit-claimed-any?
  "Legado: kit uma vez por conta. No modelo A/B, qualquer slot marcado bloqueia."
  [store chat-id player-id]
  (boolean
   (some (fn [slot]
           (:kit-inicial-resgatado (ports/get-save store chat-id player-id slot)))
         ["a" "b"])))

(defn status
  [store chat-id player-id]
  (let [player (ports/get-player store chat-id player-id)
        save-id (or (:active-save-id player) "a")
        save (when player (ports/get-save store chat-id player-id save-id))
        coll (when player (ports/list-collection store chat-id player-id save-id))
        inv (when player (ports/list-inventory store chat-id player-id save-id))
        kit-any? (when player (kit-claimed-any? store chat-id player-id))
        flags {:kit-inicial-resgatado (boolean kit-any?)
               :inicial-escolhido (boolean (:inicial-escolhido save))
               :collection-count (count coll)}
        state (onboard/onboarding-status flags)]
    {:chat-id chat-id
     :player-id player-id
     :has-trainer (boolean player)
     :active-save-id save-id
     :fence-generation (:fence-generation player 0)
     :status state
     :kit-claimed (boolean kit-any?)
     :starter-chosen (boolean (:inicial-escolhido save))
     :kit-available (onboard/kit-disponivel? flags)
     :starter-available (onboard/starter-disponivel? flags)
     :collection-count (count coll)
     :inventory inv
     :starters (onboard/starter-menu)}))

(defn claim-kit!
  [store {:keys [chat-id player-id operation-id] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (let [w (writer/freeze-writer store chat-id player-id)
        result (ports/claim-starter-kit! store w req)]
    (assoc result
           :chat-id chat-id
           :player-id player-id
           :active-save-id (:save-id w)
           :fence-generation (:fence-generation w)
           :status (:status (status store chat-id player-id)))))

(defn choose-starter!
  [store {:keys [chat-id player-id operation-id choice] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when (or (nil? choice) (empty? (str choice)))
    (throw (err/error :invalid_starter {:choice choice})))
  (let [w (writer/freeze-writer store chat-id player-id)
        result (ports/choose-starter! store w req)]
    (assoc result
           :chat-id chat-id
           :player-id player-id
           :active-save-id (:save-id w)
           :fence-generation (:fence-generation w)
           :status (:status (status store chat-id player-id)))))

(defn start-journey!
  "ensure + kit (se disponível) + status. Não escolhe starter.
   Se completed: não concede kit/starter de novo; devolve estado atual."
  [store {:keys [chat-id player-id operation-id]}]
  (let [existing (ports/get-player store chat-id player-id)
        player (or existing
                   (trainer/create-trainer! store {:chat-id chat-id :player-id player-id}))
        ensured {:created (nil? existing)
                 :chat-id chat-id
                 :player-id player-id
                 :active-save-id (:active-save-id player)
                 :fence-generation (:fence-generation player 0)}
        st0 (status store chat-id player-id)
        kit-op (or operation-id (str "onboard-kit:" chat-id "|" player-id))
        kit (when (and (:kit-available st0)
                       (not= "completed" (:status st0)))
              (claim-kit! store {:chat-id chat-id
                                 :player-id player-id
                                 :operation-id kit-op}))
        st1 (status store chat-id player-id)]
    (merge ensured
           {:kit kit
            :already-complete (= "completed" (:status st1))
            :onboarding st1})))
