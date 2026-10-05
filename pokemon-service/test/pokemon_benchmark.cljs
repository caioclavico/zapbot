(ns pokemon-benchmark
  "Renderizadores reais, arte local e nenhuma conexão de jogo."
  (:require [zapbot.pokemon.core :as pokemon]
            [zapbot.pokemon.treinador :as treinador]
            [promesa.core :as p]))

(def ^:private fixture
  {:nome "Pikachu" :nivel 12 :altura 0.4 :hp 80 :tipos ["electric"]
   :raridade "comum" :motivacao-ginasio 65
   :imagem (str "data:image/svg+xml," (js/encodeURIComponent
              "<svg xmlns='http://www.w3.org/2000/svg' width='475' height='475'><circle cx='237' cy='237' r='200' fill='#facc15'/><path d='M237 90L140 260H230L180 385L335 195H245Z' fill='#a16207'/></svg>"))})

(defn render []
  (#'pokemon/criar-cartao-treinador
   "Treinador de teste" 12
   fixture 1))

(defn battle []
  (p/then (#'pokemon/resposta-imagem-pvp
   {:pokemons {:x fixture :o fixture} :vez :x :jogadores {:x "fixture" :o "other"}}
   "Ataque elétrico! HP 60/80" {:tipo "electric" :origem :x :dano 20})
   #(get-in % [:media :buffer])))

(defn raid []
  (p/then (#'pokemon/resposta-cartao-evento :raid (:imagem fixture) "HP do chefe: 200/440")
          #(get-in % [:media :buffer])))

(defn team []
  (p/then (#'pokemon/criar-cartao-time
   (mapv (fn [i] {:indice i :registro (treinador/pokemon->registro fixture 80 nil)}) (range 12))
   0 12) :buffer))
