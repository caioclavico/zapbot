(ns pokemon.domain.mundo
  "Rotação de clima e biomas por horário.
  Puro: timezone e instante injetáveis."
  (:require [clojure.string :as str]
            [pokemon.domain.raid-gym-theme :as gym-theme])
  (:import [java.time Instant ZoneId ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(defn tipo-label-pt
  "Traduz id interno do tipo para português (reusa ginásios por tipo)."
  [tipo]
  (or (:label-pt (gym-theme/venue-for-type tipo))
      (str tipo)))

(defn tipos-labels-pt
  [tipos]
  (mapv tipo-label-pt tipos))

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

(def ^:private ceu-limpo
  (assoc (first climas) :id "ceu-limpo" :nome "Céu limpo" :emoji "🌙"))

(def ^:private periodos
  [{:nome "Madrugada" :inicio 0
    :biomas ["floresta" "caverna" "lago"]
    :especies {"floresta" ["hoothoot" "noctowl" "venonat"]
               "caverna" ["zubat" "sableye" "diglett"]
               "lago" ["psyduck" "wooper" "marill"]}}
   {:nome "Dia" :inicio 6
    :biomas ["praia" "floresta" "vulcao"]
    :especies {"praia" ["wingull" "krabby" "buizel"]
               "floresta" ["caterpie" "oddish" "seedot"]
               "vulcao" ["slugma" "numel" "growlithe"]}}
   {:nome "Entardecer" :inicio 17
    :biomas ["lago" "praia" "cidade"]
    :especies {"lago" ["lotad" "poliwag" "ralts"]
               "praia" ["shellder" "frillish" "wingull"]
               "cidade" ["meowth" "magnemite" "pidove"]}}
   {:nome "Noite" :inicio 19
    :biomas ["cidade" "floresta" "caverna"]
    :especies {"cidade" ["rattata" "shinx" "glameow"]
               "floresta" ["hoothoot" "spinarak" "murkrow"]
               "caverna" ["zubat" "umbreon" "roggenrola"]}}])

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

(defn obter-clima
  [texto]
  (let [id (normalizar texto)]
    (some #(when (or (= id (:id %)) (= id (normalizar (:nome %)))) %) (conj climas ceu-limpo))))

(defn area-disponivel
  [texto dia]
  (when-let [area (obter-area texto)]
    (some #(when (= (:id area) (:id %)) %) (areas-do-dia dia))))

(defn hora-no-fuso
  ([instant] (hora-no-fuso instant default-timezone))
  ([instant timezone-id]
   (.getHour (ZonedDateTime/ofInstant (or instant (Instant/now)) (zone timezone-id)))))

(defn rotacao
  [instant timezone-id]
  (let [local (ZonedDateTime/ofInstant (or instant (Instant/now)) (zone timezone-id))
        dia (.toLocalDate local)
        hora (.getHour local)
        indice (reduce (fn [atual [i {:keys [inicio]}]]
                         (if (<= inicio hora) i atual))
                       0 (map-indexed vector periodos))
        {:keys [nome inicio biomas especies]} (nth periodos indice)
        proximo (or (:inicio (get periodos (inc indice))) 24)
        clima (nth climas (mod (+ (.toEpochDay dia) indice) (count climas)))
        clima (if (and (not= nome "Dia") (= "ensolarado" (:id clima))) ceu-limpo clima)
        disponiveis (mapv obter-area biomas)]
    {:dia (str dia)
     :periodo {:nome nome :inicio inicio :fim proximo}
     :clima clima
     :area-padrao (first disponiveis)
     :areas-disponiveis disponiveis
     :areas-indisponiveis (filterv #(not (some #{(:id %)} biomas)) areas)
     :especies-por-bioma especies}))

(defn area-padrao
  ([instant] (area-padrao instant default-timezone))
  ([instant timezone-id] (:area-padrao (rotacao instant timezone-id))))

(defn areas-indisponiveis
  [dia]
  (let [ids (set (map :id (areas-do-dia dia)))]
    (filterv #(not (contains? ids (:id %))) areas)))

(defn resumo
  ([instant] (resumo instant default-timezone))
  ([instant timezone-id]
   (let [{:keys [dia periodo clima area-padrao areas-disponiveis areas-indisponiveis
                 especies-por-bioma]} (rotacao instant timezone-id)
         dto #(select-keys % [:id :nome :emoji :tipos])]
     {:dia dia
      :periodo periodo
      :clima (dto clima)
      :area-padrao (dto area-padrao)
      :areas-disponiveis (mapv #(assoc (dto %) :especies (get especies-por-bioma (:id %)))
                               areas-disponiveis)
      :areas-indisponiveis (mapv dto areas-indisponiveis)})))

(defn texto-resumo
  "Texto exibido em !pk clima/areas/mapa."
  ([instant] (texto-resumo instant default-timezone))
  ([instant timezone-id]
   (let [{:keys [periodo clima area-padrao areas-disponiveis]}
         (resumo instant timezone-id)]
     (str (:emoji clima) " *Clima agora: " (:nome clima) "* — favorece "
          (str/join ", " (sort (tipos-labels-pt (:tipos clima)))) ".
"
          "🕒 *" (:nome periodo) "* · "
          (format "%02dh–%02dh" (:inicio periodo) (:fim periodo)) "
"
          "📍 *Bioma deste horário:* " (:emoji area-padrao) " " (:nome area-padrao) "
"
          "🗺️ *Biomas abertos e espécies em destaque:*
"
          (str/join "
" (map (fn [{:keys [emoji nome especies]}]
                                (str emoji " " nome " · " (str/join ", " (map str/capitalize especies))))
                              areas-disponiveis))))))
