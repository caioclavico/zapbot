(ns a6-fase7-schema-rehearsal
  "Fase 7 — aplica SOMENTE 029+030 (schema aditivo) em cópia isolada.
   Sem backfill, sem criar raides, sem tocar volumes live.

   ODISSEU_PROBE_STORAGE=…/datomic ODISSEU_APPLY_A6_SCHEMA=1 clojure -M -e '(load-file \"…\")'"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private steps
  [{:id "pokemon/029-raid-lobby"
    :file "migrations/pokemon/029-raid-lobby.edn"
    :idents #{:raid/criada-em :raid/min-jogadores :raid/max-jogadores}}
   {:id "pokemon/030-raid-auto"
    :file "migrations/pokemon/030-raid-auto.edn"
    :idents #{:raid-auto-schedule/chat-id
              :raid-auto-schedule/enabled
              :raid-auto-schedule/interval-ms
              :raid-auto-schedule/next-at
              :raid-auto-schedule/ginasio-index
              :raid-auto-schedule/revision
              :raid-auto-schedule/last-spawn-id
              :raid-auto-schedule/last-raid-id
              :raid/origem
              :pokemon-outbox/id
              :pokemon-outbox/chat-id
              :pokemon-outbox/kind
              :pokemon-outbox/texto
              :pokemon-outbox/created-at
              :pokemon-outbox/acked-at}}])

(defn- env [k d] (or (System/getenv k) d))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first) sort vec))

(defn- attr-exists? [db ident]
  (some? (d/pull db '[:db/ident] ident)))

(defn- count-attr [db attr]
  (if-not (attr-exists? db attr)
    0
    (or (ffirst (d/q '[:find (count ?e) :in $ ?a :where [?e ?a]] db attr)) 0)))

(defn- sum-coins [db]
  (if-not (attr-exists? db :save/coins)
    0
    (or (ffirst (d/q '[:find (sum ?c) :where [?e :save/coins ?c]] db)) 0)))

(defn- snapshot [db label]
  (let [m (migrations db)]
    {:label label
     :migrations m
     :migration-count (count m)
     :has-028? (boolean (some #{"pokemon/028-economy-ledger"} m))
     :has-029? (boolean (some #{"pokemon/029-raid-lobby"} m))
     :has-030? (boolean (some #{"pokemon/030-raid-auto"} m))
     :players (count-attr db :player/key)
     :saves (count-attr db :save/key)
     :pokemon (count-attr db :pokemon/key)
     :pokemon-id (count-attr db :pokemon/id-pokemon)
     :coins-sum (sum-coins db)
     :raids (count-attr db :raid/id)
     :outbox (count-attr db :pokemon-outbox/id)
     :schedules (count-attr db :raid-auto-schedule/chat-id)}))

(defn- load-tx [rel]
  (let [candidates [(io/file rel)
                    (io/file (str "/work/" rel))
                    (io/file (System/getProperty "user.dir") rel)]
        f (some #(when (and % (.isFile ^java.io.File %)) %) candidates)]
    (when-not f
      (throw (ex-info "edn missing" {:rel rel :tried (mapv str candidates)})))
    (vec (edn/read-string (slurp f)))))

(defn- assert-data-stable! [before after]
  (when (or (not= (:players before) (:players after))
            (not= (:saves before) (:saves after))
            (not= (:pokemon before) (:pokemon after))
            (not= (:pokemon-id before) (:pokemon-id after))
            (not= (:coins-sum before) (:coins-sum after))
            (not= (:raids before) (:raids after)))
    (throw (ex-info "data_changed_after_schema" {:before before :after after})))
  (when (or (pos? (:outbox after)) (pos? (:schedules after)))
    (throw (ex-info "schema_created_runtime_rows" {:after after}))))

(defn -main [& _]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        apply? (= "1" (env "ODISSEU_APPLY_A6_SCHEMA" "0"))]
    (when (or (nil? storage) (str/blank? storage))
      (binding [*out* *err*] (println "ODISSEU_PROBE_STORAGE obrigatório"))
      (System/exit 2))
    (when (re-find #"(?i)odisseu-core-a56|/data/datomic-local$" (str storage))
      (binding [*out* *err*]
        (println "ABORT: storage parece volume live A5.6:" storage))
      (System/exit 3))
    (println "rehearsal.storage=" storage "apply=" apply?)
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          before (snapshot (d/db conn) "before")]
      (println "BEFORE" (pr-str before))
      (when-not (:has-028? before)
        (binding [*out* *err*] (println "ABORT: 028 ausente na cópia"))
        (System/exit 4))
      (when-not apply?
        (println "DRY-RUN — defina ODISSEU_APPLY_A6_SCHEMA=1")
        (println "OK")
        (System/exit 0))
      (doseq [step steps]
        (let [db (d/db conn)
              already (boolean (some #{(:id step)} (migrations db)))]
          (if already
            (println "noop" (:id step))
            (let [tx (load-tx (:file step))
                  idents (set (keep :db/ident tx))
                  missing (set/difference (:idents step) idents)]
              (when (seq missing)
                (throw (ex-info "edn_idents_mismatch" {:id (:id step) :missing missing})))
              (d/transact conn {:tx-data (conj tx {:odisseu.schema/migration-id (:id step)})})
              (println "applied" (:id step))))))
      (let [after (snapshot (d/db conn) "after")]
        (println "AFTER" (pr-str after))
        (assert-data-stable! before after)
        (when-not (and (:has-029? after) (:has-030? after))
          (throw (ex-info "schema_incomplete" {:after after})))
        (println "OK-APPLIED-REHEARSAL")
        (System/exit 0)))))

(-main)
