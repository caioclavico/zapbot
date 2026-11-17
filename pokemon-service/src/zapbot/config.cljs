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

(def save-transitions-enabled?
  (case (str/lower-case (env "SAVE_TRANSITIONS_ENABLED" "false"))
    "true" true
    "false" false
    (throw (js/Error. "SAVE_TRANSITIONS_ENABLED deve ser true ou false."))))
(def save-transition-legacy-coverage-verified?
  (case (str/lower-case (env "SAVE_TRANSITIONS_LEGACY_COVERAGE_VERIFIED" "false"))
    "true" true
    "false" false
    (throw (js/Error. "SAVE_TRANSITIONS_LEGACY_COVERAGE_VERIFIED deve ser true ou false."))))
;; Deliberately a code-level certification, not an environment switch. Gym,
;; battle and callback writers still include legacy snapshot paths; setting an
;; env var must not be enough to claim those writers are fenced.
(def save-transition-writer-coverage-verified? false)

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
;; Opcional: cliente principal autentica quando o cluster exige senha
;; (ex.: harness descartável com PasswordAuthenticator). Produção com
;; AllowAll continua sem estas variáveis.
(def cassandra-username (env "CASSANDRA_USERNAME" nil))
(def cassandra-password (env "CASSANDRA_PASSWORD" nil))
(def canonical-trainer-enabled?
  (case (str/lower-case (env "CANONICAL_TRAINER_ENABLED" "false"))
    "true" true "false" false
    (throw (js/Error. "CANONICAL_TRAINER_ENABLED deve ser true ou false."))))
(def canonical-trainer-username (env "CANONICAL_TRAINER_USERNAME" nil))
(def canonical-trainer-password (env "CANONICAL_TRAINER_PASSWORD" nil))
(when (and canonical-trainer-enabled?
           (or (str/blank? canonical-trainer-username)
               (str/blank? canonical-trainer-password)))
  (throw (js/Error. "Canonical trainer exige credenciais Cassandra dedicadas.")))
(def canonical-gyms-enabled?
  (case (str/lower-case (env "CANONICAL_GYMS_ENABLED" "false"))
    "true" true "false" false
    (throw (js/Error. "CANONICAL_GYMS_ENABLED deve ser true ou false."))))
;; Battle rewards and trainer/shop effects still span writers that do not
;; share a recoverable multi-partition journal. Keep gym battles blocked in
;; canonical mode until every effect adapter has an end-to-end certification.
(def canonical-gym-battle-effects-verified? false)
(def canonical-gyms-username (env "CANONICAL_GYMS_USERNAME" nil))
(def canonical-gyms-password (env "CANONICAL_GYMS_PASSWORD" nil))
(when (and canonical-gyms-enabled?
           (or (str/blank? canonical-gyms-username)
               (str/blank? canonical-gyms-password)))
  (throw (js/Error. "Canonical gyms exige credenciais Cassandra dedicadas.")))
(def gemini-api-key (env "GEMINI_API_KEY" nil))
(def gemini-model (env "GEMINI_MODEL" "gemini-3.5-flash-lite"))
