(ns pokemon.infrastructure.datomic.trade-store
  "Repositório A4.4.1. Transições usam CAS de revisão; integração ao adapter
   principal e operações de escolha entram nas etapas seguintes."
  (:require [clojure.edn :as edn] [datomic.client.api :as d]))

(defn- ->trade [e]
  (when (:db/id e)
    {:id (:trade/id e) :chat-id (:trade/chat-id e) :player-a (:trade/player-a e)
     :player-b (:trade/player-b e) :save-a (:trade/save-a e) :save-b (:trade/save-b e)
     :state (keyword (:trade/state e)) :created-at (:trade/created-at e)
     :expires-at (:trade/expires-at e) :revision (:trade/revision e)
     :context (edn/read-string (or (:trade/context-edn e) "{}"))}))

(defn get-trade [conn id]
  (->trade (d/pull (d/db conn) '[*] [:trade/id id])))

(defn create! [conn trade]
  (d/transact conn {:tx-data [(-> trade
                                 (assoc :trade/id (:id trade) :trade/chat-id (:chat-id trade)
                                        :trade/player-a (:player-a trade) :trade/player-b (:player-b trade)
                                        :trade/save-a (:save-a trade) :trade/save-b (:save-b trade)
                                        :trade/state (name (:state trade)) :trade/created-at (:created-at trade)
                                        :trade/expires-at (:expires-at trade) :trade/revision (:revision trade)
                                        :trade/context-edn (pr-str (:context trade)))
                                 (dissoc :id :chat-id :player-a :player-b :save-a :save-b :state :created-at :expires-at :revision :context))]} )
  (get-trade conn (:id trade)))

(defn transition! [conn before after]
  (d/transact conn {:tx-data [[:db/cas [:trade/id (:id before)] :trade/revision (:revision before) (:revision after)]
                              [:db/add [:trade/id (:id before)] :trade/state (name (:state after))]
                              [:db/add [:trade/id (:id before)] :trade/context-edn (pr-str (:context after))]]})
  (get-trade conn (:id before)))
