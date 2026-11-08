(ns pokemon.domain.capture
  "Probabilidade e resolução de captura — Fase 12.8: raridade ancora a chance.
  RNG injetável para testes.
  Política A6: nunca cair silenciosamente em `comum` quando a raridade falta —
  recuperar via classificação canônica da espécie; se impossível, usar
  taxa de `mitico` (mais dura) para não inflar chance."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]))

;; Faixas-base (Pokébola, sem status/seqüência) — Fase 12.8.
;; Valores centrais das faixas pedidas; leve variação por poder dentro da faixa.
;; Rebalance caça A6 (auditoria): raros+ mais duros; comuns ainda generosos.
;; Bola/Frambo aplicam-se depois, com cap por bola (95).
(def raridade-chance-base
  {"comum" 78
   "incomum" 58
   "raro" 28
   "epico" 14
   "lendario" 5
   "mitico" 3})

(def raridade-chance-min
  {"comum" 70 "incomum" 48 "raro" 18 "epico" 8 "lendario" 2 "mitico" 1})

(def raridade-chance-max
  {"comum" 88 "incomum" 68 "raro" 38 "epico" 22 "lendario" 9 "mitico" 6})

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

(def bola-aliases
  "IDs legados que somam no estoque/débito do canônico (UI + inventory-adjust)."
  {"pokebola" #{"pokebola" "poke-ball" "pokeball"}
   "grande-bola" #{"grande-bola" "super-bola" "superbola" "great-ball" "greatball" "great"}
   "ultra-bola" #{"ultra-bola" "ultra-ball" "ultraball"}
   "fruta" #{"fruta"}})

(defn bola-alias-ids
  "Conjunto de IDs inventário equivalentes ao canônico (canônico incluso)."
  [item-id]
  (get bola-aliases (str item-id) #{(str item-id)}))

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

(def raridades-validas
  #{"comum" "incomum" "raro" "epico" "lendario" "mitico"})

(def ^:private raridade-aliases
  {"épico" "epico" "lendário" "lendario" "mítico" "mitico"})

(defn normalizar-raridade
  "Slug canônico sem acento, ou nil se vazio/ inválido."
  [r]
  (when (and r (not (str/blank? (str r))))
    (let [s (-> (str r) str/lower-case str/trim)
          s (or (get raridade-aliases s) s)
          s (-> s
                (str/replace #"é" "e")
                (str/replace #"á" "a")
                (str/replace #"í" "i"))]
      (when (contains? raridades-validas s) s))))

(defn recompensa-pos-captura
  "Espelha encerrar-cacada! quando capturou? ou fugiu? (sem vitória-sem-captura).
   XP: slug inválido/ausente → comum (não infla recompensa)."
  [{:keys [raridade sequencia-nova primeira-tentativa? capturou?]}]
  (if capturou?
    (let [bonus-seq (bonus-xp-sequencia sequencia-nova)
          bonus-primeira (if primeira-tentativa? 1 0)
          r (or (normalizar-raridade raridade) "comum")
          base (get xp-base-raridade r 2)]
      {:xp (+ base bonus-seq bonus-primeira)
       :bonus-xp bonus-seq
       :bonus-primeira bonus-primeira
       :moedas (+ 2 (min 10 (long sequencia-nova)))
       :sequencia sequencia-nova})
    {:xp 1 :bonus-xp 0 :bonus-primeira 0 :moedas 0 :sequencia 0}))

(defn recompensa-vitoria-sem-captura
  "Bolsa cheia / somente-batalha: raridade + 1 XP; sem moedas; sequência intacta."
  [{:keys [raridade]}]
  (let [r (or (normalizar-raridade raridade) "comum")
        base (get xp-base-raridade r 2)]
    {:xp (+ base bonus-base-vitoria-sem-captura)
     :bonus-xp bonus-base-vitoria-sem-captura
     :bonus-primeira 0
     :moedas 0}))

(defn poder-total
  [pokemon]
  (let [g (fn [k] (long (or (get pokemon k) (get pokemon (name k)) 0)))]
    (+ (g :hp) (g :ataque) (g :defesa) (g :atq-esp) (g :def-esp) (g :veloc))))

(defn- peso-raridade
  [r]
  (get {"comum" 0 "incomum" 1 "raro" 2 "epico" 3 "lendario" 4 "mitico" 5} r 0))

(defn- maior-raridade
  [a b]
  (if (> (peso-raridade b) (peso-raridade a)) b a))

(defn classificar-raridade
  "Classificação canônica A6 (stats suavizados / flags API / taxa-captura).
  Mesma regra usada em caça (`hunt/com-raridade`)."
  [pokemon]
  (cond
    (or (:mitico-api? pokemon) (get pokemon "mitico-api?")) "mitico"
    (or (:lendario-api? pokemon) (get pokemon "lendario-api?")
        (:paradox-api? pokemon) (get pokemon "paradox-api?")) "lendario"
    :else
    (let [poder-original (- (* 2 (poder-total pokemon)) 450)
          por-poder (cond (<= poder-original 400) "comum"
                          (<= poder-original 480) "incomum"
                          (<= poder-original 540) "raro"
                          :else "epico")
          taxa (or (:taxa-captura pokemon) (get pokemon "taxa-captura"))
          por-captura (cond (nil? taxa) nil
                            (<= (long taxa) 10) "epico"
                            (<= (long taxa) 45) "raro"
                            (<= (long taxa) 120) "incomum"
                            :else "comum")]
      (if por-captura
        (maior-raridade por-poder por-captura)
        por-poder))))

(defn classificavel?
  "Há sinais suficientes para classificar sem inventar `comum`."
  [pokemon]
  (boolean
   (or (:mitico-api? pokemon) (get pokemon "mitico-api?")
       (:lendario-api? pokemon) (get pokemon "lendario-api?")
       (:paradox-api? pokemon) (get pokemon "paradox-api?")
       (some? (:taxa-captura pokemon)) (some? (get pokemon "taxa-captura"))
       (pos? (poder-total pokemon)))))

(defn resolver-raridade
  "Resolve raridade com fonte explícita.
   :snapshot — campo presente e válido
   :classificado — recuperado da espécie (legado sem :raridade)
   :desconhecida — sem sinais; chance usará faixa mítica (não comum)."
  [pokemon]
  (let [norm (normalizar-raridade (or (:raridade pokemon) (get pokemon "raridade")))]
    (cond
      norm
      {:raridade norm :fonte :snapshot :recuperada? false}

      (classificavel? pokemon)
      {:raridade (classificar-raridade pokemon)
       :fonte :classificado
       :recuperada? true}

      :else
      {:raridade "mitico" :fonte :desconhecida :recuperada? false})))

(defn raridade-de
  "Slug canônico para chance/fuga/XP. Nunca retorna nil."
  [pokemon]
  (:raridade (resolver-raridade pokemon)))

(defn assegurar-raridade
  "Garante `:raridade` canônica no mapa (recupera legado se preciso).
   Anexa `:raridade-fonte` para diagnóstico (não afeta slim de combate)."
  [pokemon]
  (when pokemon
    (let [{:keys [raridade fonte recuperada?]} (resolver-raridade pokemon)]
      (cond-> (assoc pokemon :raridade raridade :raridade-fonte (name fonte))
        recuperada? (assoc :raridade-recuperada? true)))))

(defn chance-captura
  "Taxa da espécie ancorada na raridade (faixas 12.8), com leve variação por poder.
  Desconhecida → faixa `mitico` (não infla)."
  [pokemon]
  (let [{:keys [raridade fonte]} (resolver-raridade pokemon)
        ;; Faixa mitico se desconhecida — nunca centro genérico 65/comum.
        raridade (if (= fonte :desconhecida) "mitico" raridade)
        centro (get raridade-chance-base raridade
                    (get raridade-chance-base "mitico"))
        lo (get raridade-chance-min raridade
               (get raridade-chance-min "mitico"))
        hi (get raridade-chance-max raridade
               (get raridade-chance-max "mitico"))
        poder (poder-total pokemon)
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
  (let [poke (assegurar-raridade pokemon)
        bonus (get bonus-captura-status status 1)
        base (chance-captura poke)
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
