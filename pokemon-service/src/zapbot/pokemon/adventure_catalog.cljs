(ns zapbot.pokemon.adventure-catalog
  (:require [clojure.string :as str]))

(def distribuicao
  {:version "egg-catalog-v1"
   :pools {2 [{:weight 70 :species ["pidgey" "rattata" "caterpie" "weedle"]}
              {:weight 25 :species ["bulbasaur" "charmander" "squirtle"]}
              {:weight 5 :species ["pichu" "cleffa" "igglybuff"]}]
           5 [{:weight 55 :species ["geodude" "machop" "poliwag"]}
              {:weight 35 :species ["eevee" "growlithe" "vulpix"]}
              {:weight 10 :species ["togepi" "ralts" "mareep"]}]
           10 [{:weight 40 :species ["onix" "scyther" "pinsir"]}
               {:weight 45 :species ["dratini" "larvitar" "bagon"]}
               {:weight 15 :species ["riolu" "gible" "beldum"]}]}})

(defonce ^:private dados-locais (atom nil))
(defn dados []
  (or @dados-locais
      (let [fs (js/require "fs")
            arquivo (.readFileSync fs (str js/__dirname "/../assets/egg-species-v1.json") "utf8")
            snapshot (js->clj (js/JSON.parse arquivo) :keywordize-keys true)]
        (when-not (= "egg-species-v1" (:version snapshot))
          (throw (ex-info "Catálogo de espécies incompatível." {:code :invalid-catalog})))
        (reset! dados-locais (:species snapshot)))))

(defn validar! [catalogo categoria]
  (let [pool (get-in catalogo [:pools categoria]) especies (dados)]
    (when-not (and (contains? #{2 5 10} categoria)
                   (string? (:version catalogo)) (not (str/blank? (:version catalogo)))
                   (seq pool) (<= (count pool) 32)
                   (= 100 (reduce + (map :weight pool)))
                   (every? #(and (js/Number.isSafeInteger (:weight %)) (pos? (:weight %))
                                 (seq (:species %)) (<= (count (:species %)) 28)
                                 (= (count (:species %)) (count (set (:species %))))
                                 (every? (fn [nome] (contains? especies (keyword nome))) (:species %))) pool))
      (throw (ex-info "Distribuição de ovos inválida." {:code :invalid-catalog})))
    pool))

(defn seed [] (.toString (.randomBytes (js/require "crypto") 32) "hex"))

(defn sortear [pool seed]
  (when-not (and (seq pool) (= 100 (reduce + (map :weight pool)))
                 (every? #(and (pos? (:weight %)) (seq (:species %))) pool))
    (throw (ex-info "Pool persistido inválido." {:code :invalid-catalog})))
  (let [bytes (.digest (.update (.createHash (js/require "crypto") "sha256") seed))
        faixa (* (/ (.readUInt32BE bytes 0) 4294967296) 100)
        grupo (loop [[grupo & resto] pool acumulado 0]
                (if (< faixa (+ acumulado (:weight grupo))) grupo
                    (recur resto (+ acumulado (:weight grupo)))))
        especies (:species grupo)
        indice (js/Math.floor (* (/ (.readUInt32BE bytes 4) 4294967296) (count especies)))]
    (nth especies indice)))

(defn especie [nome]
  (or (get (dados) (keyword nome))
      (throw (ex-info "Espécie ausente no catálogo local." {:code :invalid-catalog}))))
