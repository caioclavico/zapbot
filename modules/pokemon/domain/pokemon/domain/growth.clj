(ns pokemon.domain.growth
  "Curvas oficiais de XP dos Pokémon (PokéAPI growth-rate).

  Tabelas locais determinísticas — sem HTTP em runtime.
  Semântica canônica pós-growth-v1: XP **acumulado** (`:xp-total`) + nível derivado.
  Legado pré-v1: `:xp-desde-nivel` era **residual** com limiar fixo 9."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.species-form-aliases :as form-aliases]))

(def progress-version "growth-v1")
(def nivel-maximo 100)
(def legacy-xp-por-nivel 9)

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

(defn convert-legacy-residual
  "Converte registro legado (residual + limiar 9) → growth-v1 preservando nível.

  Fração residual/9 é aplicada ao intervalo oficial do mesmo nível.
  Idempotente se `:xp-progress-version` já for growth-v1."
  [registro]
  (let [ver (str (or (get registro :xp-progress-version)
                     (get registro "xp-progress-version")
                     ""))]
    (if (= ver progress-version)
      registro
      (let [species (or (get registro :species) (get registro "species")
                        (get registro :nome) (get registro "nome"))
            rate (growth-rate-for-species species)
            nivel (max 1 (min nivel-maximo
                              (long (get registro :nivel
                                         (get registro "nivel"
                                              (get registro :level
                                                   (get registro "level" 1)))))))
            legacy-residual (max 0 (long (get registro :xp-desde-nivel
                                              (get registro "xp-desde-nivel"
                                                   (or (get registro :xp)
                                                       (get registro "xp")
                                                       0)))))
            span (level-span rate nivel)
            frac (if (pos? legacy-xp-por-nivel)
                   (min 1.0 (/ (double legacy-residual)
                               (double legacy-xp-por-nivel)))
                   0.0)
            mapped (long (Math/floor (* frac (double span))))
            base (total-xp-for-level rate nivel)
            xp-total (if (>= nivel nivel-maximo)
                       (total-xp-for-level rate nivel-maximo)
                       (+ base mapped))
            residual (residual-in-level rate nivel xp-total)]
        (-> registro
            (assoc :nivel nivel
                   :xp-total xp-total
                   :xp-desde-nivel residual
                   :xp residual
                   :xp-progress-version progress-version
                   :growth-rate rate
                   :xp-legacy-residual legacy-residual))))))

(defn ensure-growth-v1
  "Garante campos growth-v1; registros novos com só nível recebem total da curva."
  [registro]
  (let [ver (str (or (get registro :xp-progress-version)
                     (get registro "xp-progress-version")
                     ""))
        has-total? (or (contains? registro :xp-total)
                       (contains? registro "xp-total"))]
    (cond
      (= ver progress-version)
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
               :xp-progress-version progress-version
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
               :xp-progress-version progress-version)))))

(defn after-evolution
  "Preserva xp-total e nível; nunca reduz nível. Ajusta total se a nova curva
  exigir mais XP acumulado para o nível atual."
  [registro]
  (let [r (ensure-growth-v1 registro)
        rate (growth-rate-for-species (or (:species r) (:nome r)))
        nivel (long (:nivel r 1))
        min-total (total-xp-for-level rate nivel)
        xp-total (max (long (:xp-total r 0)) min-total)
        ;; Se o total antigo implica nível maior na curva nova, sobe (nunca desce).
        nivel-from-xp (pokemon-level-from-total-xp rate xp-total)
        nivel' (max nivel nivel-from-xp)
        xp-total' (max xp-total (total-xp-for-level rate nivel'))
        residual (residual-in-level rate nivel' xp-total')]
    (assoc r
           :growth-rate rate
           :nivel nivel'
           :xp-total xp-total'
           :xp-desde-nivel residual
           :xp residual
           :xp-progress-version progress-version)))

(defn grant-pokemon-xp
  "Concede XP acumulado oficial. Retorna
  {:registro :subidas :nivel-final :xp-ganho :xp-total-antes :xp-total-depois}.

  `quantidade` já deve estar na unidade oficial (após escala de balanceamento).
  No nível 100, XP excedente é descartado (total fixo no teto da curva)."
  [registro quantidade]
  (let [r0 (ensure-growth-v1 registro)
        rate (or (:growth-rate r0)
                 (growth-rate-for-species (or (:species r0) (:nome r0))))
        nivel0 (long (:nivel r0 1))
        cap (total-xp-for-level rate nivel-maximo)
        antes (long (:xp-total r0 0))
        ganho (max 0 (long quantidade))
        depois (if (>= nivel0 nivel-maximo)
                 cap
                 (min cap (+ antes ganho)))
        nivel1 (pokemon-level-from-total-xp rate depois)
        subidas (max 0 (- nivel1 nivel0))
        residual (residual-in-level rate nivel1 depois)]
    {:registro (assoc r0
                      :growth-rate rate
                      :nivel nivel1
                      :xp-total depois
                      :xp-desde-nivel residual
                      :xp residual
                      :xp-progress-version progress-version)
     :subidas subidas
     :nivel-final nivel1
     :xp-ganho (- depois antes)
     :xp-total-antes antes
     :xp-total-depois depois
     :growth-rate rate}))

(defn pokemon-progress
  "Visão de progresso no nível atual (barra WhatsApp)."
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
       :growth-rate nil}
      (let [r (ensure-growth-v1 registro)
            rate (:growth-rate r)
            nivel (long (:nivel r))
            residual (long (:xp-desde-nivel r 0))
            span (level-span rate nivel)]
        (if (>= nivel nivel-maximo)
          {:conhecido? true
           :nivel nivel
           :nivel-maximo? true
           :xp-nivel-atual 0
           :xp-proximo-nivel nil
           :progresso-percentual nil
           :xp-total (long (:xp-total r))
           :growth-rate rate}
          {:conhecido? true
           :nivel nivel
           :nivel-maximo? false
           :xp-nivel-atual residual
           :xp-proximo-nivel span
           :progresso-percentual (if (pos? span)
                                   (/ (double residual) (double span))
                                   0.0)
           :xp-total (long (:xp-total r))
           :growth-rate rate})))))
