(ns odisseu.platform.domain.quiz-historico
  "Shape canônico de quiz-historico (espelha zapbot.quiz CLJS).
   chat → [texto-pergunta]; retenção = últimas historico-max entradas."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def historico-max 12)

(defn questions->edn
  [qs]
  (pr-str (mapv str qs)))

(defn edn->questions
  [s]
  (let [v (edn/read-string (str s))]
    (if (vector? v) (mapv str v) [])))

(defn normalize-entry
  "Lista Cassandra/JSON → vector de strings (ordem preservada)."
  [xs]
  (->> (or xs [])
       (map str)
       (remove str/blank?)
       vec))

(defn normalize-state
  [dados]
  (into {}
        (map (fn [[cid qs]]
               [(str cid) (normalize-entry qs)]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (into {}
        (map (fn [[cid qs]]
               [(str cid) (mapv str qs)]))
        estado))

(defn append
  "Conjuga pergunta e aplica take-last historico-max (igual ao Node)."
  [hist pergunta]
  (vec (take-last historico-max (conj (vec (or hist [])) (str pergunta)))))
