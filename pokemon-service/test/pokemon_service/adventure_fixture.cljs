(ns pokemon-service.adventure-fixture
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.config :as config]
            [zapbot.armazenamento :as storage]
            [zapbot.adventure-store :as store]
            [zapbot.pokemon.core :as core]
            [zapbot.pokemon.adventure :as adventure]
            [zapbot.pokemon.treinador :as treinador]
            [zapbot.pokemon.loja :as loja]))

(defn result [rows applied]
  #js {:rows (clj->js rows) :first #(when (seq rows) (clj->js (first rows)))
       :wasApplied #(boolean applied)})

(defn driver [db queries fault after-cas]
  #js {:getState (fn [] #js {:getConnectedHosts (fn [] #js ["test"])})
       :shutdown #(p/resolved nil)
       :execute
       (fn [sql args options]
         (try
           (let [args (js->clj args)
                 op (cond
                      (str/includes? sql "system_schema.columns") :schema-read
                      (str/includes? sql "adventure_account_binding") (if (str/starts-with? sql "SELECT") :binding-read :binding-insert)
                      (str/includes? sql "adventure_account_state") (cond (and (str/starts-with? sql "SELECT") (not (str/includes? sql "WHERE"))) :state-scan
                                                                                 (str/starts-with? sql "SELECT") :state-read
                                                                                 (str/starts-with? sql "INSERT") :state-insert :else :state-cas)
                      (str/includes? sql "adventure_sync_receipts") (if (str/starts-with? sql "SELECT") :receipt-read :receipt-write)
                      (str/includes? sql "adventure_hatches_by_month") (if (str/starts-with? sql "SELECT") :history-read :history-write)
                      (str/includes? sql "estado_particionado") (cond (str/starts-with? sql "SELECT") :trainer-read
                                                                                    (str/includes? sql "IF ") :trainer-cas
                                                                                    :else :legacy-write)
                      :else (throw (js/Error. (str "Consulta inesperada: " sql))))
                 _ (swap! queries conj {:op op :sql sql :args args :options (js->clj options :keywordize-keys true)})
                 fail (fn [after?]
                        (when-let [f @fault]
                          (when (and (= (:op f) op) (= (boolean (:after? f)) after?)
                                     (or (nil? (:match f)) ((:match f) args)))
                            (reset! fault nil)
                            (throw (js/Error. "Timeout de persistência simulado.")))))
                 _ (fail false)
                 resposta
                 (case op
                   :schema-read (result (vec (for [[table cols] storage/adventure-schema
                                                  [col [type kind position]] cols]
                                              {:table_name table :column_name col :type type :kind kind :position position})) false)
                   :state-scan
                   (let [start (js/parseInt (or (.-pageState options) "0"))
                         rows (vec (vals (:states @db))) r (result (subvec rows start (min (count rows) (+ start 32))) false)]
                     (when (< (+ start 32) (count rows)) (aset r "pageState" (str (+ start 32)))) r)
                   :binding-read (result (when-let [id (get-in @db [:bindings args])] [{:account_id id}]) false)
                   :binding-insert
                   (let [[cid pid id] args key [cid pid] old (get-in @db [:bindings key])]
                     (when-not old (swap! db assoc-in [:bindings key] id))
                     (result (if old [{:account_id old}] []) (nil? old)))
                   :state-read (result (when-let [row (get-in @db [:states (first args)])] [row]) false)
                   :state-insert
                   (let [[id version schema _ payload] args old (get-in @db [:states id])]
                     (when-not old (swap! db assoc-in [:states id] {:version version :schema_version schema :payload payload}))
                     (result [] (nil? old)))
                   :state-cas
                   (let [[version payload _ id expected] args applied (= expected (get-in @db [:states id :version]))]
                     (when applied (swap! db assoc-in [:states id] {:version version :schema_version 1 :payload payload}))
                     (result [] applied))
                   :receipt-read (result (when-let [row (get-in @db [:receipts args])] [row]) false)
                   :receipt-write
                   (let [[id day batch hash _ payload] args]
                     (swap! db assoc-in [:receipts [id day batch]] {:fingerprint hash :payload payload}) (result [] true))
                   :history-write
                   (let [[id month seq _ _ _ payload] args]
                     (swap! db assoc-in [:history [id month seq]] {:seq seq :payload payload}) (result [] true))
                   :history-read
                   (let [[id month cursor] args]
                     (result (->> (:history @db)
                                  (filter (fn [[[a m s] _]] (and (= a id) (= m month) (< s cursor))))
                                  (sort-by (comp last key) >) (take 20) (mapv val)) false))
                   :trainer-read (result (when-let [valor (get-in @db [:legacy (vec args)])] [{:valor valor}]) false)
                   :trainer-cas
                   (let [[valor modulo cid expected] args old (get-in @db [:legacy [modulo cid]])
                         applied (if (str/starts-with? sql "UPDATE") (= expected old) (nil? old))]
                     (when applied (swap! db assoc-in [:legacy [modulo cid]] valor))
                     (when-let [f @after-cas] (reset! after-cas nil) (f))
                     (result [] applied))
                   :legacy-write
                   (let [[modulo cid valor] args]
                     (if (str/starts-with? sql "DELETE") (swap! db update :legacy dissoc [modulo cid])
                         (swap! db assoc-in [:legacy [modulo cid]] valor)) (result [] true)))
                 _ (fail true)]
             (p/resolved resposta))
           (catch :default erro (p/rejected erro))))})

(defn com-banco [f]
  (let [atoms [storage/client storage/pronto storage/cache storage/confirmados storage/filas-gravacao
               storage/falhas-gravacao storage/gravacoes-pendentes storage/partes-bloqueadas storage/geracoes-partes
               treinador/contas core/jogos core/cacadas-selvagens core/filas-jogadas]
        lifecycle [adventure/aceitando? adventure/pronto? adventure/geracao]
        old-lifecycle (mapv deref lifecycle)
        antigos (mapv deref atoms)
        enabled config/adventure-enabled? readonly config/read-only? clock js/Date.now capacidade loja/capacidade-pokemon
        hidratando @storage/hidratando?
        db (atom {:bindings {} :states {} :receipts {} :history {} :legacy {}})
        queries (atom []) fault (atom nil) after-cas (atom nil)
        now (atom 1791626400000) cap (atom 50)]
    (reset! storage/hidratando? true)
    (doseq [a (drop 2 atoms)] (reset! a {}))
    (reset! storage/gravacoes-pendentes 0)
    (reset! storage/client (driver db queries fault after-cas)) (reset! storage/pronto true)
    (reset! storage/hidratando? false)
    (reset! adventure/aceitando? true) (reset! adventure/pronto? true) (reset! adventure/geracao 0)
    (set! config/adventure-enabled? true) (set! config/read-only? false)
    (set! js/Date.now #(deref now)) (set! loja/capacidade-pokemon (fn [& _] @cap))
    (-> (p/resolved nil)
        (p/then #(f {:db db :queries queries :fault fault :after-cas after-cas :now now :cap cap}))
        (p/finally (fn []
                     (reset! storage/hidratando? true)
                     (doseq [[a valor] (map vector atoms antigos)] (reset! a valor))
                     (reset! storage/hidratando? hidratando)
                     (doseq [[a v] (map vector lifecycle old-lifecycle)] (reset! a v))
                     (set! config/adventure-enabled? enabled) (set! config/read-only? readonly)
                     (set! js/Date.now clock) (set! loja/capacidade-pokemon capacidade))))))

(defn caminhada [now id mm]
  (swap! now + 600000)
  {:event-id id :from-ms (- @now 300000) :to-ms @now :distance-mm mm})
