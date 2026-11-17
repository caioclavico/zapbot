(ns pokemon.domain.mission-progress
  "Regras de progresso da master; sem RNG, economia ou resgate."
  (:require [pokemon.domain.mission-catalog :as catalog]
            [pokemon.domain.errors :as err]))

(def rules
  {:wild-defeated {:daily "selvagens"}
   :wild-captured {:daily "capturas" :weekly "tipos"}
   :pvp-won {:daily "vitorias" :weekly "pvp"}
   :gym-won {:weekly "ginasios"}
   :gift-sent {:daily "presentes" :weekly "presentes"}})

(defn apply-event
  "Aplica apenas o tipo de evento confirmado pelo produtor canônico.
  Coleções semanais são distintas e não truncadas (regra original)."
  [daily weekly level {:keys [type values day week]}]
  (when-let [{daily-id :daily weekly-id :weekly} (get rules type)]
    (when (or (and daily-id (pos? (compare (get daily "dia" day) day)))
              (and weekly-id (pos? (compare (get weekly "semana" week) week))))
      ;; Nunca rebobinar estado ou transferir ação antiga ao período novo.
      ;; A transação inteira da ação falha; nenhum efeito fica confirmado.
      (throw (err/error :mission_period_conflict {:day day :week week})))
    (let [ds (catalog/daily-state daily day level)
          ws (catalog/weekly-state weekly week)
          daily-meta (:meta (some #(when (= daily-id (:id %)) %)
                                  (catalog/scaled-daily (get ds "nivel"))))
          ds (when daily-id
               (update-in ds ["progresso" daily-id]
                          #(min daily-meta (inc (or % 0)))))
          ws (when weekly-id
               (if (#{"pvp" "presentes"} weekly-id)
                 (let [meta (:meta (some #(when (= weekly-id (:id %)) %) catalog/weekly))]
                   (update ws weekly-id #(min meta (inc (or % 0)))))
                 (update ws weekly-id #(vec (distinct (concat % values))))))]
      {:daily ds :weekly ws})))
