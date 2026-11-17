(ns odisseu.persistence.datomic
  "Helpers de conexão Datomic Local (dev). Sem lógica de domínio."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]))

(defn project-root
  []
  (let [marker (io/file "deps.edn")]
    (when-not (.exists marker)
      (throw (ex-info "Execute a partir da raiz do repositório (deps.edn)." {})))
    (.getAbsoluteFile (io/file "."))))

(defn resolve-storage-dir
  [relative-or-absolute]
  (let [f (io/file relative-or-absolute)]
    (.getAbsolutePath
     (if (.isAbsolute f)
       f
       (io/file (project-root) relative-or-absolute)))))

(defn maintenance-state
  "Reads the durable A6 bridge marker colocated with the Datomic storage.
   Unknown/corrupt state fails closed for ordinary client constructors."
  [storage-dir]
  (let [root (io/file (resolve-storage-dir storage-dir))
        dir (io/file root ".a6-maintenance")
        state-file (io/file dir "bridge-maintenance.edn")
        listing (when (.exists dir)
                  (try {:files (.listFiles dir)} (catch Throwable _ {:error true})))
        abandoned? (or (:error listing)
                       (and listing (nil? (:files listing)))
                       (some #(and (str/starts-with? (.getName %) ".bridge-maintenance-")
                                   (str/ends-with? (.getName %) ".tmp"))
                             (:files listing)))]
    (cond
      abandoned? {:phase :draining :reason :interrupted-marker-write}
      (not (.exists state-file)) {:phase :open}
      :else
      (try
        (let [state (edn/read-string (slurp state-file))]
          (if (contains? #{:open :draining :quiescent :frozen} (:phase state))
            state
            {:phase :quiescent :reason :invalid-marker}))
        (catch Throwable _ {:phase :quiescent :reason :unreadable-marker})))))

(defn- assert-client-admitted!
  [storage-dir maintenance-control?]
  (when-not maintenance-control?
    (let [state (maintenance-state storage-dir)]
      (when-not (= :open (:phase state))
        (throw (ex-info "datomic_maintenance_active"
                        {:phase (:phase state)}))))))

(defonce ^:private open-clients (atom {}))

(defn- nested-connections
  "Conexões abertas por um DurableClient. O lock de cada database fica na
   conexão; liberar só o client não solta esses flocks."
  [client-or-conn]
  (when-let [field (try (.getDeclaredField (class client-or-conn) "conns_ref")
                        (catch NoSuchFieldException _ nil))]
    (.setAccessible field true)
    (vals @(.get field client-or-conn))))

(defn- flock-channel [conn]
  (let [field (.getDeclaredField (class conn) "flock")]
    (.setAccessible field true)
    (.get field conn)))

(defn- flock-locks [channel]
  (let [table-field (.getDeclaredField (class channel) "fileLockTable")]
    (.setAccessible table-field true)
    (when-let [table (.get table-field channel)]
      (let [locks-field (.getDeclaredField (class table) "locks")]
        (.setAccessible locks-field true)
        (vec (.get locks-field table))))))

(defn park-writer!
  "Solta o FileLock do Datomic sem fechar os canais de dados. O processo
   continua apto a ler e escrever depois de `relock-writers!`. Não prova
   quiescência e não substitui o certificado de backup. Exige
   --add-opens=java.base/sun.nio.ch=ALL-UNNAMED."
  [client-or-conn]
  (try
    (let [conns (or (seq (nested-connections client-or-conn))
                    [client-or-conn])
          channels (mapv flock-channel conns)]
      (doseq [ch channels
              lk (flock-locks ch)]
        (.release lk))
      {:channels channels
       :relock! (fn [] (doseq [ch channels] (.lock ch)))})
    (catch Throwable e
      (throw (ex-info "backup_writer_release_unavailable"
                      {:class (str (class client-or-conn))}
                      e)))))

(defn release-writer!
  "Solta o flock deste client ou conexão. Mantém os canais abertos."
  [client-or-conn]
  (park-writer! client-or-conn)
  true)

(defn- writers-of [storage-dir]
  (vec (get @open-clients (resolve-storage-dir storage-dir) [])))

(defn park-storage-writers!
  "Solta os flocks deste storage e devolve a função que os recupera."
  [storage-dir]
  (let [parked (mapv park-writer! (writers-of storage-dir))]
    {:count (count parked)
     :relock! (fn [] (doseq [p parked] ((:relock! p))))}))

(defn relock-writers!
  [parked]
  (when-let [relock (:relock! parked)]
    (relock))
  parked)

(defn release-storage-writers!
  "Solta os flocks deste storage sem fechá-los de novo. Não prova quiescência."
  [storage-dir]
  (:count (park-storage-writers! storage-dir)))

(defn client
  [{:keys [system storage-dir maintenance-control?]}]
  (assert-client-admitted! storage-dir maintenance-control?)
  (let [c (d/client {:server-type :datomic-local
                     :system system
                     :storage-dir (resolve-storage-dir storage-dir)})]
    (swap! open-clients update (resolve-storage-dir storage-dir) (fnil conj []) c)
    c))

(defn ensure-db!
  [client db-name]
  (let [existing (set (d/list-databases client {}))]
    (when-not (contains? existing db-name)
      (d/create-database client {:db-name db-name})))
  (d/connect client {:db-name db-name}))

(defn delete-db!
  [client db-name]
  (when (some #{db-name} (d/list-databases client {}))
    (d/delete-database client {:db-name db-name})))

(defn read-edn-resource
  "Lê EDN de path relativo à raiz do repo."
  [rel-path]
  (let [f (io/file (project-root) rel-path)]
    (when-not (.exists f)
      (throw (ex-info "migration_not_found" {:path rel-path})))
    (edn/read-string (slurp f))))
