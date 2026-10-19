(ns odisseu.bridge.http-server
  "Entrypoint da bridge HTTP JVM (dev/staging).
   Default: loopback. Rede privada Docker só com ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true.
   Nunca usa storages M5.3B/M5.5 de referência. Sem seed destrutivo sem RESET.
   Um único writer por storage via FileLock em <storage>/.odisseu-writer.lock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.ports :as ports]
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
  "Recusa storages de referência M5.3B/M5.5 e paths de produção."
  [storage]
  (let [s (str storage)]
    (when (re-find #"(?i)m53-real-offline|m55-real-offline|m53-real-offline-bak|prod|oracle|odisseu-live"
                   s)
      (throw (ex-info "bridge_storage_forbidden"
                      {:storage s
                       :hint "Use .datomic-local/m61-staging (ou volume staging dedicado)."})))))

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
        seed? (env-bool "ODISSEU_BRIDGE_SEED" false)
        reset? (env-bool "ODISSEU_BRIDGE_RESET" false)
        private-bind? (env-bool "ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND" false)
        allow-rng? (env-bool "ODISSEU_BRIDGE_ALLOW_TEST_RNG" false)
        _ (assert-storage-safe! storage)
        _ (when (and (= "0.0.0.0" host) (not private-bind?))
            (throw (ex-info "private_bind_required"
                            {:hint "Defina ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true para 0.0.0.0."})))
        writer-lock (acquire-writer-lock! storage)
        client (persistence/client {:system system :storage-dir storage})
        _ (when reset?
            (println "[odisseu-bridge] RESET — apagando db" db-name)
            (persistence/delete-db! client db-name))
        conn (persistence/ensure-db! client db-name)
        s (doto (store/make-store conn) trainer/bootstrap!)
        demo (when seed? (seed-demo! s))
        http (bridge/start! {:host host
                             :port port
                             :token token
                             :store s
                             :allow-test-rng allow-rng?
                             :allow-private-bind private-bind?})
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
             "writer-lock=" (:path writer-lock)
             (when demo (str "seed=" (pr-str demo))))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
    @(promise)))
