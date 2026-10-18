(ns pokemon.domain.mundo
  "Rotação diária de clima e biomas — espelho de zapbot.pokemon.mundo.
  Puro: timezone e instante injetáveis."
  (:require [clojure.string :as str])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(def default-timezone "America/Sao_Paulo")

(def areas
  [{:id "floresta" :nome "Floresta Verde" :emoji "🌳"
    :tipos #{"grass" "bug" "flying" "normal"} :ceu "#86efac" :chao "#166534"}
   {:id "praia" :nome "Praia Azul" :emoji "🏖️"
    :tipos #{"water" "ice" "flying" "electric"} :ceu "#7dd3fc" :chao "#eab308"}
   {:id "caverna" :nome "Caverna Rochosa" :emoji "🪨"
    :tipos #{"rock" "ground" "dark" "poison"} :ceu "#475569" :chao "#1e293b"}
   {:id "cidade" :nome "Cidade Pokémon" :emoji "🏙️"
    :tipos #{"normal" "electric" "steel" "fighting"} :ceu "#94a3b8" :chao "#334155"}
   {:id "lago" :nome "Lago Encantado" :emoji "🌊"
    :tipos #{"water" "fairy" "psychic" "grass"} :ceu "#a5f3fc" :chao "#0f766e"}
   {:id "vulcao" :nome "Vulcão Rubro" :emoji "🌋"
    :tipos #{"fire" "rock" "ground" "dragon"} :ceu "#fb923c" :chao "#7f1d1d"}])

(def climas
  [{:id "ensolarado" :nome "Ensolarado" :emoji "☀️" :tipos #{"fire" "grass" "ground"}}
   {:id "chuvoso" :nome "Chuvoso" :emoji "🌧️" :tipos #{"water" "electric" "bug"}}
   {:id "nublado" :nome "Nublado" :emoji "☁️" :tipos #{"fairy" "fighting" "poison"}}
   {:id "ventania" :nome "Ventania" :emoji "💨" :tipos #{"flying" "dragon" "psychic"}}
   {:id "neve" :nome "Neve" :emoji "❄️" :tipos #{"ice" "steel"}}
   {:id "nevoeiro" :nome "Nevoeiro" :emoji "🌫️" :tipos #{"ghost" "dark"}}])

(defn- zone
  [timezone-id]
  (ZoneId/of (or timezone-id default-timezone)))

(defn dia-atual
  ([instant] (dia-atual instant default-timezone))
  ([instant timezone-id]
   (let [zdt (ZonedDateTime/ofInstant (or instant (Instant/now)) (zone timezone-id))]
     (.format zdt DateTimeFormatter/ISO_LOCAL_DATE))))

(defn- indice-do-dia
  [dia]
  (reduce + 0 (map int (str dia))))

(defn clima-do-dia
  ([dia] (nth climas (mod (indice-do-dia dia) (count climas)))))

(defn areas-do-dia
  ([dia]
   (let [inicio (mod (indice-do-dia dia) (count areas))]
     (mapv #(nth areas (mod (+ inicio %) (count areas))) (range 3)))))

(defn normalizar
  [texto]
  (-> (or texto "")
      str/lower-case
      (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/trim))

(defn obter-area
  [texto]
  (let [id (normalizar texto)]
    (some #(when (or (= id (:id %)) (= id (normalizar (:nome %)))) %) areas)))

(defn area-disponivel
  [texto dia]
  (when-let [area (obter-area texto)]
    (some #(when (= (:id area) (:id %)) %) (areas-do-dia dia))))

(defn hora-no-fuso
  ([instant] (hora-no-fuso instant default-timezone))
  ([instant timezone-id]
   (.getHour (ZonedDateTime/ofInstant (or instant (Instant/now)) (zone timezone-id)))))

(defn area-padrao
  ([instant] (area-padrao instant default-timezone))
  ([instant timezone-id]
   (let [dia (dia-atual instant timezone-id)
         disponiveis (areas-do-dia dia)]
     (nth disponiveis (mod (hora-no-fuso instant timezone-id) 3)))))

(defn areas-indisponiveis
  [dia]
  (let [ids (set (map :id (areas-do-dia dia)))]
    (filterv #(not (contains? ids (:id %))) areas)))

(defn resumo
  ([instant] (resumo instant default-timezone))
  ([instant timezone-id]
   (let [dia (dia-atual instant timezone-id)
         clima (clima-do-dia dia)
         atual (area-padrao instant timezone-id)
         disponiveis (areas-do-dia dia)
         indisponiveis (areas-indisponiveis dia)]
     {:dia dia
      :clima (select-keys clima [:id :nome :emoji :tipos])
      :area-padrao (select-keys atual [:id :nome :emoji :tipos])
      :areas-disponiveis (mapv #(select-keys % [:id :nome :emoji :tipos]) disponiveis)
      :areas-indisponiveis (mapv #(select-keys % [:id :nome :emoji :tipos]) indisponiveis)})))
