(ns zapbot.missoes
  "Regras das missões diárias. Progresso, resgates e XP ficam na conta da
  loja, para salvar a recompensa e o resgate na mesma atualização."
  (:require [zapbot.config :as config]))

(def catalogo
  [{:id "selvagens" :nome "Explorador" :objetivo "Derrotar selvagens"
    :meta 3 :xp 2 :pokebolas 3}
   {:id "capturas" :nome "Colecionador" :objetivo "Capturar Pokémon selvagens"
    :meta 2 :xp 3 :pokebolas 4}
   {:id "vitorias" :nome "Desafiante" :objetivo "Vencer batalhas PvP"
    :meta 1 :xp 3 :pokebolas 3}])

(defn dia-atual []
  (let [formatador (js/Intl.DateTimeFormat. "en-US"
                     #js {:timeZone config/missoes-timezone
                          :year "numeric" :month "2-digit" :day "2-digit"})
        partes (into {} (map (fn [p] [(.-type p) (.-value p)])
                             (array-seq (.formatToParts formatador (js/Date.)))))]
    (str (get partes "year") "-" (get partes "month") "-" (get partes "day"))))

(defn catalogo-do-dia [estado]
  (let [escala (inc (quot (dec (max 1 (get estado "nivel" 1))) 5))]
    (mapv #(-> % (update :meta * escala) (update :xp * escala)
               (update :pokebolas * escala)) catalogo)))

(defn estado-do-dia [conta dia nivel]
  (let [estado (get conta "missoes-diarias")]
    (if (= dia (get estado "dia"))
      estado
      {"dia" dia "nivel" nivel "progresso" {} "resgatadas" []})))

(defn disponiveis [estado]
  (filterv (fn [{:keys [id meta]}]
             (and (>= (get-in estado ["progresso" id] 0) meta)
                  (not (some #{id} (get estado "resgatadas")))))
           (catalogo-do-dia estado)))

(defn registrar-evento [conta dia evento nivel]
  (let [estado (estado-do-dia conta dia nivel)]
    (if-let [missao (some #(when (= evento (:id %)) %) (catalogo-do-dia estado))]
      (assoc conta "missoes-diarias"
             (update-in estado ["progresso" evento]
                        #(min (:meta missao) (inc (or % 0)))))
      conta)))

(defn sortear-bonus
  "Um sorteio por missão: 10% Ultra, 25% Grande, 65% sem bônus."
  []
  (let [sorteio (rand-int 100)]
    (cond (< sorteio 10) "ultra-bola"
          (< sorteio 35) "grande-bola")))

(defn sortear-reviver? []
  (< (rand-int 100) 20))
