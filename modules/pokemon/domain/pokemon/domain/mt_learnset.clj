(ns pokemon.domain.mt-learnset
  "Catálogo versionado de elegibilidade de MT genérica (level-up ofensivo).

  Fonte: PokéAPI (método level-up), filtrado para fisico/especial com poder > 0.
  Sem HTTP em runtime — Node e JVM leem o mesmo artefato versionado.
  Regenerar: `node scripts/generate-mt-learnset.cjs`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]))

(def catalog-version "mt-learnset-v1-2026-11")
(def ^:private catalog-resource "pokemon/domain/data/mt_learnset_v1.edn")
(def ^:private catalog-atom (atom nil))

(defn- load-catalog!
  []
  (let [res (io/resource catalog-resource)]
    (when-not res
      (throw (err/error :mt_learnset_missing {:resource catalog-resource})))
    (edn/read-string (slurp res))))

(defn catalog
  []
  (or @catalog-atom
      (let [c (load-catalog!)]
        (reset! catalog-atom c)
        c)))

(defn reset-catalog-for-tests!
  "Somente testes."
  ([] (reset! catalog-atom nil))
  ([c] (reset! catalog-atom c)))

(defn- slug
  [x]
  (-> (or x "") str/lower-case str/trim
      (str/replace #"\s+" "-")))

(defn species-key
  [registro-or-slug]
  (slug (if (map? registro-or-slug)
          (or (:species registro-or-slug)
              (:nome registro-or-slug)
              (get registro-or-slug "species")
              (get registro-or-slug "nome"))
          registro-or-slug)))

(defn catalog-version*
  []
  (str (or (get (catalog) "version") (:version (catalog)) catalog-version)))

(defn assert-catalog-version!
  [version]
  (let [expected (catalog-version*)]
    (when (and (some? version) (not (str/blank? (str version)))
               (not= (str version) expected))
      (throw (err/error :mt_catalog_version_mismatch
                        {:expected expected :got (str version)})))
    expected))

(defn move-entry
  [move-slug]
  (get-in (catalog) ["moves" (slug move-slug)]))

(defn species-entries
  [species]
  (vec (get-in (catalog) ["species" (species-key species)] [])))

(defn eligible-move-slugs
  "Slugs ofensivos do learnset por nível (≤ nivel do Pokémon)."
  [species nivel]
  (let [n (max 1 (long (or nivel 1)))]
    (->> (species-entries species)
         (filter #(<= (long (or (get % "nivel-min") (get % :nivel-min) 1)) n))
         (map #(or (get % "slug") (get % :slug)))
         (map slug)
         (filter #(move-entry %))
         distinct
         vec)))

(defn golpe-from-catalog
  [move-slug]
  (when-let [e (move-entry move-slug)]
    {:slug (or (get e "slug") move-slug)
     :nome-exibicao (or (get e "nome-exibicao") (get e :nome-exibicao) move-slug)
     :tipo (or (get e "tipo") (get e :tipo))
     :poder (long (or (get e "poder") (get e :poder) 0))
     :classe (keyword (or (get e "classe") (get e :classe) "fisico"))}))

(defn candidates
  "Candidatos de MT genérica: learnset ≤ nível, não conhecidos, preservam STAB no slot."
  [registro slot]
  (let [species (species-key registro)
        nivel (long (or (:nivel registro) 1))
        entries (species-entries species)
        _ (when (empty? entries)
            (throw (err/error :mt_learnset_unavailable
                              {:species species :catalog-version catalog-version})))
        conhecidos (set (map moves/chave (map moves/registro->golpe (or (:golpes registro) []))))
        tipos (or (:tipos registro) [])
        golpes (mapv moves/registro->golpe (or (:golpes registro) []))
        slot* (when (some? slot) (long slot))]
    (->> (eligible-move-slugs species nivel)
         (remove conhecidos)
         (keep golpe-from-catalog)
         (filter (fn [g]
                   (or (nil? slot*)
                       (and (<= 0 slot*) (< slot* (count golpes))
                            (let [next (assoc golpes slot* g)]
                              (some #(moves/ataque-do-tipo? % tipos) next))))))
         vec)))

(defn assert-mt-eligible!
  "Valida payload de MT genérica contra o catálogo + STAB/slot/já-conhecido.
  Retorna o golpe canônico do catálogo (ignora campos forjados do cliente)."
  [registro golpe slot {:keys [catalog-version consume-item-id]}]
  (when-not (= "mt" (str consume-item-id))
    (throw (err/error :invalid_mt_item {:consume-item-id consume-item-id})))
  (assert-catalog-version! catalog-version)
  (let [species (species-key registro)
        _ (when (empty? (species-entries species))
            (throw (err/error :mt_learnset_unavailable
                              {:species species :catalog-version catalog-version})))
        slug* (moves/chave golpe)
        entry (move-entry slug*)
        _ (when-not entry
            (throw (err/error :mt_move_not_in_catalog {:slug slug* :species species})))
        nivel (long (or (:nivel registro) 1))
        allowed (set (eligible-move-slugs species nivel))
        _ (when-not (contains? allowed slug*)
            (throw (err/error :mt_move_not_eligible
                              {:slug slug* :species species :nivel nivel})))
        canon (golpe-from-catalog slug*)
        ;; preparar-aprendizado valida slot / já conhecido / STAB
        _ (moves/preparar-aprendizado registro canon slot)]
    canon))
