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

(defn family [species]
  (let [s (-> (str species) str/lower-case)]
    (if (contains? #{"pidgey" "pidgeotto" "pidgeot"} s) "pidgey" s)))
