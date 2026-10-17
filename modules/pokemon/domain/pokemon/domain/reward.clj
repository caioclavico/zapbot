(ns pokemon.domain.reward
  "Payload canônico e hash de recompensas (sem I/O).
  Usado para idempotência estrita: mesmo operation-id + mesmo hash = retry OK;
  mesmo operation-id + hash diferente = conflito."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.model :as model])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn- canonicalize
  "Mapa ordenado recursivo para hashing estável."
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (keep (fn [[k v]]
                           (when (some? v)
                             [k (canonicalize v)]))
                         x))
    (vector? x) (mapv canonicalize x)
    (seq? x) (mapv canonicalize x)
    :else x))

(defn reward-payload
  "Extrai o efeito canônico (sem metadados de identidade/fence)."
  [{:keys [coins-delta trainer-xp-delta trainer-level pokemon item]}]
  (canonicalize
   (cond-> {}
     (some? coins-delta) (assoc :coins-delta (long coins-delta))
     (some? trainer-xp-delta) (assoc :trainer-xp-delta (long trainer-xp-delta))
     (some? trainer-level) (assoc :trainer-level (long trainer-level))
     pokemon (assoc :pokemon (canonicalize
                              (select-keys pokemon
                                           [:key :species :nickname :shiny :xp :level :in-party])))
     item (assoc :item (canonicalize
                        (select-keys item [:item-id :qty]))))))

(defn payload-hash
  [payload]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (pr-str (canonicalize payload)) StandardCharsets/UTF_8)]
    (.update digest bytes)
    (str/join (map #(format "%02x" %) (.digest digest)))))

(defn validate-gameplay-request!
  "Exige identidade completa para recompensa de gameplay."
  [{:keys [operation-id chat-id player-id save-id fence-generation] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id)
    (throw (err/error :missing_reward_identity
                      {:need [:chat-id :player-id :save-id]})))
  (when-not (integer? fence-generation)
    (throw (err/error :missing_fence_generation {})))
  (model/validate-save-slot! save-id)
  req)

(defn validate-admin-request!
  "Admin/migração: operation-id + identidade do save, sem fence."
  [{:keys [operation-id chat-id player-id save-id] :as req}]
  (when (or (nil? operation-id) (empty? (str operation-id)))
    (throw (err/error :missing_operation_id {})))
  (when-not (and chat-id player-id save-id)
    (throw (err/error :missing_reward_identity
                      {:need [:chat-id :player-id :save-id]})))
  (model/validate-save-slot! save-id)
  req)

(defn assert-receipt-payload!
  "Se já existe recibo, o hash deve coincidir."
  [stored-hash expected-hash operation-id]
  (when (and stored-hash (not= stored-hash expected-hash))
    (throw (err/error :receipt_payload_mismatch
                      {:operation-id operation-id
                       :stored stored-hash
                       :expected expected-hash})))
  true)
