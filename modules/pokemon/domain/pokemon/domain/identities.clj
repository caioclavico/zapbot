(ns pokemon.domain.identities
  "Identificadores compatíveis com o modelo canônico Cassandra
  (chat_id, player_id, active_save_id / slot id).

  Regras espelham validIdentityPart / validSlotId do save-slot-store."
  (:require [clojure.string :as str]))

(def ^:private max-identity-len 256)

(defn valid-identity-part?
  [value]
  (and (string? value)
       (pos? (count value))
       (<= (count value) max-identity-len)
       (not (re-find #"[\u0000-\u001f\u007f]" value))))

(defn valid-slot-id?
  "Mesmo padrão do Cassandra: ^[a-z0-9][a-z0-9_-]{0,63}$"
  [value]
  (and (string? value)
       (boolean (re-matches #"^[a-z0-9][a-z0-9_-]{0,63}$" value))))

(defn player-key
  [chat-id player-id]
  (when-not (and (valid-identity-part? chat-id) (valid-identity-part? player-id))
    (throw (ex-info "invalid_identity" {:chat-id chat-id :player-id player-id})))
  (str chat-id "|" player-id))

(defn save-key
  [chat-id player-id save-id]
  (when-not (valid-slot-id? save-id)
    (throw (ex-info "invalid_slot_id" {:save-id save-id})))
  (str (player-key chat-id player-id) "|" save-id))

(defn inventory-item-key
  [save-key* item-id]
  (when (or (str/blank? item-id) (> (count item-id) 64))
    (throw (ex-info "invalid_item_id" {:item-id item-id})))
  (str save-key* "|" item-id))

(defn pokedex-entry-key
  [save-key* species]
  (str save-key* "|" species))

(defn gym-occupation-key
  [chat-id gym-id]
  (str chat-id "|" gym-id))

(defn raid-participant-key
  [raid-id player-id]
  (str raid-id "|" player-id))

(defn raid-capture-key
  [raid-id player-id]
  (str raid-id "|cap|" player-id))

(defn adventure-account-key
  "Estado Adventure por jogador (global ao pid, não ao save)."
  [chat-id player-id]
  (player-key chat-id player-id))

(defn enfermaria-bed-key
  [save-key* bed-id]
  (str save-key* "|bed|" bed-id))

(defn parse-player-key
  [key]
  (let [parts (str/split key #"\|" 2)]
    (when (not= 2 (count parts))
      (throw (ex-info "invalid_player_key" {:key key})))
    {:chat-id (nth parts 0) :player-id (nth parts 1)}))
