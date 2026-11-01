(ns a64-apply-028-schema-only
  "Aplica SOMENTE pokemon/028-economy-ledger (schema aditivo).
   Sem backfill, sem bootstrap de saves, sem dual-write de moedas.

   Uso (JVM writer parado; storage exclusivo):
     ODISSEU_PROBE_STORAGE=/data/datomic-local \\
     ODISSEU_APPLY_028=1 \\
     clojure -M -e \"(load-file \\\"/work/a64_apply_028_schema_only.clj\\\")\""
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private migration-id "pokemon/028-economy-ledger")

(def ^:private expected-idents
  #{:save/economy-status
    :save/coins-operational
    :save/coins-operational-earned
    :save/coins-operational-spent
    :save/coins-historical-status
    :save/coins-historical-amount
    :coin-ledger/key
    :coin-ledger/save
    :coin-ledger/operation-id
    :coin-ledger/role
    :coin-ledger/kind
    :coin-ledger/source
    :coin-ledger/amount
    :coin-ledger/balance-after
    :coin-ledger/at})

(defn- env [k default]
  (or (System/getenv k) default))

(defn- migrations [db]
  (->> (d/q '[:find ?id :where [?e :odisseu.schema/migration-id ?id]] db)
       (map first)
       sort
       vec))

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
(defn- load-028-tx
  []
  (let [candidates [(io/file "migrations/pokemon/028-economy-ledger.edn")
                    (io/file "/work/migrations/pokemon/028-economy-ledger.edn")
                    (io/file "/work/028-economy-ledger.edn")
                    (when-let [p (System/getenv "ODISSEU_028_EDN")]
                      (io/file p))]
        f (some #(when (and % (.isFile ^java.io.File %)) %) candidates)]
    (when-not f
      (throw (ex-info "028-economy-ledger.edn não encontrado"
                      {:tried (mapv str (remove nil? candidates))})))
    (println "edn.path=" (.getAbsolutePath ^java.io.File f))
    (vec (edn/read-string (slurp f)))))

(defn- snapshot
  [db label]
  (let [m (migrations db)
        idents (into (sorted-set)
                     (filter #(attr-exists? db %) expected-idents))]
    {:label label
     :migrations m
     :migration-count (count m)
     :has-027? (boolean (some #{"pokemon/027-bag-daily"} m))
     :has-028? (boolean (some #{migration-id} m))
     :players (count-attr db :player/key)
     :saves (count-attr db :save/key)
     :pokemon (count-attr db :pokemon/key)
     :pokemon-id (count-attr db :pokemon/id-pokemon)
     :coins-sum (sum-coins db)
     :economy-status-count (count-attr db :save/economy-status)
     :coins-operational-count (count-attr db :save/coins-operational)
     :coin-ledger-count (count-attr db :coin-ledger/key)
     :028-idents-present (vec idents)
     :028-idents-missing (vec (set/difference expected-idents idents))}))

(defn -main
  [& _]
  (let [storage (env "ODISSEU_PROBE_STORAGE" nil)
        system (env "ODISSEU_PROBE_SYSTEM" "odisseu-m90-core")
        db-name (env "ODISSEU_PROBE_DB" "core")
        apply? (= "1" (env "ODISSEU_APPLY_028" "0"))]
    (when (or (nil? storage) (str/blank? storage))
      (binding [*out* *err*]
        (println "ODISSEU_PROBE_STORAGE obrigatório"))
      (System/exit 2))
    (println "probe.storage=" storage)
    (println "probe.system=" system "db=" db-name "apply028=" apply?)
    (let [client (persistence/client {:system system :storage-dir storage})
          conn (persistence/ensure-db! client db-name)
          db0 (d/db conn)
          before (snapshot db0 "before")]
      (println "BEFORE" (pr-str before))
      (when-not (:has-027? before)
        (binding [*out* *err*]
          (println "ABORT: 027-bag-daily ausente — estado inesperado"))
        (System/exit 3))
      (when (and apply? (:has-028? before))
        (println "028 já aplicada — noop idempotente")
        (println "AFTER" (pr-str (snapshot (d/db conn) "after-noop")))
        (println "OK")
        (System/exit 0))
      (when apply?
        (let [tx (load-028-tx)
              idents-in-tx (set (keep :db/ident tx))]
          (when-not (= expected-idents idents-in-tx)
            (binding [*out* *err*]
              (println "ABORT: edn 028 divergente do esperado")
              (println "missing" (set/difference expected-idents idents-in-tx))
              (println "extra" (set/difference idents-in-tx expected-idents)))
            (System/exit 4))
          (d/transact conn {:tx-data (conj tx {:odisseu.schema/migration-id migration-id})})
          (let [after (snapshot (d/db conn) "after")]
            (println "AFTER" (pr-str after))
            (when-not (:has-028? after)
              (binding [*out* *err*] (println "FAIL: 028 não marcada"))
              (System/exit 1))
            (when (seq (:028-idents-missing after))
              (binding [*out* *err*]
                (println "FAIL: idents faltando" (:028-idents-missing after)))
              (System/exit 1))
            (when (or (not= (:players before) (:players after))
                      (not= (:saves before) (:saves after))
                      (not= (:pokemon before) (:pokemon after))
                      (not= (:pokemon-id before) (:pokemon-id after))
                      (not= (:coins-sum before) (:coins-sum after)))
              (binding [*out* *err*]
                (println "FAIL: contagens/saldos mudaram após schema-only"))
              (System/exit 1))
            (when (or (pos? (:economy-status-count after))
                      (pos? (:coins-operational-count after))
                      (pos? (:coin-ledger-count after)))
              (binding [*out* *err*]
                (println "FAIL: dados de ledger criados indevidamente no schema-only"))
              (System/exit 1))
            (println "OK-APPLIED"))))
      (when-not apply?
        (println "DRY-RUN (defina ODISSEU_APPLY_028=1 para aplicar)")
        (println "OK"))
      (System/exit 0))))

(-main)
