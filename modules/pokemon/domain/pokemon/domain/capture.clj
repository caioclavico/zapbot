(ns pokemon.domain.capture
  "Probabilidade e resolução de captura — Fase 12.8: raridade ancora a chance.
  RNG injetável para testes."
  (:require [pokemon.domain.errors :as err]))

;; Faixas-base (Pokébola, sem status/seqüência) — Fase 12.8.
;; Valores centrais das faixas pedidas; leve variação por poder dentro da faixa.
(def raridade-chance-base
  {"comum" 82
   "incomum" 65
   "raro" 40
   "epico" 22
   "lendario" 8
   "mitico" 5})

(def raridade-chance-min
  {"comum" 75 "incomum" 55 "raro" 30 "epico" 15 "lendario" 5 "mitico" 3})

(def raridade-chance-max
  {"comum" 90 "incomum" 75 "raro" 50 "epico" 30 "lendario" 12 "mitico" 8})

;; Compat: mapa antigo ainda consultado por código legado de peso.
(def raridades
  {"comum" {:captura 1.15}
   "incomum" {:captura 1.0}
   "raro" {:captura 0.8}
   "epico" {:captura 0.6}
   "lendario" {:captura 0.4}
   "mitico" {:captura 0.3}})

(def chance-captura-minima 3)
(def chance-captura-maxima 90)
(def poder-referencia-captura 450)
;; ±4 pp dentro da faixa por desvio de poder (não cruza limites da raridade).
(def poder-variacao-pp 4)
(def poder-variacao-ref 200)

(def bonus-captura-status
  {:adormecido 2 :congelado 2
   :paralisado 1.5 :envenenado 1.5 :queimado 1.5 :confuso 1.5
   "adormecido" 2 "congelado" 2
   "paralisado" 1.5 "envenenado" 1.5 "queimado" 1.5 "confuso" 1.5})

(def bolas
  {"pokebola" {:nome "Pokébola" :multiplicador-captura 1 :limite-captura 95}
   "grande-bola" {:nome "Grande Bola" :multiplicador-captura 1.5 :limite-captura 95}
   "ultra-bola" {:nome "Ultra Bola" :multiplicador-captura 2 :limite-captura 95}})

;; Caps de bola: Pokébola sobe a 95 para não truncar comuns (~82–90) artificialmente.
;; Grande/Ultra mantêm 95. Chance nunca > 100 após frambo (re-capada no limite).

(def fuga-raridade
  {"comum" 5 "incomum" 7 "raro" 9 "epico" 11 "lendario" 14 "mitico" 16})

(def xp-base-raridade
  {"comum" 2 "incomum" 3 "raro" 4 "epico" 5 "lendario" 6 "mitico" 7})

(defn bonus-xp-sequencia
  "Primeira captura +0, segunda +1, terceira em diante +2."
  [sequencia]
  (min 2 (max 0 (dec (long sequencia)))))

(def bonus-base-vitoria-sem-captura 1)

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

(defn recompensa-vitoria-sem-captura
  "Bolsa cheia / somente-batalha: raridade + 1 XP; sem moedas; sequência intacta."
  [{:keys [raridade]}]
  (let [base (get xp-base-raridade (or raridade "comum") 2)]
    {:xp (+ base bonus-base-vitoria-sem-captura)
     :bonus-xp bonus-base-vitoria-sem-captura
     :bonus-primeira 0
     :moedas 0}))

(defn poder-total
  [pokemon]
  (let [g (fn [k] (long (or (get pokemon k) (get pokemon (name k)) 0)))]
    (+ (g :hp) (g :ataque) (g :defesa) (g :atq-esp) (g :def-esp) (g :veloc))))

(defn- raridade-de
  [pokemon]
  (or (:raridade pokemon) (get pokemon "raridade") "comum"))

(defn chance-captura
  "Taxa da espécie ancorada na raridade (faixas 12.8), com leve variação por poder."
  [pokemon]
  (let [raridade (raridade-de pokemon)
        centro (get raridade-chance-base raridade 65)
        lo (get raridade-chance-min raridade chance-captura-minima)
        hi (get raridade-chance-max raridade chance-captura-maxima)
        poder (poder-total pokemon)
        ;; Poder alto → um pouco mais difícil dentro da faixa.
        delta (-> (- (double poder) poder-referencia-captura)
                  (/ poder-variacao-ref)
                  (* poder-variacao-pp)
                  Math/round
                  long
                  -)
        bruto (+ centro delta)]
    (-> bruto
        (max lo)
        (min hi)
        (max chance-captura-minima)
        (min chance-captura-maxima))))

(defn chance-base-pos-batalha
  "Pós-batalha: status × espécie + bônus moderado de sequência.
  Sem o +25 legado — preserva ordenação por raridade (comuns ≫ lendários)."
  [pokemon status sequencia-capturas]
  (let [bonus (get bonus-captura-status status 1)
        base (chance-captura pokemon)
        ;; Sequência ajuda no máximo +10 (antes: +25 flat + até +20 seq).
        seq-bonus (min 10 (long sequencia-capturas))]
    (-> (Math/round (* (double bonus) base))
        (+ seq-bonus)
        (max 1)
        (min 95))))

(defn chance-com-bola
  [chance-base bola-id]
  (let [bola (get bolas bola-id)]
    (when-not bola
      (throw (err/error :unknown_ball {:bola bola-id})))
    (min (:limite-captura bola)
         (Math/round (* (double chance-base) (:multiplicador-captura bola))))))

;; Fruta Frambo (item `fruta`) — caça e raide.
(def frambo-item-id "fruta")
(def frambo-multiplicador-captura 1.5)

(defn chance-com-bola-frambo
  "Chance final: bola (± frambo 1.5×). Sem frambo ≡ chance-com-bola. Cap no limite da bola."
  [chance-base bola-id frambo?]
  (let [com-bola (chance-com-bola chance-base bola-id)]
    (if-not frambo?
      com-bola
      (let [lim (get-in bolas [bola-id :limite-captura] 95)]
        (min lim (Math/round (* (double com-bola) frambo-multiplicador-captura)))))))

(defn chances-painel
  "Mapa bola→% para UI (antes/depois da fruta)."
  [chance-base frambo?]
  (into {}
        (for [bola (keys bolas)]
          [bola (chance-com-bola-frambo chance-base bola frambo?)])))

(defn attempt-capture
  "Resolve uma tentativa. `roll` / `fuga-roll` são 0–99 (injetáveis).
  Se `:chance-base` vier carimbada no encontro, usa esse valor.
  `:frambo` aplica o multiplicador Frambo (caça ou raide)."
  [{:keys [pokemon status sequencia-capturas bola tentativas-anteriores
           roll fuga-roll chance-base frambo]}]
  (when-not (contains? bolas bola)
    (throw (err/error :unknown_ball {:bola bola})))
  (let [chance-base (long (or chance-base
                              (chance-base-pos-batalha pokemon status sequencia-capturas)))
        frambo? (boolean frambo)
        chance (chance-com-bola-frambo chance-base bola frambo?)
        roll (long (or roll (rand-int 100)))
        capturou? (< roll chance)
        tentativas (inc (long (or tentativas-anteriores 0)))
        fuga-chance (get fuga-raridade
                         (raridade-de pokemon)
                         8)
        fugiu? (and (not capturou?)
                    (or (>= tentativas 3)
                        (< (long (or fuga-roll (rand-int 100))) fuga-chance)))]
    {:capturou? capturou?
     :fugiu? (boolean fugiu?)
     :chance chance
     :chance-base chance-base
     :bola bola
     :frambo? frambo?
     :roll roll
     :tentativa tentativas
     :chances (chances-painel chance-base frambo?)}))
