(ns pokemon.domain.species-hp
  "HP máximo por espécie — fórmula realista (species-hp-v1).

  Fórmula:
    hp-max = round(hp-referencia-nivel * clamp((base-hp / 80)^0.85, 0.60, 2.20))
    hp-referencia-nivel = round(80 * 1.03^(nível-1))

  Pura: sem I/O. Não altera ataque/defesa. Não aplica a chefes de raid
  (`raid/hp-por-jogador` permanece a fonte de HP de boss).

  Conversão de saves legados é explícita (`converter-proporcional`) —
  leitura NÃO persiste nem muta."
  (:require [pokemon.domain.errors :as err]))

(def formula-version "species-hp-v1")
(def legacy-formula-version "suavizar-0.5+1.03")

;; Espelha `pokemon.domain.xp/fator-crescimento-por-nivel` sem require cíclico.
(def fator-crescimento-por-nivel 1.03)

(def base-referencia 80.0)
(def expoente-especie 0.85)
(def fator-min 0.60)
(def fator-max 2.20)
(def hp-minimo-valido 1)

(defn clamp
  [x lo hi]
  (min (double hi) (max (double lo) (double x))))

(defn fator-especie
  "Fator determinístico a partir do HP base oficial da espécie.
  Exige base-hp positivo; dados ausentes/ inválidos falham fechado."
  [base-hp]
  (when (nil? base-hp)
    (throw (err/error :species_hp_base_missing {:base-hp base-hp})))
  (let [b (double base-hp)]
    (when-not (pos? b)
      (throw (err/error :species_hp_base_invalid {:base-hp base-hp})))
    (clamp (Math/pow (/ b base-referencia) expoente-especie)
           fator-min fator-max)))

(defn hp-referencia-nivel
  "HP de referência no nível N para espécie com base 80 (sem compressão legada)."
  [nivel]
  (let [n (long (or nivel 1))]
    (when (< n 1)
      (throw (err/error :species_hp_level_invalid {:nivel nivel})))
    (long (Math/round (* base-referencia
                         (Math/pow fator-crescimento-por-nivel (dec n)))))))

(defn hp-max
  "HP máximo canônico (puro). `base-hp` = atributo oficial da espécie."
  ([base-hp nivel]
   (let [ref (hp-referencia-nivel nivel)
         f (fator-especie base-hp)
         raw (long (Math/round (* (double ref) f)))]
     (max hp-minimo-valido raw)))
  ([base-hp nivel _opts]
   (hp-max base-hp nivel)))

(defn legacy-hp-max
  "Fórmula anterior (auditoria): round(suavizar(base) * 1.03^(n-1))
  com suavizar(v)=round(75+0.5*(v-75)). Só para comparação/simulação."
  [base-hp nivel]
  (let [suav (long (Math/round (+ 75.0 (* 0.5 (- (double base-hp) 75.0)))))
        n (max 1 (long (or nivel 1)))]
    (max hp-minimo-valido
         (long (Math/round (* (double suav)
                              (Math/pow fator-crescimento-por-nivel (dec n))))))))

(defn razao-hp
  "Razão atual/max ∈ [0,1]. HP zero (desmaio) → 0."
  [hp-atual hp-max-]
  (let [mx (long hp-max-)
        at (long (or hp-atual 0))]
    (cond
      (not (pos? mx)) 0.0
      (not (pos? at)) 0.0
      :else (clamp (/ (double at) (double mx)) 0.0 1.0))))

(defn converter-proporcional
  "Política aprovada: preserva proporção; desmaiado permanece 0.
  Não cura. Resultado sempre ≤ new-max."
  [hp-atual hp-max-antigo hp-max-novo]
  (let [novo-max (max hp-minimo-valido (long hp-max-novo))
        at (long (or hp-atual 0))
        old-max (long (or hp-max-antigo 0))]
    (cond
      (not (pos? at)) {:hp novo-max :hp-atual 0 :razao 0.0 :desmaiado? true}
      (not (pos? old-max))
      {:hp novo-max :hp-atual novo-max :razao 1.0 :desmaiado? false}
      :else
      (let [r (razao-hp at old-max)
            novo-at (max 0 (min novo-max (long (Math/round (* r (double novo-max))))))]
        ;; Evita “ressuscitar” por arredondamento quando estava quase 0 mas >0
        {:hp novo-max
         :hp-atual (if (and (pos? at) (zero? novo-at)) 1 novo-at)
         :razao r
         :desmaiado? false}))))

(defn aplicar-hp-especie
  "Monta campos HP (+ metadados de fórmula) a partir de base oficial e nível.
  `hp-atual` opcional: se nil e full?=true → cheio; se :proporcional + mapa antigo."
  [{:keys [base-hp nivel hp-atual hp-max-antigo full? proporcional?]}]
  (when (nil? base-hp)
    (throw (err/error :species_hp_base_missing {})))
  (let [mx (hp-max base-hp nivel)]
    (cond
      proporcional?
      (merge (converter-proporcional hp-atual hp-max-antigo mx)
             {:hp-base (long base-hp)
              :hp-formula-version formula-version})

      (or full? (nil? hp-atual))
      {:hp mx :hp-atual mx
       :hp-base (long base-hp)
       :hp-formula-version formula-version}

      :else
      (let [at (max 0 (min mx (long hp-atual)))]
        {:hp mx :hp-atual at
         :hp-base (long base-hp)
         :hp-formula-version formula-version}))))

(defn precisa-conversao?
  "True quando o registro ainda não está na fórmula v1."
  [registro]
  (not= formula-version (str (or (:hp-formula-version registro)
                                 (get registro "hp-formula-version")
                                 ""))))

(defn converter-registro
  "Conversão idempotente em memória (não persiste).
  Exige `:hp-base` ou `base-hp` explícito — não inventa base."
  [registro]
  (let [base (or (:hp-base registro)
                 (get registro "hp-base")
                 (:base-hp registro))
        nivel (or (:nivel registro) (:level registro) (get registro "nivel") 1)
        versao (str (or (:hp-formula-version registro) ""))]
    (when (nil? base)
      (throw (err/error :species_hp_base_missing
                        {:species (or (:species registro) (:nome registro))})))
    (if (= versao formula-version)
      registro
      (let [conv (aplicar-hp-especie
                  {:base-hp base
                   :nivel nivel
                   :hp-atual (or (:hp-atual registro) (get registro "hp-atual"))
                   :hp-max-antigo (or (:hp registro) (get registro "hp"))
                   :proporcional? true})]
        (merge registro conv)))))

(defn dano-aplicado
  "Aplica dano sem HP negativo."
  [hp-atual dano]
  (max 0 (- (long (or hp-atual 0)) (max 0 (long (or dano 0))))))

(defn cura-aplicada
  "Cura sem ultrapassar hp-max."
  [hp-atual hp-max- ganho]
  (min (long hp-max-)
       (+ (long (or hp-atual 0)) (max 0 (long (or ganho 0))))))
