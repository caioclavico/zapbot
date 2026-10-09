(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.recursos :as recursos]
            [zapbot.whatsapp-saude :as saude]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.historico :as historico]
            [zapbot.adedonha :as adedonha]
            [zapbot.lembretes :as lembretes]
            [zapbot.admins :as admins]
            [zapbot.pokemon-http :as pokemon]
            [zapbot.router :as router]))

(def ^:private Client (.-Client wwjs))
(def ^:private LocalAuth (.-LocalAuth wwjs))
(def ^:private startup (js/require "../scripts/lib/whatsapp-startup.cjs"))

(defn configurar-envio-seguro!
  "Desativa o sendSeen quebrado do WhatsApp Web antes de qualquer envio.

  Message.reply também delega para client.sendMessage, então este único
  wrapper protege respostas de texto, imagens e os envios diretos do bot."
  [client]
  (let [enviar-original (.bind (.-sendMessage client) client)]
    (set! (.-sendMessage client)
          (fn [chat-id conteudo opcoes]
            (let [opcoes-seguras (js/Object.assign
                                  #js {}
                                  (or opcoes #js {})
                                  #js {:sendSeen false})]
              (enviar-original chat-id conteudo opcoes-seguras))))
    client))

(defn- on-qr [qr]
  (js/console.log "📱 Escaneie o QR code abaixo com o WhatsApp (Aparelhos conectados > Conectar aparelho):")
  (.generate qrcode qr #js {:small true}))

(defn- on-ready [client]
  (js/console.log (str "✅ " config/bot-name " conectado e pronto para uso!"))
  (js/console.log (str "📞 Número conectado: +" (.. client -info -wid -user)))
  (pokemon/iniciar! client)
  (lembretes/iniciar! client))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

;; em desenvolvimento (APP_ENV=development), só processa mensagens do chat de
;; teste (DEV_GROUP_ID) - evita responder duplicado nos grupos reais enquanto
;; uma instância local roda ao lado da de produção
(defn- permitido-pelo-ambiente? [message]
  (or (not= config/app-env "development")
      (= (chat-id message) config/dev-group-id)))

(def ^:private limite-legenda-midia 900)

(defn- enviar-texto-estruturado [message texto mentions]
  (when-not (str/blank? texto)
    (.reply message texto nil #js {:mentions (clj->js mentions)})))

(defn- responder-com-midia
  "Envia imagem sem exceder o limite prático das legendas do WhatsApp.
  Se a mídia for recusada, ainda entrega a resposta em texto em vez de deixar
  o comando aparentemente travado."
  [message resposta]
  (let [ctx         (desempenho/contexto-de message)
        texto       (or (:texto resposta) "")
        mentions    (:mentions resposta)
        texto-longo? (> (count texto) limite-legenda-midia)
        legenda     (if texto-longo?
                      (or (:legenda resposta) "🖼️ *Imagem Pokémon*")
                      texto)]
    (-> (desempenho/medir! ctx "envio_midia"
          #(.reply message (:media resposta) nil
                   #js {:caption legenda :mentions (clj->js mentions)}))
        (p/then (fn [_]
                  (when texto-longo?
                    (desempenho/medir! ctx "envio_texto_extra"
                      #(enviar-texto-estruturado message texto mentions)))))
        (p/catch (fn [erro]
                   (js/console.error "Erro ao enviar mídia; usando resposta em texto:" erro)
                   (if (str/blank? texto)
                     (p/rejected erro)
                     (desempenho/medir! ctx "envio_texto_fallback"
                       #(enviar-texto-estruturado message texto mentions))))))))

(defn- processar-mensagem [message ctx]
  (when (permitido-pelo-ambiente? message)
    (adedonha/capturar-resposta! message)
    ;; Aguarda a fila do histórico: assim !pk bug sempre encontra exatamente
    ;; a mensagem anterior, mesmo quando os eventos chegam muito próximos.
    (-> (p/resolved (desempenho/medir! ctx "historico" #(historico/registrar! message)))
        (p/then (fn [_] (desempenho/medir! ctx "processamento" #(router/processar message))))
        (p/then (fn [resposta]
                  (desempenho/medir! ctx "envio"
                    (fn []
                      (cond
                        (nil? resposta) nil
                        (:medias resposta) (let [medias (:medias resposta)
                                                 total  (count medias)
                                                 legenda-ultima (:legenda-ultima resposta)]
                                             ;; Cada imagem pode demorar um tempo diferente para subir ao
                                             ;; WhatsApp. Encadeamos os envios para as páginas não chegarem
                                             ;; embaralhadas no grupo.
                                             (-> (reduce
                                                  (fn [envio [idx media]]
                                                    (p/then envio
                                                            (fn [_]
                                                              (.reply message media nil
                                                                      #js {:caption
                                                                           (str "🎒 Página " (inc idx) "/" total
                                                                                (when (and legenda-ultima
                                                                                           (= idx (dec total)))
                                                                                  (str "\n\n" legenda-ultima)))}))))
                                                  (p/resolved nil)
                                                  (map-indexed vector medias))
                                                 (p/catch
                                                  (fn [erro]
                                                    (js/console.error "Erro ao enviar cartões; usando lista em texto:" erro)
                                                    (enviar-texto-estruturado message (:texto resposta) (:mentions resposta))))))
                        ;; documento (ex.: !pokemon time csv): manda o texto primeiro e o
                        ;; arquivo em seguida - legenda em documento não aparece de forma
                        ;; confiável no WhatsApp
                        (:documento resposta) (-> (.reply message (:texto resposta))
                                                  (p/then (fn [_]
                                                            (.reply message (:documento resposta) nil
                                                                    #js {:sendMediaAsDocument true}))))
                        (:media resposta) (responder-com-midia message resposta)
                        ;; comandos que precisam marcar alguém com @ (ex.: !pokemon,
                        ;; de quem for a vez) resolvem {:texto :mentions} em vez de
                        ;; uma string simples - todo o resto continua string normal
                        (string? resposta) (.reply message resposta)
                        :else (.reply message (:texto resposta) nil #js {:mentions (clj->js (:mentions resposta))}))))))
        (p/catch (fn [err] (js/console.error "Erro ao processar mensagem:" err))))))

(defn- on-message [message]
  (let [executar #(if (and (permitido-pelo-ambiente? message)
                          (desempenho/pokemon? (.-body message)))
                   (desempenho/acompanhar-mensagem! message (fn [ctx] (processar-mensagem message ctx)))
                   (processar-mensagem message nil))]
    (if (and (permitido-pelo-ambiente? message)
             (str/starts-with? (str/trim (or (.-body message) "")) config/prefix))
      (recursos/medir-comando! executar)
      (executar))))

(defn opcoes-puppeteer []
  (cond-> {:protocolTimeout 300000
           ;; O handler da aplicação fecha e verifica o browser. Os handlers
           ;; padrão do Puppeteer competem com ele e podem enviar SIGKILL.
           :handleSIGTERM false
           :handleSIGINT false
           :args (clj->js (cond-> ["--no-sandbox" "--disable-setuid-sandbox"
                                   "--disable-quic" "--disable-features=Quic"]
                           config/chromium-low-resource-mode
                           (into ["--disable-extensions" "--disable-default-apps" "--no-first-run"])
                           config/chromium-disable-gpu (conj "--disable-gpu")))}
    config/puppeteer-executable-path (assoc :executablePath config/puppeteer-executable-path)))

;; alimenta o cadastro de admins conhecidos (zapbot.admins) direto do evento
;; do WhatsApp - não depende do getChatModel instável usado na checagem ao
;; vivo (ver zapbot.bloqueio), então funciona mesmo em grupos onde aquele
;; falha persistentemente.
(defn- on-group-admin-changed [notification]
  (admins/processar-evento-promocao! notification))

(defonce ^:private iniciado? (atom false))

(defn- encerrar-com-sessao! [^js recovery diagnostico ^js server]
  (let [encerrando? (atom false)]
    (doseq [sinal ["SIGTERM" "SIGINT"]]
      (.on js/process sinal
           (fn []
             (when (compare-and-set! encerrando? false true)
               (recursos/parar!)
               (saude/atualizar! diagnostico "DISCONNECTED" "Encerrando navegador; preservando sessão.")
               (pokemon/parar!)
               (.close server)
               ;; Limite apenas para uma parada solicitada, nunca para startup lento.
               (js/setTimeout #(js/process.exit 1) 45000)
               (-> (p/resolved nil)
                   (p/then (fn [_] (.close recovery)))
                   (p/then (fn [_] (js/process.exit 0)))
                   (p/catch (fn [err]
                              (saude/log! (str "Erro ao encerrar: " (.-message err)))
                              (js/process.exit 1))))))))))

(defn main [& _args]
  ;; Um único cliente/browser por processo; só a injeção pode ser repetida.
  (when (compare-and-set! iniciado? false true)
    (let [puppeteer-opts (opcoes-puppeteer)
          client (Client. #js {:authStrategy (LocalAuth.)
                               :puppeteer (clj->js puppeteer-opts)})
          diagnostico (saude/criar)
          recovery (.installStartupRecovery startup client
                     #js {:onState (fn [estado]
                                     (when (#{"recovering" "error"} estado)
                                       (saude/atualizar! diagnostico
                                                        (if (= estado "error") "ERROR" "RECOVERING")
                                                        "Estado de inicialização atualizado; sessão preservada.")))})
          server (saude/servir! diagnostico client)]
      (configurar-envio-seguro! client)
      (saude/acompanhar! diagnostico client)
      (recursos/iniciar! client)
      (encerrar-com-sessao! recovery diagnostico server)
      (.on client "qr" (fn [qr] (when (.acceptingQR recovery) (on-qr qr))))
      (.on client "ready" (fn [] (when (.acceptingReady recovery) (on-ready client))))
      (.on client "disconnected" (fn [& _] (pokemon/parar!)))
      (.on client "auth_failure" (fn [& _] (pokemon/parar!)))
      ;; Preserva o fluxo de mensagens e os inicializadores dos jogos.
      (.on client "message_create" on-message)
      (.on client "group_admin_changed" on-group-admin-changed)
      (-> (armazenamento/iniciar!)
          (p/then (fn [_]
                    (saude/iniciar-avisos! diagnostico)
                    (.initialize recovery)))
          (p/catch (fn [err]
                     (when-not (.stopping recovery)
                       (saude/atualizar! diagnostico "ERROR"
                                        (str "Erro ao inicializar: " (.-message err)))
                       (js/console.error err))))))))
