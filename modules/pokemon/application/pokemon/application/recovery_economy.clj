(ns pokemon.application.recovery-economy
  "Política econômica pura para recuperação A6.4.

  Não atribui zero a ausências. Não soma fontes. Não persiste."
  (:require [clojure.string :as str]
            [pokemon.domain.reward :as reward]))

(defn- g [m k]
  (when (map? m)
    (or (get m k) (get m (name k)) (get m (keyword (name k))))))

(defn- long* [v]
  (cond
    (nil? v) nil
    (number? v) (long v)
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    :else nil))

(defn loja-fingerprint
  "Fingerprint econômico (moedas + inventário). nil se entrada ausente/inválida."
  [loja]
  (when (map? loja)
    (let [inv (or (g loja :inventario) {})
          skus (when (map? inv)
                 (->> inv
                      (map (fn [[k v]] [(str k) (or (long* v) 0)]))
                      (filter #(pos? (second %)))
                      (sort-by first)
                      vec))
          coins (long* (g loja :moedas))
          body {:coins coins
                :bag (long* (g loja :capacidade-mochila))
                :skus (or skus [])
                :qty-sum (reduce + 0 (map second (or skus [])))}]
      (assoc body :fingerprint (reward/payload-hash body)))))

(defn classify-player-economy
  "sources: [{:source :cassandra-v1-loja :loja {...}} ...]
   Ausência total → :unknown (não :zero)."
  [appearances]
  (let [present (filterv #(and (map? (:loja %))
                               (some? (loja-fingerprint (:loja %))))
                         appearances)
        fps (mapv #(assoc % :fp (loja-fingerprint (:loja %))) present)
        digests (set (map #(get-in % [:fp :fingerprint]) fps))
        coins (mapv #(get-in % [:fp :coins]) fps)]
    (cond
      (empty? fps)
      {:status :unknown
       :sources []
       :note :no-loja-record
       :coins nil
       :do-not-default-zero true}

      (= 1 (count digests))
      {:status :known
       :sources (mapv :source fps)
       :fingerprint (:fingerprint (:fp (first fps)))
       :coins (first coins)
       :sku-count (count (:skus (:fp (first fps))))
       :do-not-default-zero false}

      :else
      {:status :divergent
       :sources (mapv :source fps)
       :by-source (into {}
                        (map (fn [x]
                               [(:source x)
                                {:coins (get-in x [:fp :coins])
                                 :sku-count (count (get-in x [:fp :skus]))
                                 :fingerprint (get-in x [:fp :fingerprint])}])
                             fps))
       :coins nil
       :do-not-default-zero true
       :note :do-not-sum-or-max})))

(def recovery-options
  "Opções documentais — não executáveis por este namespace."
  [{:id :import-loja-when-known
    :applies-to :known
    :risk :low
    :description "Importar moedas/inventário da única fingerprint loja reconciliada."}
   {:id :hold-economy-divergent
    :applies-to :divergent
    :risk :medium
    :description "Importar só treinador/Pokémon; economia fica await-human sem escrever saldo."}
   {:id :hold-economy-unknown
    :applies-to :unknown
    :risk :high-if-zeroed
    :description "Não gravar moedas=0. Slot recovery sem economia até evidência."}
   {:id :human-pick-source
    :applies-to :divergent
    :risk :medium
    :description "Admin escolhe uma fonte loja explícita; nunca max/soma automática."}])

(defn option-for
  [classification]
  (->> recovery-options
       (filter #(= (:applies-to %) (:status classification)))
       vec))
