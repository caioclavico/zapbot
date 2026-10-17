(ns pokemon.domain.capacity
  "Capacidade de coleção/mochila — espelha zapbot.pokemon.armazenamento-regras."
  (:require [pokemon.domain.xp :as xp]))

(def capacidade-inicial-pokemon 30)
(def capacidade-inicial-mochila 50)

(def marcos
  {5 {:pokemon 50} 10 {:mochila 50} 15 {:pokemon 50} 20 {:mochila 50}
   25 {:pokemon 50} 30 {:mochila 50} 35 {:pokemon 50} 40 {:mochila 50}
   45 {:pokemon 50} 50 {:mochila 50}})

(defn bonus-por-nivel
  [nivel tipo]
  (reduce + 0 (for [[marco bonus] marcos
                    :when (<= marco (max 1 (long (or nivel 1))))]
                (long (get bonus tipo 0)))))

(defn capacidade-pokemon
  [nivel expansoes capacidade-legada]
  (max (long (or capacidade-legada 0))
       (+ capacidade-inicial-pokemon
          (bonus-por-nivel nivel :pokemon)
          (* 50 (max 0 (long (or expansoes 0)))))))

(defn capacidade-mochila
  [nivel expansoes capacidade-legada]
  (+ (max capacidade-inicial-mochila (long (or capacidade-legada 0)))
     (bonus-por-nivel nivel :mochila)
     (* 50 (max 0 (long (or expansoes 0))))))

(defn cabe-pokemon?
  "Sem ginásios neste recorte: ocupação = tamanho da coleção."
  [colecao-count save]
  (let [perfil (xp/perfil-treinador save)
        nivel (:nivel perfil)
        cap (capacidade-pokemon nivel
                                (:expansoes-pokemon-50 save 0)
                                (:capacidade-pokemon-legada save 0))]
    (< (long colecao-count) cap)))
