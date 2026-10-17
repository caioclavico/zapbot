(ns odisseu.bridge.http-server
  "Entrypoint da bridge HTTP JVM (dev). Somente loopback; leituras.
  Seed opcional in-process via ODISSEU_BRIDGE_SEED=true (não é rota HTTP)."
  (:require [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge])
  (:import [java.lang.management ManagementFactory]))

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

(defn- seed-demo!
  "Dados fictícios in-process — nunca exposto como rota HTTP."
  [s]
  (let [chat "chat-m41-ficticio"
        player "player-m41"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (coll-app/adicionar-pokemon!
     s chat player
     {:species "Bulbasaur" :nome "Bulbasaur" :shiny false :nivel 12
      :hp 45 :ataque 49 :defesa 49 :atq-esp 65 :def-esp 65 :veloc 45
      :tipos ["grama"] :golpes [] :raridade "comum"}
     45 nil)
    (coll-app/creditar-moedas! s chat player 120 "m41-seed-coins")
    (coll-app/ajustar-inventario! s chat player "poke-ball" 5 "m41-seed-item")
    {:chat-id chat :player-id player}))

(defn -main
  [& _]
  (let [host (env "ODISSEU_BRIDGE_HOST" "127.0.0.1")
        port (env-int "ODISSEU_BRIDGE_PORT" 18090)
        token (env "ODISSEU_BRIDGE_TOKEN" "dev-bridge-token")
        system (env "ODISSEU_BRIDGE_SYSTEM" "odisseu-m41-bridge")
        storage (env "ODISSEU_BRIDGE_STORAGE" ".datomic-local/m41-bridge")
        db-name (env "ODISSEU_BRIDGE_DB" "bridge")
        seed? (= "true" (str/lower-case (env "ODISSEU_BRIDGE_SEED" "false")))
        client (persistence/client {:system system :storage-dir storage})
        _ (when seed? (persistence/delete-db! client db-name))
        conn (persistence/ensure-db! client db-name)
        s (doto (store/make-store conn) trainer/bootstrap!)
        demo (when seed? (seed-demo! s))
        http (bridge/start! {:host host :port port :token token :store s})]
    (println "[odisseu-bridge] up"
             (:base-url http)
             "rss-mb=" (rss-mb)
             (when demo (str "seed=" (pr-str demo))))
    (.addShutdownHook
     (Runtime/getRuntime)
     (Thread. #(do (bridge/stop! http)
                   (println "[odisseu-bridge] stopped"))))
    ;; Mantém o processo vivo até SIGINT/SIGTERM.
    @(promise)))
