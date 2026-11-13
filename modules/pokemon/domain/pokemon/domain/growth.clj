(ns pokemon.domain.growth
  "Progressão de XP dos Pokémon.

  growth-v2 (canônico): fórmula simples `xpNecessario(n) = 10 + 2*(n-1)`.
  Persistência: `:xp-desde-nivel` = residual no nível; `:xp-total` =
  `xpTotalMinimo(n) + residual` (uso interno/conversão).

  growth-v1 (histórico): curvas oficiais PokéAPI — mantido para leitura e
  conversão proporcional → v2 (sem level-up).

  Legado pré-v1: residual com limiar fixo 9."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.species-form-aliases :as form-aliases]))

(def progress-version-v1 "growth-v1")
(def progress-version "growth-v2")
(def nivel-maximo 100)
(def legacy-xp-por-nivel 9)
(def ^{:doc "growth-rate sentinela dos registros growth-v2 (sem curva PokéAPI)."}
  simple-growth-rate :simple)

(def ^:private tables-resource "pokemon/domain/data/growth_rate_tables.edn")
(def ^:private species-resource "pokemon/domain/data/species_growth_rates.edn")

(def ^:private tables-atom (atom nil))
(def ^:private species-atom (atom nil))

(def curve-keys
  #{:erratic :fast :medium-fast :medium-slow :slow :fluctuating})

(defn- load-edn!
  [resource]
  (let [res (io/resource resource)]
    (when-not res
      (throw (err/error :growth_catalog_missing {:resource resource})))
    (edn/read-string (slurp res))))

(defn tables
  []
  (or @tables-atom
      (let [t (load-edn! tables-resource)]
        (reset! tables-atom t)
        t)))

(defn species-growth-index
  []
  (or @species-atom
      (let [m (load-edn! species-resource)]
        (reset! species-atom m)
        m)))

(defn reset-growth-catalogs-for-tests!
  "nil recarrega resources; mapa/tabela substitui em memória."
  [tables-or-nil species-or-nil]
  (if (nil? tables-or-nil)
    (reset! tables-atom nil)
    (reset! tables-atom tables-or-nil))
  (if (nil? species-or-nil)
    (reset! species-atom nil)
    (reset! species-atom species-or-nil))
  {:tables (tables) :species (species-growth-index)})

(defn- slug
  [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn growth-rate-for-species
  "Resolve curva pela espécie-base (aliases de forma → canônico).
  Desconhecida → :medium-fast (curva mais comum na PokéAPI)."
  [species]
  (let [raw (slug species)
        resolved (or (some-> (form-aliases/resolve-catalog-species raw) slug) raw)
        indexed (species-growth-index)
        rate (or (get indexed resolved)
                 (get indexed raw)
                 :medium-fast)]
    (if (contains? curve-keys rate)
      rate
      :medium-fast)))

(defn- totals-vec
  [rate]
  (let [row (get (tables) rate)]
    (when-not row
      (throw (err/error :growth_rate_unknown {:rate rate})))
    (vec (:total-xp-by-level row))))

(defn total-xp-for-level
  "XP acumulado mínimo para estar no nível `nivel` (1..100)."
  [rate nivel]
  (let [n (long nivel)]
    (cond
      (< n 1) 0
      (> n nivel-maximo) (total-xp-for-level rate nivel-maximo)
      :else (long (nth (totals-vec rate) (dec n))))))

(defn xp-to-next-level
  "XP ainda necessário para sair do nível atual (nil no nível 100)."
  ([rate nivel] (xp-to-next-level rate nivel 0))
  ([rate nivel xp-desde-nivel]
   (let [n (long nivel)
         residual (max 0 (long xp-desde-nivel))]
     (if (>= n nivel-maximo)
       nil
       (let [need (- (total-xp-for-level rate (inc n))
                     (total-xp-for-level rate n))]
         (max 0 (- need residual)))))))

(defn pokemon-level-from-total-xp
  "Maior nível L tal que total-xp-for-level(L) <= xp-total."
  [rate xp-total]
  (let [xp (max 0 (long xp-total))
        totals (totals-vec rate)]
    (loop [lvl 1]
      (if (>= lvl nivel-maximo)
        nivel-maximo
        (let [next-need (long (nth totals lvl))] ;; index lvl => level (lvl+1)
          (if (< xp next-need)
            lvl
            (recur (inc lvl))))))))

(defn residual-in-level
  "XP dentro do nível corrente a partir do total acumulado."
  [rate nivel xp-total]
  (let [n (max 1 (min nivel-maximo (long nivel)))
        base (total-xp-for-level rate n)
        xp (max 0 (long xp-total))]
    (if (>= n nivel-maximo)
      0
      (max 0 (- xp base)))))

(defn level-span
  "XP necessário para atravessar o nível `nivel` → `nivel+1`."
  [rate nivel]
  (let [n (long nivel)]
    (if (>= n nivel-maximo)
      0
      (- (total-xp-for-level rate (inc n))
         (total-xp-for-level rate n)))))

(defn- registro-get
  [registro & ks]
  (some (fn [k]
          (when (contains? registro k)
            (get registro k)))
        ks))

(defn- registro-has?
  [registro & ks]
  (boolean (some #(contains? registro %) ks)))

(def ^{:doc "Vitórias legadas × este fator = residual flat-9 (treinador cljs)."}
  legacy-xp-por-vitoria 3)

(defn legacy-residual-xp
  "Precedência histórica do residual flat-9:
  1) `:xp-desde-nivel`  2) `:xp`  3) `vitorias-desde-nivel × 3`  4) 0."
  [registro]
  (cond
    (registro-has? registro :xp-desde-nivel "xp-desde-nivel")
    (max 0 (long (registro-get registro :xp-desde-nivel "xp-desde-nivel")))

    (registro-has? registro :xp "xp")
    (max 0 (long (registro-get registro :xp "xp")))

    (registro-has? registro :vitorias-desde-nivel "vitorias-desde-nivel")
    (max 0 (* legacy-xp-por-vitoria
              (long (registro-get registro :vitorias-desde-nivel "vitorias-desde-nivel"))))

    :else 0))

(defn convert-legacy-residual
  "Converte registro legado (residual + limiar 9) → growth-v1.

  Política de fronteira (espelha `normalizar-xp-registro` do cljs):
  - Nível legado é piso (nunca reduz).
  - `floor(residual/9)` vira subidas de nível (cap 100).
  - O resto `(residual rem 9)/9` mapeia para o span oficial do nível resultante.
  - Residual exatamente múltiplo de 9 → novo nível com residual 0 (sem XP
    no limiar do próximo nível com nível antigo).
  - Em L100, XP total = teto da curva; progresso excedente só em metadados.

  Idempotente se `:xp-progress-version` já for growth-v1 ou growth-v2."
  [registro]
  (let [ver (str (or (registro-get registro :xp-progress-version "xp-progress-version")
                     ""))]
    (cond
      (or (= ver progress-version-v1) (= ver progress-version))
      registro

      :else
      (let [species (or (registro-get registro :species "species" :nome "nome"))
            rate (growth-rate-for-species species)
            nivel0 (max 1 (min nivel-maximo
                               (long (or (registro-get registro :nivel "nivel"
                                                       :level "level")
                                         1))))
            legacy-residual (legacy-residual-xp registro)
            room (max 0 (- nivel-maximo nivel0))
            extra (if (>= nivel0 nivel-maximo)
                    0
                    (min room (quot legacy-residual legacy-xp-por-nivel)))
            rem (if (>= nivel0 nivel-maximo)
                  0
                  (long (mod legacy-residual legacy-xp-por-nivel)))
            nivel (min nivel-maximo (+ nivel0 extra))
            span (level-span rate nivel)
            frac (if (pos? legacy-xp-por-nivel)
                   (/ (double rem) (double legacy-xp-por-nivel))
                   0.0)
            ;; rem ∈ 0..8 ⇒ frac < 1 ⇒ mapped < span (nunca pousa no limiar L+1).
            mapped (if (or (>= nivel nivel-maximo) (zero? span))
                     0
                     (long (Math/floor (* frac (double span)))))
            base (total-xp-for-level rate nivel)
            xp-total (if (>= nivel nivel-maximo)
                       (total-xp-for-level rate nivel-maximo)
                       (+ base mapped))
            residual (residual-in-level rate nivel xp-total)
            nivel-from-xp (pokemon-level-from-total-xp rate xp-total)
            ;; Consistência: nível derivado do total nunca abaixo do convertido;
            ;; se o total implica nível maior (não esperado), sobe.
            nivel' (max nivel nivel-from-xp)
            xp-total' (max xp-total (total-xp-for-level rate nivel'))
            residual' (residual-in-level rate nivel' xp-total')
            ;; Auditoria: nível legado original — nunca sobrescrever se já gravado.
            legacy-nivel (or (when-let [v (registro-get registro
                                                        :xp-legacy-nivel
                                                        "xp-legacy-nivel")]
                               (long v))
                             nivel0)]
        (-> registro
            (assoc :nivel nivel'
                   :xp-total xp-total'
                   :xp-desde-nivel residual'
                   :xp residual'
                   :xp-progress-version progress-version-v1
                   :growth-rate rate
                   :xp-legacy-residual legacy-residual
                   :xp-legacy-nivel legacy-nivel))))))

(defn ensure-growth-v1
  "Garante campos growth-v1; registros novos com só nível recebem total da curva.
  Não promove para growth-v2 (usar `ensure-growth-v2` / `convert-to-growth-v2`)."
  [registro]
  (let [ver (str (or (get registro :xp-progress-version)
                     (get registro "xp-progress-version")
                     ""))
        has-total? (or (contains? registro :xp-total)
                       (contains? registro "xp-total"))]
    (cond
      (= ver progress-version)
      registro

      (= ver progress-version-v1)
      (let [species (or (get registro :species) (get registro "species")
                        (get registro :nome) (get registro "nome"))
            rate (or (get registro :growth-rate)
                     (growth-rate-for-species species))
            nivel (max 1 (min nivel-maximo
                              (long (get registro :nivel
                                         (get registro "nivel" 1)))))
            xp-total (long (get registro :xp-total
                                (get registro "xp-total"
                                     (total-xp-for-level rate nivel))))
            residual (residual-in-level rate nivel xp-total)]
        (assoc registro
               :growth-rate rate
               :xp-total xp-total
               :xp-desde-nivel residual
               :xp residual
               :xp-progress-version progress-version-v1
               :nivel nivel))

      (and (not has-total?)
           (or (contains? registro :xp-desde-nivel)
               (contains? registro "xp-desde-nivel")
               (contains? registro :xp)
               (contains? registro "xp")
               (contains? registro :vitorias-desde-nivel)
               (contains? registro "vitorias-desde-nivel")))
      (convert-legacy-residual registro)

      :else
      (let [species (or (get registro :species) (get registro "species")
                        (get registro :nome) (get registro "nome"))
            rate (growth-rate-for-species species)
            nivel (max 1 (min nivel-maximo
                              (long (get registro :nivel
                                         (get registro "nivel" 1)))))
            xp-total (total-xp-for-level rate nivel)]
        (assoc registro
               :growth-rate rate
               :nivel nivel
               :xp-total xp-total
               :xp-desde-nivel 0
               :xp 0
               :xp-progress-version progress-version-v1)))))

;;; --- growth-v2: progressão simples -----------------------------------------

(defn xp-necessario
  "XP necessário para sair do nível `nivel` → `nivel+1`.
  nil no nível máximo. Fórmula: 10 + 2*(nivel-1)."
  [nivel]
  (let [n (long nivel)]
    (when (< n nivel-maximo)
      (+ 10 (* 2 (max 0 (dec n)))))))

(defn total-xp-for-level-simple
  "XP acumulado mínimo do nível `nivel` (1..100): (nivel-1)*(nivel+8)."
  [nivel]
  (let [n (max 1 (min nivel-maximo (long nivel)))]
    (* (dec n) (+ n 8))))

(defn level-span-simple
  "Span do nível sob growth-v2 (0 no nível 100)."
  [nivel]
  (or (xp-necessario nivel) 0))

(defn- clamp-frac
  "Fração de progresso no nível ∈ [0, 1). 1.0 colapsa para quase-cheio."
  [frac]
  (let [f (double frac)]
    (cond
      (or (Double/isNaN f) (neg? f)) 0.0
      (>= f 1.0) 0.999
      :else f)))

(defn- progress-frac-v1
  "Fração do span oficial no nível atual (growth-v1)."
  [registro]
  (let [r (ensure-growth-v1 registro)
        rate (or (:growth-rate r)
                 (growth-rate-for-species (or (:species r) (:nome r))))
        nivel (long (:nivel r 1))
        residual (long (:xp-desde-nivel r 0))
        span (level-span rate nivel)]
    (if (or (>= nivel nivel-maximo) (zero? span))
      0.0
      (clamp-frac (/ (double residual) (double span))))))

(defn xp-known?
  "True somente com campos de XP presentes; nível isolado não prova progresso."
  [registro]
  (boolean (registro-has? registro :xp-total "xp-total"
                          :xp-desde-nivel "xp-desde-nivel"
                          :xp "xp"
                          :vitorias-desde-nivel "vitorias-desde-nivel")))

(defn convert-to-growth-v2
  "Converte registro para growth-v2 preservando o nível e o progresso
  proporcional dentro do nível. **Nunca** sobe de nível nesta conversão.

  - Já v2 → idempotente (renormaliza residual/total).
  - v1 → frac = residual/span(oficial); residual' = floor(frac * span-simples).
  - Legado flat-9 → v2 diretamente, fração residual/9 (sem curva oficial).
    Residual fora de [0,8] é inconsistente: falha fechada, sem mudar o nível.
  - Sem campos de XP → devolve registro intacto (desconhecido, sem inventar zero).

  Não grava; a persistência fica a cargo do caminho de escrita."
  [registro]
  (let [ver (str (or (registro-get registro :xp-progress-version "xp-progress-version")
                     ""))]
    (cond
      (= ver progress-version)
      (let [nivel (max 1 (min nivel-maximo
                              (long (or (registro-get registro :nivel "nivel" :level "level")
                                        1))))
            residual (if (>= nivel nivel-maximo)
                       0
                       (max 0 (long (or (registro-get registro
                                                      :xp-desde-nivel "xp-desde-nivel"
                                                      :xp "xp")
                                        0))))
            span (level-span-simple nivel)
            residual' (if (or (>= nivel nivel-maximo) (zero? span))
                        0
                        (min (dec span) residual))
            xp-total (if (>= nivel nivel-maximo)
                       (total-xp-for-level-simple nivel-maximo)
                       (+ (total-xp-for-level-simple nivel) residual'))]
        (assoc registro
               :nivel nivel
               :xp-total xp-total
               :xp-desde-nivel residual'
               :xp residual'
               :xp-progress-version progress-version
               :growth-rate simple-growth-rate))

      (= ver progress-version-v1)
      (let [nivel (max 1 (min nivel-maximo
                              (long (or (registro-get registro :nivel "nivel" :level "level")
                                        1))))
            frac (progress-frac-v1 registro)
            span (level-span-simple nivel)
            residual (if (or (>= nivel nivel-maximo) (zero? span))
                       0
                       (long (Math/floor (* frac (double span)))))
            xp-total (if (>= nivel nivel-maximo)
                       (total-xp-for-level-simple nivel-maximo)
                       (+ (total-xp-for-level-simple nivel) residual))
            rate-v1 (registro-get registro :growth-rate "growth-rate")]
        (-> registro
            (assoc :nivel nivel
                   :xp-total xp-total
                   :xp-desde-nivel residual
                   :xp residual
                   :xp-progress-version progress-version
                   :growth-rate simple-growth-rate)
            (cond-> rate-v1 (assoc :growth-rate-v1 rate-v1))))

      :else
      (if-not (xp-known? registro)
        registro
        (let [nivel (max 1 (min nivel-maximo
                                (long (or (registro-get registro :nivel "nivel" :level "level")
                                          1))))
              ;; Sem versão + XP total é ambíguo; não reinterpretar como flat-9.
              total? (registro-has? registro :xp-total "xp-total")
              legacy-residual (legacy-residual-xp registro)
              _ (when total?
                  (throw (ex-info "XP sem versao mas com total acumulado: requer auditoria"
                                  {:code :growth-v2-ambiguous-unversioned-total
                                   :nivel nivel})))
              _ (when (>= legacy-residual legacy-xp-por-nivel)
                  (throw (ex-info "Residual legado fora do intervalo 0..8: requer auditoria"
                                  {:code :growth-v2-legacy-residual-overflow
                                   :nivel nivel :residual legacy-residual})))
              span (level-span-simple nivel)
              residual (if (>= nivel nivel-maximo)
                         0
                         ;; Aritmética inteira evita o arredondamento de dois floors.
                         (quot (* legacy-residual span) legacy-xp-por-nivel))
              xp-total (+ (total-xp-for-level-simple nivel) residual)]
          (assoc registro
                 :nivel nivel
                 :xp-total xp-total
                 :xp-desde-nivel residual
                 :xp residual
                 :xp-progress-version progress-version
                 :growth-rate simple-growth-rate
                 :xp-legacy-residual legacy-residual
                 :xp-legacy-nivel (long (or (registro-get registro
                                                         :xp-legacy-nivel
                                                         "xp-legacy-nivel")
                                           nivel))))))))

(defn ensure-growth-v2
  "Converte apenas quando o XP é conhecido; sem XP mantém desconhecido."
  [registro]
  (convert-to-growth-v2 registro))

(defn initialize-unknown-for-first-grant
  "Política explícita: primeiro grant POSITIVO inicializa XP desconhecido no
  início do nível já persistido, antes de aplicar o novo ganho.
  Não se aplica em leitura, evolução ou grant zero."
  [registro]
  (if (xp-known? registro)
    (convert-to-growth-v2 registro)
    (let [nivel (max 1 (min nivel-maximo
                            (long (or (registro-get registro :nivel "nivel" :level "level") 1))))]
      (assoc registro
             :nivel nivel
             :xp-total (total-xp-for-level-simple nivel)
             :xp-desde-nivel 0
             :xp 0
             :xp-progress-version progress-version
             :growth-rate simple-growth-rate))))



(defn after-evolution
  "Preserva nível e residual growth-v2; nunca reduz nível."
  [registro]
  (if-not (xp-known? registro)
    registro
    (let [r (ensure-growth-v2 registro)
          nivel (long (:nivel r 1))
          residual (long (:xp-desde-nivel r 0))
          span (level-span-simple nivel)
          residual' (if (or (>= nivel nivel-maximo) (zero? span))
                      0
                      (min residual (max 0 (dec span))))
          xp-total (if (>= nivel nivel-maximo)
                     (total-xp-for-level-simple nivel-maximo)
                     (+ (total-xp-for-level-simple nivel) residual'))]
      (assoc r
             :growth-rate simple-growth-rate
             :nivel nivel
             :xp-total xp-total
             :xp-desde-nivel residual'
             :xp residual'
             :xp-progress-version progress-version))))

(defn grant-pokemon-xp
  "Concede XP na unidade growth-v2 (residual no nível).

  Consome `xp-necessario` do nível atual, preserva excedente e continua
  subindo enquanto houver XP. No nível 100, XP excedente é descartado.

  Retorna {:registro :subidas :nivel-final :xp-ganho :xp-total-antes :xp-total-depois}."
  [registro quantidade]
  (let [ganho (max 0 (long quantidade))
        ;; Apenas um ganho positivo pode inicializar XP antes desconhecido.
        r0 (if (pos? ganho)
             (initialize-unknown-for-first-grant registro)
             (ensure-growth-v2 registro))
        nivel0 (long (or (:nivel r0) (:nivel registro) 1))
        antes (long (or (:xp-total r0) 0))]
    (cond
      (>= nivel0 nivel-maximo)
      (let [xp-total (total-xp-for-level-simple nivel-maximo)]
        {:registro (assoc r0
                          :growth-rate simple-growth-rate
                          :xp-progress-version progress-version
                          :xp-total xp-total
                          :xp-desde-nivel 0
                          :xp 0
                          :nivel nivel-maximo)
         :subidas 0
         :nivel-final nivel-maximo
         :xp-ganho 0
         :xp-total-antes antes
         :xp-total-depois xp-total
         :growth-rate simple-growth-rate})

      (zero? ganho)
      {:registro r0
       :subidas 0
       :nivel-final nivel0
       :xp-ganho 0
       :xp-total-antes antes
       :xp-total-depois antes
       :growth-rate (when (xp-known? r0) simple-growth-rate)}

      :else
      (loop [nivel nivel0
             residual (+ (long (:xp-desde-nivel r0 0)) ganho)
             subidas 0]
        (let [span (level-span-simple nivel)]
          (cond
            (>= nivel nivel-maximo)
            (let [xp-total (total-xp-for-level-simple nivel-maximo)]
              {:registro (assoc r0
                                :growth-rate simple-growth-rate
                                :nivel nivel-maximo
                                :xp-total xp-total
                                :xp-desde-nivel 0
                                :xp 0
                                :xp-progress-version progress-version)
               :subidas subidas
               :nivel-final nivel-maximo
               :xp-ganho (- xp-total antes)
               :xp-total-antes antes
               :xp-total-depois xp-total
               :growth-rate simple-growth-rate})

            (and (pos? span) (>= residual span))
            (recur (inc nivel) (- residual span) (inc subidas))

            :else
            (let [xp-total (+ (total-xp-for-level-simple nivel) residual)]
              {:registro (assoc r0
                                :growth-rate simple-growth-rate
                                :nivel nivel
                                :xp-total xp-total
                                :xp-desde-nivel residual
                                :xp residual
                                :xp-progress-version progress-version)
               :subidas subidas
               :nivel-final nivel
               :xp-ganho (- xp-total antes)
               :xp-total-antes antes
               :xp-total-depois xp-total
               :growth-rate simple-growth-rate})))))))

(defn pokemon-progress
  "Visão de progresso no nível atual (barra WhatsApp).
  Não inventa XP ausente. Cálculo in-memory — sem side-effect de persistência."
  [registro]
  (let [has-any? (or (contains? registro :xp-progress-version)
                     (contains? registro "xp-progress-version")
                     (contains? registro :xp-total)
                     (contains? registro "xp-total")
                     (contains? registro :xp-desde-nivel)
                     (contains? registro "xp-desde-nivel")
                     (contains? registro :xp)
                     (contains? registro "xp")
                     (contains? registro :vitorias-desde-nivel)
                     (contains? registro "vitorias-desde-nivel"))]
    (if-not has-any?
      {:conhecido? false
       :nivel (long (get registro :nivel (get registro "nivel" 1)))
       :nivel-maximo? (>= (long (get registro :nivel (get registro "nivel" 1)))
                          nivel-maximo)
       :xp-nivel-atual nil
       :xp-proximo-nivel nil
       :progresso-percentual nil
       :xp-total nil
       :growth-rate nil
       :xp-progress-version nil}
      (let [r (ensure-growth-v2 registro)
            nivel (long (:nivel r))
            residual (long (:xp-desde-nivel r 0))
            span (level-span-simple nivel)]
        (if (>= nivel nivel-maximo)
          {:conhecido? true
           :nivel nivel
           :nivel-maximo? true
           :xp-nivel-atual 0
           :xp-proximo-nivel nil
           :progresso-percentual nil
           :xp-total (long (:xp-total r))
           :growth-rate simple-growth-rate
           :xp-progress-version progress-version}
          {:conhecido? true
           :nivel nivel
           :nivel-maximo? false
           :xp-nivel-atual residual
           :xp-proximo-nivel span
           :progresso-percentual (if (pos? span)
                                   (/ (double residual) (double span))
                                   0.0)
           :xp-total (long (:xp-total r))
           :growth-rate simple-growth-rate
           :xp-progress-version progress-version})))))
