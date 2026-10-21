(ns pokemon.domain.mission-rewards
  "Recompensas da master. Sem I/O; sorteios internos injetados por draw."
  (:require [pokemon.domain.mission-catalog :as catalog]))

(def order
  (vec (concat ["pokebola" "grande-bola" "ultra-bola" "fruta" "fruta-dourada"
                "reviver" "catalisador-evolutivo"]
               (:trade-evolution-pool catalog/reward-rules)
               (sort ["pedra-agua" "pedra-trovao" "pedra-fogo" "pedra-folha" "pedra-lua"]))))

(defn base-items [mission]
  (into {} (for [[field item] [[:pokebolas "pokebola"] [:grandes "grande-bola"]
                              [:ultras "ultra-bola"] [:catalisadores "catalisador-evolutivo"]]
                :let [n (get mission field 0)] :when (pos? n)] [item n])))

(defn- evolution-item [draw key]
  (let [pool (:trade-evolution-pool catalog/reward-rules)]
    (nth pool (draw key (count pool)))))

(defn daily [state draw]
  (let [ready (filterv #(and (>= (get-in state ["progresso" (:id %)] 0) (:meta %))
                             (not (some #{(:id %)} (get state "resgatadas"))))
                       (catalog/scaled-daily (get state "nivel" 1)))
        items (reduce (fn [acc mission]
                        (let [id (:id mission)
                              roll (draw [id :ball] 100)
                              bonus (cond (< roll 10) "ultra-bola" (< roll 35) "grande-bola")
                              items (cond-> (base-items mission)
                                      bonus (update bonus (fnil inc 0))
                                      (< (draw [id :reviver] 100) 20) (update "reviver" (fnil inc 0))
                                      (< (draw [id :evolution] 100) 20)
                                      (update (evolution-item draw [id :evolution-index]) (fnil inc 0)))]
                          (merge-with + acc items))) {} ready)]
    {:state (update state "resgatadas" #(into (vec %) (map :id ready)))
     :ids (mapv :id ready) :xp (reduce + 0 (map :xp ready)) :coins 0 :items items}))

(defn weekly [state draw]
  (let [ready (filterv #(and (>= (if (#{"pvp" "presentes"} (:id %))
                                  (get state (:id %) 0) (count (distinct (get state (:id %) []))))
                                (:meta %))
                             (not (some #{(:id %)} (get state "resgatadas")))) catalog/weekly)
        coins (reduce + 0 (map :moedas ready))
        items (reduce #(merge-with + %1 (base-items %2)) {} ready)]
    {:state (update state "resgatadas" #(into (vec %) (map :id ready)))
     :ids (mapv :id ready) :xp 0 :coins coins
     :items (cond-> items (pos? coins)
              (update (evolution-item draw :weekly-evolution-index) (fnil inc 0)))}))

(defn gift [roll]
  (cond (< roll 10) {"ultra-bola" 1}
        (< roll 35) {"grande-bola" 2}
        :else {"pokebola" 3}))

(defn allocate
  "Prioridade original; excedente preservado em pendências, sem perda."
  [items pending free]
  (reduce (fn [{:keys [added pending free]} item]
            (let [qty (get items item 0) n (min qty free) rest (- qty n)]
              {:added (cond-> added (pos? n) (assoc item n))
               :pending (cond-> pending (pos? rest) (update item (fnil + 0) rest))
               :free (- free n)}))
          {:added {} :pending (or pending {}) :free (max 0 free)} order))
