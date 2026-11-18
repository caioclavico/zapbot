(ns pokemon.migration.account-shape
  "Formato legado de conta treinador: equipe/pc flat; enfermaria {pokemon, pronto-em}."
  (:require [clojure.string :as str]))

(defn- g
  [m k]
  (when (map? m)
    (or (get m k)
        (get m (name k))
        (get m (keyword (name k))))))

(defn enfermaria-envelope?
  "True quando o mapa é envelope de enfermaria, não o Pokémon em si."
  [entry]
  (let [inner (g entry :pokemon)
        flat-id (g entry :id-pokemon)
        flat-nome (or (g entry :nome) (g entry :especie) (g entry :species))]
    (and (map? inner)
         (nil? flat-id)
         (nil? flat-nome))))

(defn unwrap-entry
  "Desembrulha entrada de equipe/pc/enfermaria.
   Nunca inventa id-pokemon. Retorna
   {:pokemon map :pronto-em _ :enveloped? bool}."
  [entry]
  (cond
    (not (map? entry))
    {:pokemon {} :pronto-em nil :enveloped? false}

    (enfermaria-envelope? entry)
    {:pokemon (g entry :pokemon)
     :pronto-em (g entry :pronto-em)
     :enveloped? true}

    :else
    {:pokemon entry :pronto-em nil :enveloped? false}))

(defn pokemon-in-location
  "Lista ordenada de {:pokemon :location :pronto-em :enveloped?} para uma conta."
  [account]
  (let [a (if (map? account) account {})
        getv (fn [k]
               (or (get a k)
                   (get a (name k))
                   (get a (keyword k))
                   []))]
    (vec
     (mapcat
      (fn [loc]
        (map (fn [entry]
               (let [u (unwrap-entry entry)]
                 (assoc u :location (keyword (name loc)))))
             (or (getv loc) [])))
      [:equipe :pc :enfermaria]))))

(defn flat-pokemon-list
  "Pokémon desembrulhados na ordem equipe→pc→enfermaria (contagem canônica)."
  [account]
  (mapv :pokemon (pokemon-in-location account)))

(defn location-counts
  [account]
  (let [xs (pokemon-in-location account)]
    ;; Strings estáveis para equivalência IR ↔ Datomic
    {"equipe" (count (filter #(= :equipe (:location %)) xs))
     "pc" (count (filter #(= :pc (:location %)) xs))
     "enfermaria" (count (filter #(= :enfermaria (:location %)) xs))
     "total" (count xs)}))

(defn normalize-species
  [s]
  (when s
    (-> (str s)
        str/trim
        (str/replace #"\s+" " "))))
