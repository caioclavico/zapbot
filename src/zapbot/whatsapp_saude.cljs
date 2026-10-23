(ns zapbot.whatsapp-saude
  "Diagnóstico passivo: nunca reinicia o cliente nem acessa credenciais."
  (:require ["node:http" :as http]
            [clojure.string :as str]
            [zapbot.recursos :as recursos]
            [zapbot.desempenho :as desempenho]))

(defn log! [mensagem]
  (js/console.log (str (.toISOString (js/Date.)) " [WhatsApp] " mensagem)))

(defn criar []
  {:estado (atom "STARTING") :timers (atom []) :loading (atom nil)})

(defn cancelar-avisos! [saude]
  (doseq [timer @(:timers saude)] (js/clearTimeout timer))
  (reset! (:timers saude) []))

(defn atualizar! [saude estado mensagem]
  (reset! (:estado saude) estado)
  (when (#{"READY" "DISCONNECTED" "AUTH_FAILURE" "ERROR"} estado)
    (cancelar-avisos! saude))
  (log! mensagem))

(defn avisar! [saude segundos]
  (when (#{"STARTING" "AUTHENTICATED" "QR"} @(:estado saude))
    (log! (str (if (= "QR" @(:estado saude))
                 "Aguardando leitura do QR Code."
                 (case segundos
                   60 "WhatsApp ainda inicializando..."
                   120 "WhatsApp ainda não ficou pronto; Chromium pode estar lento."
                   "WhatsApp não ficou pronto após 180s. Nenhum reinício será feito pelo watchdog."))
               " Estado=" @(:estado saude) " (" segundos "s)."))))

(defn acompanhar! [saude ^js client]
  (doseq [[evento estado mensagem]
          [["qr" "QR" "QR Code gerado."]
           ["authenticated" "AUTHENTICATED" "Autenticado."]
           ["ready" "READY" "READY."]
           ["auth_failure" "AUTH_FAILURE" "Falha de autenticação: "]
           ["disconnected" "DISCONNECTED" "Desconectado: "]]]
    (.on client evento
         (fn [motivo]
           (atualizar! saude estado
                       (str mensagem (when (#{"AUTH_FAILURE" "DISCONNECTED"} estado)
                                       motivo))))))
  (.on client "loading_screen"
       (fn [percentual _mensagem]
         ;; Só registra mudanças de faixa de 25%, sem repetir a mesma faixa.
         (let [faixa (* 25 (js/Math.floor (/ percentual 25)))]
           (when (not= faixa @(:loading saude))
             (reset! (:loading saude) faixa)
             (log! (str "Carregando WhatsApp: " faixa "%.")))))))

(defn iniciar-avisos! [saude]
  (cancelar-avisos! saude)
  (log! "Inicializando...")
  (reset! (:timers saude)
          (mapv (fn [segundos]
                  (js/setTimeout #(avisar! saude segundos) (* segundos 1000)))
                [60 120 180])))

(defn resposta [saude ^js client]
  (let [^js browser (.-pupBrowser client)
        ^js page (.-pupPage client)
        chromium? (boolean (try
                             (and browser (fn? (.-isConnected browser)) (.isConnected browser)
                                  page (fn? (.-isClosed page)) (not (.isClosed page)))
                             (catch :default _ false)))
        estado @(:estado saude)
        pronto? (and (= estado "READY") chromium?
                     (not (.-lastLoggedOut client))
                     (or (nil? (.-zapbotStartupState client))
                         (= "ready" (.-zapbotStartupState client))))]
    {:status (if pronto? "ok" "degraded")
     :whatsapp estado :chromium chromium?}))

(defn- url-path [^js req]
  (let [u (str (or (.-url req) ""))]
    (if-let [i (str/index-of u "?")]
      (subs u 0 i)
      u)))

(defn- json-res! [^js res status-code body]
  (try
    (when-not (.-headersSent res)
      (.writeHead res status-code
                  #js {"Content-Type" "application/json"
                       "Cache-Control" "no-store"
                       "Connection" "close"})
      (.end res (js/JSON.stringify (clj->js body))))
    (catch :default err
      (log! (str "falha ao responder HTTP: " (or (.-message err) "erro")))
      (try
        (when-not (.-writableEnded res)
          (.end res))
        (catch :default _ nil)))))

(defn atender!
  ([saude client req res] (atender! saude client req res nil))
  ([saude ^js client ^js req ^js res opts]
   ;; Evita ECONNRESET se o cliente abortar no meio do evaluate.
   (when (fn? (.-on req))
     (.on req "aborted" #(log! "cliente HTTP abortou a requisição")))
   (when (fn? (.-on res))
     (.on res "error" #(log! (str "erro no socket HTTP: "
                                  (or (some-> % .-message) "desconhecido")))))
   (let [method (.-method req)
         path (url-path req)
         on-discover (:on-discover-groups opts)]
     (try
       (cond
         (and (= "GET" method) (= "/diagnostics" path))
         (let [amostra (recursos/amostra)
               dados (cond-> {:operacoes (desempenho/pendentes)}
                       amostra (assoc :recursos amostra))]
           (json-res! res 200 dados))

         (and (= "GET" method) (= "/health" path))
         (let [dados (resposta saude client)]
           (json-res! res (if (= "ok" (:status dados)) 200 503) dados))

         ;; Bootstrap: responde JSON primeiro; trabalho assíncrono fica no callback.
         (and on-discover
              (#{"GET" "POST"} method)
              (= "/bootstrap/discover-groups" path))
         (let [result (try
                        (on-discover)
                        (catch :default err
                          (log! (str "bootstrap discover erro: "
                                     (or (.-message err) "falha")))
                          {:status "error" :http 500
                           :note "Falha ao agendar descoberta (ver logs)."}))
               code (or (:http result) 202)
               body (cond-> {:status (or (:status result) "accepted")
                             :note (or (:note result) "")
                             :commandsProcessed false}
                      (contains? result :accepted)
                      (assoc :accepted (:accepted result)))]
           (json-res! res code body))

         :else (json-res! res 404 {:status "not_found"}))
       (catch :default err
         (log! (str "handler HTTP falhou: " (or (.-message err) "erro")))
         (json-res! res 500 {:status "error" :note "handler_exception"}))))))

(defn servir!
  ([saude client] (servir! saude client nil))
  ([saude ^js client opts]
   ;; Loopback dentro do container: nenhuma porta publicada na VM.
   (let [server (.createServer http #(atender! saude client %1 %2 opts))]
     (.on server "error" #(log! (str "Healthcheck indisponível: " (.-message %))))
     (.listen server 3001 "127.0.0.1")
     server)))
