(ns pokemon.domain.capture
  "Probabilidade e resolução de captura — fórmulas de zapbot.pokemon.core
  e multiplicadores de loja/bolas. RNG injetável para testes."
  (:require [pokemon.domain.errors :as err]))

(def chance-captura-base 70)
(def chance-captura-por-poder 0.15)
(def poder-referencia-captura 450)
(def chance-captura-minima 15)
(def chance-captura-maxima 90)

(def raridades
  {"comum" {:captura 1.15}
   "incomum" {:captura 1.0}
   "raro" {:captura 0.8}
   "epico" {:captura 0.6}
   "lendario" {:captura 0.4}
   "mitico" {:captura 0.3}})

(def bonus-captura-status
  {:adormecido 2 :congelado 2
   :paralisado 1.5 :envenenado 1.5 :queimado 1.5 :confuso 1.5
   "adormecido" 2 "congelado" 2
   "paralisado" 1.5 "envenenado" 1.5 "queimado" 1.5 "confuso" 1.5})

(def bolas
  {"pokebola" {:nome "Pokébola" :multiplicador-captura 1 :limite-captura 75}
   "grande-bola" {:nome "Grande Bola" :multiplicador-captura 1.5 :limite-captura 88}
   "ultra-bola" {:nome "Ultra Bola" :multiplicador-captura 2 :limite-captura 95}})

(def fuga-raridade
  {"comum" 5 "incomum" 7 "raro" 9 "epico" 11 "lendario" 14 "mitico" 16})

(def xp-base-raridade
  {"comum" 2 "incomum" 3 "raro" 4 "epico" 5 "lendario" 6 "mitico" 7})

(defn bonus-xp-sequencia
  "Primeira captura +0, segunda +1, terceira em diante +2."
  [sequencia]
  (min 2 (max 0 (dec (long sequencia)))))

(defn recompensa-pos-captura
  "Espelha encerrar-cacada! quando capturou? ou fugiu? (sem vitória-sem-captura)."
  [{:keys [raridade sequencia-nova primeira-tentativa? capturou?]}]
  (if capturou?
    (let [bonus-seq (bonus-xp-sequencia sequencia-nova)
          bonus-primeira (if primeira-tentativa? 1 0)
          base (get xp-base-raridade (or raridade "comum") 2)]
      {:xp (+ base bonus-seq bonus-primeira)
       :bonus-xp bonus-seq
       :bonus-primeira bonus-primeira
       :moedas (+ 2 (min 10 (long sequencia-nova)))
       :sequencia sequencia-nova})
    {:xp 1 :bonus-xp 0 :bonus-primeira 0 :moedas 0 :sequencia 0}))

(defn poder-total
  [pokemon]
  (let [g (fn [k] (long (or (get pokemon k) (get pokemon (name k)) 0)))]
    (+ (g :hp) (g :ataque) (g :defesa) (g :atq-esp) (g :def-esp) (g :veloc))))

(defn chance-captura
  "Taxa da espécie (15–90), antes de status/sequência/bola."
  [pokemon]
  (let [raridade (or (:raridade pokemon) (get pokemon "raridade") "comum")
        fator (get-in raridades [raridade :captura] 1.0)
        bruto (* (- chance-captura-base
                    (* chance-captura-por-poder
                       (- (poder-total pokemon) poder-referencia-captura)))
                 fator)
        arred (Math/round (double bruto))]
    (-> arred
        (max chance-captura-minima)
        (min chance-captura-maxima))))

(defn chance-base-pos-batalha
  "min(95, round(bonus_status * chance-captura) + 25 + min(20, 2*sequencia))."
  [pokemon status sequencia-capturas]
  (let [bonus (get bonus-captura-status status 1)
        base (chance-captura pokemon)
        seq-bonus (min 20 (* 2 (max 0 (long sequencia-capturas))))]
    (min 95 (+ (Math/round (* (double bonus) base)) 25 seq-bonus))))

(defn chance-com-bola
  [chance-base bola-id]
  (let [bola (get bolas bola-id)]
    (when-not bola
      (throw (err/error :unknown_ball {:bola bola-id})))
    (min (:limite-captura bola)
         (Math/round (* (double chance-base) (:multiplicador-captura bola))))))

(defn attempt-capture
  "Resolve uma tentativa. `roll` / `fuga-roll` são 0–99 (injetáveis).
  Espelha capturar-selvagem: bola já deve ter sido consumida pelo caller.
  Se `:chance-base` vier carimbada no encontro, usa esse valor (legado)."
  [{:keys [pokemon status sequencia-capturas bola tentativas-anteriores
           roll fuga-roll chance-base]}]
  (when-not (contains? bolas bola)
    (throw (err/error :unknown_ball {:bola bola})))
  (let [chance-base (long (or chance-base
                              (chance-base-pos-batalha pokemon status sequencia-capturas)))
        chance (chance-com-bola chance-base bola)
        roll (long (or roll (rand-int 100)))
        capturou? (< roll chance)
        tentativas (inc (long (or tentativas-anteriores 0)))
        fuga-chance (get fuga-raridade
                         (or (:raridade pokemon) (get pokemon "raridade") "comum")
                         8)
        fugiu? (and (not capturou?)
                    (or (>= tentativas 3)
                        (< (long (or fuga-roll (rand-int 100))) fuga-chance)))]
    {:capturou? capturou?
     :fugiu? (boolean fugiu?)
     :chance chance
     :chance-base chance-base
     :bola bola
     :roll roll
     :tentativa tentativas}))
