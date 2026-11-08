(ns pokemon.domain.species-form-aliases
  "Aliases explícitos forma → espécie canônica do catálogo A6.

  Não remove sufixos por heurística. Cada entrada documenta a forma preservada,
  o alvo canônico e se o HP oficial da forma coincide com o da entrada de catálogo
  (ou exige override próprio).

  Fonte HP oficial: PokeAPI / Pokédex. Catálogo A6 usa stats suavizados; a resolução
  de HP passa por `species-base/base-hp` no slug canônico (ou override da forma)."
  (:require [clojure.string :as str]))

(defn- slug [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(def form-aliases
  "Mapa {form-slug → {:canonical :official-hp :hp-equals-canonical? :notes}}."
  {"wishiwashi-solo"
   {:canonical "wishiwashi"
    :official-hp 45
    :hp-equals-canonical? true
    :notes "Solo form; School também 45 HP — catálogo wishiwashi = Solo/default."}

   "pyroar-male"
   {:canonical "pyroar"
    :official-hp 86
    :hp-equals-canonical? true
    :notes "Male/Female compartilham HP base 86."}

   "pyroar-female"
   {:canonical "pyroar"
    :official-hp 86
    :hp-equals-canonical? true
    :notes "Mesmo HP que pyroar-male."}

   "gourgeist-average"
   {:canonical "gourgeist"
    :official-hp 65
    :hp-equals-canonical? true
    :notes "Average size; Small/Large/Super têm HP distintos (55/75/85) — aliases separados."}

   "gourgeist-small"
   {:canonical nil
    :official-hp 55
    :hp-equals-canonical? false
    :notes "HP distinto — override em species-base, sem alias ao Average."}

   "gourgeist-large"
   {:canonical nil
    :official-hp 75
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "gourgeist-super"
   {:canonical nil
    :official-hp 85
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "tatsugiri-curly"
   {:canonical "tatsugiri"
    :official-hp 68
    :hp-equals-canonical? true
    :notes "Curly/Droopy/Stretchy compartilham HP 68."}

   "tatsugiri-droopy"
   {:canonical "tatsugiri"
    :official-hp 68
    :hp-equals-canonical? true
    :notes "Mesmo HP que curly."}

   "tatsugiri-stretchy"
   {:canonical "tatsugiri"
    :official-hp 68
    :hp-equals-canonical? true
    :notes "Mesmo HP que curly."}

   "frillish-male"
   {:canonical "frillish"
    :official-hp 55
    :hp-equals-canonical? true
    :notes "Gênero cosmético para HP."}

   "frillish-female"
   {:canonical "frillish"
    :official-hp 55
    :hp-equals-canonical? true
    :notes "Gênero cosmético para HP."}

   "jellicent-male"
   {:canonical "jellicent"
    :official-hp 100
    :hp-equals-canonical? true
    :notes "Gênero cosmético para HP."}

   "jellicent-female"
   {:canonical "jellicent"
    :official-hp 100
    :hp-equals-canonical? true
    :notes "Gênero cosmético para HP."}

   "mimikyu-disguised"
   {:canonical "mimikyu"
    :official-hp 55
    :hp-equals-canonical? true
    :notes "Disguised/Busted compartilham HP 55."}

   "mimikyu-busted"
   {:canonical "mimikyu"
    :official-hp 55
    :hp-equals-canonical? true
    :notes "Mesmo HP que disguised."}

   "indeedee-male"
   {:canonical "indeedee"
    :official-hp 60
    :hp-equals-canonical? true
    :notes "Catálogo indeedee ≈ Male (HP 60). Female (70) NÃO aliasa — override próprio."}

   "indeedee-female"
   {:canonical nil
    :official-hp 70
    :hp-equals-canonical? false
    :notes "HP 70 distinto do Male/catálogo — override dedicado."}

   "enamorus-incarnate"
   {:canonical "enamorus"
    :official-hp 74
    :hp-equals-canonical? true
    :notes "Incarnate/Therian compartilham HP 74."}

   "enamorus-therian"
   {:canonical "enamorus"
    :official-hp 74
    :hp-equals-canonical? true
    :notes "Mesmo HP que Incarnate."}

   "lycanroc-midday"
   {:canonical "lycanroc"
    :official-hp 75
    :hp-equals-canonical? true
    :notes "Midday/Midnight/Dusk compartilham HP 75."}

   "lycanroc-midnight"
   {:canonical "lycanroc"
    :official-hp 75
    :hp-equals-canonical? true
    :notes "Mesmo HP que midday."}

   "lycanroc-dusk"
   {:canonical "lycanroc"
    :official-hp 75
    :hp-equals-canonical? true
    :notes "Mesmo HP que midday."}

   "zygarde-50"
   {:canonical "zygarde"
    :official-hp 108
    :hp-equals-canonical? true
    :notes "Catálogo zygarde = forma 50%. 10% (54) e Complete (216) exigem override."}

   "zygarde-10"
   {:canonical nil
    :official-hp 54
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "zygarde-complete"
   {:canonical nil
    :official-hp 216
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "pumpkaboo-average"
   {:canonical "pumpkaboo"
    :official-hp 49
    :hp-equals-canonical? true
    :notes "Average; Small/Large/Super (44/54/59) — overrides separados."}

   "pumpkaboo-small"
   {:canonical nil
    :official-hp 44
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "pumpkaboo-large"
   {:canonical nil
    :official-hp 54
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "pumpkaboo-super"
   {:canonical nil
    :official-hp 59
    :hp-equals-canonical? false
    :notes "HP distinto — override dedicado."}

   "darmanitan-standard"
   {:canonical "darmanitan"
    :official-hp 105
    :hp-equals-canonical? true
    :notes "Standard/Zen compartilham HP 105."}

   "darmanitan-zen"
   {:canonical "darmanitan"
    :official-hp 105
    :hp-equals-canonical? true
    :notes "Mesmo HP que standard."}

   "deoxys-normal"
   {:canonical "deoxys"
    :official-hp 50
    :hp-equals-canonical? true
    :notes "Todas as formes Deoxys têm HP 50."}

   "deoxys-attack"
   {:canonical "deoxys"
    :official-hp 50
    :hp-equals-canonical? true
    :notes "HP 50."}

   "deoxys-defense"
   {:canonical "deoxys"
    :official-hp 50
    :hp-equals-canonical? true
    :notes "HP 50."}

   "deoxys-speed"
   {:canonical "deoxys"
    :official-hp 50
    :hp-equals-canonical? true
    :notes "HP 50."}

   "meowstic-male"
   {:canonical "meowstic"
    :official-hp 74
    :hp-equals-canonical? true
    :notes "Male/Female compartilham HP 74."}

   "meowstic-female"
   {:canonical "meowstic"
    :official-hp 74
    :hp-equals-canonical? true
    :notes "Mesmo HP que male."}

   "minior-red-meteor"
   {:canonical "minior"
    :official-hp 60
    :hp-equals-canonical? true
    :notes "Meteor/Core color variants compartilham HP 60."}

   "minior-red-core"
   {:canonical "minior"
    :official-hp 60
    :hp-equals-canonical? true
    :notes "Mesmo HP que meteor."}})

(def form-hp-overrides
  "Overrides oficiais para formas com HP ≠ entrada canônica do catálogo."
  (->> form-aliases
       (keep (fn [[form {:keys [canonical official-hp hp-equals-canonical?]}]]
               (when (and (nil? canonical) (not hp-equals-canonical?) (number? official-hp))
                 [form (long official-hp)])))
       (into {})))

(defn lookup
  "Retorna entrada de alias para o slug (ou nil)."
  [species-or-form]
  (get form-aliases (slug species-or-form)))

(defn resolve-catalog-species
  "Slug a consultar no catálogo/overrides de espécie.
  Formas com override próprio devolvem o próprio slug (não o canônico)."
  [species-or-form]
  (let [s (slug species-or-form)
        info (get form-aliases s)]
    (cond
      (nil? info) s
      (and (:canonical info) (:hp-equals-canonical? info)) (:canonical info)
      :else s)))

(defn known-form?
  [species-or-form]
  (contains? form-aliases (slug species-or-form)))
