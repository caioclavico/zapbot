(ns pokemon.domain.trade
  "Máquina de estados canônica para propostas bilaterais. Sem I/O."
  (:require [pokemon.domain.errors :as err]))

(def expiry-ms (* 5 60 1000))
(def terminal-states #{:completed :refused :cancelled :expired :invalidated})
(def transitions
  {:invited #{:accepted :refused :cancelled :expired :invalidated}
   :accepted #{:selecting :cancelled :expired :invalidated}
   :selecting #{:awaiting-confirmations :cancelled :expired :invalidated}
   :awaiting-confirmations #{:completed :cancelled :expired :invalidated}})

(defn create! [{:keys [id chat-id player-a player-b save-a save-b now]}]
  (when (or (nil? id) (= player-a player-b))
    (throw (err/error :invalid_trade {:reason :self_trade})))
  {:id id :chat-id chat-id :player-a player-a :player-b player-b
   :save-a save-a :save-b save-b :state :invited :created-at (long now)
   :expires-at (+ (long now) expiry-ms) :revision 0 :context {}})

(defn participant? [trade player-id]
  (contains? #{(:player-a trade) (:player-b trade)} player-id))

(defn transition! [trade actor next-state now]
  (let [state (:state trade)]
    (when-not (participant? trade actor)
      (throw (err/error :trade_forbidden {:trade-id (:id trade)})))
    (when (and (not (contains? terminal-states state)) (>= (long now) (:expires-at trade)))
      (throw (err/error :trade_expired {:trade-id (:id trade)})))
    (when-not (contains? (get transitions state #{}) next-state)
      (throw (err/error :invalid_trade_transition {:from state :to next-state})))
    (assoc trade :state next-state :revision (inc (long (:revision trade))))))
