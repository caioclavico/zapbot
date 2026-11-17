(ns pokemon.migration.profiles
  "Perfis esperados dos históricos conhecidos (critérios objetivos, sem sourceVersion)."
  (:require [clojure.string :as str]))

(def expected-profiles
  "Critérios para distinguir save atual (26) do histórico independente (119).
   Identidade NÃO usa sourceVersion V1/V2.
   Save atual real (M5.2): 26 + XP 29 → nível 4, SEM exigir Voltorb shiny.
   Histórico real: 119 + XP 127 → nível 10 + Voltorb shiny."
  [{:id :save-atual-26
    :label "Save atual (~26 Pokémon)"
    :pokemon-count 26
    :require-shiny-species #{}
    :require-xp-total 29
    :notes "Conta viva em treinador (+ loja). Critério composto count=26 ∧ xp-total=29; Voltorb shiny NÃO é obrigatório."}
   {:id :historico-independente-119
    :label "Histórico independente (~119 Pokémon)"
    :pokemon-count 119
    :require-shiny-species #{"Voltorb"}
    ;; Prefixo hist-voltorb-shiny só nas fixtures; saves reais usam UUID.
    :require-xp-total 127
    :notes "Snapshot legado. Voltorb shiny obrigatório. XP total 127. Id do Voltorb: fixture hist-voltorb-shiny-* ou UUID real."}])

(defn normalize-species
  [s]
  (when s
    (-> (str s)
        str/trim
        (str/replace #"\s+" " "))))
