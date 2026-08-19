(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [promesa.core :as p]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            [zapbot.config :as config]
            [zapbot.historico :as historico]
            [zapbot.admins :as admins]
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
  (historico/registrar! message)
  (-> (router/processar message)
      (p/then (fn [resposta]
                (cond
                  (nil? resposta) nil
                  ;; comandos que precisam marcar alguém com @ (ex.: !pokemon,
                  ;; de quem for a vez) resolvem {:texto :mentions} em vez de
                  ;; uma string simples - todo o resto continua string normal
                  (string? resposta) (.reply message resposta)
                  :else (.reply message (:texto resposta) nil #js {:mentions (clj->js (:mentions resposta))}))))
      (p/catch (fn [err] (js/console.error "Erro ao processar mensagem:" err)))))

;; alimenta o cadastro de admins conhecidos (zapbot.admins) direto do evento
;; do WhatsApp - não depende do getChatModel instável usado na checagem ao
;; vivo (ver zapbot.bloqueio), então funciona mesmo em grupos onde aquele
;; falha persistentemente.
(defn- on-group-admin-changed [notification]
  (admins/processar-evento-promocao! notification))

(defn main [& _args]
  (let [puppeteer-opts (cond-> {:args #js ["--no-sandbox" "--disable-setuid-sandbox"
                                            ;; --disable-quic evita ERR_CONNECTION_CLOSED comum em redes
                                            ;; WSL2/containers onde o QUIC (HTTP/3, via UDP) não funciona.
                                            "--disable-quic" "--disable-features=Quic"]}
                                config/puppeteer-executable-path (assoc :executablePath config/puppeteer-executable-path))
        client (Client. #js {:authStrategy (LocalAuth.)
                              :puppeteer    (clj->js puppeteer-opts)})]
    (.on client "qr" on-qr)
    (.on client "ready" (fn [] (on-ready client)))
    (.on client "auth_failure" on-auth-failure)
    (.on client "disconnected" on-disconnected)
    ;; message_create cobre também mensagens enviadas pelo próprio número
    ;; conectado (fromMe), diferente de "message" (só mensagens recebidas).
    (.on client "message_create" on-message)
    (.on client "group_admin_changed" on-group-admin-changed)
    (.initialize client)))
