(ns pokemon.domain.bag-rewards
  "Kit inicial e bônus diário da mochila — espelho de zapbot.pokemon.loja.
   Sem I/O; sorteios injetáveis; timezone injetável."
  (:require [clojure.string :as str]
            [pokemon.domain.mission-rewards :as rewards]
            [pokemon.domain.mundo :as mundo]
            [pokemon.domain.onboarding :as onboard]
            [pokemon.domain.shop :as shop]))

(def kit-item-id onboard/kit-item-id)
(def kit-qty onboard/kit-qty)

(def ^:dynamic *rand-int*
  "Sobrescrito nos testes (fn [n] → 0..n-1)."
  rand-int)

(def default-timezone mundo/default-timezone)

(defn dia-atual
  ([instant] (mundo/dia-atual instant default-timezone))
  ([instant timezone-id]
   (mundo/dia-atual instant (or timezone-id default-timezone))))

(defn kit-espaco-ok?
  "Legado: kit exige 10 vagas livres; senão não marca resgatado."
  [save inventory]
  (let [cap (shop/capacidade-mochila-save save)
        occ (shop/ocupacao-mochila inventory)
        livres (max 0 (- cap occ))]
    (>= livres (long kit-qty))))

(defn sortear-bola-diaria
  "60% pokebola, 30% grande-bola, 10% ultra-bola (rand-int 100)."
  ([]
   (sortear-bola-diaria (*rand-int* 100)))
  ([roll]
   (let [r (long roll)]
     (cond (< r 10) "ultra-bola"
           (< r 40) "grande-bola"
           :else "pokebola"))))

(defn proxima-sequencia-diaria
  "Se ultimo foi o dia civil anterior → sequencia+1; senão 1."
  [ultimo dia sequencia-atual]
  (let [dia-seguinte
        (when (and ultimo (string? ultimo) (= 10 (count ultimo)))
          (try
            (let [ld (java.time.LocalDate/parse ultimo)]
              (str (.plusDays ld 1)))
            (catch Exception _ nil)))]
    (if (= dia dia-seguinte)
      (inc (long (or sequencia-atual 1)))
      1)))

(defn quantidade-bolas-diario
  "3 + min(4, sequencia-1) → 3..7."
  [sequencia]
  (+ 3 (min 4 (dec (long sequencia)))))

(defn recompensas-diario
  "Mapa item→qty. draw: (fn [idx] roll 0..99) ou nil → *rand-int*."
  ([sequencia]
   (recompensas-diario sequencia nil))
  ([sequencia draw]
   (let [n (quantidade-bolas-diario sequencia)
         draw* (or draw (fn [_] (*rand-int* 100)))
         bolas (frequencies (mapv #(sortear-bola-diaria (draw* %)) (range n)))]
     (cond-> bolas
       (zero? (mod (long sequencia) 7))
       (assoc "fruta-dourada" 1)))))

(defn diario-ja-resgatado-hoje?
  [ultimo-bonus-diario dia]
  (= (str ultimo-bonus-diario) (str dia)))

(defn allocate-diario
  "Espelha guardar-recompensas: enche inventário, resto em pending."
  [items pending free]
  (rewards/allocate items pending free))

(defn texto-recompensas
  "Texto curto tipo legado: `10× Pokébola`."
  [recompensas]
  (let [nomes {"pokebola" "Pokébola"
               "grande-bola" "Grande Bola"
               "ultra-bola" "Ultra Bola"
               "fruta-dourada" "Fruta Frambo Dourada"
               "fruta" "Fruta Frambo"
               "reviver" "Reviver"
               "catalisador-evolutivo" "Catalisador Evolutivo"}]
    (->> rewards/order
         (keep (fn [id]
                 (let [q (long (get recompensas id 0))]
                   (when (pos? q)
                     (str q "× " (or (get nomes id) id))))))
         (str/join ", "))))
