(ns pokemon.domain.eggs
  "Adventure Sync — ovos/incubação (puro). Espelha pokemon-service ovos.cljs atividade."
  (:require [pokemon.domain.errors :as err]))

(def limite-inventario 9)
(def max-incubadoras 3)
(def max-temporarias 2)

(def categorias
  {:comum {:tempo-ms (* 2 60 60 1000) :pontos 100}
   :raro  {:tempo-ms (* 6 60 60 1000) :pontos 250}
   :epico {:tempo-ms (* 12 60 60 1000) :pontos 500}})

(defn estado-inicial
  []
  {:eggs []
   :incubators [{:incubator-id "permanente"
                 :kind :permanente
                 :uses nil
                 :bonus-points 0
                 :active-egg-id nil}]
   :receipts #{}})

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

(defn incubar
  "Inicia incubação e carimba save-id + fence (impede entrega em outro save)."
  [estado egg-id incubator-id agora save-id fence-generation]
  (let [o (ovo estado egg-id)
        incu (some #(when (= incubator-id (:incubator-id %)) %) (:incubators estado))]
    (when-not o (throw (err/error :egg_not_found {:egg-id egg-id})))
    (when-not incu (throw (err/error :incubator_not_found {:incubator-id incubator-id})))
    (when-not (= :inventory (:state o))
      (throw (err/error :invalid_incubation {:state (:state o)})))
    (when (:active-egg-id incu)
      (throw (err/error :incubator_busy {:incubator-id incubator-id})))
    (when-not (and save-id (integer? fence-generation))
      (throw (err/error :missing_egg_save_seal {})))
    (-> estado
        (atualizar-ovo egg-id
                       #(assoc % :state :incubating
                               :incubator-id incubator-id
                               :incubated-at (long agora)
                               :due-at (+ (long agora) (long (:time-goal-ms %)))
                               :save-id save-id
                               :fence-generation (long fence-generation)
                               :time-ms 0))
        (update :incubators
                #(mapv (fn [i]
                         (if (= incubator-id (:incubator-id i))
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

(defn aplicar-pontos
  [estado event-id pontos]
  (if (contains? (:receipts estado) event-id)
    estado
    (let [ativos (set (keep :active-egg-id (:incubators estado)))
          aplicar
          (fn [o]
            (if (and (contains? ativos (:egg-id o)) (= :incubating (:state o)))
              (let [bonus (or (some #(when (= (:incubator-id o) (:incubator-id %))
                                       (:bonus-points %))
                                    (:incubators estado))
                              0)
                    ganho (* (double pontos) (+ 1.0 (/ (double bonus) 100.0)))
                    atual (update o :points
                                  #(min (long (:points-goal o))
                                        (+ (long %) (long (Math/floor ganho)))))]
                (if (pronto? atual) (assoc atual :state :ready-to-hatch) atual))
              o))]
      (-> estado
          (update :eggs #(mapv aplicar %))
          (update :receipts conj event-id)))))

(defn preparar-nascimento
  "Registro nível 1, HP cheio — espelha preparar-registro-nascimento."
  [species pokemon-id]
  {:id-pokemon pokemon-id
   :nome species
   :species species
   :nivel 1
   :xp-desde-nivel 0
   :hp 50
   :hp-atual 50
   :ataque 40
   :defesa 40
   :atq-esp 40
   :def-esp 40
   :veloc 40
   :tipos ["normal"]
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
   :shiny false
   :raridade "comum"
   :amizade 70
   :status nil})

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
  "Idempotente: se já há resultado, devolve o existente."
  [ovo hatch-id pokemon-id]
  (if (:resultado ovo)
    ovo
    (let [reg (preparar-nascimento (:species ovo) pokemon-id)]
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
