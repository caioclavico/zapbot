(ns zapbot.config
  "Carrega variáveis de ambiente (.env) e expõe a configuração do bot."
  (:require [clojure.string :as str]
            ["dotenv" :as dotenv]))

(.config dotenv)

(defn- env
  ([k] (env k nil))
  ([k default]
   (let [v (unchecked-get js/process.env k)]
     (if (and v (not= v "")) v default))))

(defn- env-list [k default]
  (if-let [v (env k)]
    (->> (str/split v #",")
         (map str/trim)
         (remove str/blank?)
         vec)
    default))

;; Métricas opcionais, lidas uma vez ao iniciar o processo.
(def performance-metrics-enabled
  (= "true" (str/lower-case (str/trim (env "PERFORMANCE_METRICS" "false")))))

(def bot-name (env "BOT_NAME" "Odisseu"))
(def prefix (env "PREFIX" "!"))
(def admin-numbers (set (env-list "ADMIN_NUMBERS" [])))
(def default-city (env "WEATHER_DEFAULT_CITY" "Sao Paulo"))
(def news-feed-url (env "NEWS_FEED_URL" "https://g1.globo.com/rss/g1/"))
(def default-currencies (env-list "CURRENCY_DEFAULT" ["USD-BRL" "EUR-BRL" "BTC-BRL"]))
;; usado em ARM64/Docker, onde o Puppeteer não baixa um Chromium próprio
(def chromium-disable-gpu (= "true" (str/lower-case (env "CHROMIUM_DISABLE_GPU" "false"))))
(def chromium-low-resource-mode (= "true" (str/lower-case (env "CHROMIUM_LOW_RESOURCE_MODE" "true"))))
(def puppeteer-executable-path (env "PUPPETEER_EXECUTABLE_PATH"))
;; chave gratuita em https://www.themoviedb.org/settings/api (usada pelo !filme)
(def tmdb-api-key (env "TMDB_API_KEY"))
;; chave gratuita em https://aistudio.google.com/apikey (usada pelo !resuma)
(def gemini-api-key (env "GEMINI_API_KEY"))
;; evite "-latest": esse alias troca de modelo sem aviso (já trocou pra um
;; modelo "thinking" bem mais pesado/lotado do que o bot precisa) - fixe um
;; modelo leve e estável, ajustável sem precisar mexer no código
(def gemini-model (env "GEMINI_MODEL" "gemini-3.5-flash-lite"))
(def groq-api-key (env "GROQ_API_KEY"))
(def groq-model (env "GROQ_MODEL" "llama-3.1-8b-instant"))
;; credenciais gratuitas em https://developer.spotify.com/dashboard (usadas pelo !musica)
(def spotify-client-id (env "SPOTIFY_CLIENT_ID"))
(def spotify-client-secret (env "SPOTIFY_CLIENT_SECRET"))
;; usado por zapbot.armazenamento (persistência de rank/loja/admins/etc.)
(def cassandra-contact-points (env-list "CASSANDRA_CONTACT_POINTS" ["127.0.0.1"]))
(def cassandra-datacenter (env "CASSANDRA_DATACENTER" "datacenter1"))
(def cassandra-keyspace (env "CASSANDRA_KEYSPACE" "zapbot"))
;; APP_ENV=development restringe o bot a responder só no chat DEV_GROUP_ID -
;; evita respostas duplicadas nos grupos reais quando uma instância local de
;; teste roda ao lado da de produção
(def app-env (env "APP_ENV" "production"))
(def dev-group-id (env "DEV_GROUP_ID"))

;; development|staging: só DEV_GROUP_ID (+ allowlist opcional de remitentes).
(def staging-like?
  (contains? #{"development" "staging"} app-env))

;; Números/JIDs autorizados no chat de teste (vazio = qualquer membro do grupo).
;; Ex.: STAGING_ALLOWED_USERS=5511999999999,5511888888888
(def staging-allowed-users
  (set (env-list "STAGING_ALLOWED_USERS" [])))

(defn- env-flag-on? [k]
  (contains? #{"1" "true" "yes" "on"}
             (str/lower-case (str/trim (env k "false")))))

;; M6.3C — bootstrap só para descobrir DEV_GROUP_ID (sem processar mensagens).
;; STAGING_BOOTSTRAP_GROUP=1 permite boot sem DEV_GROUP_ID; nunca responde chats.
(def staging-bootstrap-group?
  (and (= "staging" app-env)
       (env-flag-on? "STAGING_BOOTSTRAP_GROUP")))

;; Após preencher DEV_GROUP_ID, exige confirmação explícita antes do bot normal.
(def staging-group-confirmed?
  (env-flag-on? "STAGING_GROUP_CONFIRMED"))

;; Permite QR na conta de TESTE (primeira autenticação / re-pareamento).
(def staging-allow-qr?
  (env-flag-on? "STAGING_ALLOW_QR"))

(def staging-group-discovery-path
  (env "STAGING_GROUP_DISCOVERY_PATH"))

(defn chat-autorizado?
  "Em staging-like, só o DEV_GROUP_ID. Fora disso, qualquer chat.
   Bootstrap de grupo: nunca autoriza (não processa mensagens)."
  [cid]
  (cond
    staging-bootstrap-group? false
    (not staging-like?) true
    :else (and (string? dev-group-id)
               (not (str/blank? dev-group-id))
               (= cid dev-group-id))))

(defn usuario-autorizado?
  "Allowlist opcional de remitentes em staging-like. Vazia = liberado."
  [uid]
  (or (not staging-like?)
      (empty? staging-allowed-users)
      (and (string? uid)
           (or (contains? staging-allowed-users uid)
               ;; aceita forma serializada sem sufixo @c.us
               (contains? staging-allowed-users
                          (first (str/split (str uid) #"@")))))))

;; Sessão WhatsApp (LocalAuth). Staging NUNCA deve usar o path de produção.
;; Produção/default: .wwebjs_auth + clientId nil → session/
;; Staging: WWEBJS_DATA_PATH=.wwebjs_auth_staging|…_core  (nunca .wwebjs_auth)
(def wwebjs-data-path
  (let [raw (env "WWEBJS_DATA_PATH")]
    (cond
      (and raw (not (str/blank? raw))) raw
      (= "staging" app-env) ".wwebjs_auth_staging"
      :else ".wwebjs_auth")))
(def wwebjs-client-id
  (let [raw (env "WWEBJS_CLIENT_ID")]
    (cond
      (and raw (not (str/blank? raw))) raw
      (= "staging" app-env) "staging"
      :else nil)))

(defn caminho-sessao-producao?
  "True para o path de produção (.wwebjs_auth), inclusive /app/.wwebjs_auth.
   False para .wwebjs_auth_staging / .wwebjs_auth_core (sufixos permitidos)."
  [p]
  (let [s (str/trim (str (or p "")))]
    (or (= s ".wwebjs_auth")
        (= s "/app/.wwebjs_auth")
        ;; Termina exatamente em "/.wwebjs_auth" — não em "_core"/"_staging".
        (boolean (re-find #"(^|/)\.wwebjs_auth$" s)))))

;; Renovação das missões por data local, independente do fuso do servidor.
(def missoes-timezone (env "MISSOES_TIMEZONE" "America/Sao_Paulo"))

;; Pokémon roda em serviço independente. O bot mantém apenas o adaptador HTTP.
(def pokemon-service-url (env "POKEMON_SERVICE_URL"))
(def pokemon-service-token (env "POKEMON_SERVICE_TOKEN"))
(defn- inteiro-positivo [nome padrao]
  (let [valor (js/Number (env nome (str padrao)))]
    (if (and (js/Number.isSafeInteger valor) (pos? valor)) valor padrao)))
(def ai-request-timeout-ms (inteiro-positivo "AI_REQUEST_TIMEOUT_MS" 8000))
(def ai-total-timeout-ms (inteiro-positivo "AI_TOTAL_TIMEOUT_MS" 30000))
(def ai-retry-base-ms (inteiro-positivo "AI_RETRY_BASE_MS" 1000))
(def pokemon-connect-timeout-ms (inteiro-positivo "POKEMON_CONNECT_TIMEOUT_MS" 5000))
(def pokemon-timeout-ms (inteiro-positivo "POKEMON_TIMEOUT_MS" 45000))
(def pokemon-event-poll-ms (inteiro-positivo "POKEMON_EVENT_POLL_MS" 15000))
(def pokemon-max-media-bytes (inteiro-positivo "POKEMON_MAX_MEDIA_BYTES" (* 10 1024 1024)))

;; M4.1/M6.1 — bridge JVM em development|staging. Produção sempre força legado.
;; POKEMON_ADAPTER=legacy|jvm (default legacy). Sem fallback de escrita.
(def pokemon-jvm-url (env "POKEMON_JVM_URL" "http://127.0.0.1:18090"))
(def pokemon-jvm-token (env "POKEMON_JVM_TOKEN"))
(def pokemon-jvm-connect-timeout-ms (inteiro-positivo "POKEMON_JVM_CONNECT_TIMEOUT_MS" 3000))
(def pokemon-jvm-timeout-ms (inteiro-positivo "POKEMON_JVM_TIMEOUT_MS" 8000))
;; Staging Compose: permite só http://odisseu-jvm:18090 (desligado por padrão).
(def pokemon-jvm-allow-docker-host?
  (and (= "staging" app-env)
       (= "true" (str/lower-case (env "POKEMON_JVM_ALLOW_DOCKER_HOST" "false")))))
(def ^:private pokemon-adapter-raw
  (str/lower-case (str/trim (env "POKEMON_ADAPTER" "legacy"))))
(def ^:private jvm-adapter-envs #{"development" "staging"})
(def pokemon-adapter
  (cond
    (and (= "jvm" pokemon-adapter-raw) (contains? jvm-adapter-envs app-env)) "jvm"
    (= "jvm" pokemon-adapter-raw) "legacy" ;; production e demais: forçar legado
    :else "legacy"))
(def pokemon-jvm-enabled?
  (= "jvm" pokemon-adapter))

;; M7.1 — bloqueio/admins via Datomic só em development|staging.
;; PLATFORM_ADAPTER=cassandra|datomic (default cassandra). Produção força cassandra.
;; Sem dual-write: se datomic e bridge cair, falha explícita (não grava Cassandra).
(def platform-jvm-url (env "PLATFORM_JVM_URL" pokemon-jvm-url))
(def platform-jvm-token (env "PLATFORM_JVM_TOKEN" pokemon-jvm-token))
(def platform-jvm-connect-timeout-ms (inteiro-positivo "PLATFORM_JVM_CONNECT_TIMEOUT_MS" 3000))
(def platform-jvm-timeout-ms (inteiro-positivo "PLATFORM_JVM_TIMEOUT_MS" 8000))
;; Mesma allowlist Docker que o Pokémon — só efetiva em staging.
(def platform-jvm-allow-private-host?
  (and (= "staging" app-env)
       (= "true" (str/lower-case (env "PLATFORM_JVM_ALLOW_PRIVATE_HOST" "false")))))
(def ^:private platform-adapter-raw
  (str/lower-case (str/trim (env "PLATFORM_ADAPTER" "cassandra"))))
(def platform-adapter
  (cond
    (and (= "datomic" platform-adapter-raw) (contains? jvm-adapter-envs app-env)) "datomic"
    (= "datomic" platform-adapter-raw) "cassandra"
    :else "cassandra"))
(def platform-datomic-enabled?
  (= "datomic" platform-adapter))

;; M7.7 — staging/dev com Platform Datomic + Pokémon JVM: sem boot Cassandra.
;; Produção nunca entra aqui (adapters forçados a cassandra/legacy).
(def cassandra-boot-enabled?
  (not (and platform-datomic-enabled? pokemon-jvm-enabled?)))

;; Diagnóstico passivo do processo e navegador; false elimina o coletor/timers.
(def odisseu-resource-metrics-enabled
  (= "true" (str/lower-case (env "ODISSEU_RESOURCE_METRICS_ENABLED" "true"))))
(def odisseu-resource-metrics-interval-ms
  (max 15000 (inteiro-positivo "ODISSEU_RESOURCE_METRICS_INTERVAL_MS" 60000)))
