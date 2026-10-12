(ns zapbot.pokemon.armazenamento-regras)

(def capacidade-inicial-pokemon 30)
(def capacidade-inicial-mochila 50)
(def marcos
  {5 {:pokemon 50} 10 {:mochila 50} 15 {:pokemon 50} 20 {:mochila 50}
   25 {:pokemon 50} 30 {:mochila 50} 35 {:pokemon 50} 40 {:mochila 50}
   45 {:pokemon 50} 50 {:mochila 50}})

(defn bonus-por-nivel [nivel tipo]
  (reduce + 0 (for [[marco bonus] marcos :when (<= marco (max 1 (or nivel 1)))]
                (get bonus tipo 0))))

(defn marcos-entre [nivel-anterior nivel-novo]
  (->> marcos
       (filter (fn [[nivel _]] (and (> nivel nivel-anterior) (<= nivel nivel-novo))))
       (sort-by key)))

(defn capacidade-pokemon [nivel expansoes capacidade-legada]
  (max (or capacidade-legada 0)
       (+ capacidade-inicial-pokemon (bonus-por-nivel nivel :pokemon) (* 50 (max 0 (or expansoes 0))))))

(defn capacidade-mochila [nivel expansoes capacidade-legada]
  (max (or capacidade-legada 0)
       (+ capacidade-inicial-mochila (bonus-por-nivel nivel :mochila) (* 50 (max 0 (or expansoes 0))))))
