(ns odisseu.platform.domain.lembretes
  "Shape canônico de lembretes (espelha zapbot.lembretes CLJS).
   Sem recorrência; deadline = epoch ms absoluto."
  (:require [clojure.string :as str]))

(def statuses #{"pending" "claimed" "completed" "cancelled" "failed"})
(def default-lease-ms 30000)

(defn normalize-entry
  [info]
  (let [info (or info {})]
    {:id (str (or (get info :id) (get info "id")))
     :chat (str (or (get info :chat) (get info "chat") ""))
     :texto (str (or (get info :texto) (get info "texto") ""))
     :criador (when-let [c (or (get info :criador) (get info "criador"))]
                (when-not (str/blank? (str c)) (str c)))
     :em (long (or (get info :em) (get info "em") 0))
     :status (str (or (get info :status) (get info "status") "pending"))
     :claimant (when-let [c (or (get info :claimant) (get info "claimant"))]
                 (when-not (str/blank? (str c)) (str c)))
     :lease-until (when-let [x (or (get info :lease-until) (get info "lease-until"))]
                    (long x))
     :fence (long (or (get info :fence) (get info "fence") 0))
     :fail-count (long (or (get info :fail-count) (get info "fail-count") 0))
     :last-error (when-let [e (or (get info :last-error) (get info "last-error"))]
                   (str e))}))

(defn ->persistivel
  "Shape Cassandra legado (sem campos operacionais)."
  [{:keys [id chat texto criador em]}]
  {"id" (str id)
   "chat" (str chat)
   "texto" (str texto)
   "criador" (str (or criador ""))
   "em" (long em)})

(defn ->wire
  [r]
  (cond-> {"id" (:id r)
           "chat" (:chat r)
           "texto" (:texto r)
           "em" (:em r)
           "status" (:status r)
           "fence" (:fence r)}
    (:criador r) (assoc "criador" (:criador r))
    (:claimant r) (assoc "claimant" (:claimant r))
    (:lease-until r) (assoc "leaseUntil" (:lease-until r))
    (:fail-count r) (assoc "failCount" (:fail-count r))
    (:last-error r) (assoc "lastError" (:last-error r))))

(defn normalize-state
  "Import Cassandra: só entradas pending com deadline original."
  [dados]
  (into {}
        (map (fn [[id info]]
               (let [e (normalize-entry (assoc (or info {}) :id (or (:id info) (get info "id") id)))]
                 [(:id e) (assoc e :status "pending" :fence 0 :fail-count 0
                                 :claimant nil :lease-until nil :last-error nil)])))
        (or dados {})))

(defn state->persistivel
  "Exporta apenas pending/failed (visíveis como pendentes no legado)."
  [estado]
  (into {}
        (keep (fn [[id r]]
                (when (#{"pending" "failed" "claimed"} (:status r))
                  [id (->persistivel r)])))
        estado))

(defn due?
  [r now-ms]
  (and (#{"pending" "failed"} (:status r))
       (<= (long (:em r)) (long now-ms))))

(defn claim-expired?
  [r now-ms]
  (and (= "claimed" (:status r))
       (or (nil? (:lease-until r))
           (< (long (:lease-until r)) (long now-ms)))))

(defn claimable?
  [r now-ms]
  (or (due? r now-ms)
      (claim-expired? r now-ms)))

(defn active-for-list?
  "Listagem ao usuário: ainda não concluído/cancelado."
  [r]
  (contains? #{"pending" "claimed" "failed"} (:status r)))
