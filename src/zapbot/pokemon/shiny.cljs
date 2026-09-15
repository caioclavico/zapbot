(ns zapbot.pokemon.shiny)

(def chance 512)

(defn sortear
  "Sorteio por encontro, fora do cache de espécies. Shiny não altera atributos."
  ([pokemon] (sortear pokemon (rand-int chance)))
  ([pokemon sorteio]
   (if (zero? sorteio)
     (assoc pokemon :shiny? true :imagem (or (:imagem-shiny pokemon) (:imagem pokemon)))
     pokemon)))
