(ns zapbot.pokemon.missoes
  "Regras das missões diárias. Progresso, resgates e XP ficam na conta da
  loja, para salvar a recompensa e o resgate na mesma atualização."
  (:require [zapbot.config :as config]))

(def catalogo
  [{:id "selvagens" :nome "Explorador" :objetivo "Derrotar selvagens"
    :meta 3 :xp 2 :pokebolas 3}
   {:id "capturas" :nome "Colecionador" :objetivo "Capturar Pokémon selvagens"
    :meta 2 :xp 3 :pokebolas 4 :grandes 1}
   {:id "vitorias" :nome "Desafiante" :objetivo "Vencer batalhas PvP"
    :meta 1 :xp 3 :pokebolas 3 :grandes 1 :ultras 1}
   {:id "presentes" :nome "Amigo generoso" :objetivo "Enviar presentes aos amigos"
    :meta 2 :xp 2 :pokebolas 3}])

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
               (update :pokebolas * escala)
               (update :grandes (fn [qtd] (* (or qtd 0) escala)))
               (update :ultras (fn [qtd] (* (or qtd 0) escala)))) catalogo)))

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

;; A semana começa na segunda-feira, no mesmo fuso das missões diárias.
(defn semana-de [dia]
  (let [data (js/Date. (str dia "T12:00:00Z"))
        deslocamento (mod (+ (.getUTCDay data) 6) 7)]
    (.setUTCDate data (- (.getUTCDate data) deslocamento))
    (subs (.toISOString data) 0 10)))

(def semanais
  [{:id "ginasios" :objetivo "Vencer em 2 ginásios diferentes" :meta 2 :moedas 60 :pokebolas 8 :grandes 4 :ultras 2}
   {:id "tipos" :objetivo "Capturar Pokémon de 5 tipos diferentes" :meta 5 :moedas 50 :pokebolas 6 :grandes 3 :ultras 1}
   {:id "pvp" :objetivo "Vencer 3 batalhas PvP" :meta 3 :moedas 60 :pokebolas 8 :grandes 4 :ultras 2}
   {:id "presentes" :objetivo "Enviar 10 presentes aos amigos" :meta 10 :moedas 50
    :pokebolas 6 :grandes 3 :ultras 1}])

(defn estado-semanal [conta semana]
  (let [estado (get conta "missoes-semanais")]
    (if (= semana (get estado "semana")) estado
        {"semana" semana "ginasios" [] "tipos" [] "pvp" 0 "presentes" 0 "resgatadas" []})))

(defn progresso-semanal [estado id]
  (if (contains? #{"pvp" "presentes"} id) (get estado id 0) (count (get estado id []))))

(defn registrar-semanal [conta semana evento valores]
  (let [estado (estado-semanal conta semana)]
    (assoc conta "missoes-semanais"
           (case evento
             "presentes" (update estado "presentes" #(min 10 (inc (or % 0))))
             "pvp" (update estado "pvp" #(min 3 (inc (or % 0))))
             ("ginasios" "tipos") (update estado evento #(vec (distinct (concat % valores))))
             estado))))

(defn recompensas-bolas [missao]
  {"pokebola" (:pokebolas missao 0)
   "grande-bola" (:grandes missao 0)
   "ultra-bola" (:ultras missao 0)})

(defn resgatar-semanais [conta semana]
  (let [estado (estado-semanal conta semana)
        prontas (filter #(and (>= (progresso-semanal estado (:id %)) (:meta %))
                              (not (some #{(:id %)} (get estado "resgatadas")))) semanais)
        moedas (reduce + 0 (map :moedas prontas))
        bolas (reduce #(merge-with + %1 (recompensas-bolas %2)) {} prontas)]
    [(-> conta
         (assoc "missoes-semanais" (update estado "resgatadas" into (map :id prontas)))
         (update "moedas" (fnil + 0) moedas)) moedas bolas]))
