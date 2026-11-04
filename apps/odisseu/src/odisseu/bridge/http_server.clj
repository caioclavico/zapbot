(ns odisseu.bridge.http-server
  "Entrypoint da bridge HTTP JVM (dev/staging).
   Default: loopback. Rede privada Docker só com ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true.
   Nunca usa storages M5.3B/M5.5 de referência. Sem seed destrutivo sem RESET.
   Um único writer por storage via FileLock em <storage>/.odisseu-writer.lock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [odisseu.platform.infrastructure.datomic.store :as platform-store]
            [pokemon.infrastructure.http.maintenance-gate :as maintenance-gate]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as recovery-catalog]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge])
  (:import [java.lang.management ManagementFactory]
           [java.nio.channels FileChannel FileLock]
           [java.nio.file StandardOpenOption]))

(defn- env
  ([k] (env k nil))
  ([k default]
   (let [v (System/getenv k)]
     (if (and v (not (str/blank? v))) v default))))

(defn- env-int
  [k default]
  (try
    (Long/parseLong (env k (str default)))
    (catch Exception _ default)))

(defn- env-bool
  [k default]
  (let [v (str/lower-case (env k (str default)))]
    (#{"1" "true" "yes" "on"} v)))

(defn- rss-mb
  []
  (try
    (let [pid (first (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@"))
          proc (.. (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid])
                   (redirectErrorStream true)
                   start)
          out (str/trim (slurp (.getInputStream proc)))]
      (.waitFor proc)
      (when (re-matches #"\d+" out)
        (long (/ (Long/parseLong out) 1024))))
    (catch Exception _ nil)))

(defn- assert-storage-safe!
  "Recusa storages de referência M5.3B/M5.5, Core A5.6 e paths de produção."
  [storage]
  (let [s (str storage)]
    (when (re-find #"(?i)m53-real-offline|m55-real-offline|m53-real-offline-bak|prod|oracle|odisseu-live|a56-datomic|odisseu-core-a56|odisseu-m90-core"
                   s)
      (throw (ex-info "bridge_storage_forbidden"
                      {:storage s
                       :hint "Use volume odisseu-a6-staging-datomic (system odisseu-a6-staging) ou lab m61/m90/m91 — nunca a56/Core."})))))

(defn- acquire-writer-lock!
  "Garante um único processo escritor por diretório Datomic Local."
  [storage]
  (let [dir (io/file (persistence/resolve-storage-dir storage))
        _ (.mkdirs dir)
        lock-file (io/file dir ".odisseu-writer.lock")
        ch (FileChannel/open
            (.toPath lock-file)
            (into-array StandardOpenOption
                        [StandardOpenOption/CREATE
                         StandardOpenOption/WRITE]))
        lock (.tryLock ch)]
    (when-not lock
      (.close ch)
      (throw (ex-info "writer_lock_held"
                      {:storage (.getAbsolutePath dir)
                       :lock-file (.getAbsolutePath lock-file)
                       :hint "Outra bridge já usa este storage. Um writer por vez."})))
    {:channel ch :lock lock :path (.getAbsolutePath lock-file)}))

(defn- release-writer-lock!
  [{:keys [^FileChannel channel ^FileLock lock]}]
  (when lock
    (try (.release lock) (catch Exception _)))
  (when channel
    (try (.close channel) (catch Exception _))))

(defn- connect-existing-db!
  [client db-name]
  (when-not (some #{db-name} (d/list-databases client {}))
    (throw (ex-info "maintenance_database_missing" {:db db-name})))
  (d/connect client {:db-name db-name}))

(defn- seed-demo!
  "Dados sintéticos in-process — nunca exposto como rota HTTP."
  [s]
  (let [chat "chat-m61-staging"
        player "player-m61"]
    (when-not (ports/get-player s chat player)
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (coll-app/adicionar-pokemon!
       s chat player
       {:species "Bulbasaur" :nome "Bulbasaur" :shiny false :nivel 12
        :hp 45 :ataque 49 :defesa 49 :atq-esp 65 :def-esp 65 :veloc 45
        :tipos ["grama"]
        :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
        :raridade "comum"}
       45 nil)
      (coll-app/creditar-moedas! s chat player 120 "m61-seed|coins")
      (coll-app/ajustar-inventario! s chat player "poke-ball" 5 "m61-seed|poke-ball")
      (coll-app/ajustar-inventario! s chat player "great-ball" 2 "m61-seed|great-ball"))
    {:chat-id chat :player-id player :seeded true}))

(defn -main
  [& _]
  (let [host (env "ODISSEU_BRIDGE_HOST" "127.0.0.1")
        port (env-int "ODISSEU_BRIDGE_PORT" 18090)
        token (env "ODISSEU_BRIDGE_TOKEN")
        _ (when (str/blank? token)
            (throw (ex-info "ODISSEU_BRIDGE_TOKEN obrigatório (não use default em staging)." {})))
        system (env "ODISSEU_BRIDGE_SYSTEM" "odisseu-m61-staging")
        storage (env "ODISSEU_BRIDGE_STORAGE" ".datomic-local/m61-staging")
        db-name (env "ODISSEU_BRIDGE_DB" "staging")
        platform-db (env "ODISSEU_PLATFORM_DB" "platform")
        platform-enabled? (env-bool "ODISSEU_PLATFORM_ENABLED" true)
        seed? (env-bool "ODISSEU_BRIDGE_SEED" false)
        reset? (env-bool "ODISSEU_BRIDGE_RESET" false)
        private-bind? (env-bool "ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND" false)
        allow-rng? (env-bool "ODISSEU_BRIDGE_ALLOW_TEST_RNG" false)
        ;; Kill switch global do tick de raides auto (default OFF).
        ;; Só ligar em lab sintético (ex.: odisseu-a6-staging).
        raid-auto-tick? (env-bool "ODISSEU_BRIDGE_RAID_AUTO_TICK" false)
        maintenance-dir (env "ODISSEU_BRIDGE_MAINTENANCE_DIR"
                             (str storage "/.a6-maintenance"))
        canonical-maintenance-dir (.getCanonicalPath (io/file storage ".a6-maintenance"))
        configured-maintenance-dir (.getCanonicalPath (io/file maintenance-dir))
        _ (when-not (= canonical-maintenance-dir configured-maintenance-dir)
            (throw (ex-info "maintenance_marker_must_share_datomic_storage"
                            {:expected canonical-maintenance-dir})))
        maintenance-token (env "ODISSEU_BRIDGE_MAINTENANCE_TOKEN")
        maintenance-state (odisseu.persistence.datomic/maintenance-state storage)
        maintenance-active? (not= :open (:phase maintenance-state))
        recovery-dump (env "ODISSEU_RECOVERY_DUMP_PATH")
        recovery-import-token (env "ODISSEU_RECOVERY_IMPORT_TOKEN")
        recovery-cat (try
                       (recovery-catalog/load-catalog
                        {:dump-path recovery-dump
                         :import-token recovery-import-token})
                       (catch Exception e
                         (binding [*out* *err*]
                           (println "[odisseu-bridge] recovery catalog:" (ex-message e)))
                         (recovery-catalog/load-catalog
                          {:import-token recovery-import-token})))
        _ (assert-storage-safe! storage)
        _ (when (and (= "0.0.0.0" host) (not private-bind?))
            (throw (ex-info "private_bind_required"
                            {:hint "Defina ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true para 0.0.0.0."})))
        writer-lock (acquire-writer-lock! storage)
        client (persistence/client {:system system :storage-dir storage
                                    :maintenance-control? true})
        _ (when (and maintenance-active? (or reset? seed?))
            (throw (ex-info "maintenance_mutating_bootstrap_forbidden" {})))
        _ (when reset?
            (println "[odisseu-bridge] RESET — apagando db" db-name platform-db)
            (persistence/delete-db! client db-name)
            (persistence/delete-db! client platform-db))
        conn (if maintenance-active?
               (connect-existing-db! client db-name)
               (persistence/ensure-db! client db-name))
        s (store/make-store conn)
        _ (when-not maintenance-active? (trainer/bootstrap! s))
        platform (when platform-enabled?
                   (platform-store/make-store
                    (if maintenance-active?
                      (connect-existing-db! client platform-db)
                      (persistence/ensure-db! client platform-db))))
        demo (when seed? (seed-demo! s))
        http (bridge/start! {:host host
                             :port port
                             :token token
                             :store s
                             :platform-store platform
                             :allow-test-rng allow-rng?
                             :allow-private-bind private-bind?
                             :recovery-catalog recovery-cat
                             :maintenance-dir maintenance-dir
                             :maintenance-token maintenance-token
                             :raid-auto-tick-enabled? raid-auto-tick?})
        stopped (atom false)
        stop! (fn []
                (when (compare-and-set! stopped false true)
                  (try (bridge/stop! http) (catch Exception _))
                  (release-writer-lock! writer-lock)
                  (println "[odisseu-bridge] stopped")))]
    (println "[odisseu-bridge] up"
             (:base-url http)
             "host=" host
             "rss-mb=" (rss-mb)
             "storage=" storage
             "system=" system
             "db=" db-name
             "platform-db=" (when platform platform-db)
             "raid-auto-tick=" raid-auto-tick?
             "maintenance-gate=" (boolean (:maintenance-gate http))
             "writer-lock=" (:path writer-lock)
             (when demo (str "seed=" (pr-str demo))))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    @(promise)))
