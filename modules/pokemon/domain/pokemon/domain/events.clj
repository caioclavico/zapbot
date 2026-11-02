(ns pokemon.domain.events
  "Eventos temáticos temporários — catálogo dinâmico por tipo/família.
  Release 12.8.0: catálogo presente mas INATIVO (sem janelas + flag off).
  Ativação operacional → 12.8.1 via POKEMON_THEMATIC_EVENTS_ENABLED=true
  e janelas inicio/fim. Não altera chances permanentes de caça."
  (:require [clojure.string :as str]
            [pokemon.domain.profile :as profile])
  (:import [java.time Instant]))

(defn thematic-events-enabled?
  "Kill-switch 12.8.0: default false. Sem GO 12.8.1 não há eventos ativos."
  []
  (= "true"
     (some-> (System/getenv "POKEMON_THEMATIC_EVENTS_ENABLED")
             str
             str/lower-case
             str/trim)))

(def catalog
  "Definições estáveis. Janelas `:inicio`/`:fim` ISO-8601; ativação por chat via schedule."
  [{:id "festival-dragoes"
    :nome "Festival dos Dragões"
    :emoji "🐉"
    :tipos-favorecidos #{"dragon"}
    :familias-doce ["dratini" "bagon" "gible" "axew" "deino" "goomy" "jangmo-o" "dreepy" "frigibax"]
    :missoes [{:id "cap-dragao" :nome "Capture Pokémon Dragão" :meta 5 :objetivo "Capturar 5 Pokémon do tipo Dragão"
               :recompensas {"fruta" 2 "ultra-bola" 1}
               :doces {"dratini" 3}}
              {:id "evo-dragao" :nome "Evolua um Dragão" :meta 1 :objetivo "Evoluir 1 Pokémon da família Dragão"
               :recompensas {"fruta" 1 "cartao-presente" 1}
               :doces {"bagon" 2}}]
    :bias-tipos 20
    :inicio nil :fim nil}
   {:id "noite-sombras"
    :nome "Noite das Sombras"
    :emoji "👻"
    :tipos-favorecidos #{"ghost" "dark"}
    :familias-doce ["gastly" "litwick" "zorua" "pawniard"]
    :missoes [{:id "cap-sombra" :nome "Capture Fantasma ou Sombrio" :meta 4
               :objetivo "Capturar 4 Pokémon Fantasma/Sombrio"
               :recompensas {"fruta" 2 "ultra-bola" 2}
               :doces {"gastly" 2}}]
    :bias-tipos 18
    :inicio nil :fim nil}
   {:id "tempestade-eletrica"
    :nome "Tempestade Elétrica"
    :emoji "⚡"
    :tipos-favorecidos #{"electric"}
    :familias-doce ["pikachu" "elekid" "magnemite"]
    :missoes [{:id "cap-eletrico" :nome "Capture Elétricos" :meta 5
               :objetivo "Capturar 5 Pokémon Elétricos"
               :recompensas {"grande-bola" 3 "fruta" 1}
               :doces {"pikachu" 3}}]
    :bias-tipos 18
    :inicio nil :fim nil}
   {:id "mare-misteriosa"
    :nome "Maré Misteriosa"
    :emoji "🌊"
    :tipos-favorecidos #{"water"}
    :familias-doce ["magikarp" "squirtle" "poliwag" "horsea" "spheal"]
    :missoes [{:id "cap-agua" :nome "Capture Pokémon Água" :meta 5
               :objetivo "Capturar 5 Pokémon Água"
               :recompensas {"fruta" 2 "grande-bola" 2}
               :doces {"magikarp" 3}}]
    :bias-tipos 18
    :inicio nil :fim nil}
   {:id "despertar-floresta"
    :nome "Despertar da Floresta"
    :emoji "🌿"
    :tipos-favorecidos #{"grass" "bug"}
    :familias-doce ["bulbasaur" "oddish" "scyther"]
    :missoes [{:id "cap-planta" :nome "Capture Planta/Inseto" :meta 5
               :objetivo "Capturar 5 Pokémon Planta ou Inseto"
               :recompensas {"fruta" 2 "pokebola" 4}
               :doces {"bulbasaur" 2}}]
    :bias-tipos 18
    :inicio nil :fim nil}
   {:id "era-fosseis"
    :nome "Era dos Fósseis"
    :emoji "🦴"
    :tipos-favorecidos #{"rock" "water" "flying"}
    :familias-doce ["kabuto" "omanyte" "lileep" "anorith" "cranidos" "shieldon"
                    "tirtouga" "archen" "tyrunt" "amaura"]
    :missoes [{:id "cap-fossil" :nome "Capture um fóssil" :meta 3
               :objetivo "Capturar 3 Pokémon de famílias fósseis"
               :recompensas {"ultra-bola" 2 "fruta" 1}
               :doces {"kabuto" 2}}]
    :bias-tipos 15
    :inicio nil :fim nil}
   {:id "eclipse-mistico"
    :nome "Eclipse Místico"
    :emoji "🌙"
    :tipos-favorecidos #{"psychic" "ghost" "fairy"}
    :familias-doce ["ralts" "abra" "eevee" "gastly"]
    :missoes [{:id "cap-mistico" :nome "Desafio do Eclipse" :meta 7
               :objetivo "Capturar 7 Pokémon Psíquico/Fantasma/Fada"
               :recompensas {"ultra-bola" 3 "cartao-presente" 2 "fruta" 2}
               :doces {"ralts" 4}}]
    :bias-tipos 12
    :inicio nil :fim nil}])

(defn by-id [id]
  (some #(when (= id (:id %)) %) catalog))

(defn status-at
  "upcoming|active|ended|inactive — inactive quando sem janela configurada."
  [event instant]
  (let [now (or instant (Instant/now))
        start (some-> (:inicio event) Instant/parse)
        end (some-> (:fim event) Instant/parse)]
    (cond
      (or (nil? start) (nil? end)) "inactive"
      (.isBefore now start) "upcoming"
      (.isBefore now end) "active"
      :else "ended")))

(defn active-events
  "Eventos ativos no instante; opcionalmente filtrados por allowlist de ids do chat.
  Com POKEMON_THEMATIC_EVENTS_ENABLED≠true (padrão 12.8.0) retorna sempre []."
  ([instant] (active-events instant nil))
  ([instant allowed-ids]
   (if-not (thematic-events-enabled?)
     []
     (let [allow? (if (seq allowed-ids) (set allowed-ids) (constantly true))]
       (->> catalog
            (filter #(and (allow? (:id %)) (= "active" (status-at % instant))))
            vec)))))

(defn favored-types
  "União dos tipos favorecidos dos eventos ativos (sem acumular além do conjunto)."
  [events]
  (into #{} (mapcat :tipos-favorecidos events)))

(defn type-bias-chance
  "Chance extra (0–35) de forçar tipos do evento no sorteio. Não empilha sem limite:
  usa o maior bias entre eventos ativos."
  [events]
  (min 35 (long (reduce max 0 (map #(long (or (:bias-tipos %) 0)) events)))))

(defn pick-candy-family
  "Sorteia família real do pool do evento (nunca doce genérico de tipo)."
  [event draw]
  (let [pool (vec (or (:familias-doce event) []))
        n (count pool)]
    (when (pos? n)
      (nth pool (mod (long (or (draw) 0)) n)))))

(defn grant-candy-map
  "Normaliza mapa família→qty usando profile/family (chaves estáveis)."
  [doces]
  (reduce-kv (fn [m fam qty]
               (let [root (profile/family fam)
                     q (long qty)]
                 (cond-> m (pos? q) (update root (fnil + 0) q))))
             {}
             (or doces {})))

(defn merge-type-sets
  "Combina tipos de bioma/clima com favorecidos de evento (para seleção)."
  [base-tipos event-tipos]
  (into (set base-tipos) event-tipos))

(defn with-windows
  "Utilitário de teste/admin: aplica inicio/fim a um evento do catálogo."
  [event-id inicio fim]
  (-> (by-id event-id)
      (assoc :inicio inicio :fim fim)))
