(ns zapbot.core
  "Ponto de entrada do bot: conecta ao WhatsApp Web e liga os eventos."
  (:require [zapbot.whatsapp-reply :as whatsapp-reply]
            [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            ["qrcode-terminal" :as qrcode]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [zapbot.config :as config]
            [zapbot.desempenho :as desempenho]
            [zapbot.recursos :as recursos]
            [zapbot.whatsapp-saude :as saude]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.historico :as historico]
            [zapbot.adedonha :as adedonha]
            [zapbot.lembretes :as lembretes]
            [zapbot.admins :as admins]
            [zapbot.bugs :as bugs]
            [zapbot.pokemon-http :as pokemon]
            [zapbot.bloqueio :as bloqueio]
            [zapbot.enquetes :as enquetes]
            [zapbot.quiz :as quiz]
            [zapbot.rank :as rank]
            [zapbot.platform-jvm :as platform]
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
  (when config/staging-bootstrap-group?
    (js/console.log "[bootstrap] Use SOMENTE a conta WhatsApp Business de TESTE — nunca o número principal.")
    (try
      (let [out (path/join (path/dirname (discovery-path)) "qr-payload.txt")]
        (fs/mkdirSync (path/dirname out) #js {:recursive true})
        (fs/writeFileSync out (str qr) "utf8")
        (js/console.log "[bootstrap] Payload QR em" out "(para ferramentas externas; prefira o ASCII abaixo)."))
      (catch :default _ nil)))
  (.generate qrcode qr #js {:small true}))

(defn- discovery-path []
  (or (when (and (string? config/staging-group-discovery-path)
                 (not (str/blank? config/staging-group-discovery-path)))
        config/staging-group-discovery-path)
      (path/join (os/homedir) "odisseu-recovery" "m6-3c-private" "groups.json")))

(defn- sleep-ms [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

(defn- err-diag
  "Diagnóstico curto sem stack/credenciais/números."
  [err]
  (let [msg (or (some-> err .-message) (str err))
        name (or (some-> err .-name) "Error")
        safe (if (> (count msg) 120) (str (subs msg 0 117) "...") msg)]
    {:name name :message safe}))

(defn- chats->grupos [chats]
  (->> (array-seq chats)
       (filter #(true? (.-isGroup %)))
       (map (fn [^js c]
              {"id" (or (some-> c .-id .-_serialized)
                        (str (.-id c)))
               "name" (or (.-name c) "")}))
       vec))

(def ^:private js-wwebjs-pronta
  "() => !!(window.WWebJS && typeof window.WWebJS.getChats === 'function')")

;; Só id + nome de grupos — sem histórico, sem mensagens privadas.
(def ^:private js-listar-grupos-store
  (str "async () => {"
       "  const mapChat = (c) => ({"
       "    id: (c.id && c.id._serialized) || String(c.id),"
       "    name: c.name || c.formattedTitle || ''"
       "  });"
       "  if (window.WWebJS && typeof window.WWebJS.getChats === 'function') {"
       "    const chats = await window.WWebJS.getChats();"
       "    return chats.filter((c) => c.isGroup).map(mapChat);"
       "  }"
       "  const Chat = window.Store && window.Store.Chat;"
       "  if (!Chat) return { __error: 'store_unavailable' };"
       "  const models = Chat.getModelsArray"
       "    ? Chat.getModelsArray()"
       "    : (Chat.models || []);"
       "  return models"
       "    .filter((m) => m.isGroup || m.groupMetadata)"
       "    .map(mapChat);"
       "}"))

(defn- wwebjs-store-pronta?
  "READY do Client ≠ inject WWebJS pronto. Evita getChats prematuro (ex.: 'r: r')."
  [^js client]
  (let [^js page (.-pupPage client)]
    (if-not page
      (p/resolved false)
      (-> (.evaluate page js-wwebjs-pronta)
          (p/then (fn [ok] (boolean ok)))
          (p/catch (fn [_] false))))))

(defn- listar-grupos-via-store
  "Fallback seguro: page.evaluate só retorna id+nome de grupos."
  [^js client]
  (let [^js page (.-pupPage client)]
    (if-not page
      (p/rejected (js/Error. "pupPage_indisponivel"))
      (-> (.evaluate page js-listar-grupos-store)
          (p/then
           (fn [raw]
             (if (and raw (aget raw "__error"))
               (p/rejected (js/Error. (str "store_fallback:" (aget raw "__error"))))
               (->> (array-seq (or raw #js []))
                    (map (fn [g]
                           {"id" (or (aget g "id") "")
                            "name" (or (aget g "name") "")}))
                    vec))))))))

(def ^:private bootstrap-tentativas 6)
(def ^:private bootstrap-backoff-ms [1500 2500 4000 6000 8000 10000])

(defn- tentar-get-chats
  [^js client tentativa]
  (-> (.getChats client)
      (p/then (fn [chats]
                {:ok? true :via "getChats" :grupos (chats->grupos chats)}))
      (p/catch
       (fn [err]
         (js/console.warn "[bootstrap] getChats falhou"
                          (str "tentativa=" tentativa "/" bootstrap-tentativas)
                          (clj->js (err-diag err)))
         {:ok? false :err err}))))

(defn- gravar-grupos-descobertos!
  [out grupos via]
  (let [payload (clj->js
                 {"generatedAt" (.toISOString (js/Date.))
                  "discoveryVia" via
                  "connectedUser" "redacted"
                  "groupCount" (count grupos)
                  "groups" grupos
                  "note"
                  (str "Preencha DEV_GROUP_ID no .env com o id do grupo de teste, "
                       "defina STAGING_GROUP_CONFIRMED=1 e reinicie "
                       "SEM STAGING_BOOTSTRAP_GROUP.")})]
    (fs/mkdirSync (path/dirname out) #js {:recursive true})
    (fs/writeFileSync out (js/JSON.stringify payload nil 2) "utf8")
    (js/console.log "[bootstrap] Grupos encontrados:" (count grupos) "(via" via ")")
    (doseq [g grupos]
      (js/console.log (str "  - " (get g "name") " => " (get g "id"))))
    (js/console.log "[bootstrap] Lista escrita em" out)
    (js/console.log "[bootstrap] Nenhuma mensagem será processada neste modo.")
    grupos))

(defn- fallback-store-descoberta
  [^js client out]
  (-> (listar-grupos-via-store client)
      (p/then (fn [grupos]
                (js/console.log "[bootstrap] Fallback Store OK")
                (gravar-grupos-descobertos! out grupos "store-evaluate")))
      (p/catch
       (fn [err]
         (js/console.error "[bootstrap] Store fallback falhou"
                           (clj->js (err-diag err)))
         (js/console.error
          "[bootstrap] Descoberta abortada — sessão preservada;"
          "reinicie o Node após alguns segundos ou defina DEV_GROUP_ID"
          "manualmente se já souber o id @g.us.")
         nil))))

(defn- tentativa-descoberta
  "Uma tentativa: espera Store se preciso, getChats, ou agenda a próxima."
  [^js client out n]
  (if (> n bootstrap-tentativas)
    (fallback-store-descoberta client out)
    (let [espera (nth bootstrap-backoff-ms (dec n) 10000)]
      (-> (wwebjs-store-pronta? client)
          (p/then
           (fn [pronta?]
             (when-not pronta?
               (js/console.log "[bootstrap] WWebJS/Store ainda não pronta;"
                               "aguardando antes de getChats"
                               (str "(tentativa " n ")")))
             (if pronta? (p/resolved nil) (sleep-ms espera))))
          (p/then (fn [_] (tentar-get-chats client n)))
          (p/then
           (fn [{:keys [ok? via grupos]}]
             (if ok?
               (gravar-grupos-descobertos! out grupos via)
               (-> (sleep-ms espera)
                   (p/then (fn [_] (tentativa-descoberta client out (inc n))))))))))))

(defn- descobrir-grupos!
  "Lista apenas grupos (id + nome). Retries + fallback Store. Sem msgs privadas."
  [^js client]
  (let [out (discovery-path)]
    (-> (sleep-ms 2000)
        (p/then (fn [_] (tentativa-descoberta client out 1)))
        (p/catch
         (fn [err]
           (js/console.error "[bootstrap] Falha inesperada na descoberta:"
                             (clj->js (err-diag err)))
           nil)))))

(defn- on-ready [client]
  (js/console.log (str "✅ " config/bot-name " conectado e pronto para uso!"))
  ;; Não logar número telefônico (PII).
  (js/console.log "📞 Conta autenticada (número omitido).")
  (if config/staging-bootstrap-group?
    (do
      (js/console.log "[bootstrap] Modo descoberta de grupo — sem comandos/notificações.")
      (descobrir-grupos! client))
    (do
      (pokemon/iniciar! client)
      (lembretes/iniciar! client))))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

;; em development|staging: só DEV_GROUP_ID (+ STAGING_ALLOWED_USERS se definido)
(defn- remetente-id [message]
  (or (.-author message)
      (when-not (.-fromMe message) (.-from message))))

(defn- permitido-pelo-ambiente? [message]
  (and (config/chat-autorizado? (chat-id message))
       (config/usuario-autorizado? (remetente-id message))))

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
  (whatsapp-reply/preparar! message)
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
  (when (config/chat-autorizado? (.-chatId notification))
    (admins/processar-evento-promocao! notification)))

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

(defn- local-auth-opts
  "LocalAuth com path/clientId configuráveis — staging isolado da produção."
  []
  (let [opts #js {:dataPath config/wwebjs-data-path}]
    (when config/wwebjs-client-id
      (unchecked-set opts "clientId" config/wwebjs-client-id))
    opts))

(defn main [& _args]
  ;; Um único cliente/browser por processo; só a injeção pode ser repetida.
  (when (and config/staging-like?
             (not config/staging-bootstrap-group?)
             (or (nil? config/dev-group-id) (str/blank? config/dev-group-id)))
    (js/console.error "APP_ENV=" config/app-env
                      "exige DEV_GROUP_ID (chat de teste). Abortando.")
    (js/console.error "Para só descobrir o ID do grupo: STAGING_BOOTSTRAP_GROUP=1")
    (js/process.exit 2))
  (when (and (= "staging" config/app-env)
             (not config/staging-bootstrap-group?)
             (string? config/dev-group-id)
             (not (str/blank? config/dev-group-id))
             (not config/staging-group-confirmed?))
    (js/console.error "Staging exige STAGING_GROUP_CONFIRMED=1 após validar DEV_GROUP_ID.")
    (js/console.error "DEV_GROUP_ID está definido, mas o grupo ainda não foi confirmado.")
    (js/process.exit 7))
  (when (and (= "staging" config/app-env)
             (config/caminho-sessao-producao? config/wwebjs-data-path))
    (js/console.error "Staging recusou WWEBJS_DATA_PATH de produção:"
                      config/wwebjs-data-path)
    (js/console.error "Use /app/.wwebjs_auth_core ou /app/.wwebjs_auth_staging"
                      "(nunca /app/.wwebjs_auth).")
    (js/process.exit 3))
  (when (compare-and-set! iniciado? false true)
    (let [puppeteer-opts (opcoes-puppeteer)
          client (Client. #js {:authStrategy (LocalAuth. (local-auth-opts))
                               :puppeteer (clj->js puppeteer-opts)})
          diagnostico (saude/criar)
          ;; STAGING_ALLOW_QR / bootstrap: restoreOnly false — senão QR em
          ;; pasta parcial dispara AUTH_TRANSITION e fecha o Chromium.
          recovery (.installStartupRecovery startup client
                     (clj->js
                      (cond-> {:onState (fn [estado]
                                          (when (#{"recovering" "error"} estado)
                                            (saude/atualizar! diagnostico
                                                             (if (= estado "error") "ERROR" "RECOVERING")
                                                             "Estado de inicialização atualizado; sessão preservada.")))}
                        (or config/staging-bootstrap-group?
                            config/staging-allow-qr?)
                        (assoc :restoreOnly false))))
          server (saude/servir! diagnostico client)]
      (when config/staging-bootstrap-group?
        (js/console.log "[bootstrap] STAGING_BOOTSTRAP_GROUP=1 — sem comandos, sem replies, sem notificações."))
      (configurar-envio-seguro! client)
      (saude/acompanhar! diagnostico client)
      (when-not config/staging-bootstrap-group?
        (recursos/iniciar! client))
      (encerrar-com-sessao! recovery diagnostico server)
      (.on client "qr" (fn [qr] (when (.acceptingQR recovery) (on-qr qr))))
      (.on client "ready" (fn [] (when (.acceptingReady recovery) (on-ready client))))
      (.on client "disconnected" (fn [& _]
                                  (when-not config/staging-bootstrap-group?
                                    (pokemon/parar!))))
      (.on client "auth_failure" (fn [& _]
                                  (when-not config/staging-bootstrap-group?
                                    (pokemon/parar!))))
      ;; Bootstrap: não processa mensagens nem eventos de admin.
      (when-not config/staging-bootstrap-group?
        (.on client "message_create" on-message)
        (.on client "group_admin_changed" on-group-admin-changed))
      (-> (if config/staging-bootstrap-group?
            (p/resolved true)
            (armazenamento/iniciar!))
          (p/then (fn [_]
                    (when (and (not config/staging-bootstrap-group?)
                               (platform/enabled?)
                               config/pokemon-jvm-enabled?)
                      (js/console.log "[boot] staging Datomic+JVM: Cassandra"
                                      (if (armazenamento/cliente-construido?)
                                        "FOI instanciado (inesperado)"
                                        "não instanciado")))
                    (if (and (not config/staging-bootstrap-group?) (platform/enabled?))
                      (-> (p/all [(bloqueio/hidratar-datomic!)
                                  (admins/hidratar-datomic!)
                                  (enquetes/hidratar-datomic!)
                                  (quiz/hidratar-datomic!)
                                  (rank/hidratar-datomic!)
                                  (lembretes/hidratar-datomic!)
                                  (bugs/hidratar-datomic!)
                                  (historico/hidratar-datomic!)])
                          (p/then (fn [_]
                                    (js/console.log "[platform] bloqueio/admins/enquetes/quiz/rank/lembretes/bugs/participantes hidratados via Datomic")
                                    true))
                          (p/catch (fn [err]
                                     (js/console.error "[platform] Datomic indisponível (fail-closed):" err)
                                     (p/rejected err))))
                      (p/resolved true))))
          (p/then (fn [_]
                    (saude/iniciar-avisos! diagnostico)
                    (.initialize recovery)))
          (p/catch (fn [err]
                     (when-not (.stopping recovery)
                       (saude/atualizar! diagnostico "ERROR"
                                        (str "Erro ao inicializar: " (.-message err)))
                       (js/console.error err))))))))
