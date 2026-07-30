(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [promesa.core :as p]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            [zapbot.config :as config]
            [zapbot.router :as router]))

(def ^:private Client (.-Client wwjs))
(def ^:private LocalAuth (.-LocalAuth wwjs))

(defn- on-qr [qr]
  (js/console.log "📱 Escaneie o QR code abaixo com o WhatsApp (Aparelhos conectados > Conectar aparelho):")
  (.generate qrcode qr #js {:small true}))

(defn- on-ready [client]
  (js/console.log (str "✅ " config/bot-name " conectado e pronto para uso!"))
  (js/console.log (str "📞 Número conectado: +" (.. client -info -wid -user))))

(defn- on-auth-failure [msg]
  (js/console.error "❌ Falha na autenticação:" msg))

(defn- on-disconnected [reason]
  (js/console.warn "⚠️ Desconectado:" reason))

(defn- on-message [message]
  (-> (router/processar message)
      (p/then (fn [resposta] (when resposta (.reply message resposta))))
      (p/catch (fn [err] (js/console.error "Erro ao processar mensagem:" err)))))

(defn main [& _args]
  (let [client (Client. #js {:authStrategy (LocalAuth.)
                              ;; --disable-quic evita ERR_CONNECTION_CLOSED comum em redes
                              ;; WSL2/containers onde o QUIC (HTTP/3, via UDP) não funciona.
                              :puppeteer    #js {:args #js ["--no-sandbox" "--disable-setuid-sandbox"
                                                             "--disable-quic" "--disable-features=Quic"]}})]
    (.on client "qr" on-qr)
    (.on client "ready" (fn [] (on-ready client)))
    (.on client "auth_failure" on-auth-failure)
    (.on client "disconnected" on-disconnected)
    (.on client "message" on-message)
    (.initialize client)))
