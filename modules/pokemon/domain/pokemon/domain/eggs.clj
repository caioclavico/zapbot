(ns pokemon.domain.eggs
  "Adventure Sync — ovos/incubação (puro). Espelha pokemon-service ovos.cljs atividade."
  (:require [clojure.string :as str]
            [pokemon.domain.errors :as err])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

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
   :pi-daily nil})

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

(defn- hatch-seed-bytes
  [seed]
  (.digest (MessageDigest/getInstance "SHA-256")
           (.getBytes (str seed) StandardCharsets/UTF_8)))

(defn- u8
  [^bytes b i]
  (bit-and (long (aget b (int i))) 0xff))

(defn preparar-nascimento
  "Registro nível 1. Shiny 1/512 e IVs 0–31 a partir do seed (hatch-id) —
  estável em retries. `species` já fixada na concessão."
  ([species pokemon-id] (preparar-nascimento species pokemon-id pokemon-id))
  ([species pokemon-id seed]
   (let [b (hatch-seed-bytes (str seed "|" species "|" pokemon-id))
         shiny? (zero? (mod (+ (bit-shift-left (u8 b 0) 8) (u8 b 1)) 512))
         iv (fn [i] (mod (u8 b (+ 2 i)) 32))
         nome (let [s (str species)]
                (if (str/blank? s) "Pokémon"
                    (str (str/upper-case (subs s 0 1)) (subs s 1))))
         ivs {:hp (iv 0) :ataque (iv 1) :defesa (iv 2)
              :atq-esp (iv 3) :def-esp (iv 4) :veloc (iv 5)}
         base 40
         hp (+ 45 (iv 0))]
     {:id-pokemon pokemon-id
      :nome nome
      :species (str species)
      :nivel 1
      :xp-desde-nivel 0
      :hp hp
      :hp-atual hp
      :ataque (+ base (iv 1))
      :defesa (+ base (iv 2))
      :atq-esp (+ base (iv 3))
      :def-esp (+ base (iv 4))
      :veloc (+ base (iv 5))
      :tipos ["normal"]
      :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
      :shiny shiny?
      :shiny? shiny?
      :ivs ivs
      :raridade "comum"
      :origem "ovo"
      :amizade 70
      :status nil})))

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
