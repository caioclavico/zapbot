(ns pokemon.domain.mission-catalog
  "Catálogo e projeção de leitura A4.3.1. Sem concessão de recompensas."
  (:require [pokemon.domain.mundo :as mundo])
  (:import [java.time Instant LocalDate]))

(def daily
  [{:id "selvagens" :nome "Explorador" :objetivo "Derrotar selvagens"
    :meta 3 :xp 2 :pokebolas 3}
   {:id "capturas" :nome "Colecionador" :objetivo "Capturar Pokémon selvagens"
    :meta 2 :xp 3 :pokebolas 4 :grandes 1}
   {:id "vitorias" :nome "Desafiante" :objetivo "Vencer batalhas PvP"
    :meta 1 :xp 3 :pokebolas 3 :grandes 1 :ultras 1 :catalisadores 1}
   {:id "presentes" :nome "Amigo generoso" :objetivo "Enviar presentes aos amigos"
    :meta 2 :xp 2 :pokebolas 3}])

(def weekly
  [{:id "ginasios" :objetivo "Vencer em 2 ginásios diferentes" :meta 2
    :moedas 60 :pokebolas 8 :grandes 4 :ultras 2 :catalisadores 1}
   {:id "tipos" :objetivo "Capturar Pokémon de 5 tipos diferentes" :meta 5
    :moedas 50 :pokebolas 6 :grandes 3 :ultras 1 :catalisadores 1}
   {:id "pvp" :objetivo "Vencer 3 batalhas PvP" :meta 3
    :moedas 60 :pokebolas 8 :grandes 4 :ultras 2 :catalisadores 1}
   {:id "presentes" :objetivo "Enviar 10 presentes aos amigos" :meta 10
    :moedas 50 :pokebolas 6 :grandes 3 :ultras 1 :catalisadores 1}])

(def new-beginning
  {:id "novo-recomeco-2026-09" :nome "Novo Recomeço"
   :inicio "2026-09-27T03:00:00Z" :fim "2026-10-04T03:00:00Z"
   :objetivos [{:id "selvagens" :nome "Vencer selvagens" :meta 3
                :recompensas {"pokebola" 10}}
               {:id "capturas" :nome "Capturar Pokémon" :meta 3
                :recompensas {"grande-bola" 5 "reviver" 2}}]})

(def reward-rules
  {:daily {:ball-bonus {:ultra-bola 10 :grande-bola 25 :none 65}
           :reviver-percent 20 :trade-evolution-percent 20
           :scope "per_completed_mission"}
   :weekly {:trade-evolution-items 1 :scope "per_claim_with_positive_coins"}
   :trade-evolution-pool ["revestimento-metalico" "escama-dragao" "upgrade" "protetor" "pedra-rei"
                          "eletrizador" "magmarizador" "tecido-ceifador" "escama-prisma"
                          "chicote-doce" "sache-perfumado"]})

(defn periods [instant timezone]
  (let [day (mundo/dia-atual instant timezone)
        date (LocalDate/parse day)]
    {:day day :week (str (.minusDays date (dec (.getValue (.getDayOfWeek date)))))}))

(defn daily-state [stored day level]
  (if (= day (get stored "dia")) stored
      {"dia" day "nivel" level "progresso" {} "resgatadas" []}))

(defn weekly-state [stored week]
  (if (= week (get stored "semana")) stored
      {"semana" week "ginasios" [] "tipos" [] "pvp" 0 "presentes" 0 "resgatadas" []}))

(defn scaled-daily [level]
  (let [scale (inc (quot (dec (max 1 level)) 5))]
    (mapv #(reduce (fn [m k] (assoc m k (* scale (get m k 0))))
                   % [:meta :xp :pokebolas :grandes :ultras]) daily)))

(defn- mission [definition progress rewarded?]
  (assoc definition :progresso progress
         :estado (cond rewarded? "rewarded"
                       (>= progress (:meta definition)) "completed"
                       (pos? progress) "in_progress"
                       :else "available")))

(defn snapshot [daily-stored weekly-stored events level instant timezone]
  (let [{:keys [day week]} (periods instant timezone)
        ds (daily-state daily-stored day level)
        ws (weekly-state weekly-stored week)
        start (Instant/parse (:inicio new-beginning))
        end (Instant/parse (:fim new-beginning))
        event-state (get events (:id new-beginning) {})]
    {:timezone timezone :claims-enabled false :progress-integration-enabled false
     :reward-rules reward-rules
     :daily {:period day :initialized (= day (get daily-stored "dia"))
             :level (get ds "nivel")
             :missions (mapv #(mission % (get-in ds ["progresso" (:id %)] 0)
                                       (boolean (some #{(:id %)} (get ds "resgatadas"))))
                             (scaled-daily (get ds "nivel")))}
     :weekly {:period week :initialized (= week (get weekly-stored "semana"))
              :missions (mapv #(mission %
                                       (if (#{"pvp" "presentes"} (:id %))
                                         (get ws (:id %) 0)
                                         (count (distinct (get ws (:id %) []))))
                                       (boolean (some #{(:id %)} (get ws "resgatadas")))) weekly)}
     :events [(assoc (dissoc new-beginning :objetivos)
                     :status (cond (.isBefore instant start) "upcoming"
                                   (.isBefore instant end) "active"
                                   :else "ended")
                     :available (and (not (.isBefore instant start)) (.isBefore instant end))
                     :missions (mapv #(mission % (get-in event-state ["progresso" (:id %)] 0)
                                               (boolean (some #{(:id %)} (get event-state "premiados"))))
                                     (:objetivos new-beginning)))]}))
