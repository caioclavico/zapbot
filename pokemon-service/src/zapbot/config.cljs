(ns zapbot.config
  "Configuração mínima do processo Pokémon."
  (:require [clojure.string :as str]
            ["dotenv" :as dotenv]))

(.config dotenv)

(defn- env [chave padrao]
  (let [valor (unchecked-get js/process.env chave)]
    (if (str/blank? valor) padrao valor)))

(def read-only? (.-readOnly (js/require "../runtime/mode.cjs")))

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
