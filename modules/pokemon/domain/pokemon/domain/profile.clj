(ns pokemon.domain.profile
  "Regras puras do perfil do treinador e do professor Pokémon.
   O catálogo permanece versionado no código para não depender de rede."
  (:require [clojure.string :as str]))

(def title-catalog
  [{:id "doador-pokemon" :label "Doador Pokémon" :metric :doacoes :threshold 1}
   {:id "doador-generoso" :label "Doador Generoso" :metric :doacoes :threshold 10}
   {:id "benfeitor" :label "Benfeitor" :metric :doacoes :threshold 50}
   {:id "mestre-doador" :label "Mestre Doador" :metric :doacoes :threshold 100}
   {:id "primeira-vitoria" :label "Primeira vitória" :metric :vitorias-treinador :threshold 1}
   {:id "batalhador" :label "Batalhador" :metric :vitorias-treinador :threshold 10}
   {:id "veterano" :label "Veterano" :metric :vitorias-treinador :threshold 50}
   {:id "campeao" :label "Campeão" :metric :vitorias-treinador :threshold 100}
   {:id "capturador" :label "Capturador" :metric :maior-sequencia-capturas :threshold 3}
   {:id "cacador" :label "Caçador" :metric :maior-sequencia-capturas :threshold 5}
   {:id "especialista-captura" :label "Especialista" :metric :maior-sequencia-capturas :threshold 10}
   {:id "mestre-captura" :label "Mestre captura" :metric :maior-sequencia-capturas :threshold 20}])

(defn available-titles [save]
  (let [metrics (merge {:doacoes 0} save)]
    (->> title-catalog
         (filter #(>= (long (get metrics (:metric % ) 0)) (:threshold %)))
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

(defn family [species]
  (let [s (-> (str species) str/lower-case)]
    (if (contains? #{"pidgey" "pidgeotto" "pidgeot"} s) "pidgey" s)))
