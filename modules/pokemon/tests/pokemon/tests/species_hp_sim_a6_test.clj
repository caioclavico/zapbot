(ns pokemon.tests.species-hp-sim-a6-test
  "Simulações species-hp-v1 vs fórmula legada — catálogo A6 completo."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.domain.species-base :as species-base]
            [pokemon.domain.species-hp :as species-hp]))

(def ^:private niveis [1 5 10 20 30 40 50])

(def ^:private especies-chave
  ["wartortle" "charizard" "snorlax" "blissey" "shuckle"
   "gengar" "dragonite" "chansey" "magikarp" "pikachu"
   "pidgey" "eevee" "gyarados" "mewtwo" "shedinja"])

(defn- linha
  [species nivel]
  (let [base (species-base/base-hp species)
        atual (species-hp/legacy-hp-max base nivel)
        novo (species-hp/hp-max base nivel)]
    {:especie species
     :base base
     :nivel nivel
     :hp-atual atual
     :hp-novo novo
     :variacao (- novo atual)
     :fator (species-hp/fator-especie base)}))

(deftest simulacao-especies-chave
  (doseq [sp especies-chave
          nv niveis]
    (let [r (linha sp nv)]
      (is (pos? (:hp-novo r)))
      (is (>= (:hp-novo r) species-hp/hp-minimo-valido))))
  (let [tabela (for [sp especies-chave nv niveis] (linha sp nv))]
    ;; Snapshot auditável: Chansey nv20 bem acima de Magikarp nv20
    (let [mag (first (filter #(and (= "magikarp" (:especie %)) (= 20 (:nivel %))) tabela))
          cha (first (filter #(and (= "chansey" (:especie %)) (= 20 (:nivel %))) tabela))]
      (is (< (:hp-novo mag) (:hp-novo cha)))
      (is (>= (:fator cha) 2.0)))))

(deftest catalogo-completo-sem-pokeapi
  (let [table (species-base/catalog-hp-table)
        n (count table)
        outliers (atom [])]
    (is (> n 900) "catálogo A6 completo esperado")
    (doseq [[sp {:keys [hp-base]}] table]
      (try
        (let [h10 (species-hp/hp-max hp-base 10)
              h50 (species-hp/hp-max hp-base 50)]
          (is (<= h10 h50))
          (when (or (< h10 1) (> h50 2000))
            (swap! outliers conj [sp h10 h50])))
        (catch Exception e
          (swap! outliers conj [sp :error (.getMessage e)]))))
    (is (empty? @outliers) (str "outliers: " (pr-str (take 20 @outliers))))))

(deftest tabela-markdown-amostra
  "Gera linhas da tabela pedida no GO (assertiva de presença)."
  (let [rows (for [sp ["wartortle" "charizard" "snorlax" "blissey" "shuckle"
                       "gengar" "dragonite" "chansey"]
                   nv [10 20 30]]
               (linha sp nv))]
    (is (= 24 (count rows)))
    (doseq [r rows]
      (is (number? (:variacao r))))))
