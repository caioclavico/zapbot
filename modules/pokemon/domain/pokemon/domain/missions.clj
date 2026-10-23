(ns pokemon.domain.missions
  "Missões diárias/semanais — espelho de zapbot.pokemon.missoes (pokemon-service).
  Puro: dia/semana e RNG injetáveis."
  (:require [clojure.string :as str]
            [pokemon.domain.mundo :as mundo])
  (:import [java.time Instant LocalDate DayOfWeek]
           [java.time.temporal ChronoUnit]))

(def timezone mundo/default-timezone)

(def catalogo-diario
  [{:id "selvagens" :nome "Explorador" :objetivo "Derrotar selvagens"
    :meta 3 :xp 2 :pokebolas 3}
   {:id "capturas" :nome "Colecionador" :objetivo "Capturar Pokémon selvagens"
    :meta 2 :xp 3 :pokebolas 4 :grandes 1}
   {:id "vitorias" :nome "Desafiante" :objetivo "Vencer batalhas PvP"
    :meta 1 :xp 3 :pokebolas 3 :grandes 1 :ultras 1 :catalisadores 1}
   {:id "presentes" :nome "Amigo generoso" :objetivo "Enviar presentes aos amigos"
    :meta 2 :xp 2 :pokebolas 3}])

(def catalogo-semanal
  [{:id "ginasios" :objetivo "Vencer em 2 ginásios diferentes"
    :meta 2 :moedas 60 :pokebolas 8 :grandes 4 :ultras 2 :catalisadores 1}
   {:id "tipos" :objetivo "Capturar Pokémon de 5 tipos diferentes"
    :meta 5 :moedas 50 :pokebolas 6 :grandes 3 :ultras 1 :catalisadores 1}
   {:id "pvp" :objetivo "Vencer 3 batalhas PvP"
    :meta 3 :moedas 60 :pokebolas 8 :grandes 4 :ultras 2 :catalisadores 1}
   {:id "presentes" :objetivo "Enviar 10 presentes aos amigos"
    :meta 10 :moedas 50 :pokebolas 6 :grandes 3 :ultras 1 :catalisadores 1}])

(def itens-evolucao-troca
  ["revestimento-metalico" "escama-dragao" "upgrade" "protetor" "pedra-rei"])

(def ordem-recompensas
  (vec (concat ["pokebola" "grande-bola" "ultra-bola"
                "fruta" "fruta-dourada" "reviver" "catalisador-evolutivo"]
               itens-evolucao-troca
               ["pedra-agua" "pedra-trovao" "pedra-fogo" "pedra-folha" "pedra-lua"])))

(def nomes-itens
  {"pokebola" "Pokébola" "grande-bola" "Grande Bola" "ultra-bola" "Ultra Bola"
   "fruta" "Fruta Frambo" "fruta-dourada" "Fruta Frambo Dourada"
   "reviver" "Reviver" "catalisador-evolutivo" "Catalisador Evolutivo"
   "revestimento-metalico" "Revestimento Metálico" "escama-dragao" "Escama do Dragão"
   "upgrade" "Upgrade" "protetor" "Protetor" "pedra-rei" "Pedra do Rei"
   "pedra-agua" "Pedra da Água" "pedra-trovao" "Pedra do Trovão"
   "pedra-fogo" "Pedra do Fogo" "pedra-folha" "Pedra da Folha" "pedra-lua" "Pedra da Lua"
   "cartao-presente" "Cartão de presente"})

(defn dia-atual
  ([instant] (mundo/dia-atual instant timezone))
  ([instant tz] (mundo/dia-atual instant tz)))

(defn semana-de
  "Segunda-feira da semana civil (mesmo fuso das diárias), espelhando master."
  [dia]
  (let [ld (LocalDate/parse (str dia))
        ;; DayOfWeek: MONDAY=1 … SUNDAY=7 → offset 0 na segunda.
        offset (long (mod (dec (.getValue (.getDayOfWeek ld))) 7))]
    (str (.minusDays ld offset))))

(defn escala-nivel
  "A cada 5 níveis, +1 multiplicador (nível 1–5 → 1, 6–10 → 2, …)."
  [nivel]
  (inc (quot (dec (max 1 (long (or nivel 1)))) 5)))

(defn catalogo-do-dia
  [estado-ou-nivel]
  (let [nivel (if (map? estado-ou-nivel)
                (long (or (get estado-ou-nivel "nivel") (:nivel estado-ou-nivel) 1))
                (long (or estado-ou-nivel 1)))
        escala (escala-nivel nivel)]
    (mapv (fn [m]
            (-> m
                (update :meta * escala)
                (update :xp * escala)
                (update :pokebolas * escala)
                (update :grandes (fn [q] (* (or q 0) escala)))
                (update :ultras (fn [q] (* (or q 0) escala)))))
          catalogo-diario)))

(defn estado-do-dia
  [conta dia nivel]
  (let [estado (or (get conta "missoes-diarias") (:missoes-diarias conta))]
    (if (= dia (or (get estado "dia") (:dia estado)))
      (into {} (map (fn [[k v]] [(name (keyword k)) v]) estado))
      {"dia" dia "nivel" (long nivel) "progresso" {} "resgatadas" []})))

(defn estado-semanal
  [conta semana]
  (let [estado (or (get conta "missoes-semanais") (:missoes-semanais conta))]
    (if (= semana (or (get estado "semana") (:semana estado)))
      (into {} (map (fn [[k v]] [(name (keyword k)) v]) estado))
      {"semana" semana "ginasios" [] "tipos" [] "pvp" 0 "presentes" 0 "resgatadas" []})))

(defn disponiveis
  [estado]
  (filterv (fn [{:keys [id meta]}]
             (and (>= (long (get-in estado ["progresso" id] 0)) meta)
                  (not (some #{id} (get estado "resgatadas" [])))))
           (catalogo-do-dia estado)))

(defn registrar-evento-diario
  [conta dia evento nivel]
  (let [estado (estado-do-dia conta dia nivel)
        missao (some #(when (= evento (:id %)) %) (catalogo-do-dia estado))]
    (if-not missao
      conta
      (assoc conta "missoes-diarias"
             (update-in estado ["progresso" evento]
                        #(min (:meta missao) (inc (long (or % 0)))))))))

(defn progresso-semanal
  [estado id]
  (if (contains? #{"pvp" "presentes"} id)
    (long (get estado id 0))
    (count (get estado id []))))

(defn registrar-evento-semanal
  [conta semana evento valores]
  (let [estado (estado-semanal conta semana)
        valores (vec (or valores []))]
    (assoc conta "missoes-semanais"
           (case evento
             "presentes" (update estado "presentes" #(min 10 (inc (long (or % 0)))))
             "pvp" (update estado "pvp" #(min 3 (inc (long (or % 0)))))
             ("ginasios" "tipos")
             (update estado evento #(vec (distinct (concat (or % []) valores))))
             estado))))

(defn recompensas-bolas
  [missao]
  {"pokebola" (long (:pokebolas missao 0))
   "grande-bola" (long (:grandes missao 0))
   "ultra-bola" (long (:ultras missao 0))
   "catalisador-evolutivo" (long (:catalisadores missao 0))})

(defn sortear-bonus
  "Um sorteio por missão: 10% Ultra, 25% Grande, 65% sem bônus.
   `roll` ∈ [0,100)."
  [roll]
  (let [sorteio (long (or roll 0))]
    (cond (< sorteio 10) "ultra-bola"
          (< sorteio 35) "grande-bola"
          :else nil)))

(defn sortear-reviver?
  [roll]
  (< (long (or roll 0)) 20))

(defn texto-recompensas
  [recompensas]
  (->> ordem-recompensas
       (keep (fn [id]
               (let [qtd (long (get recompensas id 0))]
                 (when (pos? qtd)
                   (str qtd "× " (get nomes-itens id id))))))
       (str/join ", ")))

(defn aplicar-guardar-recompensas
  "Espelha guardar-recompensas: preenche inventário até a capacidade; resto pendente."
  [inventario pendentes recompensas livres]
  (let [livres (max 0 (long livres))]
    (reduce
     (fn [[inv pend livre] item-id]
       (let [qtd (long (get recompensas item-id 0))
             recebidas (min qtd livre)]
         [(if (pos? recebidas)
            (update inv item-id (fnil + 0) recebidas)
            inv)
          (let [resto (- qtd recebidas)]
            (if (pos? resto)
              (update pend item-id (fnil + 0) resto)
              pend))
          (- livre recebidas)]))
     [(or inventario {}) (or pendentes {}) livres]
     ordem-recompensas)))

(defn montar-recompensas-diario
  "Aplica sorteios das diárias. rolls = {:bonus [..] :reviver [..] :evo [..]} por missão."
  [prontas rolls]
  (let [bonus-rolls (or (:bonus rolls) [])
        reviver-rolls (or (:reviver rolls) [])
        evo-rolls (or (:evo rolls) [])
        evo-picks (or (:evo-picks rolls) [])]
    (reduce-kv
     (fn [acc i missao]
       (let [base (recompensas-bolas missao)
             bonus (sortear-bonus (nth bonus-rolls i 100))
             com-bonus (cond-> base bonus (update bonus (fnil inc 0)))
             com-rev (cond-> com-bonus
                       (sortear-reviver? (nth reviver-rolls i 100))
                       (update "reviver" (fnil inc 0)))
             evo? (< (long (nth evo-rolls i 100)) 20)
             evo-id (when evo?
                      (or (nth evo-picks i nil)
                          (nth itens-evolucao-troca
                               (mod (long (nth evo-rolls i 0)) (count itens-evolucao-troca))
                               "upgrade")))]
         (merge-with + acc (cond-> com-rev evo-id (update evo-id (fnil inc 0))))))
     {}
     (vec prontas))))

(defn resgatar-diario
  "Retorna {:conta :xp :recompensas :prontas} ou {:vazio true}."
  [conta dia nivel rolls]
  (let [estado (estado-do-dia conta dia nivel)
        prontas (disponiveis estado)]
    (if (empty? prontas)
      {:vazio true :conta conta :prontas []}
      (let [xp (reduce + 0 (map :xp prontas))
            recompensas (montar-recompensas-diario prontas rolls)
            novo-estado (update estado "resgatadas" #(vec (distinct (concat (or % []) (map :id prontas)))))]
        {:vazio false
         :conta (-> conta
                    (assoc "missoes-diarias" novo-estado)
                    (update "xp-missoes" (fnil + 0) xp))
         :xp xp
         :recompensas recompensas
         :prontas prontas}))))

(defn resgatar-semanal
  "Retorna {:conta :moedas :recompensas :prontas}.
   Master: ao resgatar com moedas>0, sorteia 1 item de evolução-troca."
  [conta semana rolls]
  (let [estado (estado-semanal conta semana)
        prontas (filterv #(and (>= (progresso-semanal estado (:id %)) (:meta %))
                               (not (some #{(:id %)} (get estado "resgatadas" []))))
                         catalogo-semanal)
        moedas (reduce + 0 (map :moedas prontas))
        bolas (reduce #(merge-with + %1 (recompensas-bolas %2)) {} prontas)
        evo-idx (long (or (:evo-index rolls) 0))
        evo-id (when (pos? moedas)
                 (or (:evo-pick rolls)
                     (nth itens-evolucao-troca (mod evo-idx (count itens-evolucao-troca)))))
        bolas (cond-> bolas evo-id (update evo-id (fnil inc 0)))
        novo-estado (update estado "resgatadas" #(vec (distinct (concat (or % []) (map :id prontas)))))]
    {:conta (cond-> (assoc conta "missoes-semanais" novo-estado)
              (pos? moedas) (update "moedas" (fnil + 0) moedas))
     :moedas moedas
     :recompensas bolas
     :prontas prontas}))

(defn texto-diario
  [estado dia prefix]
  (let [cat (catalogo-do-dia estado)
        nivel (get estado "nivel" 1)]
    (str "📋 *Missões diárias — " dia "*\nNível de referência hoje: " nivel "\n"
         (str/join "\n\n"
                   (for [{:keys [id nome objetivo meta xp pokebolas grandes ultras catalisadores]} cat
                         :let [progresso (long (get-in estado ["progresso" id] 0))
                               resgatada? (some #{id} (get estado "resgatadas" []))]]
                     (str (cond resgatada? "🎁"
                                (>= progresso meta) "✅"
                                :else "⬜")
                          " *" nome "*: " objetivo " — " progresso "/" meta
                          "\n+" xp " PE do treinador e " pokebolas " Pokébolas, "
                          (or grandes 0) " Grandes e " (or ultras 0) " Ultras"
                          (when (pos? (or catalisadores 0))
                            (str ", " catalisadores " Catalisador Evolutivo"))
                          (when resgatada? " (resgatada)"))))
         "\n\nCada missão: 25% de chance de +1 Grande Bola, 10% de +1 Ultra Bola; 65% sem bônus."
         " Um único sorteio de bola bônus por missão, além das Pokébolas garantidas."
         "\n💎 Chance independente de 20% de +1 Reviver por missão, exclusivo das missões."
         "\nMetas, PE e Pokébolas aumentam a cada 5 níveis; a faixa fica fixa até a próxima renovação."
         "\nResgate as concluídas com " prefix " missoes diarias resgatar."
         "\nRenovação à meia-noite (" timezone "). Resgate antes da virada!"
         "\nDesistências e fugas não contam como vitórias. PE significa Pontos de experiência do treinador; Pokémon recebem XP.")))

(defn texto-semanal
  [estado semana prefix & [{:keys [resgatar? moedas recompensas]}]]
  (str "📅 *Missões semanais — semana de " semana "*\n"
       (when resgatar?
         (str "💰 " (or moedas 0) " moedas resgatadas.\n🎁 "
              (texto-recompensas (or recompensas {})) "\n"))
       (str/join "\n"
                 (for [{:keys [id objetivo meta moedas] :as missao} catalogo-semanal]
                   (str (if (some #{id} (get estado "resgatadas" [])) "✅ " "🎯 ")
                        objetivo ": " (progresso-semanal estado id) "/" meta
                        " • " moedas " moedas • "
                        (texto-recompensas (recompensas-bolas missao)))))
       "\nReinicia na segunda-feira (" timezone ")."
       "\nResgate até o fim da semana: " prefix " missoes semanais resgatar."))

(defn evento-diario?
  [evento]
  (contains? #{"selvagens" "capturas" "vitorias" "presentes"} (str evento)))

(defn evento-semanal?
  [evento]
  (contains? #{"ginasios" "tipos" "pvp" "presentes"} (str evento)))
