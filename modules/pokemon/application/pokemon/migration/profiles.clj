(ns pokemon.migration.profiles
  "Perfis esperados dos históricos conhecidos (critérios objetivos, sem sourceVersion)."
  (:require [clojure.string :as str]))

(def expected-profiles
  "Critérios para distinguir save atual (26) do histórico independente (119).
   Identidade NÃO usa sourceVersion V1/V2."
  [{:id :save-atual-26
    :label "Save atual (~26 Pokémon)"
    :pokemon-count 26
    :require-shiny-species #{"Voltorb"}
    :notes "Conta viva em treinador (+ loja). Voltorb shiny presente."}
   {:id :historico-independente-119
    :label "Histórico independente (~119 Pokémon)"
    :pokemon-count 119
    :require-shiny-species #{"Voltorb"}
    :require-shiny-id-prefix "hist-voltorb-shiny"
    :notes "Snapshot dorminhoco / envelope. Voltorb shiny com id estável hist-voltorb-shiny-*."}])

(defn normalize-species
  [s]
  (when s
    (-> (str s)
        str/trim
        (str/replace #"\s+" " "))))
