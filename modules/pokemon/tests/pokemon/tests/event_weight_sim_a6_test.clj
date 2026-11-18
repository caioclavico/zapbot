(ns pokemon.tests.event-weight-sim-a6-test
  "Simulação ≥10k encontros — chance-surto=25 (RC Combat UX+Eventos)."
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.hunt :as hunt]))

(defn- simular
  [n chance]
  (let [hits (count (filter #(< (mod (* % 7919) 100) chance) (range n)))]
    {:n n :chance chance :hits hits
     :pct (double (/ hits n))}))

(deftest surto-efetivo-cerca-de-25pct
  (is (= 25 hunt/chance-surto))
  (let [n 10000
        rolls (map #(mod (* % 7919) 100) (range n))
        hits (count (filter hunt/surto? rolls))
        pct (/ (double hits) n)]
    (is (< 0.22 pct 0.28) (str "chance-surto=25 → pct=" pct))
    (println "EVENT_WEIGHT_SIM" {:n n :pct pct :chance-surto hunt/chance-surto})))

(deftest surto-vs-legado-50-concentra-menos
  (let [legado (simular 10000 50)
        atual (simular 10000 hunt/chance-surto)]
    (is (< (:pct atual) (:pct legado)))
    (is (< 0.22 (:pct atual) 0.28))))

(deftest eevee-ainda-nos-quatro-surtos
  "Risco residual: Eevee em todos os pools — diversificação = GO futuro."
  (is (every? #(some #{"eevee"} (:especies %)) hunt/surtos))
  (is (= 4 (count hunt/surtos))))
