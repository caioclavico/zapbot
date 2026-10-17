(ns pokemon.infrastructure.http.bridge
  "Servidor HTTP mínimo da bridge Pokémon (M4.1).
  Bind forçado em loopback; somente GET de leitura; Bearer obrigatório;
  sem rotas administrativas nem escritas."
  (:require [clojure.string :as str]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.http.json :as json])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent Executors]))

(def ^:private allowed-hosts #{"127.0.0.1" "localhost" "::1"})

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

(defn- send-empty!
  [^HttpExchange ex status]
  (.sendResponseHeaders ex status -1)
  (.close (.getResponseBody ex)))

(defn- business-status
  [code]
  (case code
    (:player_not_found :no_active_save :save_not_found) 404
    :invalid_query 400
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

(defn- require-get!
  [^HttpExchange ex]
  (if (= "GET" (.getRequestMethod ex))
    true
    (do
      (send-json! ex 405
                  {:ok false
                   :error {:code "method_not_allowed"
                           :message "Somente GET é aceito nesta bridge."}})
      false)))

(defn- with-auth
  [token ^HttpExchange ex f]
  (cond
    (not (require-get! ex)) nil
    (not (bearer-ok? ex token))
    (send-json! ex 401
                {:ok false
                 :error {:code "unauthorized"
                         :message "Token Bearer inválido ou ausente."}})
    :else (try (f) (catch Throwable t (handle-error! ex t)))))

(defn- path-of
  [^HttpExchange ex]
  (let [uri (str (.getRequestURI ex))
        q (.indexOf uri (int \?))]
    (if (neg? q) uri (.substring uri 0 q))))

(defn- ok!
  [^HttpExchange ex data]
  (send-json! ex 200 {:ok true :data data}))

(defn- make-handler
  [store token]
  (reify HttpHandler
    (handle [_ ex]
      (let [path (path-of ex)
            qs (query-map (.getRawQuery (.getRequestURI ex)))
            chat-id (get qs "chatId")
            player-id (get qs "playerId")
            save-id (not-empty (get qs "saveId"))]
        (cond
          (= path "/health")
          (if (require-get! ex)
            (send-json! ex 200
                        {:ok true
                         :data {:status "up"
                                :role "pokemon-jvm-bridge"
                                :readsOnly true}})
            nil)

          ;; Bloqueio explícito de superfícies administrativas.
          (or (str/starts-with? path "/admin")
              (str/starts-with? path "/v1/admin")
              (str/includes? path "admin-seed")
              (str/includes? path "migrate"))
          (send-json! ex 404
                      {:ok false
                       :error {:code "not_found"
                               :message "Rota inexistente."}})

          (= path "/v1/trainer")
          (with-auth token ex
            #(do
               (when (or (str/blank? chat-id) (str/blank? player-id))
                 (throw (err/error :invalid_query
                                   {:required ["chatId" "playerId"]})))
               (ok! ex (reads/trainer store chat-id player-id))))

          (= path "/v1/collection")
          (with-auth token ex
            #(do
               (when (or (str/blank? chat-id) (str/blank? player-id))
                 (throw (err/error :invalid_query
                                   {:required ["chatId" "playerId"]})))
               (ok! ex (reads/collection store chat-id player-id save-id))))

          (= path "/v1/active-save")
          (with-auth token ex
            #(do
               (when (or (str/blank? chat-id) (str/blank? player-id))
                 (throw (err/error :invalid_query
                                   {:required ["chatId" "playerId"]})))
               (ok! ex (reads/active-save store chat-id player-id))))

          :else
          (send-json! ex 404
                      {:ok false
                       :error {:code "not_found"
                               :message "Rota inexistente."}}))))))

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
    (.setExecutor server (Executors/newFixedThreadPool 4))
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
