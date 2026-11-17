(ns pokemon.domain.level-evolution
  "Catálogo versionado de evoluções por nível (gatilho level-up simples).

  Espelha a intenção de `proxima-evolucao` do legado sem I/O/PokeAPI:
  somente cadeias lineares e não-ramificadas. Evoluções por pedra, troca,
  amizade pura, chuva, golpe conhecido ou escolha ramificada ficam de fora
  (não inventamos destino).

  Stats-base e dex-ids alinhados ao catálogo PokeAPI usado pelo legado."
  (:require [clojure.string :as str]
            [pokemon.domain.evolution :as evolution]
            [pokemon.domain.species-hp :as species-hp]
            [pokemon.domain.xp :as xp]))

(def catalog-version "pokeapi-level-evolutions-2026-11")

(defn- slug [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn- display-name [target]
  (-> target
      (str/replace #"-" " ")
      str/capitalize))

;; source → {:target :nivel-min :amizade-min? :hp :ataque :defesa :atq-esp :def-esp :veloc :tipos :habilidade :dex-id}
(def ^:private rules
  {;; Kanto starters
   "bulbasaur"  {:target "ivysaur"    :nivel-min 16 :hp 60 :ataque 62 :defesa 63 :atq-esp 80 :def-esp 80 :veloc 60 :tipos ["grass" "poison"] :habilidade "overgrow" :dex-id 2}
   "ivysaur"    {:target "venusaur"   :nivel-min 32 :hp 80 :ataque 82 :defesa 83 :atq-esp 100 :def-esp 100 :veloc 80 :tipos ["grass" "poison"] :habilidade "overgrow" :dex-id 3}
   "charmander" {:target "charmeleon" :nivel-min 16 :hp 58 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80 :tipos ["fire"] :habilidade "blaze" :dex-id 5}
   "charmeleon" {:target "charizard"  :nivel-min 36 :hp 78 :ataque 84 :defesa 78 :atq-esp 109 :def-esp 85 :veloc 100 :tipos ["fire" "flying"] :habilidade "blaze" :dex-id 6}
   "squirtle"   {:target "wartortle"  :nivel-min 16 :hp 59 :ataque 63 :defesa 80 :atq-esp 65 :def-esp 80 :veloc 58 :tipos ["water"] :habilidade "torrent" :dex-id 8}
   "wartortle"  {:target "blastoise"  :nivel-min 36 :hp 79 :ataque 83 :defesa 100 :atq-esp 85 :def-esp 105 :veloc 78 :tipos ["water"] :habilidade "torrent" :dex-id 9}
   ;; Comuns Gen1
   "caterpie"   {:target "metapod"    :nivel-min 7  :hp 50 :ataque 20 :defesa 55 :atq-esp 25 :def-esp 25 :veloc 30 :tipos ["bug"] :habilidade "shed-skin" :dex-id 11}
   "metapod"    {:target "butterfree" :nivel-min 10 :hp 60 :ataque 45 :defesa 50 :atq-esp 90 :def-esp 80 :veloc 70 :tipos ["bug" "flying"] :habilidade "compound-eyes" :dex-id 12}
   "weedle"     {:target "kakuna"     :nivel-min 7  :hp 45 :ataque 25 :defesa 50 :atq-esp 25 :def-esp 25 :veloc 35 :tipos ["bug" "poison"] :habilidade "shed-skin" :dex-id 14}
   "kakuna"     {:target "beedrill"   :nivel-min 10 :hp 65 :ataque 90 :defesa 40 :atq-esp 45 :def-esp 80 :veloc 75 :tipos ["bug" "poison"] :habilidade "swarm" :dex-id 15}
   "pidgey"     {:target "pidgeotto"  :nivel-min 18 :hp 63 :ataque 60 :defesa 55 :atq-esp 50 :def-esp 50 :veloc 71 :tipos ["normal" "flying"] :habilidade "keen-eye" :dex-id 17}
   "pidgeotto"  {:target "pidgeot"    :nivel-min 36 :hp 83 :ataque 80 :defesa 75 :atq-esp 70 :def-esp 70 :veloc 101 :tipos ["normal" "flying"] :habilidade "keen-eye" :dex-id 18}
   "rattata"    {:target "raticate"   :nivel-min 20 :hp 55 :ataque 81 :defesa 60 :atq-esp 50 :def-esp 70 :veloc 97 :tipos ["normal"] :habilidade "run-away" :dex-id 20}
   "spearow"    {:target "fearow"     :nivel-min 20 :hp 65 :ataque 90 :defesa 65 :atq-esp 61 :def-esp 61 :veloc 100 :tipos ["normal" "flying"] :habilidade "keen-eye" :dex-id 22}
   "ekans"      {:target "arbok"      :nivel-min 22 :hp 60 :ataque 95 :defesa 69 :atq-esp 65 :def-esp 79 :veloc 80 :tipos ["poison"] :habilidade "intimidate" :dex-id 24}
   "nidoran-f"  {:target "nidorina"   :nivel-min 16 :hp 70 :ataque 62 :defesa 67 :atq-esp 55 :def-esp 55 :veloc 56 :tipos ["poison"] :habilidade "poison-point" :dex-id 30}
   "nidoran-m"  {:target "nidorino"   :nivel-min 16 :hp 61 :ataque 72 :defesa 57 :atq-esp 55 :def-esp 55 :veloc 65 :tipos ["poison"] :habilidade "poison-point" :dex-id 33}
   "zubat"      {:target "golbat"     :nivel-min 22 :hp 75 :ataque 80 :defesa 70 :atq-esp 65 :def-esp 75 :veloc 90 :tipos ["poison" "flying"] :habilidade "inner-focus" :dex-id 42}
   "oddish"     {:target "gloom"      :nivel-min 21 :hp 60 :ataque 65 :defesa 70 :atq-esp 85 :def-esp 75 :veloc 40 :tipos ["grass" "poison"] :habilidade "chlorophyll" :dex-id 44}
   "paras"      {:target "parasect"   :nivel-min 24 :hp 60 :ataque 95 :defesa 80 :atq-esp 60 :def-esp 80 :veloc 30 :tipos ["bug" "grass"] :habilidade "effect-spore" :dex-id 47}
   "venonat"    {:target "venomoth"   :nivel-min 31 :hp 70 :ataque 65 :defesa 60 :atq-esp 90 :def-esp 75 :veloc 90 :tipos ["bug" "poison"] :habilidade "shield-dust" :dex-id 49}
   "diglett"    {:target "dugtrio"    :nivel-min 26 :hp 35 :ataque 100 :defesa 50 :atq-esp 50 :def-esp 70 :veloc 120 :tipos ["ground"] :habilidade "sand-veil" :dex-id 51}
   "meowth"     {:target "persian"    :nivel-min 28 :hp 65 :ataque 70 :defesa 60 :atq-esp 65 :def-esp 65 :veloc 115 :tipos ["normal"] :habilidade "limber" :dex-id 53}
   "psyduck"    {:target "golduck"    :nivel-min 33 :hp 80 :ataque 82 :defesa 78 :atq-esp 95 :def-esp 80 :veloc 85 :tipos ["water"] :habilidade "damp" :dex-id 55}
   "mankey"     {:target "primeape"   :nivel-min 28 :hp 65 :ataque 105 :defesa 60 :atq-esp 60 :def-esp 70 :veloc 95 :tipos ["fighting"] :habilidade "vital-spirit" :dex-id 57}
   "poliwag"    {:target "poliwhirl"  :nivel-min 25 :hp 65 :ataque 65 :defesa 65 :atq-esp 50 :def-esp 50 :veloc 90 :tipos ["water"] :habilidade "water-absorb" :dex-id 61}
   "abra"       {:target "kadabra"    :nivel-min 16 :hp 40 :ataque 35 :defesa 30 :atq-esp 120 :def-esp 70 :veloc 105 :tipos ["psychic"] :habilidade "synchronize" :dex-id 64}
   "machop"     {:target "machoke"    :nivel-min 28 :hp 80 :ataque 100 :defesa 70 :atq-esp 50 :def-esp 60 :veloc 45 :tipos ["fighting"] :habilidade "guts" :dex-id 67}
   "bellsprout" {:target "weepinbell" :nivel-min 21 :hp 65 :ataque 90 :defesa 50 :atq-esp 85 :def-esp 45 :veloc 55 :tipos ["grass" "poison"] :habilidade "chlorophyll" :dex-id 70}
   "tentacool"  {:target "tentacruel" :nivel-min 30 :hp 80 :ataque 70 :defesa 65 :atq-esp 80 :def-esp 120 :veloc 100 :tipos ["water" "poison"] :habilidade "clear-body" :dex-id 73}
   "geodude"    {:target "graveler"   :nivel-min 25 :hp 55 :ataque 95 :defesa 115 :atq-esp 45 :def-esp 45 :veloc 35 :tipos ["rock" "ground"] :habilidade "rock-head" :dex-id 75}
   "ponyta"     {:target "rapidash"   :nivel-min 40 :hp 65 :ataque 100 :defesa 70 :atq-esp 80 :def-esp 80 :veloc 105 :tipos ["fire"] :habilidade "run-away" :dex-id 78}
   "slowpoke"   {:target "slowbro"    :nivel-min 37 :hp 95 :ataque 75 :defesa 110 :atq-esp 100 :def-esp 80 :veloc 30 :tipos ["water" "psychic"] :habilidade "oblivious" :dex-id 80}
   "magnemite"  {:target "magneton"   :nivel-min 30 :hp 50 :ataque 60 :defesa 95 :atq-esp 120 :def-esp 70 :veloc 70 :tipos ["electric" "steel"] :habilidade "magnet-pull" :dex-id 82}
   "doduo"      {:target "dodrio"     :nivel-min 31 :hp 60 :ataque 110 :defesa 70 :atq-esp 60 :def-esp 60 :veloc 110 :tipos ["normal" "flying"] :habilidade "run-away" :dex-id 85}
   "seel"       {:target "dewgong"    :nivel-min 34 :hp 90 :ataque 70 :defesa 80 :atq-esp 70 :def-esp 95 :veloc 70 :tipos ["water" "ice"] :habilidade "thick-fat" :dex-id 87}
   "grimer"     {:target "muk"        :nivel-min 38 :hp 105 :ataque 105 :defesa 75 :atq-esp 65 :def-esp 100 :veloc 50 :tipos ["poison"] :habilidade "stench" :dex-id 89}
   "gastly"     {:target "haunter"    :nivel-min 25 :hp 45 :ataque 50 :defesa 45 :atq-esp 115 :def-esp 55 :veloc 95 :tipos ["ghost" "poison"] :habilidade "levitate" :dex-id 93}
   "drowzee"    {:target "hypno"      :nivel-min 26 :hp 85 :ataque 73 :defesa 70 :atq-esp 73 :def-esp 115 :veloc 67 :tipos ["psychic"] :habilidade "insomnia" :dex-id 97}
   "krabby"     {:target "kingler"    :nivel-min 28 :hp 55 :ataque 130 :defesa 115 :atq-esp 50 :def-esp 50 :veloc 75 :tipos ["water"] :habilidade "hyper-cutter" :dex-id 99}
   "voltorb"    {:target "electrode"  :nivel-min 30 :hp 60 :ataque 50 :defesa 70 :atq-esp 80 :def-esp 80 :veloc 150 :tipos ["electric"] :habilidade "soundproof" :dex-id 101}
   "cubone"     {:target "marowak"    :nivel-min 28 :hp 60 :ataque 80 :defesa 110 :atq-esp 50 :def-esp 80 :veloc 45 :tipos ["ground"] :habilidade "rock-head" :dex-id 105}
   "koffing"    {:target "weezing"    :nivel-min 35 :hp 65 :ataque 90 :defesa 120 :atq-esp 85 :def-esp 70 :veloc 60 :tipos ["poison"] :habilidade "levitate" :dex-id 110}
   "rhyhorn"    {:target "rhydon"     :nivel-min 42 :hp 105 :ataque 130 :defesa 120 :atq-esp 45 :def-esp 45 :veloc 40 :tipos ["ground" "rock"] :habilidade "lightning-rod" :dex-id 112}
   "horsea"     {:target "seadra"     :nivel-min 32 :hp 55 :ataque 65 :defesa 95 :atq-esp 95 :def-esp 45 :veloc 85 :tipos ["water"] :habilidade "swift-swim" :dex-id 117}
   "goldeen"    {:target "seaking"    :nivel-min 33 :hp 80 :ataque 92 :defesa 65 :atq-esp 65 :def-esp 80 :veloc 68 :tipos ["water"] :habilidade "swift-swim" :dex-id 119}
   "magikarp"   {:target "gyarados"   :nivel-min 20 :hp 95 :ataque 125 :defesa 79 :atq-esp 60 :def-esp 100 :veloc 81 :tipos ["water" "flying"] :habilidade "intimidate" :dex-id 130}
   "omanyte"    {:target "omastar"    :nivel-min 40 :hp 70 :ataque 60 :defesa 125 :atq-esp 115 :def-esp 70 :veloc 55 :tipos ["rock" "water"] :habilidade "swift-swim" :dex-id 139}
   "kabuto"     {:target "kabutops"   :nivel-min 40 :hp 60 :ataque 115 :defesa 105 :atq-esp 65 :def-esp 70 :veloc 80 :tipos ["rock" "water"] :habilidade "swift-swim" :dex-id 141}
   "dratini"    {:target "dragonair"  :nivel-min 30 :hp 61 :ataque 84 :defesa 65 :atq-esp 70 :def-esp 70 :veloc 70 :tipos ["dragon"] :habilidade "shed-skin" :dex-id 148}
   "dragonair"  {:target "dragonite"  :nivel-min 55 :hp 91 :ataque 134 :defesa 95 :atq-esp 100 :def-esp 100 :veloc 80 :tipos ["dragon" "flying"] :habilidade "inner-focus" :dex-id 149}
   ;; Gen2 lineares comuns
   "chikorita"  {:target "bayleef"    :nivel-min 16 :hp 60 :ataque 62 :defesa 80 :atq-esp 63 :def-esp 80 :veloc 60 :tipos ["grass"] :habilidade "overgrow" :dex-id 153}
   "bayleef"    {:target "meganium"   :nivel-min 32 :hp 80 :ataque 82 :defesa 100 :atq-esp 83 :def-esp 100 :veloc 80 :tipos ["grass"] :habilidade "overgrow" :dex-id 154}
   "cyndaquil"  {:target "quilava"    :nivel-min 14 :hp 58 :ataque 64 :defesa 58 :atq-esp 80 :def-esp 65 :veloc 80 :tipos ["fire"] :habilidade "blaze" :dex-id 156}
   "quilava"    {:target "typhlosion" :nivel-min 36 :hp 78 :ataque 84 :defesa 78 :atq-esp 109 :def-esp 85 :veloc 100 :tipos ["fire"] :habilidade "blaze" :dex-id 157}
   "totodile"   {:target "croconaw"   :nivel-min 18 :hp 65 :ataque 80 :defesa 80 :atq-esp 59 :def-esp 63 :veloc 58 :tipos ["water"] :habilidade "torrent" :dex-id 159}
   "croconaw"   {:target "feraligatr" :nivel-min 30 :hp 85 :ataque 105 :defesa 100 :atq-esp 79 :def-esp 83 :veloc 78 :tipos ["water"] :habilidade "torrent" :dex-id 160}
   "mareep"     {:target "flaaffy"    :nivel-min 15 :hp 70 :ataque 55 :defesa 55 :atq-esp 80 :def-esp 60 :veloc 45 :tipos ["electric"] :habilidade "static" :dex-id 180}
   "flaaffy"    {:target "ampharos"   :nivel-min 30 :hp 90 :ataque 75 :defesa 85 :atq-esp 115 :def-esp 90 :veloc 55 :tipos ["electric"] :habilidade "static" :dex-id 181}
   "larvitar"   {:target "pupitar"    :nivel-min 30 :hp 70 :ataque 84 :defesa 70 :atq-esp 65 :def-esp 70 :veloc 51 :tipos ["rock" "ground"] :habilidade "guts" :dex-id 247}
   "pupitar"    {:target "tyranitar"  :nivel-min 55 :hp 100 :ataque 134 :defesa 110 :atq-esp 95 :def-esp 100 :veloc 61 :tipos ["rock" "dark"] :habilidade "sand-stream" :dex-id 248}
   ;; Gen3
   "treecko"    {:target "grovyle"    :nivel-min 16 :hp 50 :ataque 65 :defesa 45 :atq-esp 85 :def-esp 65 :veloc 95 :tipos ["grass"] :habilidade "overgrow" :dex-id 253}
   "grovyle"    {:target "sceptile"   :nivel-min 36 :hp 70 :ataque 85 :defesa 65 :atq-esp 105 :def-esp 85 :veloc 120 :tipos ["grass"] :habilidade "overgrow" :dex-id 254}
   "torchic"    {:target "combusken"  :nivel-min 16 :hp 60 :ataque 85 :defesa 60 :atq-esp 85 :def-esp 60 :veloc 55 :tipos ["fire" "fighting"] :habilidade "blaze" :dex-id 256}
   "combusken"  {:target "blaziken"   :nivel-min 36 :hp 80 :ataque 120 :defesa 70 :atq-esp 110 :def-esp 70 :veloc 80 :tipos ["fire" "fighting"] :habilidade "blaze" :dex-id 257}
   "mudkip"     {:target "marshtomp"  :nivel-min 16 :hp 70 :ataque 85 :defesa 70 :atq-esp 60 :def-esp 70 :veloc 50 :tipos ["water" "ground"] :habilidade "torrent" :dex-id 259}
   "marshtomp"  {:target "swampert"   :nivel-min 36 :hp 100 :ataque 110 :defesa 90 :atq-esp 85 :def-esp 90 :veloc 60 :tipos ["water" "ground"] :habilidade "torrent" :dex-id 260}
   "ralts"      {:target "kirlia"     :nivel-min 20 :hp 38 :ataque 35 :defesa 35 :atq-esp 65 :def-esp 55 :veloc 50 :tipos ["psychic" "fairy"] :habilidade "synchronize" :dex-id 281}
   "kirlia"     {:target "gardevoir"  :nivel-min 30 :hp 68 :ataque 65 :defesa 65 :atq-esp 125 :def-esp 115 :veloc 80 :tipos ["psychic" "fairy"] :habilidade "synchronize" :dex-id 282}
   "bagon"      {:target "shelgon"    :nivel-min 30 :hp 65 :ataque 95 :defesa 100 :atq-esp 60 :def-esp 50 :veloc 50 :tipos ["dragon"] :habilidade "rock-head" :dex-id 372}
   "shelgon"    {:target "salamence"  :nivel-min 50 :hp 95 :ataque 135 :defesa 80 :atq-esp 110 :def-esp 80 :veloc 100 :tipos ["dragon" "flying"] :habilidade "intimidate" :dex-id 373}
   "beldum"     {:target "metang"     :nivel-min 20 :hp 60 :ataque 75 :defesa 100 :atq-esp 55 :def-esp 80 :veloc 50 :tipos ["steel" "psychic"] :habilidade "clear-body" :dex-id 375}
   "metang"     {:target "metagross"  :nivel-min 45 :hp 80 :ataque 135 :defesa 130 :atq-esp 95 :def-esp 90 :veloc 70 :tipos ["steel" "psychic"] :habilidade "clear-body" :dex-id 376}
   "gible"      {:target "gabite"     :nivel-min 24 :hp 68 :ataque 90 :defesa 65 :atq-esp 50 :def-esp 55 :veloc 82 :tipos ["dragon" "ground"] :habilidade "sand-veil" :dex-id 444}
   "gabite"     {:target "garchomp"   :nivel-min 48 :hp 108 :ataque 130 :defesa 95 :atq-esp 80 :def-esp 85 :veloc 102 :tipos ["dragon" "ground"] :habilidade "sand-veil" :dex-id 445}})

(defn- rule-for [registro]
  (let [source (slug (or (:species registro) (:nome registro)))]
    (get rules source)))

(defn proxima
  "Próxima evolução por nível conhecida (somente leitura). Nil se ausente/especial/ramificada."
  [registro]
  (when-let [{:keys [target nivel-min amizade-min]} (rule-for registro)]
    {:species target
     :nome (display-name target)
     :nivel-min (long (or nivel-min 1))
     :amizade-min (long (or amizade-min 0))
     :catalog-version catalog-version}))

(defn elegivel?
  "True quando o registro já atingiu nível (e amizade, se exigida) da próxima evo por nível."
  [registro]
  (when-let [info (proxima registro)]
    (evolution/elegivel-nivel? registro {:nivel-min (:nivel-min info)
                                         :amizade-min (:amizade-min info)})))

(defn prepare
  "Dados para `evolution/aplicar-evolucao` quando elegível; nil caso contrário.
  Sem I/O. Preserva identidade do registro (caller aplica no mesmo id-pokemon)."
  [registro]
  (when-let [rule (rule-for registro)]
    (when (evolution/elegivel-nivel? registro {:nivel-min (:nivel-min rule)
                                               :amizade-min (or (:amizade-min rule) 0)})
      (let [target (:target rule)
            level (long (:nivel registro 1))
            factor (Math/pow xp/fator-crescimento-por-nivel (dec level))
            scale #(long (Math/round (* (double %) factor)))
            ;; HP: fórmula species-hp-v1 (base oficial da regra × nível).
            ;; Ataque/defesa/etc. permanecem no escalonamento legado 1.03.
            hp-base (long (:hp rule))
            hp-novo (species-hp/hp-max hp-base level)
            dex-id (:dex-id rule)
            artwork (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/" dex-id ".png")
            shiny-artwork (str "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/shiny/" dex-id ".png")]
        {:dados {:nome-novo (display-name target)
                 :imagem artwork
                 :imagem-shiny shiny-artwork
                 :tipos (:tipos rule)
                 :habilidade (:habilidade rule)
                 :hp hp-novo
                 :hp-base hp-base
                 :hp-formula-version species-hp/formula-version
                 :politica-hp :proporcional
                 :ataque (scale (:ataque rule))
                 :defesa (scale (:defesa rule))
                 :atq-esp (scale (:atq-esp rule))
                 :def-esp (scale (:def-esp rule))
                 :veloc (scale (:veloc rule))}
         :target target
         :nivel-min (:nivel-min rule)
         :catalog-version catalog-version}))))
(defn apply-prepared
  "Aplica evolução preparada preservando id/dono/xp/nível/shiny/golpes via domain."
  [registro prepared]
  (when prepared
    (assoc (evolution/aplicar-evolucao registro (:dados prepared))
           :species (:target prepared))))
