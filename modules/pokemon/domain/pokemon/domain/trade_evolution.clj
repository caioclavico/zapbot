(ns pokemon.domain.trade-evolution
  "Catálogo versionado das evoluções cujo gatilho canônico é uma troca.

  A tabela contém somente condições (espécie de origem, parceiro e item) e
  stats-base obtidos do catálogo PokeAPI usado pelo legado. A aplicação dos
  efeitos continua em `pokemon.domain.evolution`, sem I/O."
  (:require [clojure.string :as str]
            [pokemon.domain.evolution :as evolution]
            [pokemon.domain.xp :as xp]))

(def catalog-version "pokeapi-trade-evolutions-2026-10")

(def ^:private item->pokeapi
  {"revestimento-metalico" "metal-coat"
   "escama-dragao" "dragon-scale"
   "upgrade" "up-grade"
   "protetor" "protector"
   "pedra-rei" "kings-rock"
   "eletrizador" "electirizer"
   "magmarizador" "magmarizer"
   "tecido-ceifador" "reaper-cloth"
   "escama-prisma" "prism-scale"
   "chicote-doce" "whipped-dream"
   "sache-perfumado" "sachet"})

(def ^:private rules
  {"kadabra" [{:target "alakazam"}]
   "machoke" [{:target "machamp"}]
   "graveler" [{:target "golem"}]
   "haunter" [{:target "gengar"}]
   "boldore" [{:target "gigalith"}]
   "gurdurr" [{:target "conkeldurr"}]
   "phantump" [{:target "trevenant"}]
   "pumpkaboo" [{:target "gourgeist"}]
   "karrablast" [{:target "escavalier" :partner "shelmet"}]
   "shelmet" [{:target "accelgor" :partner "karrablast"}]
   "spritzee" [{:target "aromatisse" :item "sachet"}]
   "swirlix" [{:target "slurpuff" :item "whipped-dream"}]
   "onix" [{:target "steelix" :item "metal-coat"}]
   "scyther" [{:target "scizor" :item "metal-coat"}]
   "seadra" [{:target "kingdra" :item "dragon-scale"}]
   "poliwhirl" [{:target "politoed" :item "kings-rock"}]
   "slowpoke" [{:target "slowking" :item "kings-rock"}]
   "porygon" [{:target "porygon2" :item "up-grade"}]
   "dusclops" [{:target "dusknoir" :item "reaper-cloth"}]
   "rhydon" [{:target "rhyperior" :item "protector"}]
   "magmar" [{:target "magmortar" :item "magmarizer"}]
   "electabuzz" [{:target "electivire" :item "electirizer"}]})

(def ^:private base-stats
  {"alakazam" [55 50 45 135 95 120] "machamp" [90 130 80 65 85 55]
   "golem" [80 120 130 55 65 45] "gengar" [60 65 60 130 75 110]
   "gigalith" [85 135 130 60 80 25] "conkeldurr" [105 140 95 55 65 45]
   "trevenant" [85 110 76 65 82 56] "gourgeist" [55 85 122 58 75 99]
   "escavalier" [70 135 105 60 105 20] "accelgor" [80 70 40 100 60 145]
   "aromatisse" [101 72 72 99 89 29] "slurpuff" [82 80 86 85 75 72]
   "steelix" [75 85 200 55 65 30] "scizor" [70 130 100 55 80 65]
   "kingdra" [75 95 95 95 95 85] "politoed" [90 75 75 90 100 70]
   "slowking" [95 75 80 100 110 30] "porygon2" [85 80 90 105 95 60]
   "huntail" [55 104 105 94 75 52] "gorebyss" [55 84 105 114 75 52]
   "dusknoir" [45 100 135 65 135 45] "rhyperior" [115 140 130 55 55 40]
   "magmortar" [75 95 67 125 95 83] "electivire" [75 123 67 95 85 95]})

(def ^:private types
  {"alakazam" ["psychic"] "machamp" ["fighting"] "golem" ["rock" "ground"]
   "gengar" ["ghost" "poison"] "gigalith" ["rock"] "conkeldurr" ["fighting"]
   "trevenant" ["ghost" "grass"] "gourgeist" ["ghost" "grass"]
   "escavalier" ["bug" "steel"] "accelgor" ["bug"] "aromatisse" ["fairy"]
   "slurpuff" ["fairy"] "steelix" ["steel" "ground"] "scizor" ["bug" "steel"]
   "kingdra" ["water" "dragon"] "politoed" ["water"] "slowking" ["water" "psychic"]
   "porygon2" ["normal"] "huntail" ["water"] "gorebyss" ["water"]
   "dusknoir" ["ghost"] "rhyperior" ["ground" "rock"] "magmortar" ["fire"]
   "electivire" ["electric"]})

(def ^:private dex-ids
  {"alakazam" 65 "machamp" 68 "golem" 76 "gengar" 94
   "gigalith" 526 "conkeldurr" 534 "trevenant" 709 "gourgeist" 711
   "escavalier" 589 "accelgor" 617 "aromatisse" 683 "slurpuff" 685
   "steelix" 208 "scizor" 212 "kingdra" 230 "politoed" 186
   "slowking" 199 "porygon2" 233 "dusknoir" 477 "rhyperior" 464
   "magmortar" 467 "electivire" 466})

(def ^:private abilities
  {"alakazam" "synchronize" "machamp" "guts" "golem" "rock-head" "gengar" "cursed-body"
   "gigalith" "sturdy" "conkeldurr" "guts" "trevenant" "natural-cure" "gourgeist" "pickup"
   "escavalier" "swarm" "accelgor" "hydration" "aromatisse" "healer" "slurpuff" "sweet-veil"
   "steelix" "rock-head" "scizor" "swarm" "kingdra" "swift-swim" "politoed" "water-absorb"
   "slowking" "oblivious" "porygon2" "trace" "huntail" "swift-swim" "gorebyss" "swift-swim"
   "dusknoir" "pressure" "rhyperior" "lightning-rod" "magmortar" "flame-body" "electivire" "motor-drive"})

(defn- slug [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn- item-compatible? [required equipped]
  (or (nil? required)
      (= required (slug equipped))
      (= required (get item->pokeapi (slug equipped)))))

(defn- matching-rule [registro parceiro-registro]
  (let [source (slug (or (:species registro) (:nome registro)))
        partner (slug (or (:species parceiro-registro) (:nome parceiro-registro)))
        equipped (slug (:item registro))]
    (some (fn [{:keys [target item] :as rule}]
            (when (and (item-compatible? item equipped)
                       (or (nil? (:partner rule)) (= (:partner rule) partner)))
              {:target target :consumed-item (when item equipped)}))
          (get rules source))))

(defn prepare
  "Retorna dados para `evolution/aplicar-evolucao` ou nil.
  O parceiro é validado antes da transação e nenhum I/O é realizado aqui."
  [registro parceiro]
  (when-let [{:keys [target consumed-item]} (matching-rule registro parceiro)]
    (let [[hp atk def spat spdef speed] (get base-stats target)
          level (long (:nivel registro 1))
          factor (Math/pow xp/fator-crescimento-por-nivel (dec level))
          scale #(long (Math/round (* (double %) factor)))]
      (let [dex-id (get dex-ids target)
            artwork (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" dex-id ".png")
            shiny-artwork (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/shiny/" dex-id ".png")]
        {:dados {:nome-novo (str/capitalize (str/replace target #"-" " "))
               :imagem artwork
               :imagem-shiny shiny-artwork
               :tipos (get types target)
               :habilidade (get abilities target)
               :hp (scale hp) :ataque (scale atk) :defesa (scale def)
               :atq-esp (scale spat) :def-esp (scale spdef) :veloc (scale speed)}
       :target target
       :consumed-item consumed-item
       :catalog-version catalog-version}))))

(defn apply-prepared
  [registro prepared]
  (when prepared
    (cond-> (assoc (evolution/aplicar-evolucao registro (:dados prepared))
                   :species (:target prepared))
      (:consumed-item prepared) (dissoc :item))))
