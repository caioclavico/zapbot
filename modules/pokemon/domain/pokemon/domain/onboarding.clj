(ns pokemon.domain.onboarding
  "Onboarding M9.6 — kit inicial (10 Pokébolas) + starters Gen1.
   Quantidades e espécies espelham legado (loja/resgatar-bolas! + core/iniciais).
   Sem I/O."
  (:require [clojure.string :as str]
            [pokemon.domain.collection :as coll]
            [pokemon.domain.errors :as err]
            [pokemon.domain.hunt :as hunt]
            [pokemon.domain.moves :as moves]))

(def kit-item-id "pokebola")
(def kit-qty 10)

(def ^:private sprites
  {"bulbasaur"
   "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/1.png"
   "charmander"
   "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/4.png"
   "squirtle"
   "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/7.png"})

;; Base stats oficiais Gen1 → suavizar-stat (mesmo fator do legado).
(def ^:private starters-raw
  [{:index 1
    :species "bulbasaur"
    :nome "Bulbasaur"
    :emoji "🌱"
    :tipos ["grass" "poison"]
    :descricao "Planta/Veneno — equilibrado e resistente."
    :base {:hp 45 :ataque 49 :defesa 49 :atq-esp 65 :def-esp 65 :veloc 45}
    :golpe {:slug "vine-whip" :nome-exibicao "Chicote de Cipó" :tipo "grass"
            :poder 45 :classe :fisico :precisao 100}}
   {:index 2
    :species "charmander"
    :nome "Charmander"
    :emoji "🔥"
    :tipos ["fire"]
    :descricao "Fogo — ofensivo e ágil."
    :base {:hp 39 :ataque 52 :defesa 43 :atq-esp 60 :def-esp 50 :veloc 65}
    :golpe {:slug "ember" :nome-exibicao "Brasa" :tipo "fire"
            :poder 40 :classe :especial :precisao 100}}
   {:index 3
    :species "squirtle"
    :nome "Squirtle"
    :emoji "💧"
    :tipos ["water"]
    :descricao "Água — defensivo com boa especial."
    :base {:hp 44 :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43}
    :golpe {:slug "water-gun" :nome-exibicao "Jato d'Água" :tipo "water"
            :poder 40 :classe :especial :precisao 100}}])

(defn- com-stats-suavizados
  [{:keys [base] :as s}]
  (let [hp (hunt/suavizar-stat (:hp base))
        atk (hunt/suavizar-stat (:ataque base))
        def (hunt/suavizar-stat (:defesa base))
        spa (hunt/suavizar-stat (:atq-esp base))
        spd (hunt/suavizar-stat (:def-esp base))
        spe (hunt/suavizar-stat (:veloc base))]
    (-> s
        (dissoc :base :golpe)
        (assoc :hp hp :ataque atk :defesa def :atq-esp spa :def-esp spd :veloc spe
               :nivel 1 :taxa-captura 45 :raridade "comum"
               :imagem (get sprites (:species s))
               :golpes [(:golpe s)]))))

(def starters
  "Catálogo público dos 3 iniciais (stats já suavizados)."
  (mapv com-stats-suavizados starters-raw))

(defn starter-menu
  []
  (mapv (fn [s]
          {:index (:index s)
           :species (:species s)
           :nome (:nome s)
           :emoji (:emoji s)
           :tipos (:tipos s)
           :descricao (:descricao s)
           :imagem (:imagem s)})
        starters))

(defn resolve-starter
  "Aceita índice 1–3 ou nome/species (pt/en)."
  [choice]
  (let [raw (str/trim (str (or choice "")))
        lower (str/lower-case raw)
        by-idx (when (re-matches #"^[123]$" lower)
                 (some #(when (= (Long/parseLong lower) (long (:index %))) %) starters))
        by-name (some (fn [s]
                        (when (or (= lower (:species s))
                                  (= lower (str/lower-case (:nome s))))
                          s))
                      starters)]
    (or by-idx by-name
        (throw (err/error :invalid_starter
                          {:choice raw :allowed [1 2 3 "bulbasaur" "charmander" "squirtle"]})))))

(defn onboarding-status
  "Máquina de estados a partir das flags + coleção.
   not_started | awaiting_starter | completed"
  [{:keys [kit-inicial-resgatado inicial-escolhido collection-count]}]
  (cond
    (or inicial-escolhido (pos? (long (or collection-count 0)))) "completed"
    kit-inicial-resgatado "awaiting_starter"
    :else "not_started"))

(defn starter-disponivel?
  "Espelha treinador/inicial-disponivel?: sem flag e sem Pokémon na coleção.
   Jogadores migrados com coleção NÃO recebem starter."
  [{:keys [inicial-escolhido collection-count]}]
  (and (not inicial-escolhido)
       (zero? (long (or collection-count 0)))))

(defn kit-disponivel?
  [{:keys [kit-inicial-resgatado]}]
  (not kit-inicial-resgatado))

(defn starter->registro
  "Registro de coleção nível 1 (amizade 70, sem IVs — legado)."
  [starter]
  (let [golpes (moves/garantir-ataque-do-tipo (:golpes starter) (:tipos starter))
        poke (assoc starter :shiny false :shiny? false :golpes golpes)]
    (coll/pokemon->registro poke (:hp poke) nil)))
