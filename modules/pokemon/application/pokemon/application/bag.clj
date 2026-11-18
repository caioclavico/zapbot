(ns pokemon.application.bag
  "Casos de uso — !pk mochila kit / diario."
  (:require [pokemon.application.ports :as ports]
            [pokemon.application.writer :as writer]
            [pokemon.domain.bag-rewards :as bag]
            [pokemon.domain.errors :as err]
            [pokemon.domain.mundo :as mundo]))

(defn claim-kit!
  "10 Pokébolas uma vez por treinador; exige 10 vagas; fence + recibo."
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
           :rewards-text (when (and (not (:kit-already-claimed result))
                                    (pos? (long (:qty result 0))))
                           (bag/texto-recompensas
                            {(:item-id result "pokebola") (:qty result)})))))

(defn claim-daily!
  "Bônus diário (3–7 bolas + fruta a cada 7 dias); overflow → pending."
  [store {:keys [chat-id player-id operation-id] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (let [w (writer/freeze-writer store chat-id player-id)
        tz (or (:timezone req)
               (not-empty (System/getenv "MISSOES_TIMEZONE"))
               mundo/default-timezone)
        result (ports/claim-bag-daily!
                store w (assoc req :timezone tz))]
    (assoc result
           :chat-id chat-id
           :player-id player-id
           :active-save-id (:save-id w)
           :fence-generation (:fence-generation w)
           :timezone tz)))
