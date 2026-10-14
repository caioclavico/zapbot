(ns zapbot.config
  "Configuração mínima do processo Pokémon."
  (:require [clojure.string :as str]
            [zapbot.pokemon.egg-rewards :as egg-rewards]
            ["dotenv" :as dotenv]))

(.config dotenv)

(defn- env [chave padrao]
  (let [valor (unchecked-get js/process.env chave)]
    (if (str/blank? valor) padrao valor)))

(def performance-metrics-enabled (.-enabled (js/require "../runtime/metrics.cjs")))

(def read-only? (.-readOnly (js/require "../runtime/mode.cjs")))

(def adventure-enabled?
  (case (str/lower-case (env "ADVENTURE_SYNC_ENABLED" "false"))
    "true" true
    "false" false
    (throw (js/Error. "ADVENTURE_SYNC_ENABLED deve ser true ou false."))))

(def restore-enabled?
  "Global kill-switch for real legacy-save replacement. Safe by default."
  (case (str/lower-case (env "POKEMON_RESTORE_ENABLED" "false"))
    "true" true
    "false" false
    (throw (js/Error. "POKEMON_RESTORE_ENABLED deve ser true ou false."))))

(def egg-probabilities
  (egg-rewards/validar-probabilidades!
   {:capture-common (js/Number (env "EGG_CAPTURE_COMMON_PERCENT" "10"))
    :pvp-epic (js/Number (env "EGG_PVP_EPIC_PERCENT" "3"))
    :pvp-rare (js/Number (env "EGG_PVP_RARE_PERCENT" "20"))}))

(def bot-name (env "BOT_NAME" "Odisseu"))
(def prefix (env "PREFIX" "!"))
(def missoes-timezone (env "MISSOES_TIMEZONE" "America/Sao_Paulo"))
(def cassandra-contact-points
  (->> (str/split (env "CASSANDRA_CONTACT_POINTS" "127.0.0.1") #",")
       (map str/trim) (remove str/blank?) vec))
(def cassandra-datacenter (env "CASSANDRA_DATACENTER" "datacenter1"))
(def cassandra-keyspace (env "CASSANDRA_KEYSPACE" "zapbot"))
(def gemini-api-key (env "GEMINI_API_KEY" nil))
(def gemini-model (env "GEMINI_MODEL" "gemini-3.5-flash-lite"))
