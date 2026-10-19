(ns odisseu.platform.infrastructure.migration.compare
  "Comparação de estado Cassandra-shape ↔ Datomic-shape (sintético)."
  (:require [clojure.set :as set]))

(defn normalize-bloqueio
  [m]
  (into (sorted-map)
        (map (fn [[cid info]]
               [cid {"bot?" (boolean (or (get info "bot?") (:bot? info)))
                     "comandos" (vec (sort (map str (or (get info "comandos")
                                                        (:comandos info)
                                                        []))))}]))
        (or m {})))

(defn normalize-admins
  "Omite chats sem admins (equivalente a ausente no Datomic)."
  [m]
  (into (sorted-map)
        (keep (fn [[cid ids]]
                (let [v (vec (sort (map str ids)))]
                  (when (seq v) [cid v])))
              (or m {}))))

(defn diff-maps
  "Retorna {:only-left :only-right :changed} comparando mapas normalizados."
  [left right]
  (let [lk (set (keys left))
        rk (set (keys right))]
    {:only-left (into (sorted-map) (select-keys left (set/difference lk rk)))
     :only-right (into (sorted-map) (select-keys right (set/difference rk lk)))
     :changed (into (sorted-map)
                    (keep (fn [k]
                            (when (not= (get left k) (get right k))
                              [k {:left (get left k) :right (get right k)}]))
                          (set/intersection lk rk)))}))

(defn equivalent?
  [left right]
  (let [d (diff-maps left right)]
    (and (empty? (:only-left d))
         (empty? (:only-right d))
         (empty? (:changed d)))))
