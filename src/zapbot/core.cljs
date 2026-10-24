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
(def ^:private group-discovery (js/require "../scripts/lib/bootstrap-group-discovery.cjs"))

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

(def ^:private bootstrap-client (atom nil))
(def ^:private discovery-inflight? (atom false))
(def ^:private discovery-retry-timers (atom []))

;; Retries leves pós-READY (sem reiniciar Chromium) se a coleção ainda sincroniza.
(def ^:private bootstrap-tentativas 8)
(def ^:private bootstrap-backoff-ms [2000 3000 5000 8000 12000 15000 20000 30000])
(def ^:private rediscovery-delays-ms [15000 45000 90000 180000])

(defn- cancelar-rediscovery! []
  (doseq [t @discovery-retry-timers]
    (try (js/clearTimeout t) (catch :default _ nil)))
  (reset! discovery-retry-timers []))

(defn- js->diag-map [raw]
  (when raw
    (js->clj raw :keywordize-keys true)))

(defn- grupos-do-resultado [raw]
  (->> (or (some-> raw .-groups) #js [])
       array-seq
       (map (fn [g]
              {"id" (or (aget g "id") "")
               "name" (or (aget g "name") "")
               "hasGroupMetadata" (boolean (aget g "hasGroupMetadata"))}))
       (filter #(not (str/blank? (get % "id"))))
       vec))

(defn- descoberta-leve
  "page.evaluate com WAWebCollections — sem getChatModel / sem msgs privadas."
  [^js client]
  (let [^js page (.-pupPage client)]
    (if-not page
      (p/rejected (js/Error. "pupPage_indisponivel"))
      (-> (.evaluate page (.-discoverGroupsInPage group-discovery))
          (p/then
           (fn [raw]
             (let [diag (or (js->diag-map (some-> raw .-diagnostics)) {})
                   grupos (grupos-do-resultado raw)]
               (js/console.log "[bootstrap] diagnóstico"
                               (clj->js {:collectionSource (:collectionSource diag)
                                         :chatCount (:chatCount diag)
                                         :groupCount (:groupCount diag)
                                         :chatsWithGroupMetadata (:chatsWithGroupMetadata diag)
                                         :idServerCounts (:idServerCounts diag)
                                         :helpers (:helpers diag)}))
               {:ok? true
                :via (str "light:" (or (:collectionSource diag) "none"))
                :grupos grupos
                :diagnostics diag})))
          (p/catch
           (fn [err]
             (js/console.warn "[bootstrap] descoberta leve falhou"
                              (clj->js (err-diag err)))
             {:ok? false :err err}))))))

(defn- tentar-get-chats-opcional
  "Client.getChats ainda pode falhar (r:r via getChatModel). Só informativo."
  [^js client]
  (-> (.getChats client)
      (p/then (fn [chats]
                (let [n (alength chats)
                      g (->> (array-seq chats)
                             (filter #(true? (.-isGroup %)))
                             count)]
                  (js/console.log "[bootstrap] getChats OK (opcional)"
                                  (str "chats=" n " groups=" g))
                  {:ok? true})))
      (p/catch
       (fn [err]
         (js/console.warn "[bootstrap] getChats falhou (esperado se metadata atrasar)"
                          (clj->js (err-diag err)))
         {:ok? false :err err}))))

(defn- ler-groups-json
  "Lê groups.json anterior (só id/name agregados). Nil se ausente/inválido."
  [out]
  (try
    (when (fs/existsSync out)
      (let [prev (js->clj (js/JSON.parse (fs/readFileSync out "utf8")))]
        {:groups (vec (or (get prev "groups") []))
         :groupCount (or (get prev "groupCount") 0)}))
    (catch :default _ nil)))

(defn- gravar-grupos-descobertos!
  "Grava resultado. Não substitui grupos bons por [] sem marcar incomplete."
  [out grupos via diagnostics]
  (let [prev (when (empty? grupos) (ler-groups-json out))
        prev-grupos (when (and prev (seq (:groups prev))) (:groups prev))
        preserved? (boolean (and (empty? grupos) (seq prev-grupos)))
        grupos-out (if preserved? prev-grupos (mapv #(select-keys % ["id" "name"]) grupos))
        incomplete? (or (empty? grupos) preserved?)
        payload (clj->js
                 {"generatedAt" (.toISOString (js/Date.))
                  "discoveryVia" via
                  "connectedUser" "redacted"
                  "groupCount" (count grupos-out)
                  "groups" grupos-out
                  "diagnostics" (or diagnostics {})
                  "incomplete" incomplete?
                  "staleGroupsPreserved" preserved?
                  "lastAttemptGroupCount" (count grupos)
                  "awaitingSync" (boolean (empty? grupos))
                  "stagingGroupConfirmed" false
                  "devGroupIdInvented" false
                  "note"
                  (str "Preencha DEV_GROUP_ID no .env com o id do grupo de teste, "
                       "defina STAGING_GROUP_CONFIRMED=1 e reinicie "
                       "SEM STAGING_BOOTSTRAP_GROUP. "
                       "Re-descobrir sem reiniciar: "
                       "curl -sS -X POST http://127.0.0.1:3001/bootstrap/discover-groups")})]
    (fs/mkdirSync (path/dirname out) #js {:recursive true})
    (fs/writeFileSync out (js/JSON.stringify payload nil 2) "utf8")
    (js/console.log "[bootstrap] Grupos no arquivo:" (count grupos-out)
                    "(via" via ")"
                    (if preserved? "[preservados — tentativa vazia]" ""))
    (doseq [g grupos-out]
      (js/console.log (str "  - " (get g "name") " => " (get g "id"))))
    (js/console.log "[bootstrap] Lista escrita em" out
                    (str "incomplete=" incomplete?))
    (js/console.log "[bootstrap] Nenhuma mensagem será processada neste modo.")
    (when (empty? grupos)
      (js/console.log "[bootstrap] zero grupos na tentativa — coleção pode sincronizar;"
                      "use POST /bootstrap/discover-groups ou aguarde retries."))
    grupos-out))

(defn- agendar-rediscovery!
  "Retries automáticos sem reiniciar Chromium enquanto groups=[]."
  [^js client]
  (cancelar-rediscovery!)
  (reset! discovery-retry-timers
          (mapv (fn [ms]
                  (js/setTimeout
                   (fn []
                     (js/console.log "[bootstrap] re-descoberta automática após"
                                     (str (/ ms 1000) "s"))
                     (descobrir-grupos! client {:reason "auto-retry"}))
                   ms))
                rediscovery-delays-ms)))

(defn- aplicar-resultado-descoberta!
  [out {:keys [ok? via grupos diagnostics]} {:keys [schedule-retry?]}]
  (if-not ok?
    (do
      (js/console.warn "[bootstrap] rodada sem resultado utilizável")
      false)
    (do
      (gravar-grupos-descobertos! out (or grupos []) via diagnostics)
      (if (seq grupos)
        (do (cancelar-rediscovery!) true)
        (do
          (when schedule-retry?
            (js/console.log "[bootstrap] zero grupos — agendando re-descoberta sem reiniciar Chromium")
            (agendar-rediscovery! @bootstrap-client))
          false)))))

(defn- tentativa-descoberta
  "Loop com backoff após READY. Agenda rediscovery só ao esgotar."
  [^js client out n]
  (if (> n bootstrap-tentativas)
    (do
      (js/console.error "[bootstrap] esgotaram tentativas leves — sessão preservada.")
      (agendar-rediscovery! client)
      (p/resolved nil))
    (let [espera (nth bootstrap-backoff-ms (dec n) 30000)]
      (-> (descoberta-leve client)
          (p/then
           (fn [resultado]
             (if (and (:ok? resultado) (seq (:grupos resultado)))
               (aplicar-resultado-descoberta! out resultado {:schedule-retry? false})
               (do
                 (when (:ok? resultado)
                   (gravar-grupos-descobertos!
                    out [] (:via resultado) (:diagnostics resultado))
                   (js/console.log "[bootstrap] ainda sem grupos;"
                                   "nova tentativa em" (str espera "ms")
                                   (str "(" n "/" bootstrap-tentativas ")")))
                 (-> (sleep-ms espera)
                     (p/then (fn [_] (tentativa-descoberta client out (inc n)))))))))))))

(defn bootstrap-discover-status
  "Status síncrono para o endpoint HTTP (sem side-effect)."
  []
  (cond
    (nil? @bootstrap-client)
    {:status "not_ready" :http 503
     :note "WhatsApp ainda não emitiu READY no modo bootstrap."}

    @discovery-inflight?
    {:status "busy" :http 409
     :note "Descoberta já em andamento — aguarde logs [bootstrap]."}

    :else
    {:status "ready" :http 202
     :note "Pode agendar descoberta."}))

(defn descobrir-grupos!
  "Lista só id+nome de grupos. Sem msgs privadas. Idempotente sob inflight."
  ([client] (descobrir-grupos! client {:reason "ready"}))
  ([^js client {:keys [reason]}]
   (if-not (compare-and-set! discovery-inflight? false true)
     (do
       (js/console.log "[bootstrap] descoberta já em andamento — ignore"
                       (str "(" (or reason "manual") ")"))
       (p/resolved {:busy true}))
     (let [out (discovery-path)
           ready? (= reason "ready")]
       (js/console.log "[bootstrap] iniciando descoberta"
                       (str "(" (or reason "manual") ")"))
       (-> (sleep-ms (if ready? 3000 500))
           (p/then
            (fn [_]
              (if ready?
                (do
                  (tentar-get-chats-opcional client)
                  (tentativa-descoberta client out 1))
                ;; HTTP / auto-retry: uma rodada (timers já agendados no esgotamento).
                (-> (descoberta-leve client)
                    (p/then
                     (fn [resultado]
                       (aplicar-resultado-descoberta!
                        out resultado {:schedule-retry? false})))))))
           (p/catch
            (fn [err]
              (js/console.error "[bootstrap] Falha inesperada na descoberta:"
                                (clj->js (err-diag err)))
              nil))
           (p/then (fn [resultado]
                     (reset! discovery-inflight? false)
                     resultado))
           (p/catch (fn [err]
                      (reset! discovery-inflight? false)
                      (p/rejected err))))))))

(defn agendar-descoberta-http!
  "Agenda descoberta após flush da resposta HTTP. Nunca bloqueia o handler."
  []
  (let [st (bootstrap-discover-status)]
    (if (not= "ready" (:status st))
      st
      (let [client @bootstrap-client]
        ;; setTimeout(0): resposta HTTP termina antes do evaluate no Chromium.
        (js/setTimeout
         (fn []
           (try
             (descobrir-grupos! client {:reason "http"})
             (catch :default err
               (js/console.error "[bootstrap] agendar http falhou"
                                 (clj->js (err-diag err))))))
         0)
        (assoc st :accepted true
               :note "Descoberta agendada; veja logs [bootstrap] e groups.json.")))))

(defn- on-ready [client]
  (js/console.log (str "✅ " config/bot-name " conectado e pronto para uso!"))
  ;; Não logar número telefônico (PII).
  (js/console.log "📞 Conta autenticada (número omitido).")
  (if config/staging-bootstrap-group?
    (do
      (reset! bootstrap-client client)
      (js/console.log "[bootstrap] Modo descoberta de grupo — sem comandos/notificações.")
      (js/console.log "[bootstrap] Re-descobrir: POST http://127.0.0.1:3001/bootstrap/discover-groups")
      (descobrir-grupos! client {:reason "ready"}))
    (do
      (pokemon/iniciar! client)
      (lembretes/iniciar! client))))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

;; ALLOW_ALL_GROUPS libera apenas o filtro de grupo; a allowlist continua ativa.
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
             (not config/allow-all-groups?)
             (not config/staging-bootstrap-group?)
             (or (nil? config/dev-group-id) (str/blank? config/dev-group-id)))
    (js/console.error "APP_ENV=" config/app-env
                      "exige DEV_GROUP_ID (chat de teste). Abortando.")
    (js/console.error "Para só descobrir o ID do grupo: STAGING_BOOTSTRAP_GROUP=1")
    (js/process.exit 2))
  (when (and (= "staging" config/app-env)
             (not config/allow-all-groups?)
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
          server (saude/servir!
                  diagnostico client
                  (when config/staging-bootstrap-group?
                    {:on-discover-groups
                     (fn []
                       ;; Síncrono + setTimeout(0): HTTP responde antes do evaluate.
                       (agendar-descoberta-http!))}))]
      (when config/staging-bootstrap-group?
        (js/console.log "[bootstrap] STAGING_BOOTSTRAP_GROUP=1 — sem comandos, sem replies, sem notificações.")
        (js/console.log "[bootstrap] Endpoint: POST/GET http://127.0.0.1:3001/bootstrap/discover-groups"))
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
