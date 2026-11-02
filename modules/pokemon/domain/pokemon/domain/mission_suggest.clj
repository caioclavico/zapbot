(ns pokemon.domain.mission-suggest
  "Ranqueamento de missões pendentes para o resumo compacto de !pk missoes.
  Separado da apresentação WhatsApp."
  (:require [clojure.string :as str]))

(defn- pending?
  "Sugestões: só missões ainda não concluídas/resgatadas."
  [mission]
  (contains? #{"available" "in_progress"} (str (:estado mission))))

(defn difficulty-score
  "Menor = mais fácil. Combina meta e valor aproximado das recompensas."
  [mission]
  (let [meta (long (or (:meta mission) 1))
        reward-weight (+ (* 1 (long (or (:pokebolas mission) 0)))
                         (* 2 (long (or (:grandes mission) 0)))
                         (* 3 (long (or (:ultras mission) 0)))
                         (* 2 (long (or (:frutas mission) 0)))
                         (* 4 (long (or (:presentes-item mission) 0)))
                         (* 3 (long (or (:catalisadores mission) 0)))
                         (* 2 (long (or (:moedas mission) 0)))
                         (* 1 (long (or (:xp mission) 0))))]
    (+ meta (quot reward-weight 4))))

(defn progress-ratio
  [mission]
  (let [meta (max 1 (long (or (:meta mission) 1)))
        prog (max 0 (long (or (:progresso mission) 0)))]
    (double (/ (min prog meta) meta))))

(defn rank-pending
  "Ordena pendentes: mais próximas da conclusão, depois menor dificuldade.
  Não inclui completed/rewarded; sem duplicatas."
  [missions]
  (->> missions
       (filter pending?)
       (sort-by (fn [m]
                  [(- (progress-ratio m))
                   (difficulty-score m)
                   (long (or (:meta m) 1))
                   (str (or (:id m) ""))]))
       vec))

(defn suggest
  "Até `limit` missões pendentes ranqueadas."
  ([missions] (suggest missions 2))
  ([missions limit]
   (vec (take (max 0 (long limit)) (rank-pending missions)))))

(defn active-event-missions
  "Missões de eventos com status active (ou available=true)."
  [events]
  (->> (or events [])
       (filter (fn [ev]
                 (or (= "active" (str (:status ev)))
                     (true? (:available ev)))))
       (mapcat (fn [ev]
                 (map #(assoc % :event-id (:id ev) :event-nome (:nome ev))
                      (or (:missions ev) []))))
       (filter pending?)
       vec))

(defn summary-projection
  "Projeção de domínio para o resumo compacto (sem texto)."
  [snapshot]
  (let [daily (get-in snapshot [:daily :missions] [])
        weekly (get-in snapshot [:weekly :missions] [])
        events (or (:events snapshot) [])
        daily-done (count (filter #(contains? #{"completed" "rewarded"}
                                              (str (:estado %))) daily))
        weekly-done (count (filter #(contains? #{"completed" "rewarded"}
                                               (str (:estado %))) weekly))
        active-events (filterv #(or (= "active" (str (:status %)))
                                    (true? (:available %)))
                               events)]
    {:daily-total (count daily)
     :daily-done daily-done
     :weekly-total (count weekly)
     :weekly-done weekly-done
     :daily-suggested (suggest daily 2)
     :weekly-suggested (suggest weekly 2)
     :event-suggested (vec (take 2 (rank-pending (active-event-missions events))))
     :active-events (mapv #(select-keys % [:id :nome :status]) active-events)}))

(defn normalize-scope
  "Normaliza token de filtro: diaria(s), semanal(is), evento(s), all."
  [raw]
  (let [n (-> (str (or raw ""))
              str/lower-case
              (java.text.Normalizer/normalize java.text.Normalizer$Form/NFD)
              (str/replace #"\p{M}" "")
              str/trim)]
    (cond
      (contains? #{"daily" "diaria" "diarias" "dia"} n) "daily"
      (contains? #{"weekly" "semanal" "semanais" "semana"} n) "weekly"
      (contains? #{"event" "evento" "eventos"} n) "event"
      (contains? #{"all" "todas" "tudo" ""} n) "all"
      :else n)))
