(ns pokemon.domain.profile
  "Regras puras do perfil do treinador e do professor Pokémon.
   O catálogo permanece versionado no código para não depender de rede."
  (:require [clojure.string :as str]
            [pokemon.domain.xp :as xp]))

(def title-catalog
  "Títulos = conquistas da master; ids estáveis no Datomic, labels = nomes master."
  (mapv (fn [{:keys [id nome metric minimo]}]
          {:id id
           :label nome
           :metric (case metric
                     :doacoes :doacoes
                     :vitorias :vitorias-treinador
                     :capturas :maior-sequencia-capturas)
           :threshold minimo})
        xp/catalogo-insignias))

(defn available-titles [save]
  (let [metrics (merge {:doacoes 0} save)]
    (->> title-catalog
         (filter #(>= (long (get metrics (:metric %) 0)) (:threshold %)))
         vec)))

(defn title-at [save index]
  (let [titles (available-titles save)]
    (when (and (integer? index) (<= 0 index) (< index (count titles)))
      (nth titles index))))

(defn friendship-band [value]
  (let [v (long (or value 70))]
    {:value v
     :label (cond (>= v 220) "melhor amigo"
                  (>= v 160) "grande amigo"
                  (>= v 100) "amigo"
                  :else "ainda se conhecendo")}))

;; Cadeias conhecidas → espécie-base da família (Doces Pokémon / cartões legado).
;; Espécies fora do mapa continuam família = species (compat A6).
(def ^:private family-roots
  {"pidgey" "pidgey" "pidgeotto" "pidgey" "pidgeot" "pidgey"
   "charmander" "charmander" "charmeleon" "charmander" "charizard" "charmander"
   "squirtle" "squirtle" "wartortle" "squirtle" "blastoise" "squirtle"
   "bulbasaur" "bulbasaur" "ivysaur" "bulbasaur" "venusaur" "bulbasaur"
   "eevee" "eevee" "vaporeon" "eevee" "jolteon" "eevee" "flareon" "eevee"
   "espeon" "eevee" "umbreon" "eevee" "leafeon" "eevee" "glaceon" "eevee"
   "sylveon" "eevee"
   "dratini" "dratini" "dragonair" "dratini" "dragonite" "dratini"
   "magikarp" "magikarp" "gyarados" "magikarp"
   "bagon" "bagon" "shelgon" "bagon" "salamence" "bagon"
   "ralts" "ralts" "kirlia" "ralts" "gardevoir" "ralts" "gallade" "ralts"
   "gible" "gible" "gabite" "gible" "garchomp" "gible"
   "gastly" "gastly" "haunter" "gastly" "gengar" "gastly"
   "machop" "machop" "machoke" "machop" "machamp" "machop"
   "abra" "abra" "kadabra" "abra" "alakazam" "abra"
   "geodude" "geodude" "graveler" "geodude" "golem" "geodude"
   "rattata" "rattata" "raticate" "rattata"
   "pikachu" "pikachu" "raichu" "pikachu" "pichu" "pikachu"
   "oddish" "oddish" "gloom" "oddish" "vileplume" "oddish" "bellossom" "oddish"
   "poliwag" "poliwag" "poliwhirl" "poliwag" "poliwrath" "poliwag" "politoed" "poliwag"
   "horsea" "horsea" "seadra" "horsea" "kingdra" "horsea"
   "slowpoke" "slowpoke" "slowbro" "slowpoke" "slowking" "slowpoke"
   "scyther" "scyther" "scizor" "scyther"
   "electabuzz" "elekid" "elekid" "elekid" "electivire" "elekid"
   "magmar" "magby" "magby" "magby" "magmortar" "magby"
   "larvitar" "larvitar" "pupitar" "larvitar" "tyranitar" "larvitar"
   "swablu" "swablu" "altaria" "swablu"
   "spheal" "spheal" "sealeo" "spheal" "walrein" "spheal"
   "aron" "aron" "lairon" "aron" "aggron" "aron"
   "trapinch" "trapinch" "vibrava" "trapinch" "flygon" "trapinch"
   "beldum" "beldum" "metang" "beldum" "metagross" "beldum"
   "axew" "axew" "fraxure" "axew" "haxorus" "axew"
   "deino" "deino" "zweilous" "deino" "hydreigon" "deino"
   "goomy" "goomy" "sliggoo" "goomy" "goodra" "goomy"
   "jangmo-o" "jangmo-o" "hakamo-o" "jangmo-o" "kommo-o" "jangmo-o"
   "dreepy" "dreepy" "drakloak" "dreepy" "dragapult" "dreepy"
   "frigibax" "frigibax" "arctibax" "frigibax" "baxcalibur" "frigibax"
   "applin" "applin" "flapple" "applin" "appletun" "applin" "dipplin" "applin"
   "hydrapple" "applin"
   "noibat" "noibat" "noivern" "noibat"
   "litwick" "litwick" "lampent" "litwick" "chandelure" "litwick"
   "zorua" "zorua" "zoroark" "zorua"
   "pawniard" "pawniard" "bisharp" "pawniard" "kingambit" "pawniard"
   "kabuto" "kabuto" "kabutops" "kabuto"
   "omanyte" "omanyte" "omastar" "omanyte"
   "lileep" "lileep" "cradily" "lileep"
   "anorith" "anorith" "armaldo" "anorith"
   "cranidos" "cranidos" "rampardos" "cranidos"
   "shieldon" "shieldon" "bastiodon" "shieldon"
   "tirtouga" "tirtouga" "carracosta" "tirtouga"
   "archen" "archen" "archeops" "archen"
   "tyrunt" "tyrunt" "tyrantrum" "tyrunt"
   "amaura" "amaura" "aurorus" "amaura"})

(defn family
  "Espécie-base da família evolutiva (chave estável dos Doces/cartões)."
  [species]
  (let [s (-> (str species) str/lower-case str/trim)]
    (get family-roots s s)))

(defn candy-label
  "Nome apresentado: Doce Charmander (não 'cartão de família')."
  [family-or-species]
  (let [fam (family family-or-species)
        pretty (->> (str/split fam #"-")
                    (map str/capitalize)
                    (str/join "-"))]
    (str "Doce " pretty)))

(defn candy-label-plural
  [family-or-species qty]
  (let [n (long (or qty 0))
        base (candy-label family-or-species)]
    (if (= 1 n) base (str/replace base #"^Doce " "Doces "))))
