(ns pokemon.domain.trainer-rewards
  "Sorteio determinístico por operação do pacote de nível; sem efeitos ou catálogo fictício."
  (:require [pokemon.domain.shop :as shop])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Random]))

;; Pesos interpolam linearmente do nível 1 ao 50. Cartão-presente é item real,
;; com envio JVM transacional e receipt; permanece raro e limitado a 1 por pacote.
;; Moedas são excluídas para não contornar o ledger econômico.
(def ^:private pool
  [{:item "pokebola" :from 44.5 :to 21}
   {:item "grande-bola" :from 15 :to 20}
   {:item "ultra-bola" :from 2 :to 12}
   {:item "pocao" :from 17 :to 13}
   {:item "pocao-maxima" :from 2 :to 12}
   {:item "fruta" :from 12 :to 8}
   {:item "fruta-dourada" :from 1 :to 5}
   {:item "cartao-presente" :from 0.5 :to 1}
   {:item "reviver" :from 6 :to 8}])

(defn pesos-no-nivel [nivel]
  (let [n (max 1 (min 50 (long nivel)))
        pct (/ (double (dec n)) 49.0)]
    (mapv (fn [{:keys [item from to]}]
            {:item item :weight (+ from (* (- to from) pct))}) pool)))

(defn- seed [operation-id level]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (str "trainer-level-reward/v1|" operation-id "|" level)
                         StandardCharsets/UTF_8)]
    (.update digest bytes)
    (.getLong (ByteBuffer/wrap (.digest digest)))))

(defn- weighted-index [^Random rng weighted]
  (let [total (reduce + 0.0 (map :weight weighted))
        target (* (.nextDouble rng) total)]
    (loop [items weighted idx 0 acc 0.0]
      (let [item (first items)
            next-acc (+ acc (:weight item))]
        (if (or (nil? (next items)) (< target next-acc))
          idx
          (recur (next items) (inc idx) next-acc))))))

(defn- quantity [level item]
  (case item
    "pokebola" (+ 2 (quot (max 0 (dec (long level))) 25))
    "grande-bola" (if (>= level 25) 2 1)
    1))

(defn pacote-normal
  "Gera 2–3 tipos distintos por nível. operation-id torna retries/rebuilds idempotentes."
  [operation-id level]
  (let [rng (Random. (seed operation-id level))
        count (+ 2 (.nextInt rng 2))
        selected (loop [left (pesos-no-nivel level) n count out []]
                   (if (zero? n)
                     out
                     (let [i (weighted-index rng left)
                           chosen (nth left i)]
                       (recur (vec (concat (subvec left 0 i) (subvec left (inc i))))
                              (dec n) (conj out (:item chosen))))))]
    (into (sorted-map)
          (map (fn [item]
                 (when-not (shop/item item)
                   (throw (ex-info "trainer_reward_item_missing_from_catalog" {:item item})))
                 [item (quantity level item)]))
          selected)))

(defn pacote-especial
  "Bônus a cada 10 níveis; nível 50 recebe o prêmio comemorativo em substituição."
  [level]
  (cond
    (= 50 (long level)) {"fruta-dourada" 1 "ultra-bola" 3 "pocao-maxima" 2 "reviver" 1}
    (and (pos? (long level)) (zero? (mod (long level) 10)))
    {"ultra-bola" 1 "pocao-maxima" 1}
    :else {}))

(defn recompensas-por-nivel [operation-id level]
  (let [normal (pacote-normal operation-id level)
        special (pacote-especial level)]
    {:nivel level
     :normal normal
     :especial special
     :items (merge-with + normal special)
     :marco-espaco (when (zero? (mod (long level) 5))
                     (if (odd? (quot (long level) 5)) :pokemon :mochila))}))
