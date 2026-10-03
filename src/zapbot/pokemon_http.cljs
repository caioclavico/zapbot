(ns zapbot.pokemon-http
  "Adaptador HTTP do Pokémon: contexto WhatsApp, entrega e efeitos genéricos.
  Não carrega regras, renderizadores ou estado de jogo no processo do bot."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            ["whatsapp-web.js" :as wwjs]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.desempenho :as desempenho]
            [zapbot.rank :as rank]
            [zapbot.historico :as historico]
            [zapbot.bloqueio :as bloqueio]))

(def ^:private transport (js/require "../scripts/lib/pokemon-http-client.cjs"))
(def ^:private MessageMedia (.-MessageMedia wwjs))
(def ^:private versao-bot (.-version (js/require "../package.json")))
(def ^:private chave-entregas "pokemon-http-deliveries")
(defonce ^:private entregas (atom (or (armazenamento/obter chave-entregas) {})))
(armazenamento/registrar! chave-entregas entregas)
(defonce ^:private cliente-http (atom nil))
(defonce ^:private cliente-whatsapp (atom nil))
(defonce ^:private relogio-eventos (atom nil))
(defonce ^:private consultando-eventos? (atom false))
(defonce ^:private filas (atom {}))
(declare erro-contrato)

(defn- configurado? []
  (and (not (str/blank? config/pokemon-service-url))
       (not (str/blank? config/pokemon-service-token))))

(defn- cliente! []
  (or @cliente-http
      (let [cliente (.createClient transport
                       #js {:baseUrl config/pokemon-service-url
                            :token config/pokemon-service-token
                            :connectTimeoutMs config/pokemon-connect-timeout-ms
                            :timeoutMs config/pokemon-timeout-ms
                            :maxMediaBytes config/pokemon-max-media-bytes})]
        (reset! cliente-http cliente)
        cliente)))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- serializar-id [id]
  (if (string? id) id (some-> id .-_serialized)))

(defn- com-limite [executar ms fallback]
  (p/create
   (fn [resolve _]
     (let [timer (js/setTimeout #(resolve fallback) ms)]
       (try
         (.then (js/Promise.resolve (executar))
                (fn [valor] (js/clearTimeout timer) (resolve valor))
                (fn [_] (js/clearTimeout timer) (resolve fallback)))
         (catch :default _ (js/clearTimeout timer) (resolve fallback)))))))

(defn- contexto-pedido [message comando argumentos]
  (let [ctx (desempenho/contexto-de message)
        request-id (serializar-id (.-id message))
        _ (when (str/blank? request-id)
            (throw (erro-contrato "Mensagem sem identificador estável; comando não encaminhado.")))
        [subcomando acao] (str/split (str/trim (str/lower-case argumentos)) #"\s+")
        pokemon? (contains? #{"pk" "pokemon"} comando)
        bug? (and pokemon? (= "bug" subcomando) (nil? acao))
        consulta-bug? (and pokemon? (or (= "bugs" subcomando)
                                        (and (= "bug" subcomando) (contains? #{"ver" "resolver" "res"} acao))))
        anterior (when bug? (some-> (historico/mensagem-anterior message) (assoc :origem "anterior")))
        cita? (and pokemon? (or bug? (contains? #{"doar" "doa" "negociar" "neg"} subcomando))
                   (.-hasQuotedMsg message) (fn? (.-getQuotedMessage message)))
        mencionados (vec (keep serializar-id (array-seq (or (.-mentionedIds message) #js []))))]
    (p/let [contato (desempenho/medir! ctx "pokemon_contexto_whatsapp"
                     #(com-limite (fn [] (if (fn? (.-getContact message)) (.getContact message) nil))
                                  2000 nil))
            citada (when (and cita? (or bug? (empty? mencionados)))
                     (desempenho/medir! ctx "pokemon_citacao_whatsapp"
                       #(com-limite (fn [] (.getQuotedMessage message)) 2000 nil)))
            admin? (when consulta-bug?
                     (desempenho/medir! ctx "pokemon_admin_whatsapp"
                       #(bloqueio/autorizado? message)))]
      {:requestId request-id
       :chatId (chat-id message)
       :playerId (or (.-author message) (.-from message))
       :playerName (or (some-> contato .-pushname) (some-> contato .-name)
                       (some-> contato .-number) "Alguém")
       :command (str comando (when-not (str/blank? argumentos) (str " " argumentos)))
       :context {:mentionedIds mencionados
                 :quotedPlayerId (when citada (or (.-author citada) (.-from citada)))
                 :isAdmin (true? admin?)
                 :botVersion versao-bot
                 :bugTarget (when bug?
                              (if citada
                                {:author (or (.-author citada) (.-from citada) "desconhecido")
                                 :text (or (.-body citada) (.-caption citada) "")
                                 :timestamp (when (number? (.-timestamp citada)) (* 1000 (.-timestamp citada)))
                                 :source "citada"}
                                anterior))}})))

(defn- enfileirar! [cid acao ctx]
  ;; A fila conserva a ordem de entrega do chat, inclusive notificações.
  (let [anterior (get @filas cid (p/resolved nil))
        atual (-> (desempenho/medir! ctx "pokemon_fila_http" #(identity anterior))
                  (p/catch (fn [_] nil))
                  (p/then (fn [_] (acao))))]
    (swap! filas assoc cid atual)
    (p/finally atual #(when (identical? atual (get @filas cid)) (swap! filas dissoc cid)))))

(defn- erro-contrato [texto]
  (doto (js/Error. texto) (aset "code" "INVALID_RESPONSE")))

(defn- validar-resposta! [resposta]
  (when-not (and (map? resposta) (vector? (:messages resposta))
                 (vector? (:effects resposta)))
    (throw (erro-contrato "Envelope Pokémon inválido.")))
  (doseq [mensagem (:messages resposta)]
    (when-not (and (contains? #{"text" "image" "document"} (:type mensagem))
                   (or (nil? (:text mensagem)) (string? (:text mensagem)))
                   (or (nil? (:mentions mensagem))
                       (and (vector? (:mentions mensagem)) (every? string? (:mentions mensagem))))
                   (or (= "text" (:type mensagem))
                       (and (string? (:mediaId mensagem)) (string? (:mimeType mensagem))
                            (string? (:filename mensagem)))))
      (throw (erro-contrato "Mensagem Pokémon inválida."))))
  resposta)

(defn- aplicar-efeitos! [resposta]
  (-> (reduce
       (fn [anterior efeito]
         (p/let [substituicoes anterior
                 resultado (rank/aplicar-efeito! efeito)]
           (if-let [token (:token efeito)]
             (assoc substituicoes token (or (if resultado (:whenTrue efeito) (:whenFalse efeito)) ""))
             substituicoes)))
       (p/resolved {}) (:effects resposta))
      (p/then
       (fn [substituicoes]
         (update resposta :messages
                 (fn [mensagens]
                   (mapv #(update % :text
                                  (fn [texto]
                                    (reduce-kv (fn [s token valor] (str/replace s token valor))
                                               (or texto "") substituicoes))) mensagens)))))))

(defn- acoes-de-envio [mensagens]
  ;; Separa documentos e legendas longas antes de marcar entregas. Uma falha
  ;; posterior não repete o texto ou a imagem já confirmados no Cassandra.
  (vec
   (mapcat
    (fn [mensagem]
      (let [texto (or (:text mensagem) "")
            texto-acao {:type "text" :text texto :mentions (:mentions mensagem)}]
        (cond
          (= "document" (:type mensagem))
          (cond-> [] (not (str/blank? texto)) (conj texto-acao)
            true (conj (assoc mensagem :text "")))

          (and (= "image" (:type mensagem)) (> (count texto) 900))
          [(assoc mensagem :text (or (:caption mensagem) "🖼️ *Imagem Pokémon*")) texto-acao]

          :else [mensagem]))) mensagens)))

(defn- enviar-acao! [http enviar ctx {:keys [type text mentions mediaId mimeType filename] :as acao}]
  (let [opcoes #js {:mentions (clj->js (or mentions []))}
        enviar-texto #(when-not (str/blank? text)
                        (desempenho/medir! ctx "pokemon_envio_whatsapp" (fn [] (enviar text opcoes))))]
    (if (= "text" type)
      (p/resolved (enviar-texto))
      (-> (p/let [buffer (desempenho/medir! ctx "pokemon_transferencia_midia" #(.media http mediaId))
                  media (MessageMedia. mimeType (desempenho/codificar-base64! ctx buffer) filename)
                  _ (desempenho/medir! ctx "pokemon_envio_whatsapp"
                      #(enviar media
                               (js/Object.assign #js {} opcoes
                                 (if (= "document" type)
                                   #js {:sendMediaAsDocument true}
                                   #js {:caption text}))))]
            nil)
          (p/catch (fn [erro]
                     (if (and (= "image" type) (not (str/blank? text)))
                       (enviar-texto)
                       (p/rejected erro))))))))

(defn- entregar! [http enviar ctx identificador resposta]
  (validar-resposta! resposta)
  (p/let [resposta (desempenho/medir! ctx "pokemon_efeitos_rank" #(aplicar-efeitos! resposta))]
    (reduce
     (fn [anterior [indice acao]]
       (p/then
        anterior
        (fn [_]
          (let [chave (str identificador ":" indice)]
            (p/let [_ (when-not (contains? @entregas chave)
                        (p/then (enviar-acao! http enviar ctx acao)
                                (fn [_] (swap! entregas assoc chave {"at" (.now js/Date)}))))
                    ;; Repetir a gravação também resolve falha anterior depois
                    ;; do envio. Nunca confirmar evento apenas em memória.
                    _ (armazenamento/salvar-confirmado! chave-entregas @entregas)]
              nil)))))
     (p/resolved nil) (map-indexed vector (acoes-de-envio (:messages resposta))))))

(defn- mensagem-falha [erro]
  (case (.-code erro)
    ("TIMEOUT" "CONNECT_TIMEOUT")
    "⏳ O serviço Pokémon demorou para responder. O comando pode ter sido processado; confira o estado antes de repetir uma compra, troca ou captura."
    "CONFIG" "⚠️ O serviço Pokémon ainda não está configurado. Os outros comandos continuam disponíveis."
    "LIMIT" "⚠️ A resposta Pokémon excedeu o limite de tamanho. Tente consultar a coleção em texto: !pk time txt."
    "⚠️ Não consegui concluir a resposta do Pokémon. O comando pode ter sido processado; confira o estado antes de repetir uma ação. Os outros comandos continuam disponíveis."))

(defn executar [message comando argumentos]
  (let [ctx (desempenho/contexto-de message)
        argumentos (or argumentos "")]
    (enfileirar!
     (chat-id message)
     (fn [] (-> (p/let [pedido (contexto-pedido message comando argumentos)
                  http (cliente!)
                  resposta (desempenho/medir! ctx "pokemon_http"
                             #(.command http (clj->js pedido)))
                  resposta (js->clj resposta :keywordize-keys true)
                  _ (when-not (= (:requestId pedido) (:requestId resposta))
                      (throw (erro-contrato "requestId Pokémon divergente.")))
                  _ (when-let [tempos (:timings resposta)]
                      (js/console.log "[PokemonHTTP]" (js/JSON.stringify
                         (clj->js {:requestId (:requestId pedido) :timings tempos}))))
                  _ (entregar! http (fn [conteudo opcoes] (.reply message conteudo nil opcoes))
                               ctx (str "command:" (:requestId pedido)) resposta)]
            nil)
          (p/catch (fn [erro]
                     (js/console.warn "[PokemonHTTP] Falha:" (or (.-code erro) "DELIVERY") (or (.-status erro) ""))
                     (mensagem-falha erro)))))
     ctx)))

(defn jogar [message argumentos]
  (executar message "pokemon" argumentos))

(defn- consultar-eventos! []
  (when (and @cliente-whatsapp (configurado?) (compare-and-set! consultando-eventos? false true))
    (-> (p/let [http (cliente!)
                resposta (.pendingEvents http)
                eventos (js->clj (.-events resposta) :keywordize-keys true)]
          (when-not (vector? eventos) (throw (erro-contrato "Lista de eventos Pokémon inválida.")))
          (reduce
           (fn [anterior evento]
             (p/then anterior
                     (fn [_]
                       (when (and @cliente-whatsapp
                                  (or (not= config/app-env "development")
                                      (= (:chatId evento) config/dev-group-id)))
                         (enfileirar!
                          (:chatId evento)
                          #(when-let [whatsapp @cliente-whatsapp]
                             (p/let [_ (when-not (and (string? (:id evento)) (string? (:chatId evento)))
                                         (throw (erro-contrato "Identidade de evento Pokémon inválida.")))
                                     _ (entregar! http
                                          (fn [conteudo opcoes] (.sendMessage whatsapp (:chatId evento) conteudo opcoes))
                                          nil (str "event:" (:id evento)) evento)
                                     _ (.ack http (:id evento))]
                               nil)) nil)))))
           (p/resolved nil) eventos))
        (p/catch (fn [erro]
                   (js/console.warn "[PokemonHTTP] Eventos pendentes, nova tentativa no próximo ciclo:"
                                    (or (.-code erro) "DELIVERY"))))
        (p/finally #(reset! consultando-eventos? false)))))

(defn parar! []
  (reset! cliente-whatsapp nil)
  (when-let [timer @relogio-eventos] (js/clearInterval timer))
  (reset! relogio-eventos nil))

(defn iniciar! [client]
  (parar!)
  (reset! cliente-whatsapp client)
  (when (configurado?)
    (let [timer (js/setInterval consultar-eventos! config/pokemon-event-poll-ms)]
      (.unref timer)
      (reset! relogio-eventos timer))
    (consultar-eventos!)))
