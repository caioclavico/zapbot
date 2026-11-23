(ns pokemon.domain.capture
  "Probabilidade e resolução de captura — Fase 12.8: raridade ancora a chance.
  RNG injetável para testes.
  Política A6: nunca cair silenciosamente em `comum` quando a raridade falta —
  recuperar via classificação canônica da espécie; se impossível, usar
  taxa de `mitico` (mais dura) para não inflar chance."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.rarity :as rarity]))

;; Políticas oficiais (:oficial-a/:oficial-b) via rarity/*politica* — default :legado.

;; Faixas-base (Pokébola, sem status) — A6 wild-capture-streak-balance.
;; Comum/incomum reduzidos; raro+ inalterados nesta fase.
;; Bola/Frambo aplicam-se depois, com cap por bola (90).
;; Política: streak NÃO altera probabilidade (só recompensas streak-unit-v1).
(def politica-chance-captura-versao "wild-catch-balance-v2")

(def raridade-chance-base
  {"comum" 65
   "incomum" 48
   "raro" 28
   "epico" 14
   "lendario" 5
   "mitico" 3})

(def raridade-chance-min
  {"comum" 55 "incomum" 40 "raro" 18 "epico" 8 "lendario" 2 "mitico" 1})

(def raridade-chance-max
  {"comum" 70 "incomum" 58 "raro" 38 "epico" 22 "lendario" 9 "mitico" 6})

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
;; Raide mantém cap legado 95 (não acoplar ao rebalance de caça).
(def chance-captura-maxima-raid 95)
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
  "Curva selvagem: 65 → 65 / 80 / 95 nas condições de referência.
  `limite-captura` impede que a Bola Média e a Ultra caiam no mesmo teto
  para um Pokémon comum (a Ultra para em 95; a Média, em 90).
  Raide continua nos multiplicadores legados via `lim-override`."
  {"pokebola" {:nome "Pokébola" :numer 1 :denom 1 :multiplicador-captura 1
               :limite-captura 90}
   "grande-bola" {:nome "Grande Bola" :numer 16 :denom 13
                  :multiplicador-captura (/ 16.0 13) :limite-captura 90}
   "ultra-bola" {:nome "Ultra Bola" :numer 19 :denom 13
                 :multiplicador-captura (/ 19.0 13) :limite-captura 95}})

(def ^:private bolas-raid
  {"pokebola" [1 1]
   "grande-bola" [3 2]
   "ultra-bola" [2 1]})

(defn- aplicar-fracao
  "Arredonda metade para cima sem erro de binário: (n*a)/b."
  [chance numer denom]
  (let [c (long chance)
        a (long numer)
        b (long denom)
        prod (* c a)
        q (quot prod b)
        r (rem prod b)]
    (if (>= (* r 2) b) (inc q) q)))

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

;; Caps de bola unificados em 90 (wild-catch-balance-v1). Frambo re-capa no limite.

(def fuga-raridade
  {"comum" 5 "incomum" 7 "raro" 9 "epico" 11 "lendario" 14 "mitico" 16})

(def xp-base-raridade
  {"comum" 2 "incomum" 3 "raro" 4 "epico" 5 "lendario" 6 "mitico" 7})

(defn bonus-xp-sequencia
  "Legado: primeira captura +0, segunda +1, terceira em diante +2.
  Mantido para compat/leituras; recompensa canônica usa `faixa-sequencia-captura`."
  [sequencia]
  (min 2 (max 0 (dec (long sequencia)))))

(def bonus-base-vitoria-sem-captura 1)

(def pe-batalha-caca 1)

(def politica-recompensa-captura-versao "streak-unit-v1")

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

(defn faixa-sequencia-captura
  "Recompensa unitária total da captura na sequência já incrementada.
  Sem base de moedas: moedas só a partir da 5ª. Teto na 30ª (valores 30+)."
  [sequencia-nova]
  (let [s (max 0 (long sequencia-nova))]
    (cond
      (<= s 0) {:xp 0 :pe 0 :moedas 0 :faixa "0"}
      (<= s 2) {:xp 2 :pe 1 :moedas 0 :faixa "1-2"}
      (<= s 4) {:xp 3 :pe 2 :moedas 0 :faixa "3-4"}
      (<= s 9) {:xp 5 :pe 3 :moedas 1 :faixa "5-9"}
      (<= s 19) {:xp 8 :pe 5 :moedas 3 :faixa "10-19"}
      (<= s 29) {:xp 12 :pe 7 :moedas 6 :faixa "20-29"}
      :else {:xp 15 :pe 9 :moedas 10 :faixa "30+"})))

(defn recompensa-pos-captura
  "Recompensa de captura (streak-unit-v1): XP/PE/moedas unitários pela sequência.
  Não inclui XP/PE de batalha (creditados na vitória). Sem capturou? → consolação
  leve; fuga final sem captura zera a sequência selvagem no caller."
  [{:keys [sequencia-nova capturou?]}]
  (if capturou?
    (let [faixa (faixa-sequencia-captura sequencia-nova)]
      {:xp (long (:xp faixa))
       :pe (long (:pe faixa))
       :moedas (long (:moedas faixa))
       :bonus-xp 0
       :bonus-primeira 0
       :sequencia (long sequencia-nova)
       :faixa (:faixa faixa)
       :politica politica-recompensa-captura-versao})
    {:xp 1 :pe 0 :moedas 0 :bonus-xp 0 :bonus-primeira 0
     :sequencia (long (or sequencia-nova 0))
     :faixa "fuga"
     :politica politica-recompensa-captura-versao}))

(defn recompensa-batalha-caca
  "XP Pokémon + PE treinador ao vencer a caçada (capturar ou liberar).
  Sem moedas. Independente da sequência."
  [{:keys [raridade]}]
  (let [r (or (normalizar-raridade raridade) "comum")
        base (get xp-base-raridade r 2)]
    {:xp (+ base bonus-base-vitoria-sem-captura)
     :pe (long pe-batalha-caca)
     :moedas 0
     :bonus-xp bonus-base-vitoria-sem-captura
     :politica politica-recompensa-captura-versao}))

(defn recompensa-vitoria-sem-captura
  "Alias histórico (bolsa cheia / liberar): mesma política de batalha."
  [opts]
  (let [r (recompensa-batalha-caca opts)]
    (-> r
        (assoc :bonus-primeira 0)
        (dissoc :pe :politica))))

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

(defn classificar-raridade-legado
  "Heurística histórica A6 (stats suavizados / flags API / taxa-captura).
  Mantida intacta para compatibilidade; ver `classificar-raridade`."
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

(defn classificar-raridade
  "Classificação canônica usada em caça/raid.
   Default `rarity/*politica* = :legado`. Com `:oficial-a`/`:oficial-b` consulta
   o catálogo oficial e cai no legado se a espécie não tiver evidência."
  [pokemon]
  (let [pol (rarity/politica-ativa)]
    (if (contains? #{:oficial-a :oficial-b} pol)
      (rarity/classificar-ou-fallback pokemon pol classificar-raridade-legado)
      (classificar-raridade-legado pokemon))))

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
  "Pós-batalha: status × espécie. Sequência NÃO altera a chance
  (política wild-catch-balance-v1); streak continua só em recompensas.
  `sequencia-capturas` permanece no arity por compatibilidade de callers."
  [pokemon status sequencia-capturas]
  (let [poke (assegurar-raridade pokemon)
        bonus (get bonus-captura-status status 1)
        base (chance-captura poke)
        _ignored (long (or sequencia-capturas 0))]
    (-> (Math/round (* (double bonus) base))
        (max 1)
        (min chance-captura-maxima))))

(defn chance-com-bola
  "Aplica multiplicador da bola. `lim-override` opcional (ex.: raide 95)."
  ([chance-base bola-id]
   (chance-com-bola chance-base bola-id nil))
  ([chance-base bola-id lim-override]
   (let [bola (get bolas bola-id)]
     (when-not bola
       (throw (err/error :unknown_ball {:bola bola-id})))
     (let [lim (long (or lim-override (:limite-captura bola) chance-captura-maxima))
           [numer denom] (if lim-override
                           (get bolas-raid bola-id)
                           [(:numer bola) (:denom bola)])]
       (min lim (aplicar-fracao chance-base numer denom))))))

;; Fruta Frambo (item `fruta`) — caça e raide.
(def frambo-item-id "fruta")
(def frambo-multiplicador-captura 1.5)

(defn chance-com-bola-frambo
  "Chance final: bola (± frambo 1.5×). Sem frambo ≡ chance-com-bola.
  Cap no limite da bola (wild 90) ou `lim-override` (raid 95)."
  ([chance-base bola-id frambo?]
   (chance-com-bola-frambo chance-base bola-id frambo? nil))
  ([chance-base bola-id frambo? lim-override]
   (let [com-bola (chance-com-bola chance-base bola-id lim-override)]
     (if-not frambo?
       com-bola
       (let [lim (long (or lim-override
                           (get-in bolas [bola-id :limite-captura])
                           chance-captura-maxima))]
         (min lim (Math/round (* (double com-bola) frambo-multiplicador-captura))))))))

(defn chances-painel
  "Mapa bola→% para UI (antes/depois da fruta)."
  ([chance-base frambo?]
   (chances-painel chance-base frambo? nil))
  ([chance-base frambo? lim-override]
   (into {}
         (for [bola (keys bolas)]
           [bola (chance-com-bola-frambo chance-base bola frambo? lim-override)]))))

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
