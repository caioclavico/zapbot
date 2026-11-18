#!/usr/bin/env bb
;; Aplica manifesto privado de reconciliação flat-9 ambíguos em Datomic ISOLADO.
;; NÃO usar em produção sem GO explícito.
;; Env:
;;   ODISSEU_BRIDGE_*  A6_EVID  A6_RECON_MANIFEST (path privado)
;;   A6_RECON_DRY_RUN=1 → só simula
(require '[datomic.client.api :as d]
         '[odisseu.persistence.datomic :as persistence]
         '[pokemon.domain.growth :as growth]
         '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def evid (or (System/getenv "A6_EVID") "/tmp/flat9-recon-evid"))
(.mkdirs (io/file evid))
(def dry-run? (= "1" (or (System/getenv "A6_RECON_DRY_RUN") "0")))
(def manifest-path
  (or (System/getenv "A6_RECON_MANIFEST")
      (throw (ex-info "A6_RECON_MANIFEST obrigatorio (path privado fora do git)" {}))))

(def manifest (edn/read-string (slurp manifest-path)))
(def entries (vec (:entries manifest)))

(def client (persistence/client {:system (or (System/getenv "ODISSEU_BRIDGE_SYSTEM") "odisseu-m90-core")
                                 :storage-dir (or (System/getenv "ODISSEU_BRIDGE_STORAGE") "/data/datomic-local")
                                 :maintenance-control? true}))
(def conn (persistence/ensure-db! client (or (System/getenv "ODISSEU_BRIDGE_DB") "core")))

(defn pull-poke [db id]
  (let [e (ffirst (d/q '[:find ?e :in $ ?id :where [?e :pokemon/id-pokemon ?id]] db id))]
    (when e
      (d/pull db '[:db/id :pokemon/id-pokemon :pokemon/level :pokemon/xp :pokemon/species
                   :pokemon/nome :pokemon/xp-progress-version :pokemon/xp-desde-nivel
                   :pokemon/xp-legacy-nivel :pokemon/xp-legacy-residual :pokemon/xp-total
                   :pokemon/growth-rate :pokemon/hp :pokemon/hp-atual :pokemon/ataque
                   :pokemon/defesa :pokemon/atq-esp :pokemon/def-esp :pokemon/veloc
                   :pokemon/shiny :pokemon/golpes :pokemon/ivs] e))))

(defn entity->reg [p]
  {:id-pokemon (str (:pokemon/id-pokemon p))
   :species (str (or (:pokemon/species p) ""))
   :nome (str (or (:pokemon/nome p) ""))
   :nivel (long (or (:pokemon/level p) 1))
   :xp (long (or (:pokemon/xp p) 0))
   :xp-desde-nivel (long (or (:pokemon/xp-desde-nivel p) (:pokemon/xp p) 0))
   :xp-total (when-let [t (:pokemon/xp-total p)] (long t))
   :xp-progress-version (when-let [v (:pokemon/xp-progress-version p)] (str v))
   :xp-legacy-nivel (when-let [v (:pokemon/xp-legacy-nivel p)] (long v))
   :xp-legacy-residual (when-let [v (:pokemon/xp-legacy-residual p)] (long v))
   :growth-rate (when-let [g (:pokemon/growth-rate p)] (keyword g))
   :hp (long (or (:pokemon/hp p) 0))
   :hp-atual (long (or (:pokemon/hp-atual p) 0))
   :ataque (long (or (:pokemon/ataque p) 0))
   :defesa (long (or (:pokemon/defesa p) 0))
   :atq-esp (long (or (:pokemon/atq-esp p) 0))
   :def-esp (long (or (:pokemon/def-esp p) 0))
   :veloc (long (or (:pokemon/veloc p) 0))
   :shiny (boolean (:pokemon/shiny p))
   :golpes (:pokemon/golpes p)
   :ivs (:pokemon/ivs p)})

(defn receipt-exists? [db rid]
  (some? (:db/id (d/pull db '[:db/id] [:operation-receipt/id rid]))))

(defn apply-entry! [conn entry]
  (let [db (d/db conn)
        rid (str (:receipt-id entry))
        id (str (:id entry))]
    (cond
      (receipt-exists? db rid)
      {:id id :status :skipped :reason :receipt-exists}

      :else
      (let [p (pull-poke db id)]
        (when-not p
          (throw (ex-info "Pokemon ausente no restore" {:id id})))
        (let [reg (entity->reg p)
              result (growth/reconcile-ambiguous-entry reg entry)]
          (case (:status result)
            :skipped
            {:id id :status :skipped :reason (:reason result)
             :nivel (:nivel reg) :xp (:xp-desde-nivel reg)}

            :blocked
            {:id id :status :blocked :reason (:reason result)
             :nivel (:nivel reg) :xp (:xp-desde-nivel reg)}

            :applied
            (let [after (:after result)
                  lookup [:pokemon/id-pokemon id]
                  summary {:kind "flat9-ambig-recon"
                           :id id
                           :nivel-antes (:nivel reg)
                           :nivel-depois (:nivel after)
                           :xp-antes (:xp-desde-nivel reg)
                           :xp-depois (:xp-desde-nivel after)
                           :origin (:origin entry)
                           :action (:action entry)}
                  tx [[:db/add lookup :pokemon/xp (long (:xp-desde-nivel after))]
                      [:db/add lookup :pokemon/xp-desde-nivel (long (:xp-desde-nivel after))]
                      [:db/add lookup :pokemon/xp-total (long (:xp-total after))]
                      [:db/add lookup :pokemon/xp-progress-version growth/flat9-progress-version]
                      [:db/add lookup :pokemon/xp-legacy-residual (long (:xp-desde-nivel reg))]
                      [:db/add lookup :pokemon/xp-legacy-nivel (long (:nivel reg))]
                      {:operation-receipt/id rid
                       :operation-receipt/kind "flat9-ambig-recon"
                       :operation-receipt/channel "maintenance"
                       :operation-receipt/payload-hash
                       (str (hash [id (:expected-raw-xp entry) (:target-residual entry)]))
                       :operation-receipt/summary (pr-str summary)}]]
              (when-not dry-run?
                (d/transact conn {:tx-data tx}))
              {:id id :status (if dry-run? :dry-applied :applied)
               :nivel-antes (:nivel reg) :nivel-depois (:nivel after)
               :xp-antes (:xp-desde-nivel reg) :xp-depois (:xp-desde-nivel after)
               :receipt rid})))))))

(def results
  (mapv (fn [e]
          (try
            (apply-entry! conn e)
            (catch Exception ex
              {:id (:id e)
               :status :error
               :error (.getMessage ex)
               :data (ex-data ex)})))
        entries))

(def summary
  {:generatedAt (str (java.time.Instant/now))
   :dry-run? dry-run?
   :manifest-version (:version manifest)
   :counts (frequencies (map :status results))
   :level-changes (count (filter #(and (#{:applied :dry-applied} (:status %))
                                       (not= (:nivel-antes %) (:nivel-depois %)))
                                 results))
   :results results})

(spit (io/file evid "recon-apply-summary.edn") (pr-str summary))
(println "RECON_APPLY_OK")
(prn (dissoc summary :results))
(doseq [r results]
  (println (pr-str r)))
(when (pos? (:level-changes summary))
  (println "FAIL level changes")
  (System/exit 2))
(when (some #(= :error (:status %)) results)
  (println "FAIL errors present")
  (System/exit 3))
(System/exit 0)
