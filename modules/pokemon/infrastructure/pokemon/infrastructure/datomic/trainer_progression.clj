(ns pokemon.infrastructure.datomic.trainer-progression
  "Expande transações que concedem PE com nível, sorteio, inventário e recibo atômicos."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.identities :as id]
            [pokemon.domain.mission-rewards :as mission-rewards]
            [pokemon.domain.trainer-rewards :as trainer-rewards]
            [pokemon.domain.xp :as xp]))

(def ^:private pe-attrs
  #{:save/vitorias-treinador :save/pe-ginasios :save/pe-raids
    :save/xp-missoes :save/doacoes-pokemon :save/maior-sequencia-capturas
    :save/trainer-xp :save/trainer-level})

(defn- save-key-from-ref [x]
  (when (and (vector? x) (= :save/key (first x))) (str (second x))))

(defn- tx-target [form]
  (cond
    (and (vector? form) (>= (count form) 3)) (second form)
    (map? form) (or (:db/id form) (when (:save/key form) [:save/key (:save/key form)]))
    :else nil))

(defn- patches-for [tx sk]
  (reduce
   (fn [acc form]
     (let [target (tx-target form)
           target-sk (save-key-from-ref target)]
       (if (and (= sk target-sk) (vector? form) (>= (count form) 4)
                (contains? pe-attrs (nth form 2))
                (#{:db/add :db/cas} (first form)))
         (assoc acc (nth form 2) (last form))
         (if (and (map? form) (= sk (some-> (or (:save/key form)
                                                  (save-key-from-ref (:db/id form))) str)))
           (merge acc (select-keys form pe-attrs))
           acc))))
   {} tx))

(defn- save-components [save]
  (let [metrics {:doacoes-pokemon (:save/doacoes-pokemon save 0)
                 :vitorias-treinador (:save/vitorias-treinador save 0)
                 :maior-sequencia-capturas (:save/maior-sequencia-capturas save 0)}]
    {:vitorias-treinador (long (:save/vitorias-treinador save 0))
     :pe-ginasios (long (:save/pe-ginasios save 0))
     :pe-raids (long (:save/pe-raids save 0))
     :xp-missoes (long (:save/xp-missoes save 0))
     :xp-insignias (xp/xp-insignias-from-metrics metrics)}))

(defn- patched-save [save patches]
  (reduce-kv assoc save patches))

(defn- component-total [save]
  (xp/xp-treinador-total (save-components save)))

(defn- legacy-start [save old-total]
  (let [legacy (xp/progresso-treinador-legado old-total)
        stored (long (:save/trainer-level save 1))
        level (max 1 stored (:nivel legacy))
        persisted (:save/trainer-xp-level-progress save)]
    ;; Once 036 has established the progressive level and residual, never
    ;; reinterpret cumulative PE using the old 5,7,9… curve. A rollback
    ;; target must understand these fields; the pre-036 binary is unsafe.
    (if (some? persisted)
      {:level (max 1 stored) :residual (max 0 (long persisted))}
      {:level level
       :residual (if (> stored (:nivel legacy)) 0 (long (:xp-atual legacy)))})))

(defn- read-pending [value]
  (if (string? value)
    (try (edn/read-string value) (catch Exception _ {}))
    {}))

(defn- pending-patch [tx sk]
  (some (fn [form]
          (when (and (vector? form) (= :db/add (first form))
                     (= sk (save-key-from-ref (second form)))
                     (= :save/recompensas-pendentes-edn (nth form 2)))
            (nth form 3))) tx))

(defn- existing-level-rewards [db save]
  (into {}
        (map (fn [[level payload]]
               [(str level) (try (edn/read-string payload) (catch Exception _ {}))]))
        (d/q '[:find ?level ?payload
               :in $ ?save
               :where [?r :trainer-level-reward/save ?save]
                      [?r :trainer-level-reward/level ?level]
                      [?r :trainer-level-reward/reward-edn ?payload]]
             db (:db/id save))))

(defn- remove-save-attrs [tx sk attrs]
  (vec
   (remove (fn [form]
             (and (vector? form) (>= (count form) 3)
                  (= :db/add (first form))
                  (= sk (save-key-from-ref (second form)))
                  (contains? attrs (nth form 2)))) tx)))

(defn- inventory-count [db save]
  (reduce + 0
          (map (comp long first)
               (d/q '[:find ?qty
                      :in $ ?save
                      :where [?i :inventory-item/save ?save]
                             [?i :inventory-item/qty ?qty]]
                    db (:db/id save)))))

(defn- inventory-delta-in-tx [db tx sk]
  (reduce
   (fn [acc form]
     (let [row (when (map? form) form)
           ik (or (:inventory-item/key row)
                  (when (and (vector? form) (= :db/add (first form))
                             (= :inventory-item/qty (nth form 2)))
                    (some-> (second form) second)))
           item-id (or (:inventory-item/item-id row)
                       (when (and ik (str/starts-with? (str ik) (str sk "|")))
                         (subs (str ik) (inc (count (str sk "|"))))))
           save-ref (or (:inventory-item/save row)
                        (when ik (when (str/starts-with? (str ik) (str sk "|")) [:save/key sk])))]
       (if (and (= [:save/key sk] save-ref) ik item-id)
         (let [new-qty (long (or (:inventory-item/qty row)
                                 (when (and (vector? form) (= :db/add (first form))) (last form))
                                 0))
               old (long (:inventory-item/qty
                          (d/pull db '[:inventory-item/qty] [:inventory-item/key ik]) 0))]
           (assoc acc (str item-id) (- new-qty old)))
         acc)))
   {} tx))

(defn- inventory-key-from-form [form]
  (or (when (map? form) (:inventory-item/key form))
      (when (and (vector? form) (>= (count form) 4)
                 (= :db/add (first form))
                 (= :inventory-item/qty (nth form 2))
                 (vector? (second form))
                 (= :inventory-item/key (first (second form)))
                 (string? (second (second form))))
        (second (second form)))))

(defn- remove-inventory-keys [tx keys-to-replace]
  (if (empty? keys-to-replace)
    tx
    (vec (remove #(contains? keys-to-replace (inventory-key-from-form %)) tx))))

(defn- receipt-op-for-save [tx save]
  (let [parts (str/split (:save/key save) #"\|" 3)
        pk (str (first parts) "|" (second parts))
        slot (:save/slot-id save)]
    (some (fn [row]
            (when (and (map? row)
                       (= pk (:operation-receipt/player-key row))
                       (= slot (:operation-receipt/save-id row)))
              (:operation-receipt/id row))) tx)))

(defn- allocate-level-items [db tx save sk level items pending]
  (let [capacity (capacity/capacidade-mochila
                  level (long (:save/expansoes-mochila-50 save 0))
                  (long (:save/capacidade-mochila-legada save 50)))
        occupied (+ (inventory-count db save)
                    (reduce + 0 (vals (inventory-delta-in-tx db tx sk))))
        allocation (mission-rewards/allocate
                    items pending (max 0 (- capacity occupied))
                    (into mission-rewards/order ["pocao" "pocao-maxima"]))
        inv-tx (mapcat (fn [[item qty]]
                         (let [key (id/inventory-item-key sk item)
                               old (long (:inventory-item/qty
                                          (d/pull db '[:inventory-item/qty]
                                                  [:inventory-item/key key]) 0))
                               original-delta (long (get (inventory-delta-in-tx db tx sk)
                                                         (str item) 0))]
                           [{:inventory-item/key key :inventory-item/save [:save/key sk]
                             :inventory-item/item-id item
                             :inventory-item/qty (+ old original-delta (long qty))}]))
                       (:added allocation))]
    {:tx (vec inv-tx) :added (:added allocation) :pending (:pending allocation)}))

(defn- positive-delta [after before]
  (into {}
        (keep (fn [[item qty]]
                (let [delta (- (long qty) (long (get before item 0)))]
                  (when (pos? delta) [item delta]))))
        after))

(defn- progress-save [db tx save operation-id]
  (let [sk (:save/key save)
        patches (patches-for tx sk)
        old-components (component-total save)
        new-save (patched-save save patches)
        new-components (component-total new-save)
        stored-xp (long (:save/trainer-xp save 0))
        direct-xp (max 0 (- (long (:save/trainer-xp patches stored-xp)) stored-xp))
        pe-delta (+ (max 0 (- new-components old-components)) direct-xp)
        old-total (max stored-xp old-components)
        new-total (max (+ old-total pe-delta) new-components)
        start (legacy-start save old-total)
        previous-rewards (existing-level-rewards db save)
        next (xp/avancar-nivel-treinador (:level start) (:residual start) pe-delta)
        crossed (:subidas next)
        already (set (map #(Long/parseLong (str %)) (keys previous-rewards)))
        crossed (vec (remove already crossed))]
    (when (pos? pe-delta)
      (let [events (mapv (fn [level]
                           (trainer-rewards/recompensas-por-nivel
                            (or operation-id (str "trainer-level|" sk "|" new-total)) level))
                         crossed)
            items (reduce #(merge-with + %1 (:items %2)) {} events)
            pending-base (read-pending (or (pending-patch tx sk)
                                           (:save/recompensas-pendentes-edn save)))
            allocation (allocate-level-items db tx save sk (:nivel next) items pending-base)
            history-tx (map (fn [event]
                              {:trainer-level-reward/key (str sk "|level-reward|" (:nivel event))
                               :trainer-level-reward/save [:save/key sk]
                               :trainer-level-reward/level (long (:nivel event))
                               :trainer-level-reward/operation-id
                               (str (or operation-id (str "trainer-level|" sk "|" new-total)))
                               :trainer-level-reward/reward-edn
                               (pr-str (select-keys event
                                                   [:normal :especial :marco-espaco :items]))})
                            events)
            newly-pending (positive-delta (:pending allocation) pending-base)
            event-summary {:save-key sk
                           :trainer (or (:save/display-name save) "Treinador")
                           :operation-id operation-id
                           :pe-recebido pe-delta
                           :nivel-anterior (:level start)
                           :nivel-atual (:nivel next)
                           :xp-atual (:xp-atual next)
                           :xp-necessario (:xp-necessario next)
                           :niveis events
                           ;; Anuncia apenas este pacote; pendências preexistentes
                           ;; não são confundidas com a recompensa do nível.
                           :items items
                           :items-no-inventario (:added allocation)
                           :pending newly-pending
                           :duplicate false}
            attrs-to-replace #{:save/trainer-level :save/trainer-xp
                               :save/trainer-xp-level-progress
                               :save/recompensas-pendentes-edn}
            extra-tx (into (into (:tx allocation) history-tx)
                           [[:db/add [:save/key sk] :save/trainer-xp new-total]
                            [:db/add [:save/key sk] :save/trainer-level (:nivel next)]
                            [:db/add [:save/key sk] :save/trainer-xp-level-progress (:xp-atual next)]])
            extra-tx (if (or (seq (:pending allocation)) (pending-patch tx sk))
                       (conj extra-tx [:db/add [:save/key sk] :save/recompensas-pendentes-edn
                                       (pr-str (:pending allocation))]) extra-tx)]
        {:save-key sk :replace attrs-to-replace :tx extra-tx
         :inventory-keys (set (map #(id/inventory-item-key sk %) (keys (:added allocation))))
         :event (when (seq events) event-summary) :pe-delta pe-delta :crossed crossed}))))

(defn- touched-save-keys [tx]
  (->> tx
       (keep (fn [form]
               (let [target (tx-target form)
                     sk (save-key-from-ref target)]
                 (when (and sk (vector? form) (>= (count form) 4)
                            (contains? pe-attrs (nth form 2))) sk))))
       distinct vec))

(defn- receipt-save-key [row]
  (when (and (:operation-receipt/player-key row) (:operation-receipt/save-id row))
    (str (:operation-receipt/player-key row) "|" (:operation-receipt/save-id row))))

(defn- summary-with-event [row event]
  (if (and (:operation-receipt/summary row) event)
    (let [summary (try (edn/read-string (:operation-receipt/summary row))
                       (catch Exception _ {}))
          public-event (dissoc event :save-key :operation-id)]
      (assoc row :operation-receipt/summary
             (pr-str (-> summary
                         (assoc :trainer-level-up public-event)
                         (update :result #(assoc (or % {})
                                                 :trainer-level-ups [public-event]))))))
    row))

(defn enrich
  "Aplica progressão a cada save cujo PE mudou na TX original, em especial raids multi-participante.
   Pacote, save, inventário, histórico e resumo do recibo são persistidos na mesma transação."
  [db built]
  (let [tx (vec (:tx built))
        saves (touched-save-keys tx)
        updates (keep (fn [sk]
                        (let [save (d/pull db '[*] [:save/key sk])]
                          (when (:save/key save)
                            (progress-save db tx save (receipt-op-for-save tx save))))) saves)
        updates (vec updates)
        tx' (reduce (fn [current update]
                      (remove-save-attrs current (:save-key update) (:replace update))) tx updates)
        inventory-keys (into #{} (mapcat :inventory-keys) updates)
        tx' (remove-inventory-keys tx' inventory-keys)
        tx' (into tx' (mapcat :tx updates))
        events-by-save (into {} (map (juxt :save-key :event) updates))
        tx' (mapv (fn [row]
                    (if (map? row)
                      (summary-with-event row (get events-by-save (receipt-save-key row)))
                      row)) tx')]
    (if (empty? updates)
      built
      (let [events (mapv :event updates)]
        (assoc built
               :tx tx'
               :result (cond-> (:result built)
                         (seq (keep identity events))
                         (assoc :trainer-level-ups
                                (mapv #(dissoc % :save-key :operation-id)
                                      (keep identity events)))))))))
