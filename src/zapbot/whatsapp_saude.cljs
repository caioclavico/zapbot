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

(defn atender!
  ([saude client req res] (atender! saude client req res nil))
  ([saude ^js client ^js req ^js res opts]
   (let [method (.-method req)
         path (url-path req)
         on-discover (:on-discover-groups opts)]
     (cond
       (and (= "GET" method) (= "/diagnostics" path))
       (let [amostra (recursos/amostra)
             dados (cond-> {:operacoes (desempenho/pendentes)}
                     amostra (assoc :recursos amostra))]
         (.writeHead res 200 #js {"Content-Type" "application/json" "Cache-Control" "no-store"})
         (.end res (js/JSON.stringify (clj->js dados))))

       (and (= "GET" method) (= "/health" path))
       (let [dados (resposta saude client)]
         (.writeHead res (if (= "ok" (:status dados)) 200 503)
                     #js {"Content-Type" "application/json" "Cache-Control" "no-store"})
         (.end res (js/JSON.stringify (clj->js dados))))

       ;; Bootstrap only: re-descobre grupos sem reiniciar Chromium / sem comandos.
       (and on-discover
            (#{"GET" "POST"} method)
            (= "/bootstrap/discover-groups" path))
       (do
         (.writeHead res 202 #js {"Content-Type" "application/json" "Cache-Control" "no-store"})
         (.end res (js/JSON.stringify
                    #js {:accepted true
                         :note "Descoberta agendada; veja logs [bootstrap] e groups.json. Sem mensagens/comandos."}))
         (try
           (on-discover)
           (catch :default err
             (log! (str "bootstrap discover erro: " (or (.-message err) "falha"))))))

       :else (do (.writeHead res 404) (.end res))))))

(defn servir!
  ([saude client] (servir! saude client nil))
  ([saude ^js client opts]
   ;; Loopback dentro do container: nenhuma porta publicada na VM.
   (let [server (.createServer http #(atender! saude client %1 %2 opts))]
     (.on server "error" #(log! (str "Healthcheck indisponível: " (.-message %))))
     (.listen server 3001 "127.0.0.1")
     server)))
