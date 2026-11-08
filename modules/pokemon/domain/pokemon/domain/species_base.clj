(ns pokemon.domain.species-base
  "HP base oficial por espécie a partir do catálogo canônico A6.

  O snapshot `raid_boss_catalog.edn` guarda stats **já suavizados**
  (`suavizar-stat` com compressão 0.5). Esta camada recupera a base oficial
  pela inversa linear (erro típico ±1 por arredondamento) e permite overrides
  explícitos para espécies de simulação/auditoria.

  Formas (ex.: `wishiwashi-solo`) resolvem via aliases explícitos em
  `species-form-aliases` — sem strip heurístico de sufixos.

  Sem PokeAPI em runtime. Dados ausentes → erro explícito (não inventa HP)."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid-boss :as raid-boss]
            [pokemon.domain.species-form-aliases :as form-aliases]))
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
  "HP base oficial da espécie/forma.
  Ordem: override de forma distinta → override de espécie → catálogo (via alias canônico).
  Lança se não houver resolução confiável (não inventa HP)."
  [species]
  (let [raw-slug (slug species)
        form-ov (get form-aliases/form-hp-overrides raw-slug)
        resolved (form-aliases/resolve-catalog-species raw-slug)]
    (or form-ov
        (get official-hp-overrides raw-slug)
        (get official-hp-overrides resolved)
        (when-let [row (catalog-row resolved)]
          (let [raw (:hp row)]
            (when (nil? raw)
              (throw (err/error :species_hp_base_missing
                                {:species raw-slug :resolved resolved
                                 :reason :catalog-hp-nil})))
            (unsmooth-stat raw)))
        (throw (err/error :species_hp_base_missing
                          {:species raw-slug :resolved resolved
                           :reason :not-in-catalog})))))

(defn base-hp-known?
  [species]
  (boolean
   (let [raw-slug (slug species)
         resolved (form-aliases/resolve-catalog-species raw-slug)]
     (or (contains? form-aliases/form-hp-overrides raw-slug)
         (contains? official-hp-overrides raw-slug)
         (contains? official-hp-overrides resolved)
         (catalog-row resolved)))))
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
