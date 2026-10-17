(ns pokemon.domain.collection
  "Coleção / registro de Pokémon — espelha pokemon->registro do legado
  (campos usados em consulta, HP/status, XP, golpes e evolução). Sem I/O."
  (:require [pokemon.domain.errors :as err]
            [pokemon.domain.moves :as moves]
            [pokemon.domain.xp :as xp])
  (:import [java.util UUID]))

(defn new-id-pokemon
  []
  (str (UUID/randomUUID)))

(defn pokemon->registro
  "Converte mapa interno (+ hp-atual/status) no formato de registro da coleção.
  Aceita chaves keyword (legado core) ou string."
  [pokemon hp-atual status]
  (let [g (fn [k & [default]]
            (let [v (get pokemon k (get pokemon (name k) default))]
              v))
        id (or (g :id-pokemon) (g "id-pokemon") (new-id-pokemon))
        status-str (xp/status->str status)
        tipos (vec (or (g :tipos) []))
        golpes-raw (or (g :golpes) [])
        golpes (mapv moves/golpe->registro
                     (moves/garantir-ataque-do-tipo golpes-raw tipos))
        base {:id-pokemon id
              :nome (g :nome)
              :species (or (g :species) (g :nome))
              :shiny (boolean (or (g :shiny?) (g :shiny) false))
              :hp (long (or (g :hp) 0))
              :hp-atual (long hp-atual)
              :ataque (long (or (g :ataque) 0))
              :defesa (long (or (g :defesa) 0))
              :atq-esp (long (or (g :atq-esp) 0))
              :def-esp (long (or (g :def-esp) 0))
              :veloc (long (or (g :veloc) 0))
              :nivel (long (or (g :nivel) 1))
              :xp-desde-nivel (long (or (g :xp-desde-nivel) 0))
              :amizade (long (or (g :amizade) 70))
              :raridade (or (g :raridade) "comum")
              :tipos tipos
              :golpes golpes
              :golpes-removidos (vec (or (g :golpes-removidos) []))
              :historico-golpes (or (g :historico-golpes) {})
              :in-party true}]
    (cond-> base
      status-str (assoc :status status-str)
      (g :imagem) (assoc :imagem (g :imagem))
      (g :imagem-shiny) (assoc :imagem-shiny (g :imagem-shiny))
      (g :habilidade) (assoc :habilidade (g :habilidade))
      (g :ivs) (assoc :ivs (g :ivs)))))

(defn validate-index!
  [idx colecao-count]
  (when-not (and (integer? idx) (<= 0 idx) (< idx colecao-count))
    (throw (err/error :invalid_collection_index {:idx idx :count colecao-count})))
  idx)

(defn next-index
  [colecao]
  (count colecao))

(defn apply-hp-status
  [registro hp-atual status]
  (let [status-str (xp/status->str status)]
    (cond-> (assoc registro :hp-atual (long hp-atual))
      status-str (assoc :status status-str)
      (nil? status-str) (dissoc :status))))
