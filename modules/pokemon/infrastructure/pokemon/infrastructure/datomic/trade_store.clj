(ns pokemon.infrastructure.datomic.trade-store
  "Repositório A4.4.1. Transições usam CAS de revisão; integração ao adapter
   principal e operações de escolha entram nas etapas seguintes."
  (:require [clojure.edn :as edn] [datomic.client.api :as d]
            [pokemon.domain.errors :as err] [pokemon.domain.reward :as reward]
            [pokemon.domain.trade :as trade]))

(defn- ->trade [e]
  (when (:db/id e)
    {:id (:trade/id e) :chat-id (:trade/chat-id e) :player-a (:trade/player-a e)
     :player-b (:trade/player-b e) :save-a (:trade/save-a e) :save-b (:trade/save-b e)
     :state (keyword (:trade/state e)) :created-at (:trade/created-at e)
     :expires-at (:trade/expires-at e) :revision (:trade/revision e)
     :fence-a (:trade/fence-a e) :fence-b (:trade/fence-b e)
     :save-revision-a (:trade/save-revision-a e)
     :save-revision-b (:trade/save-revision-b e)
     :context (edn/read-string (or (:trade/context-edn e) "{}"))}))

(defn get-trade [conn id]
  (->trade (d/pull (d/db conn) '[*] [:trade/id id])))

(defn- receipt [db op] (d/pull db '[*] [:operation-receipt/id op]))
(defn- result-receipt [e hash op]
  (when (:db/id e)
    (reward/assert-receipt-payload! (:operation-receipt/payload-hash e) hash op)
    (assoc (edn/read-string (:operation-receipt/summary e)) :duplicate true)))

(defn create! [conn request]
  (let [{:keys [operation-id chat-id player-a player-b save-a save-b fence-a fence-b now]} request
        payload (reward/canonicalize (select-keys request [:chat-id :player-a :player-b :save-a :save-b :fence-a :fence-b :now]))
        hash (reward/payload-hash payload)
        db (d/db conn)]
    (or (result-receipt (receipt db operation-id) hash operation-id)
        (let [a (d/pull db '[*] [:player/key (str chat-id "|" player-a)])
              b (d/pull db '[*] [:player/key (str chat-id "|" player-b)])
              sa (d/pull db '[*] [:save/key (str chat-id "|" player-a "|" save-a)])
              sb (d/pull db '[*] [:save/key (str chat-id "|" player-b "|" save-b)])
              _ (when (or (= player-a player-b) (not (:db/id a)) (not (:db/id b))
                          (not= save-a (:player/active-save-id a)) (not= save-b (:player/active-save-id b))
                          (not= fence-a (:player/fence-generation a)) (not= fence-b (:player/fence-generation b)))
                  (throw (err/error :invalid_trade {})))
              t (trade/create! {:id operation-id :chat-id chat-id :player-a player-a :player-b player-b :save-a save-a :save-b save-b :now now})
              summary {:trade-id operation-id :state :invited}]
          (try
            (d/transact conn {:tx-data [[:db/cas [:player/key (:player/key a)] :player/active-save-id save-a save-a]
                                         [:db/cas [:player/key (:player/key a)] :player/fence-generation fence-a fence-a]
                                         [:db/cas [:player/key (:player/key b)] :player/active-save-id save-b save-b]
                                         [:db/cas [:player/key (:player/key b)] :player/fence-generation fence-b fence-b]
                                         [:db/cas [:save/key (:save/key sa)] :save/revision (:save/revision sa) (:save/revision sa)]
                                         [:db/cas [:save/key (:save/key sb)] :save/revision (:save/revision sb) (:save/revision sb)]
                                         (-> t
                                 (assoc :trade/id (:id t) :trade/chat-id (:chat-id t)
                                        :trade/player-a (:player-a t) :trade/player-b (:player-b t)
                                        :trade/save-a (:save-a t) :trade/save-b (:save-b t)
                                        :trade/state (name (:state t)) :trade/created-at (:created-at t)
                                        :trade/expires-at (:expires-at t) :trade/revision (:revision t)
                                        :trade/context-edn (pr-str (:context t)))
                                 (assoc :trade/fence-a fence-a :trade/fence-b fence-b
                                        :trade/save-revision-a (:save/revision sa) :trade/save-revision-b (:save/revision sb))
                                 (dissoc :id :chat-id :player-a :player-b :save-a :save-b :state :created-at :expires-at :revision :context))
                              {:operation-receipt/id operation-id :operation-receipt/kind "trade-create" :operation-receipt/channel "gameplay"
                               :operation-receipt/payload-hash hash :operation-receipt/player-key (:player/key a) :operation-receipt/save-id save-a
                               :operation-receipt/summary (pr-str summary)}]})
            (assoc (get-trade conn operation-id) :duplicate false)
            (catch Exception ex
              (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id) (throw ex))))))))

(defn transition! [conn before after]
  (d/transact conn {:tx-data [[:db/cas [:trade/id (:id before)] :trade/revision (:revision before) (:revision after)]
                              [:db/add [:trade/id (:id before)] :trade/state (name (:state after))]
                              [:db/add [:trade/id (:id before)] :trade/context-edn (pr-str (:context after))]]})
  (get-trade conn (:id before)))

(defn- participant-entity [db chat-id player-id]
  (d/pull db '[*] [:player/key (str chat-id "|" player-id)]))

(defn- save-entity [db chat-id player-id save-id]
  (d/pull db '[*] [:save/key (str chat-id "|" player-id "|" save-id)]))

(defn- transition-request [conn request action target-state required-actor]
  (let [{:keys [operation-id trade-id chat-id player-id save-id fence-generation now]} request
        payload (reward/canonicalize (select-keys request [:trade-id :chat-id :player-id :save-id :fence-generation :now :action]))
        hash (reward/payload-hash payload)
        db (d/db conn)]
    (or (result-receipt (receipt db operation-id) hash operation-id)
        (let [before (get-trade conn trade-id)
              actor (participant-entity db chat-id player-id)
              a (participant-entity db chat-id (:player-a before))
              b (participant-entity db chat-id (:player-b before))
              sa (save-entity db chat-id (:player-a before) (:save-a before))
              sb (save-entity db chat-id (:player-b before) (:save-b before))
              expired? (and before (>= (long now) (long (:expires-at before))))
              actor-ok (and before (= chat-id (:chat-id before))
                            (trade/participant? before player-id)
                            (or (nil? required-actor)
                                (= player-id (if (= required-actor :player-b)
                                               (:player-b before)
                                               (:player-a before))))
                            (= save-id (:player/active-save-id actor))
                            (= fence-generation (:player/fence-generation actor)))]
          (when-not actor-ok (throw (err/error :trade_forbidden {})))
          (when-not (and a b sa sb
                         (= (:save-a before) (:player/active-save-id a))
                         (= (:save-b before) (:player/active-save-id b))
                         (= (:fence-a before) (:player/fence-generation a))
                         (= (:fence-b before) (:player/fence-generation b)))
            (throw (err/error :invalid_trade {})))
          (when (or (nil? before) (contains? #{:completed :refused :cancelled :expired :invalidated} (:state before)))
            (throw (err/error :invalid_trade_transition {})))
          (when (and expired? (not= action :expire))
            (throw (err/error :trade_expired {})))
          (let [after (if expired?
                        (assoc before :state :expired :revision (inc (:revision before)))
                        (assoc before :state target-state :revision (inc (:revision before))))
                summary {:trade-id trade-id :state (:state after)}
                tx [[:db/cas [:trade/id trade-id] :trade/revision (:revision before) (:revision after)]
                    [:db/cas [:player/key (:player/key a)] :player/active-save-id (:save-a before) (:save-a before)]
                    [:db/cas [:player/key (:player/key a)] :player/fence-generation (:fence-a before) (:fence-a before)]
                    [:db/cas [:player/key (:player/key b)] :player/active-save-id (:save-b before) (:save-b before)]
                    [:db/cas [:player/key (:player/key b)] :player/fence-generation (:fence-b before) (:fence-b before)]
                    [:db/add [:trade/id trade-id] :trade/state (name (:state after))]
                    [:db/add [:trade/id trade-id] :trade/revision (:revision after)]
                    {:operation-receipt/id operation-id :operation-receipt/kind (str "trade-" (name action))
                     :operation-receipt/channel "gameplay" :operation-receipt/payload-hash hash
                     :operation-receipt/player-key (:player/key actor) :operation-receipt/save-id save-id
                     :operation-receipt/summary (pr-str summary)}]]
            (try
              (d/transact conn {:tx-data tx})
              (assoc (get-trade conn trade-id) :duplicate false)
              (catch Exception ex
                (or (result-receipt (receipt (d/db conn) operation-id) hash operation-id)
                    (throw ex)))))))))

(defn accept! [conn request]
  (transition-request conn (assoc request :action :accept)
                      :accept :accepted :player-b))

(defn refuse! [conn request]
  (transition-request conn (assoc request :action :refuse)
                      :refuse :refused :player-b))

(defn cancel! [conn request]
  (transition-request conn (assoc request :action :cancel)
                      :cancel :cancelled nil))

(defn expire! [conn request]
  (transition-request conn (assoc request :action :expire)
                      :expire :expired nil))

(defn list-trades [conn chat-id player-id]
  (->> (d/q '[:find [?e ...]
              :in $ ?chat ?player
              :where [?e :trade/chat-id ?chat]
                     [?e :trade/player-a ?a]
                     [?e :trade/player-b ?b]
                     [(or (= ?player ?a) (= ?player ?b))]]
            (d/db conn) chat-id player-id)
       (map #(->trade (d/pull (d/db conn) '[*] %))) vec))
