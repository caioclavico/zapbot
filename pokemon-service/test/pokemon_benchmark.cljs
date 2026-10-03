(ns pokemon-benchmark
  "Fixture local para comparar o renderizador preservado e o extraído."
  (:require [zapbot.pokemon.core :as pokemon]))

(defn render []
  (#'pokemon/criar-cartao-treinador
   "Treinador de teste" 12
   {:nome "Pikachu" :nivel 12
    :imagem (str "data:image/svg+xml," (js/encodeURIComponent
              "<svg xmlns='http://www.w3.org/2000/svg' width='96' height='96'><circle cx='48' cy='48' r='40' fill='#facc15'/></svg>"))} 1))
