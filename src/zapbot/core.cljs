(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [promesa.core :as p]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            [zapbot.router :as router]))

(def ^:private Client (.-Client wwjs))
(def ^:private LocalAuth (.-LocalAuth wwjs))

(defn- on-qr [qr]
  (js/console.log "📱 Escaneie o QR code abaixo com o WhatsApp (Aparelhos conectados > Conectar aparelho):")
  (.generate qrcode qr #js {:small true}))

(defn- on-ready []
  (js/console.log "✅ ZapBot conectado e pronto para uso!"))

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
                              :puppeteer    #js {:args #js ["--no-sandbox" "--disable-setuid-sandbox"]}})]
    (.on client "qr" on-qr)
    (.on client "ready" on-ready)
    (.on client "auth_failure" on-auth-failure)
    (.on client "disconnected" on-disconnected)
    (.on client "message" on-message)
    (.initialize client)))
