(ns pokemon.domain.raid-gym-theme
  "Identidade temática do ginásio de raid pelo tipo primário do chefe.
  Não altera dificuldade (nível/liga vêm do cursor de cadência). Sem migration."
  (:require [clojure.string :as str]))

(def type-venues
  "18 tipos Pokémon → identidade visual/textual do ginásio de raid."
  {"normal"   {:id "normal"   :nome "⚪ Normal"   :emoji "⚪" :label-pt "Normal"
               :sky ["#1f2937" "#374151" "#111827"] :plat ["#d1d5db" "#6b7280"] :accent "#f3f4f6"}
   "fire"     {:id "fogo"     :nome "🔥 Fogo"     :emoji "🔥" :label-pt "Fogo"
               :sky ["#7f1d1d" "#9a3412" "#450a0a"] :plat ["#f97316" "#b45309"] :accent "#fdba74"}
   "water"    {:id "agua"     :nome "💧 Água"     :emoji "💧" :label-pt "Água"
               :sky ["#0c4a6e" "#075985" "#082f49"] :plat ["#38bdf8" "#0369a1"] :accent "#bae6fd"}
   "grass"    {:id "planta"   :nome "🍃 Planta"   :emoji "🍃" :label-pt "Planta"
               :sky ["#14532d" "#166534" "#052e16"] :plat ["#4ade80" "#15803d"] :accent "#bbf7d0"}
   "electric" {:id "eletrico" :nome "⚡ Elétrico" :emoji "⚡" :label-pt "Elétrico"
               :sky ["#422006" "#713f12" "#1c1917"] :plat ["#facc15" "#a16207"] :accent "#fef08a"}
   "ice"      {:id "gelo"     :nome "❄️ Gelo"     :emoji "❄️" :label-pt "Gelo"
               :sky ["#0e7490" "#155e75" "#083344"] :plat ["#a5f3fc" "#0891b2"] :accent "#ecfeff"}
   "fighting" {:id "lutador"  :nome "🥊 Lutador"  :emoji "🥊" :label-pt "Lutador"
               :sky ["#7c2d12" "#9a3412" "#431407"] :plat ["#fb923c" "#c2410c"] :accent "#fed7aa"}
   "poison"   {:id "veneno"   :nome "☠️ Veneno"   :emoji "☠️" :label-pt "Veneno"
               :sky ["#4c1d95" "#5b21b6" "#2e1065"] :plat ["#c084fc" "#7e22ce"] :accent "#e9d5ff"}
   "ground"   {:id "terra"    :nome "🏜️ Terra"    :emoji "🏜️" :label-pt "Terra"
               :sky ["#78350f" "#92400e" "#451a03"] :plat ["#d97706" "#b45309"] :accent "#fde68a"}
   "flying"   {:id "voador"   :nome "🌪️ Voador"   :emoji "🌪️" :label-pt "Voador"
               :sky ["#1e3a8a" "#1d4ed8" "#0f172a"] :plat ["#93c5fd" "#2563eb"] :accent "#dbeafe"}
   "psychic"  {:id "psiquico" :nome "🔮 Psíquico" :emoji "🔮" :label-pt "Psíquico"
               :sky ["#831843" "#9d174d" "#500724"] :plat ["#f472b6" "#db2777"] :accent "#fbcfe8"}
   "bug"      {:id "inseto"   :nome "🐛 Inseto"   :emoji "🐛" :label-pt "Inseto"
               :sky ["#365314" "#3f6212" "#1a2e05"] :plat ["#a3e635" "#65a30d"] :accent "#ecfccb"}
   "rock"     {:id "pedra"    :nome "🪨 Pedra"    :emoji "🪨" :label-pt "Pedra"
               :sky ["#44403c" "#57534e" "#1c1917"] :plat ["#a8a29e" "#78716c"] :accent "#e7e5e4"}
   "ghost"    {:id "fantasma" :nome "👻 Fantasma" :emoji "👻" :label-pt "Fantasma"
               :sky ["#2e1065" "#4c1d95" "#0f0724"] :plat ["#a78bfa" "#6d28d9"] :accent "#ddd6fe"}
   "dragon"   {:id "dragao"   :nome "🐉 Dragão"   :emoji "🐉" :label-pt "Dragão"
               :sky ["#1e1b4b" "#312e81" "#0b1026"] :plat ["#818cf8" "#4338ca"] :accent "#c7d2fe"}
   "dark"     {:id "sombrio"  :nome "🌑 Sombrio"  :emoji "🌑" :label-pt "Sombrio"
               :sky ["#0a0a0a" "#171717" "#000000"] :plat ["#525252" "#262626"] :accent "#a3a3a3"}
   "steel"    {:id "aco"      :nome "⚙️ Aço"      :emoji "⚙️" :label-pt "Aço"
               :sky ["#334155" "#475569" "#0f172a"] :plat ["#94a3b8" "#64748b"] :accent "#e2e8f0"}
   "fairy"    {:id "fada"     :nome "🧚 Fada"     :emoji "🧚" :label-pt "Fada"
               :sky ["#9d174d" "#be185d" "#500724"] :plat ["#f9a8d4" "#db2777"] :accent "#fce7f3"}})

(def ^:private fallback
  {:id "misterio" :nome "🏛️ Ginásio" :emoji "🏛️" :label-pt "Misterioso"
   :sky ["#0f0a2a" "#1e1b4b" "#020617"] :plat ["#fbbf24" "#b45309"] :accent "#fde68a"})

(defn normalize-type
  [t]
  (when t
    (-> (str t) str/lower-case str/trim
        (str/replace #"^:+" ""))))

(defn primary-type
  "Tipo primário do catálogo (primeiro da lista). Aceita keywords/strings."
  [tipos]
  (when (seq tipos)
    (normalize-type (first tipos))))

(defn venue-for-type
  [tipo]
  (get type-venues (normalize-type tipo) fallback))

(defn venue-for-boss
  "Identidade do ginásio a partir dos tipos reais do chefe."
  [chefe-or-tipos]
  (let [tipos (cond
                (map? chefe-or-tipos)
                (or (:tipos chefe-or-tipos) (get chefe-or-tipos "tipos") [])
                (sequential? chefe-or-tipos) chefe-or-tipos
                :else [])
        primary (primary-type tipos)]
    (assoc (venue-for-type primary)
           :tipo primary
           :tipos (mapv normalize-type tipos))))

(defn apply-boss-venue
  "Sobrescreve nome/id de exibição do ginásio com o tema do tipo do chefe.
  Preserva nivel-ginasio / liga (dificuldade) já definidos."
  [spawn-fields chefe]
  (let [v (venue-for-boss chefe)]
    (cond-> spawn-fields
      true (assoc :nome-ginasio (:nome v)
                  :ginasio-tema (:id v)
                  :boss-tipo (:tipo v))
      ;; Mantém :ginasio interno do cursor (nível); nome visível muda.
      (:nome v) identity)))
