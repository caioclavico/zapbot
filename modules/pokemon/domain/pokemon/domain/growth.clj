(ns pokemon.domain.growth
  "Progressão canônica flat-9-v2 + conversão segura a partir de growth-v1/legado.

  Canônico (`flat-9-v2`):
  - `:xp-desde-nivel` / `:xp` = residual 0..8 (0 no nível 100)
  - `:xp-total` sintético = `(nivel-1)*9 + residual` (compatibilidade; NÃO é XP PokéAPI)
  - 9 XP por nível, independente de espécie/nível
  - Conversão **nunca** sobe nível nem concede atributos

  Tabelas PokéAPI permanecem só para migrar fração growth-v1 → flat-9."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.species-form-aliases :as form-aliases]))

(def ^{:doc "Versão canônica de progressão (PR #86)."}
  flat9-progress-version "flat-9-v2")

(def ^{:doc "Alias canônico."}
  progress-version flat9-progress-version)

(def ^{:doc "Versão anterior (curvas oficiais); só leitura/migração."}
  growth-v1-progress-version "growth-v1")

(def ^{:doc "growth-v2 simples (PR #84); se aparecer, migra por fração do span."}
  growth-v2-progress-version "growth-v2")

(def nivel-maximo 100)
(def flat9-xp-por-nivel 9)
(def legacy-xp-por-nivel flat9-xp-por-nivel)

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
  "Resolve curva PokéAPI pela espécie (só migração v1). Desconhecida → :medium-fast."
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
  "XP acumulado mínimo na curva oficial para estar no nível `nivel` (migração v1)."
  [rate nivel]
  (let [n (long nivel)]
    (cond
      (< n 1) 0
      (> n nivel-maximo) (total-xp-for-level rate nivel-maximo)
      :else (long (nth (totals-vec rate) (dec n))))))

(defn level-span
  "Span oficial do nível (migração v1)."
  [rate nivel]
  (let [n (long nivel)]
    (if (>= n nivel-maximo)
      0
      (- (total-xp-for-level rate (inc n))
         (total-xp-for-level rate n)))))

(defn residual-in-level
  "Residual oficial dentro do nível (migração v1)."
  [rate nivel xp-total]
  (let [n (max 1 (min nivel-maximo (long nivel)))
        base (total-xp-for-level rate n)
        xp (max 0 (long xp-total))]
    (if (>= n nivel-maximo)
      0
      (max 0 (- xp base)))))

(defn pokemon-level-from-total-xp
  "Nível a partir de XP acumulado oficial (só migração/auditoria v1)."
  [rate xp-total]
  (let [xp (max 0 (long xp-total))
        totals (totals-vec rate)]
    (loop [lvl 1]
      (if (>= lvl nivel-maximo)
        nivel-maximo
        (let [next-need (long (nth totals lvl))]
          (if (< xp next-need)
            lvl
            (recur (inc lvl))))))))

(defn flat9-synthetic-total
  "Total sintético canônico: (nivel-1)*9 + residual (0 no L100)."
  [nivel residual]
  (let [n (max 1 (min nivel-maximo (long nivel)))
        r (if (>= n nivel-maximo) 0 (max 0 (min (dec flat9-xp-por-nivel) (long residual))))]
    (+ (* (dec n) flat9-xp-por-nivel) r)))

(defn- registro-get
  [registro & ks]
  (some (fn [k]
          (when (contains? registro k)
            (get registro k)))
        ks))

(defn- registro-has?
  [registro & ks]
  (boolean (some #(contains? registro %) ks)))

(def ^{:doc "Vitórias legadas × este fator = residual flat-9."}
  legacy-xp-por-vitoria 3)

(defn legacy-residual-xp
  "Precedência: `:xp-desde-nivel` → `:xp` → `vitorias-desde-nivel × 3` → 0."
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

(defn- progress-ver
  [registro]
  (str (or (registro-get registro :xp-progress-version "xp-progress-version") "")))

(defn- persist-nivel
  [registro]
  (max 1 (min nivel-maximo
              (long (or (registro-get registro :nivel "nivel" :level "level") 1)))))

(defn ambiguous-legacy?
  "Sem versão e residual ≥ 9: origem ambígua (incidente #85). Não auto-converter."
  [registro]
  (let [ver (progress-ver registro)]
    (and (or (nil? ver) (= ver "") (= ver "nil"))
         (>= (legacy-residual-xp registro) flat9-xp-por-nivel))))

(defn proven-flat9-legacy?
  "Sem versão e residual 0..8: flat-9 legado comprovado."
  [registro]
  (let [ver (progress-ver registro)
        r (legacy-residual-xp registro)]
    (and (or (nil? ver) (= ver "") (= ver "nil"))
         (>= r 0)
         (< r flat9-xp-por-nivel))))

(defn- assoc-flat9
  "Associa campos canônicos flat-9-v2 sem alterar espécie/stats."
  [registro nivel residual & {:keys [legacy-residual legacy-nivel growth-rate]}]
  (let [n (max 1 (min nivel-maximo (long nivel)))
        resid (if (>= n nivel-maximo)
                0
                (max 0 (min (dec flat9-xp-por-nivel) (long residual))))
        total (flat9-synthetic-total n resid)
        base (-> registro
                 (assoc :nivel n
                        :xp-desde-nivel resid
                        :xp resid
                        :xp-total total
                        :xp-progress-version flat9-progress-version)
                 (dissoc :vitorias-desde-nivel "vitorias-desde-nivel"))]
    (cond-> base
      (some? growth-rate) (assoc :growth-rate growth-rate)
      (some? legacy-residual) (assoc :xp-legacy-residual (long legacy-residual))
      (some? legacy-nivel) (assoc :xp-legacy-nivel (long legacy-nivel)))))

(defn convert-growth-v1-to-flat9
  "Preserva nível. residual_novo = floor((resid_v1 / span) * 9) limitado a 0..8.
  Nunca sobe nível."
  [registro]
  (let [species (or (registro-get registro :species "species" :nome "nome"))
        rate (or (when-let [g (registro-get registro :growth-rate "growth-rate")]
                   (keyword g))
                 (growth-rate-for-species species))
        nivel (persist-nivel registro)
        xp-total (long (or (registro-get registro :xp-total "xp-total")
                           (total-xp-for-level rate nivel)))
        resid-v1 (residual-in-level rate nivel xp-total)
        span (level-span rate nivel)
        resid9 (if (or (>= nivel nivel-maximo) (zero? span))
                 0
                 (min (dec flat9-xp-por-nivel)
                      (long (Math/floor (* (/ (double resid-v1) (double span))
                                           (double flat9-xp-por-nivel))))))]
    (assoc-flat9 registro nivel resid9 :growth-rate rate)))

(defn convert-growth-v2-to-flat9
  "Se existir growth-v2 (span simples): mesma regra de fração; preserva nível."
  [registro]
  ;; Span simple histórico era ~10+nível em alguns ensaios; se :xp-total+residual
  ;; presentes, usa residual/span implícito via xp-desde-nivel e assume span=max(resid+1,9)
  ;; Preferência: se há xp-desde-nivel e nível, trata residual como fração de um span
  ;; derivado de xp-total se possível; senão residual já 0..8 → preserva.
  (let [nivel (persist-nivel registro)
        resid (long (or (registro-get registro :xp-desde-nivel "xp-desde-nivel")
                        (registro-get registro :xp "xp")
                        0))
        ;; Sem span v2 canônico no tip live: se residual já <9, preserva;
        ;; se maior, mapeia mod-safe via floor(resid / max(resid,9) * 9) → 0..8
        resid9 (cond
                 (>= nivel nivel-maximo) 0
                 (< resid flat9-xp-por-nivel) resid
                 :else (min (dec flat9-xp-por-nivel)
                            (long (Math/floor (* 9.0 (/ (double resid)
                                                        (double (max resid flat9-xp-por-nivel))))))))]
    (assoc-flat9 registro nivel resid9
                 :legacy-residual (when (>= resid flat9-xp-por-nivel) resid)
                 :legacy-nivel nivel)))

(defn convert-proven-flat9-legacy
  "Legado 0..8 sem versão → flat-9-v2 preservando residual."
  [registro]
  (let [nivel (persist-nivel registro)
        resid (if (>= nivel nivel-maximo) 0 (legacy-residual-xp registro))]
    (assoc-flat9 registro nivel resid
                 :legacy-residual resid
                 :legacy-nivel nivel)))

(defn convert-legacy-residual
  "Compat: legado → growth-v1 com hotfix (nunca sobe nível).
  Preferir `ensure-flat9-v2` para progressão nova."
  [registro]
  (let [ver (progress-ver registro)]
    (if (= ver growth-v1-progress-version)
      registro
      (let [species (or (registro-get registro :species "species" :nome "nome"))
            rate (growth-rate-for-species species)
            nivel0 (persist-nivel registro)
            legacy-residual (legacy-residual-xp registro)
            nivel nivel0
            rem (if (>= nivel0 nivel-maximo)
                  0
                  (long (mod legacy-residual legacy-xp-por-nivel)))
            span (level-span rate nivel)
            frac (if (pos? legacy-xp-por-nivel)
                   (/ (double rem) (double legacy-xp-por-nivel))
                   0.0)
            mapped (if (or (>= nivel nivel-maximo) (zero? span))
                     0
                     (long (Math/floor (* frac (double span)))))
            base (total-xp-for-level rate nivel)
            xp-total (if (>= nivel nivel-maximo)
                       (total-xp-for-level rate nivel-maximo)
                       (+ base mapped))
            residual (residual-in-level rate nivel xp-total)
            legacy-nivel (or (when-let [v (registro-get registro
                                                        :xp-legacy-nivel
                                                        "xp-legacy-nivel")]
                               (long v))
                             nivel0)]
        (-> registro
            (assoc :nivel nivel
                   :xp-total xp-total
                   :xp-desde-nivel residual
                   :xp residual
                   :xp-progress-version growth-v1-progress-version
                   :growth-rate rate
                   :xp-legacy-residual legacy-residual
                   :xp-legacy-nivel legacy-nivel))))))

(defn ensure-growth-v1
  "Mantido para migração/auditoria. Preferir ensure-flat9-v2."
  [registro]
  (let [ver (progress-ver registro)
        has-total? (registro-has? registro :xp-total "xp-total")]
    (cond
      (= ver growth-v1-progress-version)
      (let [species (or (registro-get registro :species "species" :nome "nome"))
            rate (or (when-let [g (registro-get registro :growth-rate "growth-rate")]
                       (keyword g))
                     (growth-rate-for-species species))
            nivel (persist-nivel registro)
            xp-total (long (or (registro-get registro :xp-total "xp-total")
                               (total-xp-for-level rate nivel)))
            residual (residual-in-level rate nivel xp-total)]
        (assoc registro
               :growth-rate rate
               :xp-total xp-total
               :xp-desde-nivel residual
               :xp residual
               :xp-progress-version growth-v1-progress-version
               :nivel nivel))

      (and (not has-total?)
           (or (registro-has? registro :xp-desde-nivel "xp-desde-nivel")
               (registro-has? registro :xp "xp")
               (registro-has? registro :vitorias-desde-nivel "vitorias-desde-nivel")))
      (convert-legacy-residual registro)

      :else
      (let [species (or (registro-get registro :species "species" :nome "nome"))
            rate (growth-rate-for-species species)
            nivel (persist-nivel registro)
            xp-total (total-xp-for-level rate nivel)]
        (assoc registro
               :growth-rate rate
               :nivel nivel
               :xp-total xp-total
               :xp-desde-nivel 0
               :xp 0
               :xp-progress-version growth-v1-progress-version)))))

(defn ensure-flat9-v2
  "Garante flat-9-v2. **Nunca** sobe nível.

  - Já flat-9-v2: normaliza residual.
  - growth-v1: fração do span → 0..8.
  - growth-v2: fração/preservação segura.
  - Legado 0..8: preserva residual.
  - Ambíguo (unversioned residual≥9): **não converte** (retorna inalterado).
  - Só nível: residual 0."
  [registro]
  (let [ver (progress-ver registro)]
    (cond
      (= ver flat9-progress-version)
      (let [nivel (persist-nivel registro)
            resid (if (>= nivel nivel-maximo)
                    0
                    (max 0 (min (dec flat9-xp-por-nivel)
                                (long (or (registro-get registro :xp-desde-nivel "xp-desde-nivel")
                                          (registro-get registro :xp "xp")
                                          0)))))]
        (assoc-flat9 registro nivel resid
                     :legacy-residual (registro-get registro :xp-legacy-residual "xp-legacy-residual")
                     :legacy-nivel (registro-get registro :xp-legacy-nivel "xp-legacy-nivel")
                     :growth-rate (when-let [g (registro-get registro :growth-rate "growth-rate")]
                                    (keyword g))))

      (= ver growth-v1-progress-version)
      (convert-growth-v1-to-flat9 registro)

      (= ver growth-v2-progress-version)
      (convert-growth-v2-to-flat9 registro)

      (ambiguous-legacy? registro)
      registro

      (proven-flat9-legacy? registro)
      (convert-proven-flat9-legacy registro)

      :else
      (assoc-flat9 registro (persist-nivel registro) 0))))

(defn ensure-flat9-v2-for-grant
  "Como ensure-flat9-v2, mas ambíguos iniciam residual 0 no nível persistido
  (primeiro ganho; não inventa progresso histórico). Preserva residual bruto
  em :xp-legacy-residual."
  [registro]
  (if (ambiguous-legacy? registro)
    (let [nivel (persist-nivel registro)
          raw (legacy-residual-xp registro)]
      (assoc-flat9 registro nivel 0
                   :legacy-residual raw
                   :legacy-nivel nivel))
    (ensure-flat9-v2 registro)))

(defn after-evolution
  "Preserva residual flat-9 e nível; nunca reduz nível."
  [registro]
  (let [r (ensure-flat9-v2-for-grant registro)
        nivel (long (:nivel r 1))
        resid (long (:xp-desde-nivel r 0))]
    (assoc-flat9 r nivel resid
                 :legacy-residual (:xp-legacy-residual r)
                 :legacy-nivel (:xp-legacy-nivel r)
                 :growth-rate (:growth-rate r))))

(defn grant-pokemon-xp
  "Concede XP flat-9 (pontos). Retorna
  {:registro :subidas :nivel-final :xp-ganho :xp-total-antes :xp-total-depois}.

  Preserva excedente entre níveis. No L100, discarta excedente.
  Conversão prévia nunca conta como subida."
  [registro quantidade]
  (let [r0 (ensure-flat9-v2-for-grant registro)
        nivel0 (long (:nivel r0 1))
        resid0 (long (:xp-desde-nivel r0 0))
        antes (long (:xp-total r0 0))
        ganho (max 0 (long quantidade))]
    (if (or (zero? ganho) (>= nivel0 nivel-maximo))
      (let [r1 (assoc-flat9 r0 nivel-maximo 0
                            :legacy-residual (:xp-legacy-residual r0)
                            :legacy-nivel (:xp-legacy-nivel r0)
                            :growth-rate (:growth-rate r0))
            ;; se já <100 e ganho 0, mantém
            r-final (if (>= nivel0 nivel-maximo) r1 r0)]
        {:registro r-final
         :subidas 0
         :nivel-final (long (:nivel r-final))
         :xp-ganho 0
         :xp-total-antes antes
         :xp-total-depois (long (:xp-total r-final 0))})
      (loop [nivel nivel0
             resid (+ resid0 ganho)
             subidas 0]
        (cond
          (>= nivel nivel-maximo)
          (let [r (assoc-flat9 r0 nivel-maximo 0
                               :legacy-residual (:xp-legacy-residual r0)
                               :legacy-nivel (:xp-legacy-nivel r0)
                               :growth-rate (:growth-rate r0))]
            {:registro r
             :subidas subidas
             :nivel-final nivel-maximo
             :xp-ganho (- (long (:xp-total r)) antes)
             :xp-total-antes antes
             :xp-total-depois (long (:xp-total r))})

          (< resid flat9-xp-por-nivel)
          (let [r (assoc-flat9 r0 nivel resid
                               :legacy-residual (:xp-legacy-residual r0)
                               :legacy-nivel (:xp-legacy-nivel r0)
                               :growth-rate (:growth-rate r0))]
            {:registro r
             :subidas subidas
             :nivel-final nivel
             :xp-ganho (- (long (:xp-total r)) antes)
             :xp-total-antes antes
             :xp-total-depois (long (:xp-total r))})

          :else
          (recur (inc nivel) (- resid flat9-xp-por-nivel) (inc subidas)))))))

(defn pokemon-progress
  "Barra WhatsApp: residual/9. Ambíguos → conhecido? false."
  [registro]
  (let [nivel0 (persist-nivel registro)
        maximo? (>= nivel0 nivel-maximo)]
    (cond
      (ambiguous-legacy? registro)
      {:conhecido? false
       :nivel nivel0
       :nivel-maximo? maximo?
       :xp-nivel-atual nil
       :xp-proximo-nivel nil
       :progresso-percentual nil
       :xp-total nil
       :xp-progress-version nil
       :ambiguous? true}

      (not (or (registro-has? registro :xp-progress-version "xp-progress-version")
               (registro-has? registro :xp-total "xp-total")
               (registro-has? registro :xp-desde-nivel "xp-desde-nivel")
               (registro-has? registro :xp "xp")
               (registro-has? registro :vitorias-desde-nivel "vitorias-desde-nivel")))
      {:conhecido? false
       :nivel nivel0
       :nivel-maximo? maximo?
       :xp-nivel-atual nil
       :xp-proximo-nivel nil
       :progresso-percentual nil
       :xp-total nil
       :xp-progress-version nil}

      :else
      (let [r (ensure-flat9-v2 registro)
            converted? (= (progress-ver r) flat9-progress-version)]
        (if-not converted?
          {:conhecido? false
           :nivel nivel0
           :nivel-maximo? maximo?
           :xp-nivel-atual nil
           :xp-proximo-nivel nil
           :progresso-percentual nil
           :xp-total nil
           :ambiguous? true}
          (let [nivel (long (:nivel r))
                residual (long (:xp-desde-nivel r 0))]
            (if (>= nivel nivel-maximo)
              {:conhecido? true
               :nivel nivel
               :nivel-maximo? true
               :xp-nivel-atual 0
               :xp-proximo-nivel nil
               :progresso-percentual nil
               :xp-total (long (:xp-total r))
               :xp-progress-version flat9-progress-version}
              {:conhecido? true
               :nivel nivel
               :nivel-maximo? false
               :xp-nivel-atual residual
               :xp-proximo-nivel flat9-xp-por-nivel
               :progresso-percentual (/ (double residual) (double flat9-xp-por-nivel))
               :xp-total (long (:xp-total r))
               :xp-progress-version flat9-progress-version})))))))
