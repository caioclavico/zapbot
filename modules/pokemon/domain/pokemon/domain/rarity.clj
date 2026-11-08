(ns pokemon.domain.rarity
  "Classificação canônica de raridade baseada em evidências oficiais (PokéAPI).

  Catálogo local versionado: `data/official_rarity_catalog.edn` — sem HTTP em runtime.

  Políticas:
  - `:legado` — heurística atual (poder × taxa via `capture/classificar-raridade-legado`)
  - `:oficial-a` — âncora capture_rate + gates especiais (UB/pseudo/flags)
  - `:oficial-b` — A + ajuste leve de disponibilidade quando a cobertura de encontros é confiável

  Ativação global exige aprovação. Default dinâmico `:legado` (NO-GO automático)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.errors :as err]))

(def catalog-resource "pokemon/domain/data/official_rarity_catalog.edn")

(def raridades-validas
  #{"comum" "incomum" "raro" "epico" "lendario" "mitico"})

(def ^:private ordem
  {"comum" 0 "incomum" 1 "raro" 2 "epico" 3 "lendario" 4 "mitico" 5})

(defonce ^:private catalog-atom (atom nil))

;; Default seguro: legado. Nunca ativar oficial sem GO explícito.
(def ^:dynamic *politica* :legado)

(defn- parse-catalog
  [raw]
  (let [data (cond
               (map? raw) raw
               (string? raw) (edn/read-string raw)
               :else (throw (err/error :rarity_catalog_invalid {:reason :type})))
        species (mapv (fn [row]
                        (let [row (if (map? row) row {})]
                          (-> row
                              (update :species str)
                              (update :taxa-captura #(when % (long %)))
                              (update :bst-raw #(long (or % 0)))
                              (update :evo-stage #(long (or % 1)))
                              (update :chain-max-stage #(long (or % 1)))
                              (update :encounter-locations #(long (or % 0)))
                              (update :encounter-versions #(long (or % 0)))
                              (update :encounter-rows #(long (or % 0)))
                              (assoc :lendario-api? (boolean (or (:lendario-api? row) (:lendario-api row)))
                                     :mitico-api? (boolean (or (:mitico-api? row) (:mitico-api row)))
                                     :paradox-api? (boolean (or (:paradox-api? row) (:paradox-api row)))
                                     :ultra-beast? (boolean (or (:ultra-beast? row) (:ultra-beast row)))
                                     :bebe-api? (boolean (or (:bebe-api? row) (:bebe-api row)))
                                     :dados-incompletos? (boolean (or (:dados-incompletos? row)
                                                                      (:dados-incompletos row)))
                                     :has-encounters? (boolean (or (:has-encounters? row)
                                                                   (:has-encounters row)))
                                     :evo-methods (vec (or (:evo-methods row) []))))))
                      (or (:species data) []))]
    {:version (str (:version data))
     :source (str (:source data))
     :source-docs (str (:source-docs data))
     :built-at (str (:built-at data))
     :total-pokemons (long (or (:total-pokemons data) (count species)))
     :incomplete-count (long (or (:incomplete-count data) 0))
     :notes (str (:notes data))
     :species species
     :by-species (into {} (map (fn [r] [(:species r) r]) species))}))

(defn load-catalog!
  "Carrega (ou recarrega) o catálogo oficial."
  ([] (load-catalog! nil))
  ([source]
   (let [raw (cond
               (map? source) source
               (string? source) (slurp source)
               (instance? java.net.URL source) (slurp source)
               :else
               (let [res (io/resource catalog-resource)]
                 (when-not res
                   (throw (err/error :rarity_catalog_missing
                                     {:resource catalog-resource})))
                 (slurp res)))
         parsed (parse-catalog raw)]
     (reset! catalog-atom parsed)
     parsed)))

(defn catalog
  []
  (or @catalog-atom (load-catalog!)))

(defn reset-catalog-for-tests!
  [parsed-or-nil]
  (if (nil? parsed-or-nil)
    (do (reset! catalog-atom nil) (catalog))
    (reset! catalog-atom (parse-catalog parsed-or-nil))))

(defn- slug
  [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn lookup-species
  "Busca evidências oficiais por slug de espécie."
  ([species-or-row] (lookup-species (catalog) species-or-row))
  ([cat species-or-row]
   (let [sp (cond
              (string? species-or-row) (slug species-or-row)
              (map? species-or-row)
              (slug (or (:species species-or-row)
                        (get species-or-row "species")
                        (:nome species-or-row)
                        (get species-or-row "nome")))
              :else nil)]
     (when (and sp (seq sp))
       (get (:by-species cat) sp)))))

(defn- bump
  "Sobe no máximo até épico (não atravessa lendário/mítico)."
  [r n]
  (let [i (min 3 (+ (get ordem r 0) (long n)))]
    (some (fn [[k v]] (when (= v i) k)) ordem)))

(defn- demote
  [r n]
  (let [i (max 0 (- (get ordem r 0) (long n)))]
    (some (fn [[k v]] (when (= v i) k)) ordem)))

(defn- base-from-capture
  "Âncora oficial capture_rate → faixa Odisseu.
   Não é a chance de captura do bot — só evidência de dificuldade oficial."
  [taxa]
  (let [t (long taxa)]
    (cond
      (<= t 10) "epico"
      (<= t 45) "raro"
      (<= t 120) "incomum"
      :else "comum")))

(defn- trade-methods?
  [row]
  (boolean (some #{"trade" "trade-held"} (or (:evo-methods row) []))))

(defn- pseudo?
  "Linha pseudo-lendária: BST≥600, estágio≥2, sem flag lendário/mítico."
  [row]
  (and (>= (long (:bst-raw row 0)) 600)
       (>= (long (:evo-stage row 1)) 2)
       (not (:lendario-api? row))
       (not (:mitico-api? row))))

(defn- classify-oficial
  "Classifica a partir da linha de evidências. Retorna {:raridade :motivo :perfil :incerto?}."
  [row perfil]
  (cond
    (:mitico-api? row)
    {:raridade "mitico" :motivo :flag-mythical :perfil perfil :incerto? false}

    (or (:lendario-api? row) (:paradox-api? row))
    {:raridade "lendario" :motivo :flag-legendary :perfil perfil :incerto? false}

    (:ultra-beast? row)
    {:raridade "epico" :motivo :ultra-beast :perfil perfil :incerto? false}

    (pseudo? row)
    {:raridade "epico" :motivo :pseudo-legendary :perfil perfil :incerto? false}

    :else
    (let [taxa (:taxa-captura row)
          incerto? (boolean (:dados-incompletos? row))
          base (if taxa
                 (base-from-capture taxa)
                 ;; Sem taxa: preservar incerteza — não inventar comum.
                 nil)]
      (if (nil? base)
        {:raridade nil :motivo :dados-insuficientes :perfil perfil :incerto? true}
        (let [r0 (if (and (trade-methods? row)
                          (< (get ordem base 0) (get ordem "raro"))
                          (<= (long taxa) 90))
                   "raro"
                   base)
              r1 (if (and (= perfil :oficial-b)
                          (>= (long (:encounter-versions row 0)) 5)
                          (>= (long (:encounter-rows row 0)) 10)
                          (not incerto?))
                   (cond
                     (and (<= (long (:encounter-locations row 0)) 5)
                          (< (get ordem r0) (get ordem "raro")))
                     (bump r0 1)

                     (and (>= (long (:encounter-locations row 0)) 80)
                          (>= (long taxa) 45)
                          (>= (get ordem r0) (get ordem "raro")))
                     (demote r0 1)

                     :else r0)
                   r0)
              motivo (cond
                       (not= r1 base) :capture+availability
                       (not= r0 base) :trade-floor
                       :else :capture)]
          {:raridade r1
           :motivo motivo
           :perfil perfil
           :incerto? incerto?})))))

(defn classificar-detalhado
  "Classifica com metadados. `perfil` = :oficial-a | :oficial-b.
   Se a espécie não estiver no catálogo, {:raridade nil :motivo :especie-ausente}."
  ([pokemon-or-species] (classificar-detalhado pokemon-or-species :oficial-a))
  ([pokemon-or-species perfil]
   (let [perfil (keyword perfil)
         row (lookup-species pokemon-or-species)]
     (if-not row
       {:raridade nil :motivo :especie-ausente :perfil perfil :incerto? true}
       (classify-oficial row perfil)))))

(defn classificar
  "Slug canônico ou nil se evidência insuficiente / espécie ausente."
  ([pokemon-or-species] (classificar pokemon-or-species :oficial-a))
  ([pokemon-or-species perfil]
   (:raridade (classificar-detalhado pokemon-or-species perfil))))

(defn classificar-ou-fallback
  "Usa oficial; se nil, chama `fallback-fn` (ex.: classificador legado)."
  [pokemon perfil fallback-fn]
  (or (classificar pokemon perfil)
      (when fallback-fn (fallback-fn pokemon))))

(defn audit-distribution
  "Frequências por raridade para um perfil sobre o catálogo completo."
  ([] (audit-distribution :oficial-a))
  ([perfil]
   (let [cat (catalog)
         rows (mapv (fn [row]
                      (let [r (classify-oficial row (keyword perfil))]
                        (assoc r :species (:species row)
                               :taxa-captura (:taxa-captura row)
                               :bst-raw (:bst-raw row))))
                    (:species cat))
         by (frequencies (map :raridade rows))
         incert (count (filter :incerto? rows))
         ausentes (count (filter #(nil? (:raridade %)) rows))]
     {:version (:version cat)
      :perfil (keyword perfil)
      :total (count rows)
      :por-raridade by
      :incertos incert
      :ausentes ausentes
      :elegiveis-raid (reduce + 0 (map #(long (get by % 0))
                                       ["raro" "epico" "lendario" "mitico"]))})))

(defn comparar-com-legado
  "Diff espécie a espécie entre oficial e `legado-fn` (mapa → raridade)."
  [perfil legado-fn species-maps]
  (let [pairs (for [m species-maps
                    :let [sp (or (:species m) (get m "species"))
                          ofic (classificar m perfil)
                          leg (legado-fn m)]
                    :when (and ofic leg (not= ofic leg))]
                {:species sp :oficial ofic :legado leg})]
    {:perfil (keyword perfil)
     :reclassificados (count pairs)
     :por-transicao (frequencies (map #(str (:legado %) "->" (:oficial %)) pairs))
     :amostra (vec (take 40 (sort-by :species pairs)))}))

(defn politica-ativa
  []
  *politica*)

(defn with-politica
  "Executa `f` sob política dada (:legado | :oficial-a | :oficial-b)."
  [politica f]
  (binding [*politica* (keyword politica)]
    (f)))
