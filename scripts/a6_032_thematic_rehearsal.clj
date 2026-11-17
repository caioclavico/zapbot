(ns a6-032-thematic-rehearsal
  "Ensaio descartável da migration 032 (eventos temáticos) em cópia isolada.
   Sem backfill, sem ativar flag, sem tocar volumes live.

   ODISSEU_PROBE_STORAGE=<cópia>/datomic \\
   ODISSEU_APPLY_032=1 \\
   clojure -M -e '(load-file \"scripts/a6_032_thematic_rehearsal.clj\")' -m a6-032-thematic-rehearsal"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private mig-id "pokemon/032-thematic-events")
(def ^:private mig-file "migrations/pokemon/032-thematic-events.edn")
(def ^:private new-attrs
  [:thematic-event-schedule/chat-id
   :thematic-event-occurrence/id
   :thematic-event-schedule/enabled
   :thematic-event-occurrence/status])

(defn- env [k d] (or (System/getenv k) d))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first) sort vec))

(defn- attr-exists? [db ident]
  (some? (d/pull db '[:db/ident] ident)))

(defn- count-attr [db a]
  (if-not (attr-exists? db a)
    0
    (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db a)) 0)))

(defn- sum-coins [db]
  (if-not (attr-exists? db :save/coins)
    0
    (or (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins ?c]] db)) 0)))

(defn- sum-operational [db]
  (if-not (attr-exists? db :save/coins-operational)
    0
    (or (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins-operational ?c]] db)) 0)))

(defn- recovery-slots [db]
  (if-not (attr-exists? db :save/slot-id)
    []
    (->> (d/q '[:find ?slot (count ?e)
                :where [?e :save/slot-id ?slot]]
              db)
         (map (fn [[s n]] {:slot s :n n}))
         (sort-by :slot)
         vec)))

(defn- economy-status-counts [db]
  (if-not (attr-exists? db :save/economy-status)
    {}
    (->> (d/q '[:find ?st (count ?e)
                :where [?e :save/economy-status ?st]]
              db)
         (into {}))))

(defn- professor-cards-digest [db]
  (if-not (attr-exists? db :save/professor-cards-edn)
    {:saves-with-cards 0 :digest "n/a"}
    (let [rows (d/q '[:find ?sk ?edn
                      :where
                      [?e :save/key ?sk]
                      [?e :save/professor-cards-edn ?edn]]
                    db)
          digest (->> rows
                      (map (fn [[sk edn]] [sk edn]))
                      sort
                      pr-str
                      (#(.getBytes ^String % "UTF-8"))
                      (#(.digest (java.security.MessageDigest/getInstance "SHA-256") %))
                      (#(apply str (map (fn [b] (format "%02x" b)) %))))]
      {:saves-with-cards (count rows) :digest digest})))

(defn- mission-edn-digest [db attr]
  (if-not (attr-exists? db attr)
    "n/a"
    (let [rows (d/q '[:find ?sk ?edn
                      :in $ ?a
                      :where
                      [?e :save/key ?sk]
                      [?e ?a ?edn]]
                    db attr)]
      (->> rows sort pr-str
           (#(.getBytes ^String % "UTF-8"))
           (#(.digest (java.security.MessageDigest/getInstance "SHA-256") %))
           (#(apply str (map (fn [b] (format "%02x" b)) %)))))))

(defn- fence-digest [db]
  (if-not (attr-exists? db :player/fence-generation)
    "n/a"
    (let [rows (d/q '[:find ?pk ?fence
                      :where
                      [?e :player/key ?pk]
                      [?e :player/fence-generation ?fence]]
                    db)]
      (->> rows sort pr-str
           (#(.getBytes ^String % "UTF-8"))
           (#(.digest (java.security.MessageDigest/getInstance "SHA-256") %))
           (#(apply str (map (fn [b] (format "%02x" b)) %)))))))

(defn- snapshot [db label]
  (let [m (migrations db)
        cards (professor-cards-digest db)]
    {:label label
     :migrations m
     :migration-count (count m)
     :has-031? (boolean (some #{"pokemon/031-raid-capture-frambo"} m))
     :has-032? (boolean (some #{mig-id} m))
     :schedule-attr? (attr-exists? db :thematic-event-schedule/chat-id)
     :occurrence-attr? (attr-exists? db :thematic-event-occurrence/id)
     :thematic-schedules (count-attr db :thematic-event-schedule/chat-id)
     :thematic-occurrences (count-attr db :thematic-event-occurrence/id)
     :players (count-attr db :player/key)
     :saves (count-attr db :save/key)
     :pokemon (count-attr db :pokemon/id-pokemon)
     :coins-sum (sum-coins db)
     :coins-operational-sum (sum-operational db)
     :raids (count-attr db :raid/id)
     :outbox (count-attr db :pokemon-outbox/id)
     :raid-schedules (count-attr db :raid-auto-schedule/chat-id)
     :slots (recovery-slots db)
     :economy-status (economy-status-counts db)
     :professor-cards cards
     :missoes-diarias-digest (mission-edn-digest db :save/missoes-diarias-edn)
     :missoes-semanais-digest (mission-edn-digest db :save/missoes-semanais-edn)
     :missoes-eventos-digest (mission-edn-digest db :save/missoes-eventos-edn)
     :fence-digest (fence-digest db)}))

(defn- load-tx [rel]
  (let [candidates [(io/file rel)
                    (io/file (str "/work/" rel))
                    (io/file (System/getProperty "user.dir") rel)]
        f (some #(when (and % (.isFile ^java.io.File %)) %) candidates)]
    (when-not f
      (throw (ex-info "edn missing" {:rel rel :tried (mapv str candidates)})))
    (vec (edn/read-string (slurp f)))))

(defn- assert-data-stable! [before after]
  (doseq [k [:players :saves :pokemon :coins-sum :coins-operational-sum
             :raids :raid-schedules :slots :economy-status
             :missoes-diarias-digest :missoes-semanais-digest :missoes-eventos-digest
             :fence-digest]]
    (when (not= (get before k) (get after k))
      (throw (ex-info "data_changed_after_schema"
                      {:key k :before (get before k) :after (get after k)}))))
  (when (not= (get-in before [:professor-cards :digest])
              (get-in after [:professor-cards :digest]))
    (throw (ex-info "professor_cards_changed" {:before (:professor-cards before)
                                               :after (:professor-cards after)})))
  (when (or (pos? (:thematic-schedules after)) (pos? (:thematic-occurrences after)))
    (throw (ex-info "schema_created_runtime_rows" {:after after}))))

(defn -main [& _]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        apply? (= "1" (env "ODISSEU_APPLY_032" "0"))]
    (when (or (nil? storage) (str/blank? storage))
      (binding [*out* *err*] (println "ODISSEU_PROBE_STORAGE obrigatório"))
      (System/exit 2))
    (when (re-find #"(?i)odisseu-core-a56|/data/datomic-local$|odisseu-a6-cutover-datomic|cutover-runtime"
                   (str storage))
      (binding [*out* *err*]
        (println "ABORT: storage parece volume live:" storage))
      (System/exit 3))
    (when-not (re-find #"(?i)odisseu-recovery|readiness-work|fase1281|fase12"
                       (str storage))
      (binding [*out* *err*]
        (println "ABORT: storage fora de recovery/readiness:" storage))
      (System/exit 3))
    (println "rehearsal.storage=" storage "apply=" apply?)
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          before (snapshot (d/db conn) "before")]
      (println "BEFORE" (pr-str before))
      (when-not (:has-031? before)
        (binding [*out* *err*] (println "ABORT: 031 ausente — base inesperada"))
        (System/exit 4))
      (when-not apply?
        (println "DRY-RUN — defina ODISSEU_APPLY_032=1")
        (println "OK")
        (System/exit 0))
      (if (:has-032? before)
        (println "noop" mig-id)
        (let [tx (load-tx mig-file)]
          (when-not (every? map? tx)
            (throw (ex-info "032_tx_unexpected" {:n (count tx)})))
          (when-not (every? #(and (keyword? (:db/ident %))
                                  (keyword? (:db/valueType %))
                                  (keyword? (:db/cardinality %)))
                            tx)
            (throw (ex-info "032_shape_unexpected" {:sample (take 2 tx)})))
          (d/transact conn {:tx-data (conj tx {:odisseu.schema/migration-id mig-id})})
          (println "applied" mig-id)))
      (let [after (snapshot (d/db conn) "after")]
        (println "AFTER" (pr-str after))
        (assert-data-stable! before after)
        (when-not (and (:has-032? after) (:schedule-attr? after) (:occurrence-attr? after))
          (throw (ex-info "032_incomplete" {:after after})))
        (when-not (zero? (:thematic-schedules after))
          (throw (ex-info "032_created_schedules" {:n (:thematic-schedules after)})))
        (when-not (zero? (:thematic-occurrences after))
          (throw (ex-info "032_created_occurrences" {:n (:thematic-occurrences after)})))
        (println "OK_032_ADDITIVE_IDEMPOTENT_DATA_STABLE")
        (System/exit 0)))))
