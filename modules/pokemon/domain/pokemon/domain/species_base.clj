(ns pokemon.domain.species-base
  "HP base oficial por espécie a partir do catálogo canônico A6.

  O snapshot `raid_boss_catalog.edn` guarda stats **já suavizados**
  (`suavizar-stat` com compressão 0.5). Esta camada recupera a base oficial
  pela inversa linear (erro típico ±1 por arredondamento) e permite overrides
  explícitos para espécies de simulação/auditoria.

  Sem PokeAPI em runtime. Dados ausentes → erro explícito (não inventa HP)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid-boss :as raid-boss]))

(def media-stat-referencia 75.0)
(def fator-compressao-legado 0.5)

(defn unsmooth-stat
  "Inversa de `hunt/suavizar-stat` (compressão 0.5)."
  [suavizado]
  (long (Math/round
         (+ media-stat-referencia
            (/ (- (double (or suavizado 0)) media-stat-referencia)
               fator-compressao-legado)))))

;; Overrides oficiais (PokeAPI / Pokédex) para espécies críticas de balanço.
(def ^:private official-hp-overrides
  {"magikarp" 20
   "pidgey" 40
   "pikachu" 35
   "eevee" 55
   "wartortle" 59
   "charizard" 78
   "gyarados" 95
   "gengar" 60
   "snorlax" 160
   "chansey" 250
   "blissey" 255
   "shuckle" 20
   "dragonite" 91
   "mewtwo" 106
   "shedinja" 1
   "bulbasaur" 45
   "charmander" 39
   "squirtle" 44
   "ivysaur" 60
   "venusaur" 80
   "charmeleon" 58
   "blastoise" 79})

(defn- slug [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn- catalog-row
  [species]
  (let [s (slug species)
        rows (:species (raid-boss/catalog))]
    (some #(when (= s (slug (:species %))) %) rows)))

(defn base-hp
  "HP base oficial da espécie. Preferência: override → catálogo (unsmooth).
  Lança se a espécie não existir no catálogo A6."
  [species]
  (let [s (slug species)]
    (or (get official-hp-overrides s)
        (when-let [row (catalog-row s)]
          (let [raw (:hp row)]
            (when (nil? raw)
              (throw (err/error :species_hp_base_missing
                                {:species s :reason :catalog-hp-nil})))
            (unsmooth-stat raw)))
        (throw (err/error :species_hp_base_missing
                          {:species s :reason :not-in-catalog})))))

(defn base-hp-known?
  [species]
  (boolean
   (or (contains? official-hp-overrides (slug species))
       (catalog-row species))))

(defn catalog-hp-table
  "Tabela {species → {:hp-suavizado :hp-base :fonte}} para simulações.
  Não usa PokeAPI."
  []
  (into (sorted-map)
        (map (fn [row]
               (let [s (slug (:species row))
                     suav (long (:hp row 0))
                     override? (contains? official-hp-overrides s)
                     base (if override?
                            (get official-hp-overrides s)
                            (unsmooth-stat suav))]
                 [s {:hp-suavizado suav
                     :hp-base base
                     :fonte (if override? :override :unsmooth-catalog)}])))
        (:species (raid-boss/catalog))))
