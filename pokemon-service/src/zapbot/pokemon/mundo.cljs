(ns zapbot.pokemon.mundo
  "Rotação diária de clima e áreas das caçadas. Tudo é derivado da data para
  permanecer igual após reinícios e em todas as instâncias do bot."
  (:require [clojure.string :as str]
            [zapbot.config :as config]))

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

(defn dia-atual []
  (.toLocaleDateString (js/Date.) "en-CA" #js {:timeZone config/missoes-timezone}))

(defn- indice-do-dia [dia]
  (reduce (fn [total caractere] (+ total (.charCodeAt caractere 0))) 0 dia))

(defn clima-do-dia
  ([] (clima-do-dia (dia-atual)))
  ([dia] (nth climas (mod (indice-do-dia dia) (count climas)))))

(defn areas-do-dia
  ([] (areas-do-dia (dia-atual)))
  ([dia]
   (let [inicio (mod (indice-do-dia dia) (count areas))]
     (mapv #(nth areas (mod (+ inicio %) (count areas))) (range 3)))))

(defn normalizar [texto]
  (-> (or texto "") str/lower-case
      (.normalize "NFD") (str/replace #"[\u0300-\u036f]" "") str/trim))

(defn obter-area [texto]
  (let [id (normalizar texto)]
    (some #(when (or (= id (:id %)) (= id (normalizar (:nome %)))) %) areas)))

(defn area-disponivel [texto]
  (let [area (obter-area texto)]
    (some #(when (= (:id area) (:id %)) %) (areas-do-dia))))

(defn- hora-no-fuso [data]
  (let [formatador (js/Intl.DateTimeFormat.
                    "en-US" #js {:timeZone config/missoes-timezone
                                  :hour "2-digit" :hourCycle "h23"})]
    (js/parseInt (.format formatador data) 10)))

(defn area-padrao
  ([] (area-padrao (js/Date.)))
  ([data]
   (nth (areas-do-dia (.toLocaleDateString
                       data "en-CA" #js {:timeZone config/missoes-timezone}))
        (mod (hora-no-fuso data) 3))))

(defn areas-indisponiveis
  "Biomas que não aparecem em nenhum horário da rotação do dia."
  ([] (areas-indisponiveis (dia-atual)))
  ([dia]
   (let [ids-disponiveis (set (map :id (areas-do-dia dia)))]
     (filterv #(not (contains? ids-disponiveis (:id %))) areas))))

(defn resumo []
  (let [clima (clima-do-dia)
        atual (area-padrao)
        disponiveis (areas-do-dia)
        indisponiveis (areas-indisponiveis)]
    (str (:emoji clima) " *Clima de hoje: " (:nome clima) "* — favorece "
         (str/join ", " (sort (:tipos clima))) ".\n"
         "📍 *Bioma deste horário:* " (:emoji atual) " " (:id atual) "\n"
         "🕒 *Rotação de hoje:* "
         (str/join " • " (map #(str (:emoji %) " " (:id %)) disponiveis)) "\n"
         "🚫 *Sem acesso por horário hoje:* "
         (str/join " • " (map #(str (:emoji %) " " (:id %)) indisponiveis)))))
