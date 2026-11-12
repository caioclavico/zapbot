(ns pokemon.domain.egg-hatch
  "Nascimento canônico a partir do catálogo local (raid_boss_catalog) +
  species-base / species-hp / growth-v1 / moves / rarity.
  Sem HTTP. Espécie ausente → erro recuperável (não consome ovo)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.growth :as growth]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.raid-boss :as raid-boss]
            [pokemon.domain.rarity :as rarity]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def ^:private type-move-names
  {"normal" "Investida" "fire" "Lança-chamas" "water" "Jato d'Água"
   "electric" "Choque do Trovão" "grass" "Folha Mágica" "ice" "Raio de Gelo"
   "fighting" "Soco Focado" "poison" "Bomba de Lodo" "ground" "Terremoto"
   "flying" "Golpe Aéreo" "psychic" "Psíquico" "bug" "Tesoura X"
   "rock" "Pedra Afiada" "ghost" "Bola Sombria" "dragon" "Garra de Dragão"
   "dark" "Pulso Noturno" "steel" "Canhão Flash" "fairy" "Brilho Mágico"})

(defn- slug [x]
  (-> (or x "") str/lower-case str/trim (str/replace #"\s+" "-")))

(defn- hatch-seed-bytes
  [seed]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str seed) StandardCharsets/UTF_8)))

(defn- u8
  [^bytes b i]
  (bit-and (long (aget b (int i))) 0xff))

(defn catalog-row
  "Linha canônica da espécie no catálogo local. nil se ausente."
  [species]
  (let [s (slug species)
        rows (:species (raid-boss/catalog))]
    (some #(when (= s (slug (:species %))) %) rows)))

(defn- golpes-iniciais
  [tipos]
  (let [tipos (vec (or tipos ["normal"]))
        t1 (or (first tipos) "normal")
        t2 (or (second tipos) t1)
        g [{:nome-exibicao (get type-move-names t1 "Investida")
            :tipo t1 :poder 45 :classe :fisico :slug (str t1 "-basic")}
           {:nome-exibicao (get type-move-names t2 "Investida")
            :tipo t2 :poder 40 :classe :especial :slug (str t2 "-basic")}]]
    (moves/garantir-ataque-do-tipo g tipos)))

(defn- display-name
  [species row]
  (or (not-empty (str (:nome row)))
      (let [s (str species)]
        (if (str/blank? s) "Pokémon"
            (str (str/upper-case (subs s 0 1)) (subs s 1))))))

(defn- raridade-especie
  [species row]
  (or (not-empty (str (:raridade row)))
      (not-empty (str (rarity/classificar species)))
      "comum"))
(defn preparar-registro
  "Monta registro nível 1 canônico. Determinístico para o mesmo seed.
   Lança `:egg_species_data_missing` se a espécie não estiver no catálogo
   ou sem HP base confiável."
  [species pokemon-id seed]
  (let [row (catalog-row species)
        _ (when-not row
            (throw (err/error :egg_species_data_missing
                              {:species species :reason :not-in-catalog})))
        _ (when-not (species-base/base-hp-known? species)
            (throw (err/error :egg_species_data_missing
                              {:species species :reason :hp-base-missing})))
        b (hatch-seed-bytes (str seed "|" (slug species) "|" pokemon-id))
        shiny? (zero? (mod (+ (bit-shift-left (u8 b 0) 8) (u8 b 1)) 512))
        iv (fn [i] (mod (u8 b (+ 2 i)) 32))
        ivs {:hp (iv 0) :ataque (iv 1) :defesa (iv 2)
             :atq-esp (iv 3) :def-esp (iv 4) :veloc (iv 5)}
        tipos (vec (or (:tipos row) []))
        _ (when-not (seq tipos)
            (throw (err/error :egg_species_data_missing
                              {:species species :reason :tipos-ausentes})))
        base-hp (species-base/base-hp species)
        nivel 1
        hp (species-hp/hp-max base-hp nivel)
        ;; Stats oficiais recuperados do snapshot suavizado + IV residual.
        atk (+ (species-base/unsmooth-stat (:ataque row 40)) (quot (iv 1) 8))
        defe (+ (species-base/unsmooth-stat (:defesa row 40)) (quot (iv 2) 8))
        spatk (+ (species-base/unsmooth-stat (:atq-esp row 40)) (quot (iv 3) 8))
        spdef (+ (species-base/unsmooth-stat (:def-esp row 40)) (quot (iv 4) 8))
        veloc (+ (species-base/unsmooth-stat (:veloc row 40)) (quot (iv 5) 8))
        golpes (golpes-iniciais tipos)
        dex (or (:dex-id row) (:id row))
        imagem (when dex
                 (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
                      "sprites/pokemon/other/official-artwork/" dex ".png"))
        imagem-shiny (when dex
                       (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/"
                            "sprites/pokemon/other/official-artwork/shiny/" dex ".png"))
        reg {:id-pokemon pokemon-id
             :nome (display-name species row)
             :species (slug (:species row))
             :nivel nivel
             :hp hp
             :hp-atual hp
             :hp-base base-hp
             :ataque atk
             :defesa defe
             :atq-esp spatk
             :def-esp spdef
             :veloc veloc
             :tipos tipos
             :golpes golpes
             :shiny shiny?
             :shiny? shiny?
             :ivs ivs
             :raridade (raridade-especie species row)
             :origem "ovo"
             :amizade 70
             :status nil
             :species-hp-formula species-hp/formula-version}
        reg (cond-> reg
              imagem (assoc :imagem (if shiny? (or imagem-shiny imagem) imagem))
              imagem-shiny (assoc :imagem-shiny imagem-shiny))]
    (growth/ensure-growth-v1 reg)))
