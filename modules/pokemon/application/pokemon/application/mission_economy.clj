(ns pokemon.application.mission-economy
  "Contratos de presentes/resgates; Node não fornece prêmios ou RNG."
  (:require [clojure.string :as str]
            [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.model :as model]
            [pokemon.domain.ops :as ops]))

(defn validate! [writer request]
  (ops/validate-mutation! (merge request writer))
  (model/validate-create-player! writer)
  (doseq [v [(:chat-id writer) (:player-id writer)]]
    (when (str/includes? v "|") (throw (err/error :invalid_identity {}))))
  request)

(defn claim! [store writer kind request]
  (validate! writer request)
  (when (or (not (string? (:period request))) (str/blank? (:period request)))
    (throw (err/error :invalid_query {:required ["period"]})))
  (case kind
    :daily (ports/claim-daily-missions! store writer request)
    :weekly (ports/claim-weekly-missions! store writer request)
    (throw (err/error :invalid_query {:kind kind}))))

(defn validate-gift! [writer request]
  (validate! writer request)
  (let [target (:target-player-id request)]
    (when (or (not (id/valid-identity-part? target)) (str/blank? target)
              (str/includes? target "|") (= target (:player-id writer)))
      (throw (err/error :invalid_gift_target {}))))
  request)

(defn send-gift! [store writer request]
  (validate-gift! writer request)
  (ports/send-gift! store writer request))

(defn collect-pending! [store writer request]
  (validate! writer request)
  (ports/collect-mission-pending! store writer request))

(defn dto
  "IDs de itens são valores, evitando camelCase acidental nas chaves JSON."
  [result]
  (reduce (fn [m k]
            (if (map? (get m k))
              (update m k #(mapv (fn [[id qty]] {:item-id id :qty qty}) (sort-by key %))) m))
          result [:items :added :pending]))
