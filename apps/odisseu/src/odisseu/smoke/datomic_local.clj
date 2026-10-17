(ns odisseu.smoke.datomic-local
  "Smoke M1: Datomic Local em ambiente de desenvolvimento.

  - Cria banco isolado sob `.datomic-local/`
  - Registra e consulta um treinador fictício
  - Atualiza em transação
  - Reabre o banco e verifica persistência
  - Reporta heap JVM e RSS do processo

  Não acessa Cassandra, WhatsApp, Chromium nem produção."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [datomic.client.api :as d])
  (:import [java.lang.management ManagementFactory]
           [java.time Instant]))

(def ^:private schema
  [{:db/ident       :treinador/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/doc         "Identificador fictício do treinador (smoke M1)"}
   {:db/ident       :treinador/nome
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :treinador/moedas
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :treinador/nivel
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}])

(defn- project-root
  []
  (let [marker (io/file "deps.edn")]
    (when-not (.exists marker)
      (throw (ex-info "Execute o smoke a partir da raiz do repositório (deps.edn)." {})))
    (.getAbsoluteFile (io/file "."))))

(defn- storage-dir
  []
  (let [override (System/getProperty "odisseu.datomic.storage-dir")
        base (if (str/blank? override)
               (io/file (project-root) ".datomic-local" "smoke")
               (io/file override))]
    (.getAbsolutePath base)))

(defn- heap-stats
  []
  (let [rt (Runtime/getRuntime)
        mb (fn [n] (long (/ n (* 1024 1024))))]
    {:heap-used-mb  (mb (- (.totalMemory rt) (.freeMemory rt)))
     :heap-total-mb (mb (.totalMemory rt))
     :heap-max-mb   (mb (.maxMemory rt))
     :jvm-xmx-prop  (System/getProperty "odisseu.smoke.heap-max-mb")}))

(defn- rss-kb
  "RSS do processo atual via `ps` (macOS/Linux). Retorna nil se indisponível."
  []
  (try
    (let [pid (.getName (ManagementFactory/getRuntimeMXBean))
          pid (if (str/includes? pid "@")
                (first (str/split pid #"@"))
                pid)
          proc (.. (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid])
                   (redirectErrorStream true)
                   start)
          out (str/trim (slurp (.getInputStream proc)))
          _ (.waitFor proc)]
      (when (re-matches #"\d+" out)
        (Long/parseLong out)))
    (catch Exception _
      nil)))

(defn- memory-report
  [label]
  (let [heap (heap-stats)
        rss (rss-kb)]
    (merge {:label label
            :at (str (Instant/now))}
           heap
           {:rss-kb rss
            :rss-mb (when rss (long (/ rss 1024)))})))

(defn- assert!
  [ok? msg]
  (when-not ok?
    (throw (ex-info msg {}))))

(defn- find-treinador
  [db treinador-id]
  (d/pull db
          '[:treinador/id :treinador/nome :treinador/moedas :treinador/nivel]
          [:treinador/id treinador-id]))

(defn- client-cfg
  [storage]
  {:server-type :datomic-local
   :system      "odisseu-m1-smoke"
   :storage-dir storage})

(defn- phase-write!
  "Cria banco, schema, treinador e atualização. Retorna mapa de verificação."
  [storage db-name treinador-id]
  (let [client (d/client (client-cfg storage))]
    (when (some #{db-name} (d/list-databases client {}))
      (d/delete-database client {:db-name db-name}))
    (assert! (d/create-database client {:db-name db-name})
             "create-database falhou")
    (let [conn (d/connect client {:db-name db-name})]
      (d/transact conn {:tx-data schema})
      (d/transact conn {:tx-data [{:treinador/id     treinador-id
                                   :treinador/nome   "Ash Ficticio"
                                   :treinador/moedas 100
                                   :treinador/nivel  1}]})
      (let [antes (find-treinador (d/db conn) treinador-id)]
        (assert! (= 100 (:treinador/moedas antes)) "moedas iniciais")
        (d/transact conn {:tx-data [{:treinador/id     treinador-id
                                     :treinador/moedas 250
                                     :treinador/nivel  3}]})
        (let [depois (find-treinador (d/db conn) treinador-id)]
          (assert! (= 250 (:treinador/moedas depois)) "moedas após update")
          (assert! (= 3 (:treinador/nivel depois)) "nível após update")
          {:written depois
           :mem-after-write (memory-report "após escrita")})))))

(defn- phase-reopen!
  "Novo client: reabre o mesmo storage e confirma persistência."
  [storage db-name treinador-id]
  (let [client (d/client (client-cfg storage))
        conn (d/connect client {:db-name db-name})
        encontrado (find-treinador (d/db conn) treinador-id)]
    (assert! (= treinador-id (:treinador/id encontrado)) "id persistido")
    (assert! (= "Ash Ficticio" (:treinador/nome encontrado)) "nome persistido")
    (assert! (= 250 (:treinador/moedas encontrado)) "moedas persistidas")
    (assert! (= 3 (:treinador/nivel encontrado)) "nível persistido")
    {:reopened encontrado
     :mem-after-reopen (memory-report "após reopen")}))

(defn -main
  [& _args]
  (let [storage (storage-dir)
        db-name "treinador-ficticio"
        treinador-id "smoke-trainer-001"
        _ (io/make-parents (io/file storage ".keep"))
        mem-start (memory-report "início")
        write-result (phase-write! storage db-name treinador-id)
        reopen-result (phase-reopen! storage db-name treinador-id)
        report {:ok true
                :java-version (System/getProperty "java.version")
                :java-vendor (System/getProperty "java.vendor")
                :os-name (System/getProperty "os.name")
                :os-arch (System/getProperty "os.arch")
                :datomic-dep "com.datomic/local 1.0.301"
                :storage-dir storage
                :db-name db-name
                :system "odisseu-m1-smoke"
                :treinador (:reopened reopen-result)
                :memory [mem-start
                         (:mem-after-write write-result)
                         (:mem-after-reopen reopen-result)]
                :nota "Medição isolada da JVM; sem Node/Chromium/Cassandra neste processo."}]
    (println "=== Odisseu M1 smoke Datomic Local: OK ===")
    (pprint/pprint report)
    (shutdown-agents)
    (System/exit 0)))
