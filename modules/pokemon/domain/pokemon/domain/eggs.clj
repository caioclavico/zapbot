(ns pokemon.domain.eggs
  "Adventure Sync — ovos/incubação (puro). Espelha pokemon-service ovos.cljs atividade."
  (:require [clojure.string :as str]
            [pokemon.domain.egg-hatch :as egg-hatch]
            [pokemon.domain.errors :as err]))

(def limite-inventario 9)
(def max-incubadoras 3)
(def max-temporarias 2)

(def categorias
  "Metas padrão na concessão. Ovos já persistidos mantêm points-goal/time-goal próprios."
  {:comum {:tempo-ms (* 2 60 60 1000) :pontos 100}
   :raro  {:tempo-ms (* 6 60 60 1000) :pontos 250}
   :epico {:tempo-ms (* 12 60 60 1000) :pontos 500}
   :especial {:tempo-ms (* 24 60 60 1000) :pontos 800}})

(defn estado-inicial
  []
  {:eggs []
   :incubators [{:incubator-id "permanente"
                 :kind :permanente
                 :uses nil
                 :bonus-points 0
                 :active-egg-id nil}]
   :receipts #{}
   :pending-egg-grants []
   ;; Fila FIFO de direitos de incubadora (progressão). Ver egg-activity.
   :pending-incubator-grants []
   ;; Vitórias canônicas de raid (por jogador / conta Adventure).
   :raid-wins 0
   :pi-daily nil})

(defn temporary-incubators
  [estado]
  (filterv #(#{:extra :especial} (keyword (:kind %))) (:incubators estado)))

(defn free-temporary-slots
  "Vagas restantes para incubadoras temporárias (máx. 2)."
  [estado]
  (max 0 (- max-temporarias (count (temporary-incubators estado)))))

(defn- ovo
  [estado egg-id]
  (some #(when (= egg-id (:egg-id %)) %) (:eggs estado)))

(defn- atualizar-ovo
  [estado egg-id f]
  (update estado :eggs
          #(mapv (fn [o] (if (= egg-id (:egg-id o)) (f o) o)) %)))

(defn novo-ovo
  [egg-id categoria criado-em species]
  (let [cat (keyword categoria)
        meta (or (get categorias cat)
                 (throw (err/error :invalid_egg_category {:categoria categoria})))]
    {:egg-id egg-id
     :category cat
     :created-at (long criado-em)
     :state :inventory
     :points 0
     :points-goal (:pontos meta)
     :time-ms 0
     :time-goal-ms (:tempo-ms meta)
     :species species
     :incubator-id nil
     :incubated-at nil
     :due-at nil
     :hatched-at nil
     :save-id nil
     :fence-generation nil
     :hatch-id nil
     :resultado nil}))

(defn inventariados-count
  [estado]
  (count (remove #(= :hatched (:state %)) (:eggs estado))))

(defn conceder-ovo
  [estado ovo]
  (when (>= (inventariados-count estado) limite-inventario)
    (throw (err/error :egg_inventory_full {:limit limite-inventario})))
  (if (some #(= (:egg-id ovo) (:egg-id %)) (:eggs estado))
    estado
    (update estado :eggs conj ovo)))

(defn pronto?
  [ovo]
  (or (>= (long (:points ovo 0)) (long (:points-goal ovo 0)))
      (>= (long (:time-ms ovo 0)) (long (:time-goal-ms ovo 0)))))

(defn resolve-incubator-id
  "Resolve id canônico. Aceita atalhos `extra`/`especial` → primeira livre do kind."
  [estado incubator-id]
  (let [raw (str (or incubator-id "permanente"))
        want (case raw
               ("extra" "temporaria" "temporária") :extra
               "especial" :especial
               nil)]
    (if want
      (or (some #(when (and (= want (keyword (:kind %)))
                            (nil? (:active-egg-id %)))
                   (:incubator-id %))
                (:incubators estado))
          (throw (err/error :incubator_not_found {:incubator-id raw :kind want})))
      raw)))

(defn incubar
  "Inicia incubação e carimba save-id + fence (impede entrega em outro save)."
  [estado egg-id incubator-id agora save-id fence-generation]
  (let [o (ovo estado egg-id)
        resolved (resolve-incubator-id estado incubator-id)
        incu (some #(when (= resolved (:incubator-id %)) %) (:incubators estado))]
    (when-not o (throw (err/error :egg_not_found {:egg-id egg-id})))
    (when-not incu (throw (err/error :incubator_not_found {:incubator-id resolved})))
    (when-not (= :inventory (:state o))
      (throw (err/error :invalid_incubation {:state (:state o)})))
    (when (:active-egg-id incu)
      (throw (err/error :incubator_busy {:incubator-id resolved})))
    (when-not (and save-id (integer? fence-generation))
      (throw (err/error :missing_egg_save_seal {})))
    (-> estado
        (atualizar-ovo egg-id
                       #(assoc % :state :incubating
                               :incubator-id resolved
                               :incubated-at (long agora)
                               :due-at (+ (long agora) (long (:time-goal-ms %)))
                               :save-id save-id
                               :fence-generation (long fence-generation)
                               :time-ms 0))
        (update :incubators
                #(mapv (fn [i]
                         (if (= resolved (:incubator-id i))
                           (assoc i :active-egg-id egg-id)
                           i))
                       %)))))

(defn avancar-tempo
  [estado agora]
  (update estado :eggs
          #(mapv (fn [o]
                   (if (= :incubating (:state o))
                     (let [elapsed (max 0 (- (long agora) (long (:incubated-at o 0))))
                           atual (assoc o :time-ms (max (long (:time-ms o 0)) elapsed))]
                       (if (pronto? atual)
                         (assoc atual :state :ready-to-hatch)
                         atual))
                     o))
                 %)))

(defn adicionar-incubadora
  "Concede incubadora temporária idempotente por incubator-id."
  [estado incubator-id kind]
  (let [kind (keyword kind)
        id (str incubator-id)
        existing (some #(when (= id (:incubator-id %)) %) (:incubators estado))]
    (cond
      existing estado
      (not (#{:extra :especial} kind))
      (throw (err/error :invalid_incubator_kind {:kind kind}))
      (>= (count (:incubators estado)) max-incubadoras)
      (throw (err/error :incubator_limit {:limit max-incubadoras}))
      (>= (count (filter #(#{:extra :especial} (:kind %)) (:incubators estado)))
          max-temporarias)
      (throw (err/error :incubator_limit {:limit max-temporarias}))
      :else
      (update estado :incubators conj
              {:incubator-id id
               :kind kind
               :uses 3
               :bonus-points (if (= :especial kind) 25 0)
               :active-egg-id nil}))))

(defn aplicar-pontos
  "Credita PI nos ovos em incubação. O total do evento é dividido entre incubadoras
  ativas (sem multiplicar a mesma recompensa). Bonus % da incubadora aplica-se à
  fatia daquele ovo. Idempotente por event-id."
  [estado event-id pontos]
  (if (contains? (:receipts estado) event-id)
    estado
    (let [ativos (->> (:incubators estado)
                      (keep (fn [i]
                              (when-let [eid (:active-egg-id i)]
                                {:egg-id eid
                                 :bonus (long (:bonus-points i 0))})))
                      vec)
          n (count ativos)
          base (long pontos)]
      (if (or (zero? n) (zero? base))
        (update estado :receipts conj event-id)
        (let [share (quot base n)
              rem (rem base n)
              gains (into {}
                          (map-indexed
                           (fn [i {:keys [egg-id bonus]}]
                             (let [raw (+ share (if (< i rem) 1 0))
                                   ganho (long (Math/floor
                                                (* (double raw)
                                                   (+ 1.0 (/ (double bonus) 100.0)))))]
                               [egg-id ganho]))
                           ativos))
              aplicar
              (fn [o]
                (if-let [g (get gains (:egg-id o))]
                  (if (= :incubating (:state o))
                    (let [atual (update o :points
                                        #(min (long (:points-goal o))
                                              (+ (long %) (long g))))]
                      (if (pronto? atual) (assoc atual :state :ready-to-hatch) atual))
                    o)
                  o))]
          (-> estado
              (update :eggs #(mapv aplicar %))
              (update :receipts conj event-id)))))))

(defn preparar-nascimento
  "Registro nível 1 canônico (catálogo local + growth-v1). Determinístico no seed.
   Sem dados da espécie → erro `:egg_species_data_missing` (não consumir ovo)."
  ([species pokemon-id] (preparar-nascimento species pokemon-id pokemon-id))
  ([species pokemon-id seed]
   (egg-hatch/preparar-registro species pokemon-id seed)))

(defn assert-hatch-save!
  "Entrega só no save carimbado. Fence do ovo NÃO precisa bater com o writer
  atual (A→B→A sobe a geração); callback stale é bloqueado pelo fence do writer."
  [ovo writer-ctx]
  (when-not (contains? #{:ready-to-hatch :delivery-pending} (:state ovo))
    (throw (err/error :egg_not_ready {:state (:state ovo)})))
  (when (not= (:save-id ovo) (:save-id writer-ctx))
    (throw (err/error :egg_save_mismatch
                      {:sealed (:save-id ovo)
                       :writer (:save-id writer-ctx)})))
  true)

(defn fixar-resultado
  "Idempotente: se já há resultado, devolve o existente (sem re-sortear shiny/IVs)."
  [ovo hatch-id pokemon-id]
  (if (:resultado ovo)
    ovo
    (let [reg (preparar-nascimento (:species ovo) pokemon-id hatch-id)]
      (assoc ovo
             :state :delivery-pending
             :hatch-id hatch-id
             :resultado {:hatch-id hatch-id
                         :pokemon-id pokemon-id
                         :species (:species ovo)
                         :registro reg
                         :save-id (:save-id ovo)}))))
(defn concluir-hatch
  [estado egg-id agora]
  (let [o (ovo estado egg-id)
        incubator-id (:incubator-id o)]
    (when-not (= :delivery-pending (:state o))
      (throw (err/error :egg_not_pending {:state (:state o)})))
    (let [incu (some #(when (= incubator-id (:incubator-id %)) %) (:incubators estado))
          usos (:uses incu)
          atual-inc (if (nil? usos) incu (assoc incu :uses (dec usos)))
          incubadoras (mapv (fn [i]
                              (if (= incubator-id (:incubator-id i))
                                (assoc atual-inc :active-egg-id nil)
                                i))
                            (:incubators estado))
          incubadoras' (if (and (some? usos) (zero? (:uses atual-inc)))
                         (vec (remove #(= incubator-id (:incubator-id %)) incubadoras))
                         incubadoras)]
      (-> estado
          (atualizar-ovo egg-id
                         #(assoc % :state :hatched :hatched-at (long agora)))
          (assoc :incubators incubadoras')))))
