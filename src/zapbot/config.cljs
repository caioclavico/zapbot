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
(defn- bounded-positive-int [key default min-value max-value]
  (let [parsed (js/parseInt (env key (str default)) 10)]
    (if (and (js/Number.isSafeInteger parsed) (<= min-value parsed max-value))
      parsed
      default)))
(def ai-content-history-window
  (bounded-positive-int "AI_CONTENT_HISTORY_WINDOW" 120 20 200))
(def ai-content-generation-attempts
  (bounded-positive-int "AI_CONTENT_GENERATION_ATTEMPTS" 2 1 2))
(def ai-content-max-output-tokens
  (bounded-positive-int "AI_CONTENT_MAX_OUTPUT_TOKENS" 320 64 512))
(def ai-content-history-path
  (env "AI_CONTENT_HISTORY_PATH" "/app/data/ai-content-history.json"))
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

;; Homologação HTTP-only: sem Client/LocalAuth/QR/Chromium.
;; WHATSAPP_TRANSPORT=disabled|off|none
(def whatsapp-transport-disabled?
  (contains? #{"disabled" "off" "none" "0" "false"}
             (str/lower-case (str/trim (env "WHATSAPP_TRANSPORT" "enabled")))))

;; Bind do /health (default loopback). Em Compose A6 HTTP-only use 0.0.0.0.
(def health-bind-host
  (or (env "HEALTH_BIND_HOST") "127.0.0.1"))

;; Piloto/liberação controlada: ALLOW_ALL_GROUPS=true abre qualquer @g.us
;; (ainda sujeito a STAGING_ALLOWED_USERS). Default false.
(def allow-all-groups?
  (env-flag-on? "ALLOW_ALL_GROUPS"))

;; Fase 11.1 — allowlist multi-grupo (IDs literais @g.us). Compatível com DEV_GROUP_ID.
;; CSV; IDs inválidos → falha segura (nega todos os chats em staging-like).
(def allowed-group-ids-csv (env "ALLOWED_GROUP_IDS" ""))

;; Se true em staging-like, STAGING_ALLOWED_USERS vazio = negar todos (fail-closed).
;; STRICT_USER_ALLOWLIST é alias opcional da mesma flag.
(def staging-strict-user-allowlist?
  (or (env-flag-on? "STAGING_STRICT_USER_ALLOWLIST")
      (env-flag-on? "STRICT_USER_ALLOWLIST")))

(def ^:private group-allowlist-lib
  (js/require "../scripts/lib/group-allowlist.cjs"))

;; Objeto JS bruto (preserva groupSet) — não round-trip via cljs.
(def ^:private authorized-groups-js
  (.buildAuthorizedGroups group-allowlist-lib
                          #js {:allowedGroupIdsCsv allowed-group-ids-csv
                               :devGroupId dev-group-id}))

(def authorized-groups
  "Espelho CLJ do resultado (inspeção/logs)."
  (js->clj authorized-groups-js :keywordize-keys true))

(def allowed-group-ids
  "Vetor deduplicado de @g.us autorizados (ALLOWED_GROUP_IDS ∪ DEV_GROUP_ID)."
  (vec (or (.-groups authorized-groups-js) #js [])))

(def allowed-groups-config-error?
  (not (boolean (.-ok authorized-groups-js))))

(when (and staging-like? allowed-groups-config-error?)
  (js/console.error "[config] ALLOWED_GROUP_IDS/DEV_GROUP_ID inválido(s) — fail-closed:"
                    (pr-str (js->clj (.-invalid authorized-groups-js)))))

(defn chat-autorizado?
  "Em staging-like: membros de ALLOWED_GROUP_IDS ∪ DEV_GROUP_ID.
   ALLOW_ALL_GROUPS=true libera grupos em qualquer ambiente, nunca chats privados.
   IDs ambíguos exigem is-group? true obtido dos metadados do Chat."
  ([cid] (chat-autorizado? cid nil))
  ([cid is-group?]
   (.chatAuthorized group-allowlist-lib
                    #js {:chatId cid
                         :isGroup is-group?
                         :stagingLike staging-like?
                         :bootstrap staging-bootstrap-group?
                         :allowAllGroups allow-all-groups?
                         :authorized authorized-groups-js})))
(defn usuario-autorizado?
  "Allowlist de remitentes em staging-like — separada da de grupo.
   STRICT=1 (STAGING_STRICT_USER_ALLOWLIST|STRICT_USER_ALLOWLIST) + lista vazia → nega todos.
   STRICT=0 + lista vazia → libera qualquer uid string (participantes do grupo autorizado).
   Match literal do JID completo apenas (sem normalizar @lid/@c.us)."
  [uid]
  (.userAuthorized group-allowlist-lib
                   #js {:userId uid
                        :stagingLike staging-like?
                        :strictUserAllowlist staging-strict-user-allowlist?
                        :allowedUsersCsv (env "STAGING_ALLOWED_USERS" "")}));; Sessão WhatsApp (LocalAuth). Staging NUNCA deve usar o path de produção.
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
;; Fase 3 — kill switch do tick de raides automáticas (default off).
(def pokemon-raid-auto-tick?
  (= "true" (str/lower-case (env "POKEMON_RAID_AUTO_TICK" "false"))))
(def pokemon-raid-auto-tick-ms
  (inteiro-positivo "POKEMON_RAID_AUTO_TICK_MS" 30000))

;; Idle timeout de batalhas JVM (espelha POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS na bridge).
(def pokemon-battle-idle-timeout-seconds
  (inteiro-positivo "POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" 300))

;; Kit/diário da mochila na JVM — desligado até backfill RO das flags recovery.
(def pokemon-bag-kit-daily-enabled?
  (= "true" (str/lower-case (str/trim (env "POKEMON_BAG_KIT_DAILY_ENABLED" "false")))))

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

;; Release Announcer opt-in: exige intent JSON confirmado + piloto.
;; Default false — READY sozinho NÃO anuncia. ALLOW_ALL_GROUPS NÃO autoriza anúncios.
(def release-announcer-enabled?
  (= "true" (str/lower-case (str/trim (env "RELEASE_ANNOUNCER_ENABLED" "false")))))
(def release-announcement-intent-path (env "RELEASE_ANNOUNCEMENT_INTENT_PATH" ""))
(def release-pilot-chat-id (env "RELEASE_PILOT_CHAT_ID" ""))

(defn release-chat-autorizado?
  "Allowlist independente do Release Announcer (piloto only nesta etapa).
   NÃO consulta ALLOW_ALL_GROUPS / chat-autorizado?."
  [cid]
  (and (string? release-pilot-chat-id)
       (not (str/blank? release-pilot-chat-id))
       (= (str cid) release-pilot-chat-id)))

;; Pokédex de espécie (top-level !pokedex/!dex/!pdx) — homologação restrita ao piloto.
;; Default true: sem allowlist explícita a feature não vaza para outros grupos.
;; !pk pokedex (coleção pessoal) NÃO usa este gate.
(def pokedex-species-pilot-only?
  (= "true" (str/lower-case (str/trim (env "POKEDEX_SPECIES_PILOT_ONLY" "true")))))
(def pokedex-species-pilot-chat-id
  (let [raw (env "POKEDEX_SPECIES_PILOT_CHAT_ID" "")]
    (if (str/blank? raw) release-pilot-chat-id raw)))

(defn pokedex-species-chat-autorizado?
  "Quando pilot-only: só o chat piloto. Quando false: todos (GA futuro)."
  [cid]
  (if-not pokedex-species-pilot-only?
    true
    (and (string? pokedex-species-pilot-chat-id)
         (not (str/blank? pokedex-species-pilot-chat-id))
         (= (str cid) pokedex-species-pilot-chat-id))))

;; M7.7 — staging/dev com Platform Datomic + Pokémon JVM: sem boot Cassandra.
;; Produção nunca entra aqui (adapters forçados a cassandra/legacy).
(def cassandra-boot-enabled?
  (not (and platform-datomic-enabled? pokemon-jvm-enabled?)))

;; Diagnóstico passivo do processo e navegador; false elimina o coletor/timers.
(def odisseu-resource-metrics-enabled
  (= "true" (str/lower-case (env "ODISSEU_RESOURCE_METRICS_ENABLED" "true"))))
(def odisseu-resource-metrics-interval-ms
  (max 15000 (inteiro-positivo "ODISSEU_RESOURCE_METRICS_INTERVAL_MS" 60000)))
