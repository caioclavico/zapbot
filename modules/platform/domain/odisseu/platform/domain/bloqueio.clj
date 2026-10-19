(ns odisseu.platform.domain.bloqueio
  "Shape canônico do módulo bloqueio (espelha zapbot.bloqueio CLJS).
   Strings nas chaves externas para round-trip com JSON Cassandra."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(def protegidos #{"bloquear" "desbloquear"})
(def jogos-comandos #{"bola8" "sorteio" "velha" "naval" "adedonha" "stop" "quiz" "pokemon"})

(defn normalize-chat-entry
  "Cassandra/persistível → shape interno {:bot? bool :comandos #{str}}."
  [info]
  (let [info (or info {})]
    {:bot? (boolean (or (get info :bot?) (get info "bot?")))
     :comandos (set (or (get info :comandos) (get info "comandos") #{}))}))

(defn ->persistivel
  "Shape interno → mapa com strings (como estado->persistivel no CLJS)."
  [{:keys [bot? comandos]}]
  {"bot?" (boolean bot?)
   "comandos" (vec (sort (map str comandos)))})

(defn normalize-state
  "Mapa chat→info (JSON Cassandra) → mapa chat→{:bot? :comandos}."
  [dados]
  (into {}
        (map (fn [[cid info]]
               [(str cid) (normalize-chat-entry info)]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (into {}
        (map (fn [[cid info]]
               [(str cid) (->persistivel info)]))
        estado))

(defn bloquear-comandos
  [estado cid cmds]
  (let [cid (str cid)
        cmds (->> cmds (map str/lower-case) (remove protegidos) set)]
    (update-in estado [cid :comandos] (fnil set/union #{}) cmds)))

(defn desbloquear-comandos
  [estado cid cmds]
  (let [cid (str cid)
        cmds (set (map str/lower-case cmds))]
    (update-in estado [cid :comandos] (fnil set/difference #{}) cmds)))

(defn set-bot-blocked
  [estado cid blocked?]
  (assoc-in estado [(str cid) :bot?] (boolean blocked?)))

(defn comando-bloqueado?
  [estado cid comando]
  (let [info (get estado (str cid))
        cmd (str/lower-case (str comando))]
    (boolean
     (or (:bot? info)
         (contains? (:comandos info #{}) cmd)))))
