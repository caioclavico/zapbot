(ns odisseu.platform.domain.participantes
  "Shape canônico de participantes (espelha zapbot.historico CLJS).
   chat → {player-id → nome}. Mensagens do histórico continuam só em RAM."
  (:require [clojure.string :as str]))

(defn participant-key
  [chat-id player-id]
  (str chat-id "|" player-id))

(defn normalize-names
  [m]
  (into {}
        (map (fn [[pid nome]]
               [(str pid) (str nome)]))
        (or m {})))

(defn normalize-state
  [dados]
  (into {}
        (map (fn [[cid names]]
               [(str cid) (normalize-names names)]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (normalize-state estado))

(defn ->wire-chat
  [cid names]
  {"chatId" (str cid)
   "players"
   (->> names
        (map (fn [[pid nome]]
               {"playerId" (str pid) "name" (str nome)}))
        (sort-by #(get % "playerId"))
        vec)})

(defn valid-upsert?
  [chat-id player-id name]
  (and (not (str/blank? (str chat-id)))
       (not (str/blank? (str player-id)))
       (not (str/blank? (str name)))))
