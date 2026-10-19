(ns odisseu.platform.domain.admins
  "Shape canônico de admins-conhecidos (espelha zapbot.admins CLJS).")

(defn normalize-state
  "Cassandra JSON chat→[ids] → chat→#{ids}."
  [dados]
  (into {}
        (map (fn [[cid ids]]
               [(str cid) (set (map str ids))]))
        (or dados {})))

(defn state->persistivel
  [estado]
  (into {}
        (map (fn [[cid ids]]
               [(str cid) (vec (sort (map str ids)))]))
        estado))

(defn lembrar
  [estado cid id]
  (if (and cid id)
    (update estado (str cid) (fnil conj #{}) (str id))
    estado))

(defn esquecer
  [estado cid id]
  (if (and cid id)
    (update estado (str cid) (fnil disj #{}) (str id))
    estado))

(defn conhecido?
  [estado cid ids]
  (boolean (some (get estado (str cid) #{}) (map str ids))))

(defn admin-key
  [cid player-id]
  (str cid "|" player-id))
