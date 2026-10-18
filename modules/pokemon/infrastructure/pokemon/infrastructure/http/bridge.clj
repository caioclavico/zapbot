(ns pokemon.infrastructure.http.bridge
  "Servidor HTTP da bridge Pokémon (M4.1 leituras + M4.2 escritas explícitas).
  Bind forçado em loopback; Bearer obrigatório nas rotas de domínio;
  sem rotas administrativas nem endpoint genérico de mutação."
  (:require [clojure.string :as str]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.json :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private allowed-hosts #{"127.0.0.1" "localhost" "::1"})

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
    (:player_not_found :no_active_save :save_not_found :operation_not_found) 404
    (:invalid_query :missing_operation_id :missing_reward_identity
     :missing_writer_context :missing_fence_generation :invalid_coins_delta
     :invalid_save_slot :invalid_slot_id :invalid_identity) 400
    (:stale_writer_fence :writer_fence_conflict :activate_conflict
     :receipt_payload_mismatch :reward_conflict :reward_save_mismatch) 409
    :operation_pending 409
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
  [store token]
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
                       :capabilities ["trainer-read" "collection-read" "active-save-read"
                                      "trainer-ensure" "saves-list" "save-activate"
                                      "reward-coins" "operation-get"]}))

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
                                #(tx/credit-coins!
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
                                 StandardCharsets/UTF_8)]
                      (when (or (str/blank? op-id) (str/includes? op-id "/"))
                        (throw (err/error :invalid_query {:required ["operationId"]})))
                      (ok! ex (tx/get-operation store op-id))))

                :else
                (send-json! ex 404
                            {:ok false
                             :error {:code "not_found" :message "Rota inexistente."}})))))
        (catch Throwable t
          (handle-error! ex t))))))

(defn start!
  "Sobe HttpServer em loopback. Retorna {:server … :port … :host …}."
  [{:keys [host port token store] :or {host "127.0.0.1" port 18090}}]
  (when-not (contains? allowed-hosts host)
    (throw (ex-info "bridge_host_forbidden"
                    {:host host
                     :allowed allowed-hosts
                     :message "Bridge só pode bindar em loopback."})))
  (when (or (nil? token) (str/blank? token))
    (throw (ex-info "bridge_token_required" {})))
  (when (nil? store)
    (throw (ex-info "bridge_store_required" {})))
  (let [addr (InetSocketAddress. ^String host (int port))
        server (HttpServer/create addr 0)
        handler (make-handler store token)]
    (.createContext server "/" handler)
    (.setExecutor server (Executors/newFixedThreadPool 8))
    (.start server)
    (let [bound (.getPort (.getAddress server))]
      {:server server
       :host host
       :port bound
       :base-url (str "http://" host ":" bound)
       :stop! (fn [] (.stop server 0))})))

(defn stop!
  [{:keys [stop!]}]
  (when stop! (stop!)))
