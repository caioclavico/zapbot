(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [promesa.core :as p]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.historico :as historico]
            [zapbot.adedonha :as adedonha]
            [zapbot.lembretes :as lembretes]
            [zapbot.admins :as admins]
            [zapbot.router :as router]))

(def ^:private Client (.-Client wwjs))
(def ^:private LocalAuth (.-LocalAuth wwjs))

(defn- on-qr [qr]
  (js/console.log "📱 Escaneie o QR code abaixo com o WhatsApp (Aparelhos conectados > Conectar aparelho):")
  (.generate qrcode qr #js {:small true}))

(defn- on-ready [client]
  (js/console.log (str "✅ " config/bot-name " conectado e pronto para uso!"))
  (js/console.log (str "📞 Número conectado: +" (.. client -info -wid -user)))
  (lembretes/iniciar! client))

(defn- on-auth-failure [msg]
  (js/console.error "❌ Falha na autenticação:" msg))

(defn- on-disconnected [reason]
  (js/console.warn "⚠️ Desconectado:" reason))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

;; em desenvolvimento (APP_ENV=development), só processa mensagens do chat de
;; teste (DEV_GROUP_ID) - evita responder duplicado nos grupos reais enquanto
;; uma instância local roda ao lado da de produção
(defn- permitido-pelo-ambiente? [message]
  (or (not= config/app-env "development")
      (= (chat-id message) config/dev-group-id)))

(defn- on-message [message]
  (when (permitido-pelo-ambiente? message)
    (historico/registrar! message)
    (adedonha/capturar-resposta! message)
    (-> (router/processar message)
        (p/then (fn [resposta]
                  (cond
                    (nil? resposta) nil
                    ;; comandos que precisam marcar alguém com @ (ex.: !pokemon,
                    ;; de quem for a vez) resolvem {:texto :mentions} em vez de
                    ;; uma string simples - todo o resto continua string normal
                    (string? resposta) (.reply message resposta)
                    :else (.reply message (:texto resposta) nil #js {:mentions (clj->js (:mentions resposta))}))))
        (p/catch (fn [err] (js/console.error "Erro ao processar mensagem:" err))))))

;; alimenta o cadastro de admins conhecidos (zapbot.admins) direto do evento
;; do WhatsApp - não depende do getChatModel instável usado na checagem ao
;; vivo (ver zapbot.bloqueio), então funciona mesmo em grupos onde aquele
;; falha persistentemente.
(defn- on-group-admin-changed [notification]
  (admins/processar-evento-promocao! notification))

(def ^:private timeout-aviso-inicializacao-ms (* 60 1000))

;; sem isso, uma trava no Puppeteer/Chromium (RAM/CPU insuficiente, processo
;; zumbi antigo segurando o profile, etc.) fica em silêncio total no log -
;; "qr"/"ready" cancelam o aviso assim que um dos dois acontecer de verdade.
(defn- avisar-se-travar! [client]
  (let [id (js/setTimeout
            (fn []
              (js/console.warn
               (str "⚠️ Já se passaram " (/ timeout-aviso-inicializacao-ms 1000)
                    "s sem QR code nem conexão - o Chromium pode estar travado "
                    "(RAM/CPU insuficiente, processo zumbi antigo, SingletonLock, etc). "
                    "Verifique com 'docker stats' e 'docker exec zapbot ps aux'.")))
            timeout-aviso-inicializacao-ms)]
    (.once client "qr" (fn [_] (js/clearTimeout id)))
    (.once client "ready" (fn [] (js/clearTimeout id)))))

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
    ;; espera o Cassandra carregar (rank/loja/admins/etc.) antes de conectar
    ;; no WhatsApp - iniciar! nunca rejeita (loga e segue sem persistência
    ;; nessa execução se não conseguir conectar), então isso nunca trava o boot.
    (p/then (armazenamento/iniciar!)
            (fn [_]
              (avisar-se-travar! client)
              (-> (.initialize client)
                  (p/catch (fn [err] (js/console.error "❌ Erro ao inicializar o cliente do WhatsApp:" err))))))))
