(ns zapbot.pokemon.ovos)

(def limite-inventario 9)
(def intervalo-ms 300000)
(def janela-ms (* 7 24 60 60 1000))

;; Etapa A: domínio experimental por tempo/pontos. Este estado ainda não é
;; ligado ao Cassandra nem aos comandos; a flag de produção permanece falsa.
(def categorias-atividade
  {:comum {:tempo-ms (* 2 60 60 1000) :pontos 100}
   :raro {:tempo-ms (* 6 60 60 1000) :pontos 250}
   :epico {:tempo-ms (* 12 60 60 1000) :pontos 500}})
(def max-incubadoras-atividade 3)
(def max-temporarias-atividade 2)
(def atividade-schema-version 2)

(defn- categoria-legada [categoria]
  (or (get {2 :comum 5 :raro 10 :epico "2" :comum "5" :raro "10" :epico} categoria)
      (erro! :legacy-category "Categoria legada desconhecida.")))

(defn- converter-ovo-legado [ovo]
  (let [categoria (categoria-legada (:category ovo))
        meta (get categorias-atividade categoria)
        objetivo (max 1 (:goal-mm ovo))
        progresso (min objetivo (max 0 (:progress-mm ovo)))
        pontos (min (:pontos meta)
                    (js/Math.floor (* (:pontos meta) (/ progresso objetivo))))]
    (-> ovo
        (assoc :category categoria
               :points pontos
               :points-goal (:pontos meta)
               :time-ms 0
               :time-goal-ms (:tempo-ms meta)
               :legacy-progress-mm progresso
               :legacy-goal-mm objetivo)
        (dissoc :goal-mm :progress-mm))))

(defn transicionar-legado
  "Converte somente em memória um estado v1 de distância para v2.
   A marca de versão torna a operação idempotente; nenhuma entrega é criada."
  [estado]
  (if (>= (or (:mechanics-version estado) 1) atividade-schema-version)
    estado
    (-> estado
        (update :eggs #(mapv converter-ovo-legado %))
        (assoc :mechanics-version atividade-schema-version
               :transition-receipt "distance-to-activity-v2"))))

(defn estado-atividade-inicial []
  {:eggs []
   :incubators [{:incubator-id "permanente" :kind :permanente :uses nil
                 :bonus-points 0 :active-egg-id nil}]
   :receipts #{}})

(defn novo-ovo-atividade [id categoria criado-em]
  (let [{:keys [tempo-ms pontos]} (get categorias-atividade categoria)]
    (when-not (and (string? id) (not (empty? id)) tempo-ms)
      (erro! :invalid-activity-egg "Categoria de ovo de atividade inválida."))
    {:egg-id id :category categoria :created-at criado-em :state :inventory
     :points 0 :points-goal pontos :time-ms 0 :time-goal-ms tempo-ms
     :incubator-id nil :incubated-at nil :hatched-at nil}))

(defn conceder-ovo-atividade [estado ovo]
  (when (>= (count (remove #(= :hatched (:state %)) (:eggs estado))) limite-inventario)
    (erro! :egg-inventory-full "Inventário de ovos cheio."))
  (if (some #(= (:egg-id ovo) (:egg-id %)) (:eggs estado)) estado
      (update estado :eggs conj ovo)))

(defn adicionar-incubadora-atividade [estado id kind usos bonus]
  (when (or (some #(= id (:incubator-id %)) (:incubators estado))
            (>= (count (:incubators estado)) max-incubadoras-atividade)
            (and (not= kind :permanente)
                 (>= (count (remove #(= :permanente (:kind %)) (:incubators estado)))
                     max-temporarias-atividade)))
    (erro! :incubator-limit "Limite de incubadoras atingido."))
  (update estado :incubators conj {:incubator-id id :kind kind :uses usos
                                   :bonus-points (or bonus 0) :active-egg-id nil}))

(defn incubar-atividade [estado egg-id incubator-id agora]
  (let [ovo (some #(when (= egg-id (:egg-id %)) %) (:eggs estado))
        incubadora (some #(when (= incubator-id (:incubator-id %)) %) (:incubators estado))]
    (when-not ovo (erro! :egg-not-found "Ovo não encontrado."))
    (when-not incubadora (erro! :incubator-not-found "Incubadora não encontrada."))
    (when-not (= :inventory (:state ovo)) (erro! :invalid-incubation "Ovo não está disponível."))
    (when (:active-egg-id incubadora) (erro! :incubator-busy "Incubadora ocupada."))
    (-> estado
        (atualizar-ovo egg-id #(assoc % :state :incubating :incubator-id incubator-id
                                      :incubated-at agora))
        (update :incubators #(mapv (fn [i] (if (= incubator-id (:incubator-id i))
                                             (assoc i :active-egg-id egg-id) i)) %)))))

(defn- pronto-atividade? [ovo]
  (or (>= (:points ovo) (:points-goal ovo))
      (>= (:time-ms ovo) (:time-goal-ms ovo))))

(defn aplicar-pontos-atividade [estado event-id pontos]
  (when-not (and (string? event-id) (not (empty? event-id))
                 (js/Number.isSafeInteger pontos) (pos? pontos))
    (erro! :invalid-activity "Evento ou pontos inválidos."))
  (if (contains? (:receipts estado) event-id) estado
      (let [ativos (set (keep :active-egg-id (:incubators estado)))
            recebido (fn [ovo]
                       (if (and (contains? ativos (:egg-id ovo)) (= :incubating (:state ovo)))
                         (let [inc (some #(when (= (:incubator-id ovo) (:incubator-id %)) (:bonus-points %)) (:incubators estado))
                               pontos (+ pontos (js/Math.floor (* pontos (/ (or inc 0) 100))))
                               atual (update ovo :points #(min (:points-goal ovo) (+ % pontos)))]
                           (if (pronto-atividade? atual) (assoc atual :state :ready-to-hatch) atual)) ovo))]
        (-> estado (update :eggs #(mapv recebido %)) (update :receipts conj event-id)))))

(defn avancar-tempo-atividade [estado agora]
  (when-not (js/Number.isSafeInteger agora) (erro! :invalid-time "Instante inválido."))
  (update estado :eggs #(mapv (fn [ovo]
                                (if (= :incubating (:state ovo))
                                  (let [atual (assoc ovo :time-ms (max 0 (- agora (:incubated-at ovo))))]
                                    (if (pronto-atividade? atual) (assoc atual :state :ready-to-hatch) atual)) ovo)) %)))

(defn concluir-atividade [estado egg-id agora]
  (let [ovo (some #(when (= egg-id (:egg-id %)) %) (:eggs estado))
        incubator-id (:incubator-id ovo)]
    (when-not (= :ready-to-hatch (:state ovo)) (erro! :not-ready "Ovo ainda não está pronto."))
    (let [incubadora (some #(when (= incubator-id (:incubator-id %)) %) (:incubators estado))
          usos (:uses incubadora)
          atual-inc (if (nil? usos) incubadora
                        (assoc incubadora :uses (dec usos)))
          incubadoras (mapv (fn [i] (if (= incubator-id (:incubator-id i))
                                      (assoc atual-inc :active-egg-id nil) i)) (:incubators estado))]
      (-> estado
          (update :eggs #(mapv (fn [o] (if (= egg-id (:egg-id o))
                                         (assoc o :state :hatched :hatched-at agora) o)) %))
          (assoc :incubators (if (and (some? usos) (zero? (:uses atual-inc)))
                               (vec (remove #(= incubator-id (:incubator-id %)) incubadoras))
                               incubadoras))))))

(defn erro! [code texto] (throw (ex-info texto {:code code})))
(defn inteiro! [valor]
  (when-not (and (js/Number.isSafeInteger valor) (<= 0 valor))
    (erro! :invalid-distance "Distância/tempo deve ser um inteiro seguro não negativo."))
  valor)

(defn estado-inicial [account-id cid pid incubator-id]
  {:schema-version 1 :account-id account-id :chat-id cid :player-id pid
   :eggs [] :incubators [{:incubator-id incubator-id :reusable true :active-egg-id nil}]
   :receipts [] :history-pending [] :next-hatch-seq 1 :total-credited-mm 0 :finalized-before 0})

(defn ovo [estado id] (some #(when (= id (:egg-id %)) %) (:eggs estado)))
(defn atualizar-ovo [estado id mudar]
  (update estado :eggs #(mapv (fn [ovo] (if (= id (:egg-id ovo)) (mudar ovo) ovo)) %)))
(defn novo-ovo [id categoria catalogo pool seed agora]
  (when-not (contains? #{2 5 10} categoria) (erro! :invalid-category "Ovo deve ser de 2, 5 ou 10 km."))
  {:egg-id id :category categoria :goal-mm (* categoria 1000000) :progress-mm 0
   :state "inventory" :created-at agora :catalog-version catalogo :pool pool :seed seed})

(defn conceder [estado ovo]
  (when (>= (count (remove #(= "hatched" (:state %)) (:eggs estado))) limite-inventario)
    (erro! :egg-inventory-full "Inventário de ovos cheio."))
  (update estado :eggs conj ovo))

(defn incubar [estado egg-id agora]
  (let [ovo (ovo estado egg-id) incubadora (first (:incubators estado))]
    (when-not ovo (erro! :egg-not-found "Ovo não encontrado nesta conta."))
    (when-not (= "inventory" (:state ovo)) (erro! :invalid-incubation "Ovo não está disponível."))
    (when (:active-egg-id incubadora) (erro! :incubator-busy "Incubadora já está ocupada."))
    (-> estado
        (atualizar-ovo egg-id #(assoc % :state "incubating" :incubated-at agora
                                    :eligible-from (* (inc (js/Math.floor (/ agora intervalo-ms))) intervalo-ms)))
        (assoc-in [:incubators 0 :active-egg-id] egg-id))))

(defn aplicar-distancia
  "Incremento interno já validado/consolidado. Não é uma leitura móvel bruta."
  [estado {:keys [from-ms to-ms distance-mm]} agora]
  (doseq [valor [from-ms to-ms distance-mm agora]] (inteiro! valor))
  (when-not (and (< from-ms to-ms) (<= to-ms agora))
    (erro! :invalid-interval "Intervalo interno inválido ou futuro."))
  (when (< from-ms (max (get estado :finalized-before 0) (- agora janela-ms)))
    (erro! :window-expired "Incremento fora da janela de sete dias."))
  (let [id (get-in estado [:incubators 0 :active-egg-id]) ovo (ovo estado id)]
    (if-not (and ovo (= "incubating" (:state ovo)))
      [estado {:credited-mm 0 :egg-id nil}]
      (do
        (when (< from-ms (:eligible-from ovo))
          (erro! :before-incubation "Incremento inclui caminhada anterior à incubação."))
        (let [credito (min distance-mm (- (:goal-mm ovo) (:progress-mm ovo)))
              progresso (+ (:progress-mm ovo) credito)]
          [(-> estado
               (atualizar-ovo id #(assoc % :progress-mm progresso
                                       :state (if (= progresso (:goal-mm ovo)) "ready_to_hatch" "incubating")))
               (update :total-credited-mm + credito))
           {:credited-mm credito :egg-id id :ready? (= progresso (:goal-mm ovo))}])))))

(defn preparar-nascimento [estado egg-id resultado]
  (let [ovo (ovo estado egg-id)]
    (when-not (contains? #{"ready_to_hatch" "delivery_pending"} (:state ovo))
      (erro! :not-ready "Ovo ainda não está pronto."))
    (if (:resultado ovo) estado
        (atualizar-ovo estado egg-id #(assoc % :state "delivery_pending" :resultado resultado)))))

(defn concluir-nascimento [estado egg-id agora]
  (let [ovo (ovo estado egg-id)]
    (when-not (= "delivery_pending" (:state ovo)) (erro! :not-ready "Entrega não está pendente."))
    (let [historico (merge (:resultado ovo)
                          {:egg-id egg-id :category (:category ovo) :catalog-version (:catalog-version ovo)
                           :seq (:next-hatch-seq estado) :hatched-at agora})]
      (-> estado
          ;; O resultado completo passa para history-pending; não duplicá-lo
          ;; no payload durante a recuperação, nem reter seed após a entrega.
          (atualizar-ovo egg-id #(-> % (assoc :state "hatched")
                                    (dissoc :resultado :pool :seed)))
          (assoc-in [:incubators 0 :active-egg-id] nil)
          (update :history-pending conj historico)
          (update :next-hatch-seq inc)))))

(defn consulta [estado]
  {:account-id (:account-id estado)
   :eggs (mapv #(dissoc % :seed :pool :resultado)
               (remove #(= "hatched" (:state %)) (:eggs estado)))
   :incubators (:incubators estado) :total-credited-mm (:total-credited-mm estado)
   :history-pending (count (:history-pending estado))})
