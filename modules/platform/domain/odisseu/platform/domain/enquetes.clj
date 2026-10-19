(ns odisseu.platform.domain.enquetes
  "Shape canônico de enquetes (espelha zapbot.enquetes CLJS).
   Uma enquete ativa por chat; votos eleitor→n (1-based); sem deadline."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def max-opcoes 10)
(def min-opcoes 2)

(defn vote-key
  [chat-id voter-id]
  (str chat-id "|" voter-id))

(defn normalize-entry
  "Cassandra/persistível → {:pergunta :opcoes :votos :prefix}."
  [info]
  (let [info (or info {})
        votos-raw (or (get info :votos) (get info "votos") {})
        votos (into {}
                    (map (fn [[k v]]
                           [(str k) (long (if (number? v) v (Long/parseLong (str v))))])
                         votos-raw))
        opcoes (vec (or (get info :opcoes) (get info "opcoes") []))]
    {:pergunta (str (or (get info :pergunta) (get info "pergunta") ""))
     :opcoes (mapv str opcoes)
     :votos votos
     :prefix (str (or (get info :prefix) (get info "prefix") "!"))}))

(defn ->persistivel
  [{:keys [pergunta opcoes votos prefix]}]
  {"pergunta" (str pergunta)
   "opcoes" (mapv str opcoes)
   "votos" (into {} (map (fn [[k v]] [(str k) (long v)]) (or votos {})))
   "prefix" (str (or prefix "!"))})

(defn normalize-state
  [dados]
  (into {}
        (map (fn [[cid info]]
               [(str cid) (normalize-entry info)]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (into {}
        (map (fn [[cid info]]
               [(str cid) (->persistivel info)]))
        estado))

(defn options->edn
  [opcoes]
  (pr-str (mapv str opcoes)))

(defn edn->options
  [s]
  (let [v (edn/read-string (str s))]
    (if (vector? v) (mapv str v) [])))

(defn valid-create?
  [{:keys [pergunta opcoes]}]
  (and (not (str/blank? (str pergunta)))
       (<= min-opcoes (count opcoes) max-opcoes)
       (every? #(not (str/blank? (str %))) opcoes)))

(defn valid-choice?
  [enquete n]
  (and (integer? n)
       (<= 1 n (count (:opcoes enquete)))))
