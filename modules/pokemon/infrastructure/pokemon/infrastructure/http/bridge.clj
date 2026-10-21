(ns pokemon.infrastructure.http.bridge
  "Servidor HTTP da bridge Pokémon (M4.1 leituras + M4.2 escritas explícitas).
  Bind forçado em loopback; Bearer obrigatório nas rotas de domínio;
  sem rotas administrativas nem endpoint genérico de mutação."
  (:require [clojure.string :as str]
            [odisseu.platform.infrastructure.http.handlers :as platform-http]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.mission-queries :as missions]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.json :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private loopback-hosts #{"127.0.0.1" "localhost" "::1"})
;; 0.0.0.0 só com flag explícita (rede privada Docker). Nunca publicar a porta.

;; operation-id em voo → :operation_pending (sem inventar novo id no cliente).
(def ^:private inflight (atom #{}))

(defn- query-map
  [^String raw]
  (if (str/blank? raw)
    {}
    (->> (str/split raw #"&")
         (keep (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   (when-not (str/blank? k)
                     [(URLDecoder/decode k StandardCharsets/UTF_8)
                      (URLDecoder/decode (or v "") StandardCharsets/UTF_8)]))))
         (into {}))))

(defn- header
  [^HttpExchange ex name]
  (some-> (.getRequestHeaders ex) (.getFirst name)))

(defn- bearer-ok?
  [^HttpExchange ex expected]
  (let [auth (or (header ex "Authorization") "")]
    (and (string? expected)
         (not (str/blank? expected))
         (= auth (str "Bearer " expected)))))

(defn- send-json!
  [^HttpExchange ex status body]
  (let [bytes (.getBytes ^String (json/write-str body) StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders ex) "Content-Type" "application/json; charset=utf-8")
    (.set (.getResponseHeaders ex) "Cache-Control" "no-store")
    (.sendResponseHeaders ex status (alength bytes))
    (with-open [os (.getResponseBody ex)]
      (.write os bytes))))

(defn- read-body
  [^HttpExchange ex]
  (with-open [in (.getRequestBody ex)]
    (let [raw (slurp in)]
      (if (str/blank? raw)
        {}
        (json/read-str raw)))))

(defn- business-status
  [code]
  (case code
    (:player_not_found :no_active_save :save_not_found :operation_not_found
     :encounter_not_found :battle_not_found) 404
    (:invalid_query :missing_operation_id :missing_reward_identity
     :missing_writer_context :missing_fence_generation :invalid_coins_delta
     :unknown_shop_item :item_not_for_sale :item_use_unsupported
     :wrong_status_item :no_status_to_cure :missing_item_id
     :invalid_buy_qty :invalid_collection_index
     :invalid_save_slot :invalid_slot_id :invalid_identity :unknown_ball
     :area_unavailable :invalid_wild_pokemon :hunt_no_candidates
     :unknown_gym :unknown_heal_item :invalid_gym_team
     :invalid_gym_npc_team :invalid_motivation_index
     :invalid_raid_pokemon :invalid_raid_move :raid_not_found
     :invalid_starter :invalid_move_slot :move_already_known
     :must_keep_stab_move :no_move_offer :move_not_in_history
     :cannot_forget_last_move :pokemon_not_found
     :pvp_level_incompatible :pvp_no_challenge :not_pvp_battle) 400
    (:stale_writer_fence :writer_fence_conflict :activate_conflict
     :receipt_payload_mismatch :reward_conflict :reward_save_mismatch
     :operation_forbidden :battle_pending :battle_already_active
     :battle_busy :battle_save_mismatch :encounter_owner_mismatch
     :encounter_not_ready
     :stale_turn_index :chat_hunt_busy :hunt_cooldown
     :pvp_chat_busy :pvp_self_accept :pvp_not_waiting :pvp_challenge_expired
     :pvp_out_of_turn :pvp_not_participant :pvp_cancel_forbidden
     :gym_cas_conflict :not_gym_leader :gym_defense_pending
     :cannot_challenge_own_gym :gym_locked :gym_daily_limit
     :raid_not_open :already_in_raid :raid_full :raid_cannot_leave
     :raid_not_creator :not_in_raid :raid_need_players :raid_not_in_combat
     :raid_not_your_turn :raid_save_mismatch :raid_liga_mismatch
     :raid_capture_unavailable
     :starter_unavailable :mission_period_conflict :mission_event_identity_conflict
     :mission_event_already_processed) 409
    :operation_pending 409
    (:durability_compromised :durability_maintenance
     :durability_journal_required) 503
    (:insufficient_item :insufficient_coins :bag_full :collection_full
     :pokemon_full_hp :active_pokemon_fainted
     :no_active_pokemon :motivation_full :gym_empty :gym_team_unhealthy) 422
    :unauthorized 401
    :method_not_allowed 405
    422))

(defn- handle-error!
  [^HttpExchange ex th]
  (if-let [code (err/error-code th)]
    (let [data (dissoc (ex-data th) :pokemon/error)]
      (send-json! ex (business-status code)
                  {:ok false
                   :error {:code (name code)
                           :message (ex-message th)
                           :details data}}))
    (do
      (binding [*out* *err*]
        (println "[bridge] erro interno:" (ex-message th)))
      (send-json! ex 500
                  {:ok false
                   :error {:code "internal_error"
                           :message "Erro interno na bridge."}}))))

(defn- method-of [^HttpExchange ex]
  (.getRequestMethod ex))

(defn- path-of
  [^HttpExchange ex]
  (let [uri (str (.getRequestURI ex))
        q (.indexOf uri (int \?))]
    (if (neg? q) uri (.substring uri 0 q))))

(defn- ok!
  [^HttpExchange ex data]
  (send-json! ex 200 {:ok true :data data}))

(defn- require-auth!
  [token ^HttpExchange ex]
  (when-not (bearer-ok? ex token)
    (throw (err/error :unauthorized {}))))

(defn- require-method!
  [^HttpExchange ex allowed]
  (let [m (method-of ex)]
    (when-not (contains? allowed m)
      (throw (err/error :method_not_allowed {:method m :allowed (vec allowed)})))))

(defn- with-operation!
  [operation-id f]
  (let [id (str operation-id)]
    (loop []
      (let [prev @inflight]
        (cond
          (contains? prev id)
          (throw (err/error :operation_pending {:operation-id id}))

          (compare-and-set! inflight prev (conj prev id))
          (try
            (f)
            (finally
              (swap! inflight disj id)))

          :else (recur))))))

(defn- g [m & ks]
  (some #(get m %) ks))

(defn- long-or-nil [v]
  (cond
    (nil? v) nil
    (integer? v) (long v)
    (string? v) (try (Long/parseLong v) (catch Exception _ nil))
    (number? v) (long v)
    :else nil))

(defn- make-handler
  "opts opcionais:
   :credit-coins! (fn [store req]) — M8.5 durable-op; default tx/credit-coins!
   :score-point! (fn [store cid pid nome jogo op]) — M8.6 rank durable-op
   :platform-hooks mapa M8.7 (set-bot-blocked! / block-commands! / …)"
  [store token allow-test-rng? platform-store
   & [{:keys [credit-coins! score-point! platform-hooks]
       :or {credit-coins! tx/credit-coins!
            platform-hooks {}}}]]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [path (path-of ex)
              method (method-of ex)
              qs (query-map (.getRawQuery (.getRequestURI ex)))]
          (cond
            (= path "/health")
            (do
              (require-method! ex #{"GET"})
              (ok! ex {:status "up"
                       :role "pokemon-jvm-bridge"
                       :readsOnly false
                       :allowTestRng (boolean allow-test-rng?)
                       :platformEnabled (boolean platform-store)
                       :capabilities (cond-> ["trainer-read" "collection-read" "active-save-read"
                                              "trainer-ensure" "saves-list" "save-activate"
                                              "missions-read" "missions-prepare"
                                              "reward-coins" "operation-get"
                                              "encounter-read" "encounter-capture"
                                              "battle-start" "battle-turn"
                                              "hunt-plan" "hunt-start"
                                              "gyms-read" "gym-team" "gym-heal"
                                              "gym-challenge"
                                              "raid-read" "raid-join" "raid-leave"
                                              "raid-start" "raid-attack" "raid-capture"
                                              "joy-read" "joy-send" "joy-collect"
                                              "adventure-read" "egg-incubate" "egg-hatch"
                                              "shop-read" "shop-buy" "inventory-read"
                                              "item-use"
                                              "pokemon-set-active" "pokemon-evolve"
                                              "pokemon-learn-move" "pokemon-forget-move"
                                              "pokemon-offer-move" "pokemon-refuse-offer"
                                              "pokemon-relearn-move"
                                              "pvp-open" "pvp-accept" "pvp-cancel"
                                              "pvp-turn" "pvp-active-read"
                                              "onboarding-read" "onboarding-start"
                                              "onboarding-kit" "onboarding-starter"]
                                       platform-store
                                       (into ["platform-blocks" "platform-admins"]))}))

            (or (str/starts-with? path "/admin")
                (str/starts-with? path "/v1/admin")
                (str/includes? path "admin-seed")
                (str/includes? path "migrate"))
            (send-json! ex 404
                        {:ok false
                         :error {:code "not_found" :message "Rota inexistente."}})

            :else
            (do
              (require-auth! token ex)
              (cond
                (str/starts-with? path "/v1/platform")
                (if-not platform-store
                  (send-json! ex 503
                              {:ok false
                               :error {:code "platform_unavailable"
                                       :message "Bridge sem store de platform (bloqueio/admins)."}})
                  (let [body (when (#{"POST" "PUT" "PATCH"} method) (read-body ex))
                        popts (cond-> (or platform-hooks {})
                                score-point! (assoc :score-point! score-point!))
                        res (platform-http/handle platform-store method path qs body
                                                  popts)]
                    (ok! ex (:data res))))

                (= path "/v1/missions")
                (do (require-method! ex #{"GET"})
                    (let [chat (g qs "chatId") player (g qs "playerId")]
                      (when (or (str/blank? chat) (str/blank? player))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (missions/read-missions store chat player
                                                      (not-empty (g qs "saveId")) {}))))

                (= path "/v1/missions/prepare")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat (g body "chatId") player (g body "playerId")
                          slot (g body "saveId") fence (long-or-nil (g body "fenceGeneration"))]
                      (when (or (str/blank? chat) (str/blank? player)
                                (str/blank? slot) (nil? fence))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId" "fenceGeneration"]})))
                      (ok! ex (missions/prepare-missions!
                               store {:chat-id chat :player-id player :save-id slot
                                      :fence-generation fence} {}))))

                (= path "/v1/trainer")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/trainer store chat-id player-id))))

                (= path "/v1/collection")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          save-id (not-empty (g qs "saveId"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/collection store chat-id player-id save-id))))

                (= path "/v1/pokedex")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          save-id (not-empty (g qs "saveId"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/pokedex store chat-id player-id save-id))))

                (= path "/v1/pokemon/ficha")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          save-id (not-empty (g qs "saveId"))
                          idx (long-or-nil (g qs "collectionIndex" "idx" "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id) (nil? idx))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "collectionIndex"]})))
                      (ok! ex (reads/pokemon-ficha store chat-id player-id save-id idx))))

                (= path "/v1/active-save")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/active-save store chat-id player-id))))

                (= path "/v1/saves")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (tx/list-saves store chat-id player-id))))

                (= path "/v1/trainer/ensure")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (tx/ensure-trainer! store {:chat-id chat-id :player-id player-id}))))

                (= path "/v1/onboarding")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (tx/onboarding-status store chat-id player-id))))

                (= path "/v1/onboarding/start")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          operation-id (g body "operationId" "operation-id")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (with-operation! (or operation-id
                                                   (str "onboard-start:" chat-id "|" player-id))
                                #(tx/start-journey!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :operation-id (or operation-id
                                                           (str "onboard-kit:" chat-id "|" player-id))})))))

                (= path "/v1/onboarding/kit")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          operation-id (g body "operationId" "operation-id")]
                      (when (or (str/blank? chat-id) (str/blank? player-id) (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/claim-starter-kit!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :operation-id operation-id})))))

                (= path "/v1/onboarding/starter")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          operation-id (g body "operationId" "operation-id")
                          choice (or (g body "choice") (g body "starter")
                                     (g body "species") (g body "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? operation-id) (nil? choice))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "operationId" "choice"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/choose-starter!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :operation-id operation-id
                                         :choice (str choice)})))))

                (= path "/v1/saves/activate")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          expected (long-or-nil (g body "expectedFenceGeneration"
                                                   "expected-fence-generation"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id) (str/blank? save-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"]})))
                      (ok! ex (tx/activate-save!
                               store {:chat-id chat-id
                                      :player-id player-id
                                      :save-id save-id
                                      :expected-fence-generation expected}))))

                (= path "/v1/rewards/coins")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId" "operation-id")
                          coins-delta (long-or-nil (g body "coinsDelta" "coins-delta"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (str/blank? operation-id)
                                (nil? fence) (nil? coins-delta))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "coinsDelta"]})))
                      (ok! ex (with-operation! operation-id
                                #(credit-coins!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :save-id save-id
                                         :fence-generation fence
                                         :operation-id operation-id
                                         :coins-delta coins-delta})))))

                (str/starts-with? path "/v1/operations/")
                (do (require-method! ex #{"GET"})
                    (let [op-id (URLDecoder/decode
                                 (.substring path (count "/v1/operations/"))
                                 StandardCharsets/UTF_8)
                          chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? op-id) (str/includes? op-id "/"))
                        (throw (err/error :invalid_query {:required ["operationId"]})))
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId"]})))
                      (ok! ex (tx/get-operation store op-id chat-id player-id))))

                (= path "/v1/encounter")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/active-encounter store chat-id player-id))))

                (= path "/v1/hunt/plan")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          area-id (not-empty (g qs "areaId"))
                          agora (long-or-nil (g qs "agoraMs"))
                          roll-tipos (long-or-nil (g qs "rollTipos"))
                          roll-surto (long-or-nil (g qs "rollSurto"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/hunt-plan
                               store chat-id player-id
                               (cond-> {:area-id area-id}
                                 (some? agora) (assoc :agora-ms agora)
                                 (and allow-test-rng? (some? roll-tipos))
                                 (assoc :roll-tipos roll-tipos)
                                 (and allow-test-rng? (some? roll-surto))
                                 (assoc :roll-surto roll-surto))))))

                (= path "/v1/hunt/start")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId" "operation-id")
                          area-id (g body "areaId" "area-id")
                          tipos (or (g body "tiposEncontro" "tipos-encontro") [])
                          surto? (boolean (g body "surto" "surto?"))
                          poder (long-or-nil (g body "poderAlvo" "poder-alvo"))
                          candidates (or (g body "candidates") [])
                          shiny-roll (long-or-nil (g body "shinyRoll" "shiny-roll"))
                          clima-id (g body "climaId" "clima-id")
                          agora (long-or-nil (g body "agoraMs" "agora-ms"))
                          battle-id (g body "battleId" "battle-id")]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (str/blank? operation-id)
                                (nil? fence))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/start-hunt!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :area-id area-id
                                                 :tipos-encontro tipos
                                                 :surto? surto?
                                                 :poder-alvo poder
                                                 :candidates candidates
                                                 :clima-id clima-id
                                                 :allow-test-rng allow-test-rng?}
                                          battle-id (assoc :battle-id battle-id)
                                          (some? agora) (assoc :agora-ms agora)
                                          (and allow-test-rng? (some? shiny-roll))
                                          (assoc :shiny-roll shiny-roll)))))))

                (= path "/v1/encounter/capture")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId" "operation-id")
                          bola (g body "bola" "ball")
                          battle-id (g body "battleId" "battle-id")
                          roll (long-or-nil (g body "roll"))
                          fuga-roll (long-or-nil (g body "fugaRoll" "fuga-roll"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (str/blank? operation-id)
                                (nil? fence) (str/blank? bola))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "bola"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/attempt-encounter-capture!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :bola bola}
                                          battle-id (assoc :battle-id battle-id)
                                          (some? roll) (assoc :roll roll)
                                          (some? fuga-roll) (assoc :fuga-roll fuga-roll)))))))

                (= path "/v1/battles/start")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId" "operation-id")
                          battle-id (g body "battleId" "battle-id")
                          pokemon-x (or (g body "pokemonX" "pokemon-x") {})
                          pokemon-o (or (g body "pokemonO" "pokemon-o") {})
                          hp-x (long-or-nil (g body "hpX" "hp-x"))
                          hp-o (long-or-nil (g body "hpO" "hp-o"))
                          collection-index (long-or-nil (g body "collectionIndex"
                                                           "collection-index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (str/blank? operation-id)
                                (nil? fence))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/start-wild-battle!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :pokemon-x pokemon-x
                                                 :pokemon-o pokemon-o}
                                          battle-id (assoc :battle-id battle-id)
                                          (some? hp-x) (assoc :hp-x hp-x)
                                          (some? hp-o) (assoc :hp-o hp-o)
                                          (some? collection-index)
                                          (assoc :collection-index collection-index)))))))

                (= path "/v1/battles/turn")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId" "chat-id")
                          player-id (g body "playerId" "player-id")
                          save-id (g body "saveId" "save-id")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId" "operation-id")
                          battle-id (g body "battleId" "battle-id")
                          expected (long-or-nil (g body "expectedTurnIndex"
                                                   "expected-turn-index"))
                          action (g body "action")
                          move-index (long-or-nil (g body "moveIndex" "move-index"))
                          item-id (g body "itemId" "item-id")
                          rng (or (g body "rng") {})]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (str/blank? operation-id)
                                (nil? fence) (str/blank? battle-id)
                                (nil? expected) (str/blank? action))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "battleId" "expectedTurnIndex"
                                                      "action"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/confirm-battle-turn!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :battle-id battle-id
                                                 :expected-turn-index expected
                                                 :action (keyword action)
                                                 :rng rng}
                                          (some? move-index)
                                          (assoc :move-index move-index)
                                          (not (str/blank? item-id))
                                          (assoc :item-id item-id)))))))

                (= path "/v1/gyms")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          agora (long-or-nil (g qs "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/gyms-overview store chat-id player-id
                                                   {:agora-ms agora}))))

                (= path "/v1/gyms/team")
                (cond
                  (= method "GET")
                  (let [chat-id (g qs "chatId")
                        player-id (g qs "playerId")]
                    (when (or (str/blank? chat-id) (str/blank? player-id))
                      (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                    (ok! ex (reads/gym-team store chat-id player-id)))

                  (= method "POST")
                  (let [body (read-body ex)
                        chat-id (g body "chatId")
                        player-id (g body "playerId")
                        save-id (g body "saveId")
                        fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                        indices (or (g body "indices") [])]
                    (when (or (str/blank? chat-id) (str/blank? player-id)
                              (str/blank? save-id) (nil? fence))
                      (throw (err/error :invalid_query
                                        {:required ["chatId" "playerId" "saveId"
                                                    "fenceGeneration" "indices"]})))
                    (ok! ex (tx/set-gym-team!
                             store {:chat-id chat-id
                                    :player-id player-id
                                    :save-id save-id
                                    :fence-generation fence
                                    :indices indices})))

                  :else
                  (throw (err/error :method_not_allowed {:method method})))

                (= path "/v1/gyms/heal")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          gym-id (g body "gymId" "gym-id")
                          indice (long-or-nil (g body "indice" "index"))
                          item (g body "item" "itemId")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? gym-id)
                                (nil? indice) (str/blank? (str item)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "gymId" "indice" "item"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/heal-gym-motivation!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :gym-id gym-id
                                                 :indice indice
                                                 :item item}
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/gyms/challenge")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          gym-id (g body "gymId" "gym-id")
                          candidates (or (g body "candidates") [])
                          nome (g body "nome" "name")
                          battle-id (g body "battleId" "battle-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))
                          dia (g body "dia" "day")]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? gym-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "gymId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/start-gym-challenge!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :gym-id gym-id
                                                 :candidates candidates}
                                          nome (assoc :nome nome)
                                          battle-id (assoc :battle-id battle-id)
                                          (some? agora) (assoc :agora-ms agora)
                                          dia (assoc :dia dia)))))))

                (str/starts-with? path "/v1/gyms/")
                (do (require-method! ex #{"GET"})
                    (let [gym-id (subs path (count "/v1/gyms/"))
                          chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          agora (long-or-nil (g qs "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? gym-id)
                                (#{"team" "heal" "challenge"} gym-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "gymId"]})))
                      (ok! ex (reads/gym-detail store chat-id player-id gym-id
                                                {:agora-ms agora}))))

                (= path "/v1/raids/active")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          agora (long-or-nil (g qs "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/active-raid store chat-id player-id
                                                 {:agora-ms agora}))))

                (= path "/v1/raids/eligible")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/raid-eligible-pokemon store chat-id player-id))))

                (= path "/v1/raids/open")
                (do (require-method! ex #{"POST"})
                    (when-not allow-test-rng?
                      (throw (err/error :operation_forbidden
                                        {:reason "raid-open-harness-only"})))
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          ginasio (g body "ginasio" "gymId")
                          liga (g body "liga")
                          chefe (or (g body "chefe" "boss") {})
                          nivel (long-or-nil (g body "nivelGinasio" "nivel-ginasio"))
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? ginasio))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "ginasio"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/open-raid!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :ginasio ginasio
                                                 :chefe chefe}
                                          raid-id (assoc :raid-id raid-id)
                                          liga (assoc :liga liga)
                                          nivel (assoc :nivel-ginasio nivel)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/raids/join")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          idx (long-or-nil (g body "collectionIndex" "collection-index"))
                          auto? (boolean (or (g body "auto") (= "auto" (str (g body "mode")))))
                          nome (g body "nome" "name")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id)
                                (and (not auto?) (nil? idx)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex|auto"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/join-raid!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :auto? auto?}
                                          raid-id (assoc :raid-id raid-id)
                                          (some? idx) (assoc :collection-index idx)
                                          nome (assoc :nome nome)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/raids/leave")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/leave-raid!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          raid-id (assoc :raid-id raid-id)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/raids/start")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/start-raid!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          raid-id (assoc :raid-id raid-id)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/raids/attack")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          expected (long-or-nil (g body "expectedTurnIndex"
                                                   "expected-turn-index"))
                          move-index (long-or-nil (g body "moveIndex" "move-index"))
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? expected)
                                (nil? move-index))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "expectedTurnIndex" "moveIndex"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/raid-attack!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :expected-turn-index expected
                                                 :move-index move-index}
                                          raid-id (assoc :raid-id raid-id)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/raids/capture")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          raid-id (g body "raidId" "raid-id")
                          bola (g body "bola" "ball")
                          roll (long-or-nil (g body "roll"))
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? (str bola)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "bola"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/raid-capture!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :bola bola}
                                          raid-id (assoc :raid-id raid-id)
                                          (and allow-test-rng? (some? roll))
                                          (assoc :roll roll)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/joy")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          agora (long-or-nil (g qs "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/joy-status store chat-id player-id
                                                {:agora-ms agora}))))

                (= path "/v1/joy/send")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/joy-send!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :idx idx}
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/joy/collect")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/joy-collect!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/adventure")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")
                          agora (long-or-nil (g qs "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/adventure-status store chat-id player-id
                                                      {:agora-ms agora}))))

                (= path "/v1/adventure/eggs/grant")
                (do (require-method! ex #{"POST"})
                    (when-not allow-test-rng?
                      (throw (err/error :operation_forbidden
                                        {:reason "egg-grant-harness-only"})))
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          egg-id (g body "eggId" "egg-id")
                          category (g body "category" "categoria")
                          species (g body "species" "especie")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/grant-egg!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          egg-id (assoc :egg-id egg-id)
                                          category (assoc :category category)
                                          species (assoc :species species)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/adventure/eggs/incubate")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          egg-id (g body "eggId" "egg-id")
                          incubator-id (g body "incubatorId" "incubator-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? (str egg-id)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "eggId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/incubate-egg!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :egg-id egg-id}
                                          incubator-id (assoc :incubator-id incubator-id)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/adventure/eggs/hatch")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          egg-id (g body "eggId" "egg-id")
                          hatch-id (g body "hatchId" "hatch-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? (str egg-id)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "eggId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/hatch-egg!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :egg-id egg-id}
                                          hatch-id (assoc :hatch-id hatch-id)
                                          (some? agora) (assoc :agora-ms agora)))))))

                (= path "/v1/shop")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/shop-catalog store chat-id player-id))))

                (= path "/v1/inventory")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")
                          player-id (g qs "playerId")]
                      (when (or (str/blank? chat-id) (str/blank? player-id))
                        (throw (err/error :invalid_query {:required ["chatId" "playerId"]})))
                      (ok! ex (reads/inventory-bag store chat-id player-id))))

                (= path "/v1/shop/buy")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          item-id (g body "itemId" "item-id" "item")
                          qty (long-or-nil (g body "qty" "quantity"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? (str item-id)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "itemId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/buy-item!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :item-id item-id}
                                          (some? qty) (assoc :qty qty)))))))

                (= path "/v1/items/use")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          item-id (g body "itemId" "item-id" "item")
                          kind (g body "kind" "mode")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/use-item!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          item-id (assoc :item-id item-id)
                                          kind (assoc :kind kind)
                                          (some? idx) (assoc :idx idx)))))))

                (= path "/v1/pokemon/active")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/set-active-pokemon!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :save-id save-id
                                         :fence-generation fence
                                         :operation-id operation-id
                                         :idx idx})))))

                (= path "/v1/pokemon/favorite")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          clear? (boolean (or (g body "clear") (g body "clear?")))
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id)
                                (and (not clear?) (nil? idx)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex|clear"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/set-favorite-pokemon!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :clear? clear?}
                                          (some? idx) (assoc :idx idx)))))))

                (= path "/v1/pokemon/evolve")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          item-id (g body "itemId" "item-id" "item")
                          dados (or (g body "dados") (g body "species") {})]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx)
                                (empty? dados))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex" "dados"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/evolve-pokemon!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :idx idx
                                                 :dados dados}
                                          item-id (assoc :item-id item-id)))))))

                (= path "/v1/pokemon/learn-move")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          golpe (or (g body "golpe") (g body "move") {})
                          slot (long-or-nil (g body "slot" "moveIndex"))
                          oferta? (boolean (g body "oferta" "oferta?" "fromOffer"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx)
                                (empty? golpe))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex" "golpe"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/learn-move!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :idx idx
                                                 :golpe golpe
                                                 :oferta? oferta?}
                                          (some? slot) (assoc :slot slot)))))))

                (= path "/v1/pokemon/forget-move")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          slot (long-or-nil (g body "slot" "moveIndex"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx) (nil? slot))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex" "slot"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/forget-move!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :save-id save-id
                                         :fence-generation fence
                                         :operation-id operation-id
                                         :idx idx
                                         :slot slot})))))

                (= path "/v1/pokemon/offer-move")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          golpe (or (g body "golpe") (g body "move") {})]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx)
                                (empty? golpe))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex" "golpe"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/offer-move!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :save-id save-id
                                         :fence-generation fence
                                         :operation-id operation-id
                                         :idx idx
                                         :golpe golpe})))))

                (= path "/v1/pokemon/refuse-offer")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/refuse-offer!
                                  store {:chat-id chat-id
                                         :player-id player-id
                                         :save-id save-id
                                         :fence-generation fence
                                         :operation-id operation-id
                                         :idx idx})))))

                (= path "/v1/pokemon/relearn-move")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          idx (long-or-nil (g body "collectionIndex" "idx" "index"))
                          slug (g body "slug" "moveId" "id")
                          slot (long-or-nil (g body "slot" "moveIndex"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (nil? idx)
                                (str/blank? (str slug)))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "collectionIndex" "slug"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/relearn-move!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :idx idx
                                                 :slug slug}
                                          (some? slot) (assoc :slot slot)))))))

                (= path "/v1/pvp/active")
                (do (require-method! ex #{"GET"})
                    (let [chat-id (g qs "chatId")]
                      (when (str/blank? chat-id)
                        (throw (err/error :invalid_query {:required ["chatId"]})))
                      (ok! ex (or (tx/active-pvp store chat-id)
                                  {:chat-id chat-id :battle nil}))))

                (= path "/v1/pvp/open")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          player-name (g body "playerName" "player-name")
                          agora (long-or-nil (g body "agoraMs" "agora"))]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/open-pvp!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :player-name player-name}
                                          (some? agora) (assoc :agora agora)))))))

                (= path "/v1/pvp/accept")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          player-name (g body "playerName" "player-name")
                          battle-id (g body "battleId" "battle-id")
                          agora (long-or-nil (g body "agoraMs" "agora"))
                          tie (g body "firstTurnTie" "first-turn-tie")]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/accept-pvp!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :player-name player-name}
                                          battle-id (assoc :battle-id battle-id)
                                          (some? agora) (assoc :agora agora)
                                          tie (assoc :first-turn-tie tie)))))))

                (= path "/v1/pvp/cancel")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          battle-id (g body "battleId" "battle-id")]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/cancel-pvp!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id}
                                          battle-id (assoc :battle-id battle-id)))))))

                (= path "/v1/pvp/turn")
                (do (require-method! ex #{"POST"})
                    (let [body (read-body ex)
                          chat-id (g body "chatId")
                          player-id (g body "playerId")
                          save-id (g body "saveId")
                          fence (long-or-nil (g body "fenceGeneration" "fence-generation"))
                          operation-id (g body "operationId")
                          battle-id (g body "battleId" "battle-id")
                          expected (long-or-nil (g body "expectedTurnIndex"
                                                   "expected-turn-index"))
                          action (g body "action")
                          move-index (long-or-nil (g body "moveIndex" "move-index"))
                          item-id (g body "itemId" "item-id")
                          rng (g body "rng")]
                      (when (or (str/blank? chat-id) (str/blank? player-id)
                                (str/blank? save-id) (nil? fence)
                                (str/blank? operation-id) (str/blank? action))
                        (throw (err/error :invalid_query
                                          {:required ["chatId" "playerId" "saveId"
                                                      "fenceGeneration" "operationId"
                                                      "action"]})))
                      (ok! ex (with-operation! operation-id
                                #(tx/confirm-pvp-turn!
                                  store (cond-> {:chat-id chat-id
                                                 :player-id player-id
                                                 :save-id save-id
                                                 :fence-generation fence
                                                 :operation-id operation-id
                                                 :battle-id battle-id
                                                 :action action}
                                          (some? expected)
                                          (assoc :expected-turn-index expected)
                                          (some? move-index)
                                          (assoc :move-index move-index)
                                          item-id (assoc :item-id item-id)
                                          (map? rng) (assoc :rng rng)))))))

                :else
                (send-json! ex 404
                            {:ok false
                             :error {:code "not_found" :message "Rota inexistente."}})))))
        (catch Throwable t
          (handle-error! ex t))))))

(defn start!
  "Sobe HttpServer. Default: loopback.
  `:allow-private-bind true` permite host 0.0.0.0 (rede privada de containers);
  a porta NÃO deve ser publicada publicamente no Compose.
  `:allow-test-rng true` só para smoke/dev — nunca no caminho WhatsApp."
  [{:keys [host port token store platform-store allow-test-rng allow-private-bind
           credit-coins! score-point! platform-hooks]
    :or {host "127.0.0.1" port 18090 allow-test-rng false allow-private-bind false}}]
  (let [allowed (cond-> loopback-hosts
                  allow-private-bind (conj "0.0.0.0"))]
    (when-not (contains? allowed host)
      (throw (ex-info "bridge_host_forbidden"
                      {:host host
                       :allowed allowed
                       :message "Bridge só binda em loopback (ou 0.0.0.0 com allow-private-bind)."})))
    (when (or (nil? token) (str/blank? token))
      (throw (ex-info "bridge_token_required" {})))
    (when (nil? store)
      (throw (ex-info "bridge_store_required" {})))
    (let [addr (InetSocketAddress. ^String host (int port))
          server (HttpServer/create addr 0)
          handler (make-handler store token (boolean allow-test-rng) platform-store
                                (cond-> {}
                                  credit-coins! (assoc :credit-coins! credit-coins!)
                                  score-point! (assoc :score-point! score-point!)
                                  (seq platform-hooks) (assoc :platform-hooks platform-hooks)))]
      (.createContext server "/" handler)
      (.setExecutor server (Executors/newFixedThreadPool 8))
      (.start server)
      (let [bound (.getPort (.getAddress server))
            ;; URL interna: em 0.0.0.0 use 127.0.0.1 para health local
            url-host (if (= "0.0.0.0" host) "127.0.0.1" host)]
        {:server server
         :host host
         :port bound
         :base-url (str "http://" url-host ":" bound)
         :allow-test-rng (boolean allow-test-rng)
         :allow-private-bind (boolean allow-private-bind)
         :platform-enabled (boolean platform-store)
         :durable-coins? (boolean credit-coins!)
         :durable-rank? (boolean score-point!)
         :durable-blocks-admins? (boolean (seq platform-hooks))
         :stop! (fn [] (.stop server 1))}))))

(defn stop!
  [{:keys [stop!]}]
  (when stop! (stop!)))
