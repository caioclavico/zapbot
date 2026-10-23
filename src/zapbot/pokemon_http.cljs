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
(def ^:private jvm-transport (js/require "../scripts/lib/pokemon-jvm-client.cjs"))
(def ^:private jvm-present (js/require "../scripts/lib/pokemon-jvm-present.cjs"))
(def ^:private jvm-media (js/require "../scripts/lib/pokemon-jvm-media.cjs"))
(def ^:private pokeapi (js/require "../scripts/lib/pokemon-pokeapi.cjs"))
(def ^:private MessageMedia (.-MessageMedia wwjs))
(def ^:private versao-bot (.-version (js/require "../package.json")))
(def ^:private chave-entregas "pokemon-http-deliveries")
(defonce ^:private entregas (atom (or (armazenamento/obter chave-entregas) {})))
(armazenamento/registrar! chave-entregas entregas)
(defonce ^:private cliente-http (atom nil))
(defonce ^:private cliente-jvm (atom nil))
(defonce ^:private cliente-whatsapp (atom nil))
(defonce ^:private relogio-eventos (atom nil))
(defonce ^:private consultando-eventos? (atom false))
(defonce ^:private filas (atom {}))
;; Remoção de golpe com janela de cancelamento (espelha master; estado só no Node).
(defonce ^:private remocoes-pendentes (atom {}))
(def ^:private segundos-remover-golpe 30)
(declare erro-contrato)

(defn- parse-indice-1based
  "Converte texto 1-based em índice 0-based, ou nil se inválido."
  [texto total]
  (when (and (pos? (long total)) (re-matches #"[1-9][0-9]*" (str texto)))
    (let [n (js/parseInt (str texto) 10)]
      (when (and (<= 1 n) (<= n total)) (dec n)))))

(defn- configurado? []
  (and (not (str/blank? config/pokemon-service-url))
       (not (str/blank? config/pokemon-service-token))))

(defn- jvm-configurado? []
  (and config/pokemon-jvm-enabled?
       (not (str/blank? config/pokemon-jvm-url))
       (not (str/blank? config/pokemon-jvm-token))))

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

(defn- cliente-jvm! []
  (or @cliente-jvm
      (let [cliente (.createClient jvm-transport
                       #js {:baseUrl config/pokemon-jvm-url
                            :token config/pokemon-jvm-token
                            :connectTimeoutMs config/pokemon-jvm-connect-timeout-ms
                            :timeoutMs config/pokemon-jvm-timeout-ms
                            :allowDockerJvmHost config/pokemon-jvm-allow-docker-host?})]
        (reset! cliente-jvm cliente)
        cliente)))

(defn- tokens-args [argumentos]
  (vec (remove str/blank? (str/split (str/trim (str/lower-case (or argumentos ""))) #"\s+"))))

(defn- tokens-para-jvm
  "Top-level !loja/!mochila entram como comando; !pk * já traz o subcomando nos args."
  [comando argumentos]
  (let [args (tokens-args argumentos)
        cmd (str/lower-case (str comando))]
    (if (contains? #{"loja" "mochila" "presente" "presentes" "missoes" "missões"} cmd)
      (into [cmd] args)
      args)))

(defn- envelope-texto [texto]
  (js->clj (.envelope jvm-present nil texto) :keywordize-keys true))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- serializar-id [id]
  ;; WhatsApp atual serializa MsgKey em $1; builds anteriores usam _serialized.
  ;; Preservar o ID canônico recebido mantém dedupe e identidade entre versões.
  (let [valor (if (string? id) id
                (when id (or (.-_serialized id) (aget id "$1"))))]
    (when (and (string? valor) (not (str/blank? valor))) valor)))

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
        ;; Doações continuam aceitando o autor citado; negociação usa somente
        ;; menções reais, para que uma resposta citada nunca autorize uma troca.
        cita? (and pokemon? (or bug? (contains? #{"doar" "doa"} subcomando))
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
                       (and (string? (:mimeType mensagem))
                            (string? (:filename mensagem))
                            (or (string? (:mediaId mensagem))
                                (string? (:base64 mensagem))))))
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
  ;; Preserva o catálogo da loja na legenda; separa documentos e demais
  ;; legendas longas antes de marcar entregas. Uma falha
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

          (and (= "image" (:type mensagem))
               (not= "loja-pokemon.jpg" (:filename mensagem))
               (> (count texto) 900))
          [(assoc mensagem :text (or (:caption mensagem) "🖼️ *Imagem Pokémon*")) texto-acao]

          :else [mensagem]))) mensagens)))

(defn- enviar-acao! [http enviar ctx {:keys [type text mentions mediaId base64 mimeType filename] :as acao}]
  (let [opcoes #js {:mentions (clj->js (or mentions []))}
        enviar-texto #(when-not (str/blank? text)
                        (desempenho/medir! ctx "pokemon_envio_whatsapp" (fn [] (enviar text opcoes))))]
    (if (= "text" type)
      (p/resolved (enviar-texto))
      (-> (p/let [b64 (if (and (string? base64) (not (str/blank? base64)))
                       base64
                       (desempenho/codificar-base64!
                        ctx
                        (desempenho/medir! ctx "pokemon_transferencia_midia" #(.media http mediaId))))
                  media (MessageMedia. mimeType b64 filename)
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
  ;; Legado HTTP e JVM compartilham códigos; JVM usa present.formatError.
  (if (jvm-configurado?)
    (.formatError jvm-present erro)
    (case (.-code erro)
      ("TIMEOUT" "CONNECT_TIMEOUT")
      "⏳ O serviço Pokémon demorou para responder. O comando pode ter sido processado; confira o estado antes de repetir uma compra, troca ou captura."
      "CONFIG" "⚠️ O serviço Pokémon ainda não está configurado. Os outros comandos continuam disponíveis."
      "LIMIT" "⚠️ A resposta Pokémon excedeu o limite de tamanho. Tente consultar a coleção em texto: !pk time txt."
      "⚠️ Não consegui concluir a resposta do Pokémon. O comando pode ter sido processado; confira o estado antes de repetir uma ação. Os outros comandos continuam disponíveis.")))

(defn- entregar-envelope-jvm! [message ctx pedido envelope]
  (let [resposta (assoc (js->clj envelope :keywordize-keys true)
                        :requestId (:requestId pedido))]
    (entregar! nil (fn [conteudo opcoes] (.reply message conteudo nil opcoes))
               ctx (str "jvm:" (:requestId pedido)) resposta)))

(defn- envelope-jvm
  "Texto obrigatório; imagem opcional. Efeitos ficam vazios (não são TX Datomic)."
  ([pedido texto] (envelope-jvm pedido texto nil))
  ([pedido texto imagem]
   (if imagem
     (.envelope jvm-present (:requestId pedido) texto
                #js {:image imagem :caption texto :effects #js []})
     (.envelope jvm-present (:requestId pedido) texto #js {:effects #js []}))))

(defn- render-seguro!
  "Render de mídia pós-TX. Qualquer falha → nil (nunca reexecuta gameplay)."
  [ctx metrica f]
  (-> (desempenho/medir! ctx metrica f)
      (p/catch (fn [err]
                 (js/console.error (str "mídia JVM (" metrica "):") err)
                 nil))))

(defn- try-joy-collect!
  "Lazy collect como o legado: ignora joy_nothing_ready."
  [jvm ctx pedido chat-id player-id]
  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
          op-id (.stableOperationId jvm-transport
                                    (:requestId pedido) "joy-collect")
          data (-> (desempenho/medir! ctx "pokemon_jvm_joy_collect"
                     #(.joyCollect jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))
                   (p/catch (fn [err]
                              (when-not (= "JOY_NOTHING_READY"
                                           (str/upper-case (str (or (.-code err) ""))))
                                (throw err))
                              nil)))]
    data))

(defn- executar-joy-jvm!
  "Fluxo !pk joy / enfermeira / hospital."
  [jvm ctx pedido chat-id player-id args-joy]
  (let [prefix (str config/prefix "pk")
        raw (str/trim (str/join " " args-joy))]
    (if (str/blank? raw)
      (p/let [coletados (try-joy-collect! jvm ctx pedido chat-id player-id)
              data (desempenho/medir! ctx "pokemon_jvm_joy"
                     #(.getJoyStatus jvm #js {:chatId chat-id :playerId player-id}))
              texto (if (and coletados (pos? (or (.-n coletados) 0)))
                      (str (.formatJoyCollect jvm-present coletados prefix)
                           "\n\n"
                           (.formatJoyStatus jvm-present data prefix))
                      (.formatJoyStatus jvm-present data prefix))]
        (envelope-jvm pedido texto))
      (let [nums (->> (str/split raw #"[,\s]+")
                      (map #(js/parseInt % 10))
                      (filter #(and (not (js/isNaN %)) (pos? %)))
                      (map dec)
                      (sort >)
                      vec)]
        (when (empty? nums)
          (throw (erro-contrato (str "❓ Use " prefix " joy 1,2 (índices da coleção)"))))
        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                enviados
                (reduce
                 (fn [chain idx]
                   (p/then chain
                           (fn [acc]
                             (let [op-id (.stableOperationId jvm-transport
                                                             (:requestId pedido)
                                                             (str "joy-send:" idx))]
                               (p/then (desempenho/medir! ctx "pokemon_jvm_joy_send"
                                          #(.joySend jvm
                                             #js {:chatId chat-id
                                                  :playerId player-id
                                                  :saveId (.-activeSaveId ativo)
                                                  :fenceGeneration (.-fenceGeneration ativo)
                                                  :operationId op-id
                                                  :collectionIndex idx}))
                                       (fn [data] (conj acc data)))))))
                 (js/Promise.resolve [])
                 nums)
                nomes (->> enviados
                           (map #(some-> % .-pokemon .-nome))
                           (filter some?)
                           (str/join ", "))
                texto (str "🏥 A Enfermeira Joy recebeu"
                           (if (str/blank? nomes) " seus Pokémon" (str " *" nomes "*"))
                           ".\nEles voltarão em 30 minutos.\n"
                           prefix " joy · " prefix " time")]
          (envelope-jvm pedido texto))))))

(defn- executar-ovos-jvm!
  "Fluxo !pk ovos / ovo / incubar / incubadoras — sob demanda (sem push)."
  [jvm ctx pedido chat-id player-id sub args]
  (let [prefix (str config/prefix "pk")
        sub (str/lower-case (str (or sub "")))]
    (cond
      (or (= "incubadoras" sub) (= "incubadora" sub))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_adventure"
                       #(.getAdventure jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatAdventureStatus jvm-present data prefix)))

      (= "incubar" sub)
      (let [n-str (first args)
            kind (str/lower-case (str (or (second args) "permanente")))
            incubator (case kind
                        ("extra" "especial" "temporaria" "temporária") kind
                        "permanente")]
        (when (str/blank? (str n-str))
          (throw (erro-contrato (str "❓ Use " prefix " incubar <n> [permanente]"))))
        (p/let [adv (.getAdventure jvm #js {:chatId chat-id :playerId player-id})
                egg (.resolveEggIndex jvm-present adv n-str)
                _ (when-not egg
                    (throw (erro-contrato "❓ Índice de ovo inválido. Use !pk ovos.")))
                _ (when-not (= "inventory" (str (.-state egg)))
                    (throw (erro-contrato "❓ Esse ovo não está disponível para incubar.")))
                ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "egg-inc:" (.-eggId egg)))
                data (desempenho/medir! ctx "pokemon_jvm_egg_incubate"
                       #(.incubateEgg jvm
                          #js {:chatId chat-id
                               :playerId player-id
                               :saveId (.-activeSaveId ativo)
                               :fenceGeneration (.-fenceGeneration ativo)
                               :operationId op-id
                               :eggId (.-eggId egg)
                               :incubatorId (if (= incubator "permanente")
                                              "permanente"
                                              incubator)}))]
          (envelope-jvm pedido (.formatEggIncubate jvm-present data prefix))))

      (or (= "nascer" sub) (= "hatch" sub) (= "chocar" sub))
      (let [n-str (first args)]
        (when (str/blank? (str n-str))
          (throw (erro-contrato (str "❓ Use " prefix " ovo nascer <n>"))))
        (p/let [adv (.getAdventure jvm #js {:chatId chat-id :playerId player-id})
                egg (.resolveEggIndex jvm-present adv n-str)
                _ (when-not egg
                    (throw (erro-contrato "❓ Índice de ovo inválido.")))
                ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "egg-hatch:" (.-eggId egg)))
                data (desempenho/medir! ctx "pokemon_jvm_egg_hatch"
                       #(.hatchEgg jvm
                          #js {:chatId chat-id
                               :playerId player-id
                               :saveId (.-activeSaveId ativo)
                               :fenceGeneration (.-fenceGeneration ativo)
                               :operationId op-id
                               :eggId (.-eggId egg)
                               :hatchId (str "hatch:" (.-eggId egg))}))]
          (envelope-jvm pedido (.formatEggHatch jvm-present data prefix))))

      :else
      ;; ovos / ovo / ajuda → status
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_adventure"
                       #(.getAdventure jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatAdventureStatus jvm-present data prefix))))))

(defn- executar-raid-jvm!
  "Fluxo !pk raide / gin entrar|iniciar|atacar|… — gameplay JVM antes de mídia."
  [jvm ctx pedido chat-id player-id player-name args-raid]
  (let [sub (.resolveRaidSubcommand jvm-present (or (first args-raid) ""))
        resto (vec (rest args-raid))
        prefix (str config/prefix "pk")]
    (cond
      (or (nil? sub) (str/blank? (str sub)))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                       #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatRaidStatus jvm-present data prefix)))

      (contains? #{"abrir" "cancelar"} sub)
      (envelope-jvm pedido (.formatGymBlocked jvm-present sub))

      (= "time" sub)
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_eligible"
                       #(.getRaidEligible jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatRaidEligible jvm-present data prefix)))

      (= "entrar" sub)
      (let [raw (str/lower-case (str (or (first resto) "auto")))
            auto? (or (= "auto" raw) (str/blank? (str (first resto))))
            idx (when-not auto?
                  (let [n (js/parseInt raw 10)]
                    (when-not (js/isNaN n) (dec n))))]
        (when (and (not auto?) (or (nil? idx) (neg? idx)))
          (throw (erro-contrato "❓ Use !pk raide entrar auto ou !pk raide entrar <n>")))
        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "raid-join:" (if auto? "auto" idx)))
                data (desempenho/medir! ctx "pokemon_jvm_raid_join"
                       #(.joinRaid jvm
                          (clj->js (cond-> {:chatId chat-id
                                            :playerId player-id
                                            :saveId (.-activeSaveId ativo)
                                            :fenceGeneration (.-fenceGeneration ativo)
                                            :operationId op-id
                                            :nome player-name}
                                     auto? (assoc :auto true)
                                     (not auto?) (assoc :collectionIndex idx)))))]
          (envelope-jvm pedido (.formatRaidJoin jvm-present data prefix))))

      (= "sair" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-leave")
              _ (desempenho/medir! ctx "pokemon_jvm_raid_leave"
                  #(.leaveRaid jvm
                     #js {:chatId chat-id
                          :playerId player-id
                          :saveId (.-activeSaveId ativo)
                          :fenceGeneration (.-fenceGeneration ativo)
                          :operationId op-id}))]
        (envelope-jvm pedido (.formatRaidLeave jvm-present)))

      (= "iniciar" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-start")
              data (desempenho/medir! ctx "pokemon_jvm_raid_start"
                     #(.startRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))]
        (envelope-jvm pedido (.formatRaidStart jvm-present data prefix)))

      (= "atacar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not raid
                  (throw (erro-contrato "❓ Nenhuma raide ativa neste chat.")))
              _ (when-not (.-suaVez raid)
                  (throw (erro-contrato "⏳ Não é a sua vez de atacar.")))
              move (js/parseInt (or (first resto) "1") 10)
              move-idx (if (js/isNaN move) 0 (max 0 (dec move)))
              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido)
                                        (str "raid-atk:" (.-raidId raid) ":"
                                             (.-turnIndex raid) ":" move-idx))
              data (desempenho/medir! ctx "pokemon_jvm_raid_attack"
                     #(.raidAttack jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id
                             :raidId (.-raidId raid)
                             :expectedTurnIndex (.-turnIndex raid)
                             :moveIndex move-idx}))]
        (envelope-jvm pedido (.formatRaidAttack jvm-present data prefix)))

      (= "capturar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not (and raid (.-capturaPendente raid))
                  (throw (erro-contrato "❓ Captura de raide indisponível agora.")))
              bola (str/join " " resto)
              _ (when (str/blank? bola)
                  (throw (erro-contrato
                          (str "❓ Use " prefix " raide capturar <pokebola|grande-bola|ultra-bola>"))))
              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido)
                                        (str "raid-cap:" (.-raidId raid) ":" bola))
              data (desempenho/medir! ctx "pokemon_jvm_raid_capture"
                     #(.raidCapture jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id
                             :raidId (.-raidId raid)
                             :bola bola}))
              img (render-seguro! ctx "pokemon_jvm_media_raid_cap"
                    #(.renderCaptureBall jvm-media
                        #js {:bola (or (.-bola data) "pokebola")
                             :capturou (boolean (.-capturou data))
                             :fugiu false}))]
        (envelope-jvm pedido (.formatRaidCapture jvm-present data prefix) img))

      :else
      (envelope-jvm pedido
                    (.formatUnavailable jvm-present
                                        (str "raide " (str/join " " args-raid)))))))

(defn- lado-pvp
  "Retorna \"x\"|\"o\"|nil a partir de jogadores no DTO da batalha."
  [battle player-id]
  (let [jog (some-> battle .-jogadores)]
    (cond
      (and jog (= player-id (.-x jog))) "x"
      (and jog (= player-id (.-o jog))) "o"
      :else nil)))

(defn- confirmar-turno-pvp!
  [jvm ctx pedido chat-id player-id battle
   {:keys [action move-index item-id]}]
  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
          op-suffix (cond
                      (= action "attack") (str "atk:" (or move-index 0))
                      (= action "defend") "def"
                      (= action "item") (str "item:" (or item-id "?"))
                      :else (str action))
          op-id (.stableOperationId jvm-transport
                                    (:requestId pedido)
                                    (str "pvp-turn:"
                                         (.-battleId battle) ":"
                                         (or (.-turnIndex battle) 0) ":"
                                         op-suffix))
          body (clj->js (cond-> {:chatId chat-id
                                 :playerId player-id
                                 :saveId (.-activeSaveId ativo)
                                 :fenceGeneration (.-fenceGeneration ativo)
                                 :operationId op-id
                                 :battleId (.-battleId battle)
                                 :expectedTurnIndex (or (.-turnIndex battle) 0)
                                 :action action}
                          (some? move-index) (assoc :moveIndex move-index)
                          (some? item-id) (assoc :itemId item-id)))
          data (desempenho/medir! ctx "pokemon_jvm_pvp_turn"
                 #(.confirmPvpTurn jvm body))
          b (.-battle data)
          side (lado-pvp b player-id)
          texto (.formatPvpBattle jvm-present b (str config/prefix "pk")
                                  #js {:mySide side})]
    (envelope-jvm pedido texto)))

(defn- confirmar-turno-encontro!
  "Turno de caça/ginásio: attack | defend | item. TX JVM antes da mídia."
  [jvm ctx pedido chat-id player-id encontro
   {:keys [action move-index item-id]}]
  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
          op-suffix (cond
                      (= action "attack") (str "atk:" (or move-index 0))
                      (= action "defend") "def"
                      (= action "item") (str "item:" (or item-id "?"))
                      :else (str action))
          op-id (.stableOperationId jvm-transport
                                    (:requestId pedido)
                                    (str "battle-turn:"
                                         (.-battleId encontro) ":"
                                         (.-turnIndex encontro) ":"
                                         op-suffix))
          body (clj->js (cond-> {:chatId chat-id
                                 :playerId player-id
                                 :saveId (.-activeSaveId ativo)
                                 :fenceGeneration (.-fenceGeneration ativo)
                                 :operationId op-id
                                 :battleId (.-battleId encontro)
                                 :expectedTurnIndex (.-turnIndex encontro)
                                 :action action}
                          (some? move-index) (assoc :moveIndex move-index)
                          (some? item-id) (assoc :itemId item-id)))
          data (desempenho/medir! ctx "pokemon_jvm_battle_turn"
                 #(.confirmBattleTurn jvm body))
          texto (.formatBattleTurn jvm-present data (str config/prefix "pk"))
          b (.-battle data)
          fugiu? (= "flee" (or (.-outcome data) (some-> b .-outcome)))
          img (render-seguro! ctx "pokemon_jvm_media_turn"
                #(.renderHuntArena jvm-media
                    #js {:area (or (some-> b .-area) (.-area encontro))
                         :pokemonX (or (some-> b .-pokemonX) (.-pokemonX encontro))
                         :pokemonO (or (some-> b .-pokemonO) (.-pokemonO encontro))
                         :fugiu fugiu?
                         :aguardandoCaptura
                         (boolean (or (.-aguardandoCaptura data)
                                      (some-> b .-aguardandoCaptura)))
                         :caption texto
                         :efeito (.efeitoDeTurno jvm-media data)
                         :mostrarEntrada false}))]
    (envelope-jvm pedido texto img)))

(defn- executar-missoes-jvm!
  "Consulta e resgate de missões; período, prêmio e estado pertencem ao Core."
  [jvm ctx pedido chat-id player-id resto]
  (let [prefix (str config/prefix "pk")
        primeiro (str/lower-case (str (or (first resto) "")))
        diarias? (= "diarias" primeiro)
        semanais? (= "semanais" primeiro)
        acao (if diarias? (second resto) (first resto))
        scope (cond semanais? "weekly" diarias? "daily" :else "all")]
    (p/let [missoes (desempenho/medir! ctx "pokemon_jvm_missions"
                         #(.getMissions jvm #js {:chatId chat-id :playerId player-id}))]
      (if-not (= "resgatar" (str/lower-case (str (or acao ""))))
        (envelope-jvm pedido (.formatMissions jvm-present missoes scope prefix))
        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                weekly? semanais?
                period (if weekly? (.-period (.-weekly missoes)) (.-period (.-daily missoes)))
                op-id (.stableOperationId jvm-transport (:requestId pedido)
                                            (str "mission-claim:" (if weekly? "weekly:" "daily:") period))
                data (desempenho/medir! ctx "pokemon_jvm_mission_claim"
                       #(if weekly?
                          (.claimWeeklyMissions jvm #js {:chatId chat-id :playerId player-id
                                                         :saveId (.-activeSaveId ativo)
                                                         :fenceGeneration (.-fenceGeneration ativo)
                                                         :operationId op-id :period period})
                          (.claimDailyMissions jvm #js {:chatId chat-id :playerId player-id
                                                        :saveId (.-activeSaveId ativo)
                                                        :fenceGeneration (.-fenceGeneration ativo)
                                                        :operationId op-id :period period})))]
          (envelope-jvm pedido (.formatMissionClaim jvm-present data
                                                     (if weekly? "weekly" "daily") prefix)))))))

(defn- executar-presente-jvm!
  [jvm ctx pedido chat-id player-id]
  (let [target (first (get-in pedido [:context :mentionedIds]))]
    (when (str/blank? (str target))
      (throw (erro-contrato (str "🎁 Use " config/prefix "presente @amigo."))))
    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
            op-id (.stableOperationId jvm-transport (:requestId pedido) (str "gift-send:" target))
            data (desempenho/medir! ctx "pokemon_jvm_gift_send"
                   #(.sendGift jvm #js {:chatId chat-id :playerId player-id
                                         :saveId (.-activeSaveId ativo)
                                         :fenceGeneration (.-fenceGeneration ativo)
                                         :operationId op-id :targetPlayerId target}))]
      (envelope-jvm pedido (.formatGift jvm-present data (str config/prefix "presente"))))))

(defn- executar-jvm! [message ctx pedido args]
  ;; Identidade só do contexto WhatsApp; formatação/mídia só no Node.
  ;; Gameplay (JVM) completa antes de qualquer composição de imagem.
  (let [jvm (cliente-jvm!)
        chat-id (:chatId pedido)
        player-id (:playerId pedido)
        player-name (:playerName pedido)
        primeiro (str (or (first args) ""))
        ;; !pk 1‥4 → atacar com o índice preservado em resto.
        primeiro-arg (str/lower-case (str (or (first args) "")))
        trade? (= "troca" primeiro-arg)
        ;; A negociação só é exposta por !pk troca. O token já vem nos args
        ;; recebidos de pokemon/jogar e não integra o contrato HTTP.
        trade-args (if trade?
                     (vec (rest args))
                     args)
        cmd (when-not trade? (.resolveCommand jvm-present (clj->js args)))
        resto (if (re-matches #"[1-4]" primeiro)
                [primeiro]
                (vec (rest args)))]
    (if trade?
      (p/let [envelope
              (let [sub (str/lower-case (str (or (first trade-args) "")))
            rest-args (vec (rest trade-args))
            op-id (fn [suffix] (.stableOperationId jvm-transport (:requestId pedido) suffix))
            ativo! #(.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
            col! #(.getCollection jvm #js {:chatId chat-id :playerId player-id})
            indice! (fn [texto col]
                       (let [arr (or (.-collection col) #js [])
                             idx (parse-indice-1based texto (alength arr))]
                         (when (nil? idx) (throw (erro-contrato "❓ Índice de Pokémon inválido.")))
                         (let [p (aget arr idx)]
                           (or (.-pokemonId p) (.-id p) (.-uuid p)))))
            body-op (fn [action trade-id]
                      (p/let [a (ativo!)]
                        #js {:chatId chat-id :playerId player-id :tradeId trade-id
                             :saveId (.-activeSaveId a) :fenceGeneration (.-fenceGeneration a)
                             :operationId (op-id action)}))]
        (cond
          (str/blank? sub)
          (p/let [data (.listTrades jvm #js {:chatId chat-id :playerId player-id})
                  trades (or (.-trades data) #js [])]
            (envelope-jvm pedido (if (zero? (alength trades))
                                  "🤝 Nenhuma proposta pendente."
                                  (str "🤝 Propostas pendentes: " (alength trades)
                                       ". Use !pk troca aceitar <id> ou !pk troca cancelar <id>."))))
          (= sub "aceitar")
          (p/let [b (body-op "trade-accept" (first rest-args))
                  data (.acceptTrade jvm b)] (envelope-jvm pedido (str "✅ Proposta aceita: " (.-tradeId data))))
          (= sub "confirmar")
          (p/let [b (body-op "trade-complete" (first rest-args))
                  listed (.listTrades jvm #js {:chatId chat-id :playerId player-id})
                  trades (array-seq (or (.-trades listed) #js []))
                  found (some #(when (= (str (or (.-tradeId %) (.-id %))) (str (first rest-args))) %) trades)]
            (when found
              (aset b "pokemonA" (or (.-pokemonA found) (.-pokemon-a found)))
              (aset b "pokemonB" (or (.-pokemonB found) (.-pokemon-b found))))
            (let [data (.completeTrade jvm b)]
              (p/then data
                      (fn [result]
                        (let [evolucoes (array-seq (or (.-evolutions result) #js []))
                              detalhe (when (seq evolucoes)
                                        (str " Evoluíram: " (str/join ", " evolucoes)))]
                          (envelope-jvm pedido
                                        (str "🤝 Troca concluída: "
                                             (or (.-tradeId result) (first rest-args))
                                             detalhe)))))))
          (= sub "recusar")
          (p/let [b (body-op "trade-refuse" (first rest-args))
                  data (.refuseTrade jvm b)] (envelope-jvm pedido (str "🚫 Proposta recusada: " (.-tradeId data))))
          (= sub "cancelar")
          (p/let [b (body-op "trade-cancel" (first rest-args))
                  data (.cancelTrade jvm b)] (envelope-jvm pedido (str "🚫 Proposta cancelada: " (.-tradeId data))))
          (and (= 3 (count trade-args)) (re-matches #"[1-9][0-9]*" (first trade-args)))
          (let [[meu outro menc] trade-args
                mentions (vec (get-in pedido [:context :mentionedIds]))
                target (first mentions)]
            (when-not (= 1 (count mentions))
              (throw (erro-contrato "❓ Mencione exatamente um treinador.")))
            (p/let [a (ativo!) ca (col!) at (.getActiveSave jvm #js {:chatId chat-id :playerId target})
                    ct (.getCollection jvm #js {:chatId chat-id :playerId target})
                    pokemon-a (indice! meu ca) pokemon-b (indice! outro ct)
                    data (.createTrade jvm #js {:chatId chat-id :playerId player-id :playerA player-id :playerB target
                                                :saveA (.-activeSaveId a) :saveB (.-activeSaveId at)
                                                :fenceGeneration (.-fenceGeneration a)
                                                :fenceA (.-fenceGeneration a) :fenceB (.-fenceGeneration at)
                                                :pokemonA pokemon-a :pokemonB pokemon-b
                                                :operationId (op-id (str "trade-create:" target ":" meu ":" outro))})]
              (envelope-jvm pedido (str "🤝 Proposta criada: " (or (.-tradeId data) (.-id data))))))
          :else (throw (erro-contrato "❓ Use !pk troca <meu índice> <índice do outro> @treinador ou !pk troca aceitar|confirmar|recusar|cancelar <id>."))))]
        (entregar-envelope-jvm! message ctx pedido envelope))
      (if-not cmd
      (entregar-envelope-jvm!
       message ctx pedido
       (envelope-jvm pedido (.formatUnavailable jvm-present (str/join " " args))))
      (p/let [envelope
              (case cmd
                "amizade"
                (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                        texto (first resto)
                        idx (parse-indice-1based (or texto "1") 999999)
                        data (.getFriendship jvm #js {:chatId chat-id :playerId player-id
                                                      :saveId (.-activeSaveId ativo) :collectionIndex idx})]
                  (envelope-jvm pedido (.formatFriendship jvm-present data)))

                "titulo"
                (let [sub (str/lower-case (str (or (first resto) "")))]
                  (if (re-matches #"[0-9]+" sub)
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            data (.selectTitle jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId ativo)
                                                        :fenceGeneration (.-fenceGeneration ativo)
                                                        :titleIndex (dec (js/parseInt sub 10))
                                                        :operationId (.stableOperationId jvm-transport (:requestId pedido) (str "title:" sub))})]
                      (envelope-jvm pedido (str "🏅 Título selecionado: " (or (.-label data) (.-title data)))))
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            data (.getTitles jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId ativo)})]
                      (envelope-jvm pedido (.formatTitles jvm-present data)))))

                "professor"
                (let [sub (str/lower-case (str (or (first resto) ""))) restp (vec (rest resto))]
                  (cond
                    (= sub "cartoes")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.getProfessorCards jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)})]
                      (envelope-jvm pedido (str "🎓 Cartões: " (pr-str (js->clj (.-cards d))))))
                    (= sub "cancelar")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.professorCancel jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                         :fenceGeneration (.-fenceGeneration a) :operationId (.stableOperationId jvm-transport (:requestId pedido) "prof-cancel")})]
                      (envelope-jvm pedido "✅ Envio ao professor cancelado."))
                    (= sub "confirmar")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.professorConfirm jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                          :fenceGeneration (.-fenceGeneration a) :token (first restp)
                                                          :now (js/Date.now) :operationId (.stableOperationId jvm-transport (:requestId pedido) "prof-confirm")})]
                      (envelope-jvm pedido (.formatProfessor jvm-present d)))
                    (= sub "usar")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.professorUse jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                      :fenceGeneration (.-fenceGeneration a) :collectionIndex (dec (js/parseInt (first restp) 10))
                                                      :operationId (.stableOperationId jvm-transport (:requestId pedido) (str "prof-use:" (first restp)))})]
                      (envelope-jvm pedido (.formatProfessor jvm-present d)))
                    :else (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                  d (.professorSend jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                             :fenceGeneration (.-fenceGeneration a) :collectionIndex (dec (js/parseInt (first restp) 10))
                                                             :now (js/Date.now) :operationId (.stableOperationId jvm-transport (:requestId pedido) (str "prof-send:" (first restp)))})]
                             (envelope-jvm pedido (.formatProfessor jvm-present d)))))

                "treinador"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_trainer"
                                #(.getTrainer jvm #js {:chatId chat-id :playerId player-id}))
                        texto (.formatTrainer jvm-present data player-name)
                        ativo (.-ativo data)
                        img (when ativo
                              (render-seguro! ctx "pokemon_jvm_media_trainer"
                                #(.renderSpriteCard jvm-media ativo
                                                    #js {:filename "treinador-ativo.png"
                                                         :size 280})))]
                  (envelope-jvm pedido texto img))

                "time"
                (let [prefix (str config/prefix "pk")
                      marcadores-txt #{"txt" "texto"}
                      modo-texto? (boolean (some marcadores-txt resto))
                      resto* (vec (remove marcadores-txt resto))]
                  (cond
                    (= "ativo" (first resto*))
                    (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                                   #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                            idx (long (or (.-ativoIndex col) 0))
                            total (alength (or (.-collection col) #js []))
                            ficha (when (pos? total)
                                    (desempenho/medir! ctx "pokemon_jvm_ficha"
                                      #(.getPokemonFicha jvm
                                         #js {:chatId chat-id
                                              :playerId player-id
                                              :collectionIndex idx})))
                            texto (if ficha
                                    (.formatFicha jvm-present ficha prefix)
                                    (str "❓ Sem Pokémon ativo. " prefix " time"))
                            poke (when ficha (.-pokemon ficha))
                            img (when poke
                                  (render-seguro! ctx "pokemon_jvm_media_ficha"
                                    #(.renderSpriteCard jvm-media poke
                                                        #js {:filename "ficha-ativo.png"
                                                             :size 320})))]
                      (envelope-jvm pedido texto img))

                    :else
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_collection"
                                    #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                            texto (.formatCollection jvm-present data (clj->js resto*) prefix)
                            page (.collectionPageSlice jvm-present data (clj->js resto*))
                            slice (.-slice page)
                            img (when (and (not modo-texto?)
                                           slice
                                           (pos? (alength slice)))
                                  (render-seguro! ctx "pokemon_jvm_media_time"
                                    #(.renderTeamGrid jvm-media slice
                                                      #js {:filename "meu-time-pokemon.png"
                                                           :trainerLevel (or (.-trainerLevel data) 1)
                                                           :ativoIndex (or (.-ativoIndex data) 0)})))]
                      (envelope-jvm pedido texto img))))

                "shiny"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_shiny"
                                #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido (.formatCollection jvm-present data #js ["shiny"] (str config/prefix "pk"))))

                "pc"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_storage"
                                #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido (.formatStorage jvm-present data (clj->js resto) (str config/prefix "pk"))))

                "espaco"
                (if (= "comprar" (str/lower-case (str (or (first resto) ""))))
                  (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                          data (.buyItem jvm #js {:chatId chat-id :playerId player-id
                                                  :saveId (.-activeSaveId a)
                                                  :fenceGeneration (.-fenceGeneration a)
                                                  :operationId (.stableOperationId jvm-transport (:requestId pedido) "space-buy")
                                                  :itemId "mochila" :qty 1})]
                    (envelope-jvm pedido (str "✅ +50 vagas Pokémon compradas. "
                                               "Consulte " config/prefix " espaco.")))
                  (p/let [data (desempenho/medir! ctx "pokemon_jvm_space"
                                  #(.getShop jvm #js {:chatId chat-id :playerId player-id}))]
                    (envelope-jvm pedido (.formatSpace jvm-present data (str config/prefix "pk")))))

                "times"
                (envelope-jvm pedido
                              (str "👥 Escalações salvas ainda dependem do armazenamento legado. Use "
                                   config/prefix "pk time para consultar a coleção canônica."))

                "pokedex"
                (let [prefix (str config/prefix "pk")
                      sub (str/lower-case (str (or (first resto) "")))]
                  (cond
                    (= "descobertas" sub)
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_pokedex"
                                    #(.getPokedex jvm #js {:chatId chat-id :playerId player-id}))]
                      (envelope-jvm pedido (.formatDiscoveries jvm-present data prefix)))

                    (re-matches #"[0-9]+" sub)
                    (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                                   #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                            total (alength (or (.-collection col) #js []))
                            idx (parse-indice-1based sub total)]
                      (if (nil? idx)
                        (envelope-jvm pedido
                                      (str "❓ Escolha um número válido: " prefix " pokedex <1-"
                                           (max 1 total) "> (veja com " prefix " time)."))
                        (p/let [ficha (desempenho/medir! ctx "pokemon_jvm_ficha"
                                         #(.getPokemonFicha jvm
                                            #js {:chatId chat-id
                                                 :playerId player-id
                                                 :collectionIndex idx}))
                                texto (.formatFicha jvm-present ficha prefix)
                                poke (.-pokemon ficha)
                                img (when poke
                                      (render-seguro! ctx "pokemon_jvm_media_ficha"
                                        #(.renderSpriteCard jvm-media poke
                                                            #js {:filename "ficha-pokemon.png"
                                                                 :size 320})))]
                          (envelope-jvm pedido texto img))))

                    :else
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_pokedex"
                                    #(.getPokedex jvm #js {:chatId chat-id :playerId player-id}))]
                      (envelope-jvm pedido
                                    (.formatPokedex jvm-present data (clj->js resto) prefix)))))

                "favorito"
                (let [prefix (str config/prefix "pk")
                      raw (str/lower-case (str (or (first resto) "")))]
                  (cond
                    (contains? #{"remover" "rm" "tirar" "clear"} raw)
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      "favorite-clear")
                            data (desempenho/medir! ctx "pokemon_jvm_favorite"
                                   #(.setFavoritePokemon jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id
                                           :clear true}))]
                      (envelope-jvm pedido (.formatFavorite jvm-present
                                                            #js {:clear true} prefix)))

                    (re-matches #"[0-9]+" raw)
                    (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                                   #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                            total (alength (or (.-collection col) #js []))
                            idx (parse-indice-1based raw total)]
                      (if (nil? idx)
                        (envelope-jvm pedido
                                      (str "❓ Escolha um número válido: " prefix " favorito <1-"
                                           (max 1 total) ">."))
                        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                op-id (.stableOperationId jvm-transport
                                                          (:requestId pedido)
                                                          (str "favorite:" idx))
                                data (desempenho/medir! ctx "pokemon_jvm_favorite"
                                       #(.setFavoritePokemon jvm
                                          #js {:chatId chat-id
                                               :playerId player-id
                                               :saveId (.-activeSaveId ativo)
                                               :fenceGeneration (.-fenceGeneration ativo)
                                               :operationId op-id
                                               :collectionIndex idx}))]
                          (envelope-jvm pedido (.formatFavorite jvm-present data prefix)))))

                    :else
                    (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                                   #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                            fav (.-favorito col)]
                      (envelope-jvm pedido
                                    (.formatFavorite jvm-present
                                                     (if fav #js {:atual fav} #js {})
                                                     prefix)))))

                "save"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_active"
                                #(.getActiveSave jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido (.formatActiveSave jvm-present data)))

                "saves"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_saves"
                                #(.listSaves jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido (.formatSavesList jvm-present data)))

                "menu"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_onboarding"
                                #(.getOnboarding jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido
                                (.formatMainMenu jvm-present data (str config/prefix "pk"))))

                "iniciar"
                (p/let [op-id (.stableOperationId jvm-transport
                                                  (:requestId pedido)
                                                  "onboard-start")
                        data (desempenho/medir! ctx "pokemon_jvm_onboard_start"
                               #(.startJourney jvm
                                  #js {:chatId chat-id
                                       :playerId player-id
                                       :operationId op-id}))
                        onboard (.-onboarding data)
                        starters (when onboard (.-starters onboard))
                        img (when (and starters
                                       (pos? (alength starters))
                                       (= "awaiting_starter" (some-> onboard .-status)))
                              (render-seguro! ctx "pokemon_jvm_media_starters"
                                #(.renderStartersCard jvm-media starters
                                                      #js {:filename "iniciais.png"})))]
                  (envelope-jvm pedido
                                (.formatStartJourney jvm-present data (str config/prefix "pk"))
                                img))

                "inicial"
                (let [choice (str/join " " resto)]
                  (if (str/blank? choice)
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_onboarding"
                                    #(.getOnboarding jvm #js {:chatId chat-id :playerId player-id}))
                            starters (.-starters data)
                            img (when (and starters
                                           (pos? (alength starters))
                                           (not= "completed" (.-status data)))
                                  (render-seguro! ctx "pokemon_jvm_media_starters"
                                    #(.renderStartersCard jvm-media starters
                                                          #js {:filename "iniciais.png"})))]
                      (if (= "completed" (.-status data))
                        (envelope-jvm pedido
                                      (.formatStarterChosen jvm-present
                                                            #js {:starterAlreadyChosen true}
                                                            (str config/prefix "pk")))
                        (envelope-jvm pedido
                                      (.formatStartersMenu jvm-present data
                                                           (str config/prefix "pk"))
                                      img)))
                    (p/let [op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      (str "onboard-starter:" choice))
                            data (desempenho/medir! ctx "pokemon_jvm_onboard_starter"
                                   #(.chooseStarter jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :operationId op-id
                                           :choice choice}))
                            img (when (.-imagem data)
                                  (render-seguro! ctx "pokemon_jvm_media_starter_ok"
                                    #(.renderSpriteCard jvm-media
                                                        #js {:nome (or (.-nome data) (.-species data))
                                                             :imagem (.-imagem data)
                                                             :nivel 1}
                                                        #js {:filename "inicial-escolhido.png"
                                                             :size 280})))]
                      (envelope-jvm pedido
                                    (.formatStarterChosen jvm-present data
                                                          (str config/prefix "pk"))
                                    img))))

                "ativar"
                (let [slot (first resto)]
                  (when-not (contains? #{"a" "b"} slot)
                    (throw (erro-contrato "Use: !pk ativar a|b")))
                  (p/let [antes (.listSaves jvm #js {:chatId chat-id :playerId player-id})
                          data (desempenho/medir! ctx "pokemon_jvm_activate"
                                 #(.activateSave jvm
                                    #js {:chatId chat-id
                                         :playerId player-id
                                         :saveId slot
                                         :expectedFenceGeneration (.-fenceGeneration antes)}))]
                    (envelope-jvm pedido (.formatActivate jvm-present data))))

                "recibo"
                (let [op-id (first resto)]
                  (when (str/blank? op-id)
                    (throw (erro-contrato "Use: !pk recibo <operationId>")))
                  (p/let [data (desempenho/medir! ctx "pokemon_jvm_receipt"
                                  #(.getOperation jvm
                                     #js {:operationId op-id
                                          :chatId chat-id
                                          :playerId player-id}))]
                    (envelope-jvm pedido (.formatReceipt jvm-present data))))

                "missoes"
                (executar-missoes-jvm! jvm ctx pedido chat-id player-id resto)

                "presente"
                (executar-presente-jvm! jvm ctx pedido chat-id player-id)

                "ginasio"
                (let [sub (.resolveGymSubcommand jvm-present (or (first resto) ""))
                      args-gin (vec (rest resto))]
                  (cond
                    (or (nil? sub) (str/blank? (str sub))
                        (contains? #{"pedra" "agua" "eletrico" "elétrico" "planta" "fogo"}
                                   (str/lower-case (str (or (first resto) "")))))
                    (let [gym-id (when (seq resto)
                                   (str/lower-case
                                    (-> (str/join " " resto)
                                        (str/replace #"elétrico" "eletrico"))))]
                      (if (and gym-id (contains? #{"pedra" "agua" "eletrico" "planta" "fogo"} gym-id))
                        (p/let [data (desempenho/medir! ctx "pokemon_jvm_gym_detail"
                                       #(.getGym jvm #js {:chatId chat-id
                                                          :playerId player-id
                                                          :gymId gym-id}))]
                          (envelope-jvm pedido
                                        (.formatGymDetail jvm-present data
                                                          (str config/prefix "pk"))))
                        (p/let [data (desempenho/medir! ctx "pokemon_jvm_gyms"
                                       #(.listGyms jvm #js {:chatId chat-id
                                                            :playerId player-id}))]
                          (envelope-jvm pedido
                                        (.formatGymsOverview jvm-present data
                                                             (str config/prefix "pk"))))))

                    (= "desafiar" sub)
                    (let [gym-id (str/lower-case
                                  (-> (str (or (first args-gin) ""))
                                      (str/replace #"elétrico" "eletrico")))]
                      (when-not (contains? #{"pedra" "agua" "eletrico" "planta" "fogo"} gym-id)
                        (throw (erro-contrato
                                "❓ Use !pk gin desafiar <pedra|agua|eletrico|planta|fogo>")))
                      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                                          #(.getActiveRaid jvm #js {:chatId chat-id
                                                                    :playerId player-id}))
                              raid (.-raid raid-data)]
                        (if (and raid (= gym-id (str (.-ginasio raid)))
                                 (= "inscricoes" (str (.-fase raid))))
                          ;; Legado: desafiar com raide no ginásio → entrar auto.
                          (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id
                                                                 :playerId player-id})
                                  op-id (.stableOperationId jvm-transport
                                                            (:requestId pedido)
                                                            (str "raid-join:" (.-raidId raid) ":auto"))
                                  data (desempenho/medir! ctx "pokemon_jvm_raid_join"
                                         #(.joinRaid jvm
                                            #js {:chatId chat-id
                                                 :playerId player-id
                                                 :saveId (.-activeSaveId ativo)
                                                 :fenceGeneration (.-fenceGeneration ativo)
                                                 :operationId op-id
                                                 :raidId (.-raidId raid)
                                                 :auto true
                                                 :nome player-name}))]
                            (envelope-jvm pedido
                                          (.formatRaidJoin jvm-present data
                                                           (str config/prefix "pk"))))
                          (p/let [detail (desempenho/medir! ctx "pokemon_jvm_gym_detail"
                                           #(.getGym jvm #js {:chatId chat-id
                                                              :playerId player-id
                                                              :gymId gym-id}))
                                  gym (.-gym detail)
                                  _ (when-not (.-desbloqueado gym)
                                      (throw (erro-contrato "🔒 Vença os ginásios anteriores primeiro.")))
                                  ocup (.-ocupacao gym)
                                  cands (if ocup
                                          #js []
                                          (desempenho/medir! ctx "pokemon_jvm_gym_npc"
                                            #(.buildGymNpcTeam pokeapi (.-timeNpc gym))))
                                  ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                  op-id (.stableOperationId jvm-transport
                                                            (:requestId pedido)
                                                            (str "gym-challenge:" gym-id))
                                  data (desempenho/medir! ctx "pokemon_jvm_gym_challenge"
                                         #(.startGymChallenge jvm
                                            #js {:chatId chat-id
                                                 :playerId player-id
                                                 :saveId (.-activeSaveId ativo)
                                                 :fenceGeneration (.-fenceGeneration ativo)
                                                 :operationId op-id
                                                 :gymId gym-id
                                                 :candidates cands
                                                 :nome player-name}))
                                  texto (.formatGymChallengeStart jvm-present data
                                                                  (str config/prefix "pk"))
                                  battle (.-battle data)
                                  img (render-seguro! ctx "pokemon_jvm_media_gym"
                                        #(.renderHuntArena jvm-media
                                            #js {:area nil
                                                 :pokemonX (some-> battle .-pokemonX)
                                                 :pokemonO (some-> battle .-pokemonO)
                                                 :fugiu false
                                                 :aguardandoCaptura false
                                                 :caption texto
                                                 :mostrarEntrada true}))]
                            (envelope-jvm pedido texto img)))))

                    (contains? #{"ranking" "historico"} sub)
                    (envelope-jvm pedido (.formatGymBlocked jvm-present sub))

                    (contains? #{"entrar" "iniciar" "sair" "cancelar" "atacar" "capturar"} sub)
                    ;; Mesmos fluxos que !pk raide <sub> …
                    (p/let [args-raid (into [sub] args-gin)
                            env (executar-raid-jvm! jvm ctx pedido chat-id player-id
                                                    player-name args-raid)]
                      env)

                    (= "time" sub)
                    (let [raw (str/trim (str/join " " args-gin))]
                      (cond
                        (str/blank? raw)
                        (p/let [data (desempenho/medir! ctx "pokemon_jvm_gym_team"
                                       #(.getGymTeam jvm #js {:chatId chat-id
                                                              :playerId player-id}))]
                          (envelope-jvm pedido
                                        (.formatGymTeam jvm-present data
                                                         (str config/prefix "pk"))))

                        (= "auto" (str/lower-case raw))
                        (p/let [col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                                items (vec (js->clj (.-collection col) :keywordize-keys true))
                                saudaveis (->> items
                                               (filter #(pos? (long (or (:hp-atual %)
                                                                        (:hpAtual %) 0))))
                                               (take 3)
                                               (mapv #(long (or (:collection-index %)
                                                                (:collectionIndex %) 0))))
                                ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                _ (when-not (= 3 (count saudaveis))
                                    (throw (erro-contrato
                                            "❓ Precisa de 3 Pokémon saudáveis: !pk gin time 1,2,3")))
                                data (desempenho/medir! ctx "pokemon_jvm_gym_team_set"
                                       #(.setGymTeam jvm
                                          #js {:chatId chat-id
                                               :playerId player-id
                                               :saveId (.-activeSaveId ativo)
                                               :fenceGeneration (.-fenceGeneration ativo)
                                               :indices (clj->js saudaveis)}))]
                          (envelope-jvm pedido
                                        (.formatGymTeamSaved jvm-present data
                                                             (str config/prefix "pk"))))

                        :else
                        (let [nums (->> (str/split raw #"[,\s]+")
                                        (remove str/blank?)
                                        (mapv #(js/parseInt % 10))
                                        (mapv #(dec %)))]
                          (when-not (and (= 3 (count nums))
                                         (every? #(and (integer? %) (not (js/isNaN %)) (>= % 0))
                                                 nums))
                            (throw (erro-contrato
                                    "❓ Use !pk gin time auto ou !pk gin time 1,2,3")))
                          (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id
                                                                :playerId player-id})
                                  data (desempenho/medir! ctx "pokemon_jvm_gym_team_set"
                                         #(.setGymTeam jvm
                                            #js {:chatId chat-id
                                                 :playerId player-id
                                                 :saveId (.-activeSaveId ativo)
                                                 :fenceGeneration (.-fenceGeneration ativo)
                                                 :indices (clj->js nums)}))]
                            (envelope-jvm pedido
                                          (.formatGymTeamSaved jvm-present data
                                                               (str config/prefix "pk")))))))

                    (contains? #{"pocao" "fruta" "fruta-dourada"} sub)
                    (let [gym-id (str/lower-case (str (or (first args-gin) "")))
                          n-str (second args-gin)
                          indice (when (and n-str (re-matches #"[1-3]" n-str))
                                   (dec (js/parseInt n-str 10)))]
                      (when-not (contains? #{"pedra" "agua" "eletrico" "planta" "fogo"} gym-id)
                        (throw (erro-contrato "❓ Ginásio: pedra, agua, eletrico, planta ou fogo.")))
                      (when (nil? indice)
                        (throw (erro-contrato
                                (str "❓ Use !pk gin " sub " " gym-id " <1-3>"))))
                      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido)
                                                        (str "gym-heal:" gym-id ":" indice ":" sub))
                              data (desempenho/medir! ctx "pokemon_jvm_gym_heal"
                                     #(.healGym jvm
                                        #js {:chatId chat-id
                                             :playerId player-id
                                             :saveId (.-activeSaveId ativo)
                                             :fenceGeneration (.-fenceGeneration ativo)
                                             :operationId op-id
                                             :gymId gym-id
                                             :indice indice
                                             :item sub}))]
                        (envelope-jvm pedido (.formatGymHeal jvm-present data))))

                    :else
                    (envelope-jvm pedido
                                  (.formatUnavailable jvm-present
                                                      (str "ginasio " (str/join " " resto))))))

                "cacar"
                (p/let [plano (desempenho/medir! ctx "pokemon_jvm_hunt_plan"
                                 #(.getHuntPlan jvm
                                    #js {:chatId chat-id
                                         :playerId player-id
                                         :areaId (not-empty (str/join " " resto))}))
                        bloqueio (.formatHuntEligibility jvm-present plano)]
                  (if bloqueio
                    (envelope-jvm pedido bloqueio)
                    (p/let [cands (desempenho/medir! ctx "pokemon_jvm_pokeapi"
                                     #(.buildHuntCandidates pokeapi plano))
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido) "hunt-start")
                            ;; TX primeiro — falha de mídia não reexecuta start.
                            data (desempenho/medir! ctx "pokemon_jvm_hunt_start"
                                   #(.startHunt jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId plano)
                                           :fenceGeneration (.-fenceGeneration plano)
                                           :operationId op-id
                                           :areaId (.-id (.-area plano))
                                           :climaId (.-id (.-clima plano))
                                           :tiposEncontro (.-tiposEncontro plano)
                                           :surto (.-surto plano)
                                           :poderAlvo (.-poderAlvo plano)
                                           :candidates cands}))
                            texto (.formatHuntStart jvm-present plano data
                                                    (str config/prefix "pk"))
                            battle (.-battle data)
                            img (render-seguro! ctx "pokemon_jvm_media_hunt"
                                  #(.renderHuntArena jvm-media
                                      #js {:area (or (.-area data) (.-area plano))
                                           :pokemonX (some-> battle .-pokemonX)
                                           :pokemonO (or (.-selvagem data)
                                                         (some-> battle .-pokemonO))
                                           :fugiu false
                                           :aguardandoCaptura false
                                           :caption texto
                                           :mostrarEntrada true}))]
                      (envelope-jvm pedido texto img))))

                "capturar"
                (p/let [enc (desempenho/medir! ctx "pokemon_jvm_encounter"
                               #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))]
                  (if (empty? resto)
                    (envelope-jvm pedido
                                  (.formatCaptureMenu jvm-present enc
                                                      (str config/prefix "pk")))
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      (str "encounter-capture:"
                                                           (str/join "-" resto)))
                            data (desempenho/medir! ctx "pokemon_jvm_capture"
                                   #(.attemptEncounterCapture jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id
                                           :bola (str/join " " resto)
                                           :battleId (some-> (.-encounter enc) .-battleId)}))
                            texto (.formatCaptureResult jvm-present data
                                                        (str config/prefix "pk"))
                            img (render-seguro! ctx "pokemon_jvm_media_capture"
                                  #(.renderCaptureBall jvm-media
                                      #js {:bola (or (.-bola data) "pokebola")
                                           :capturou (boolean (.-capturou data))
                                           :fugiu (boolean (.-fugiu data))}))]
                      (envelope-jvm pedido texto img))))

                "atacar"
                (p/let [pvp (.getActivePvp jvm #js {:chatId chat-id})
                        battle (.-battle pvp)
                        enc (when-not (and battle (.-active battle)
                                           (= "active" (.-phase battle)))
                              (desempenho/medir! ctx "pokemon_jvm_encounter"
                                #(.getEncounter jvm #js {:chatId chat-id :playerId player-id})))
                        encontro (some-> enc .-encounter)]
                  (cond
                    (and battle (.-active battle) (= "active" (.-phase battle)))
                    (let [idx (js/parseInt (or (first resto) "1") 10)
                          move-idx (if (js/isNaN idx) 0 (max 0 (dec idx)))]
                      (confirmar-turno-pvp!
                       jvm ctx pedido chat-id player-id battle
                       {:action "attack" :move-index move-idx}))

                    (nil? encontro)
                    (envelope-jvm pedido "❓ Não há caçada, ginásio ou PvP em andamento.")

                    (.-aguardandoCaptura encontro)
                    (envelope-jvm pedido
                                  (str "🎯 O selvagem já está pronto para captura. Use "
                                       config/prefix "pk capturar."))

                    (not (.-active encontro))
                    (envelope-jvm pedido "❓ Esta batalha não está ativa.")

                    :else
                    (let [idx (js/parseInt (or (first resto) "1") 10)
                          move-idx (if (js/isNaN idx) 0 (max 0 (dec idx)))]
                      (confirmar-turno-encontro!
                       jvm ctx pedido chat-id player-id encontro
                       {:action "attack" :move-index move-idx}))))

                "defender"
                (p/let [pvp (.getActivePvp jvm #js {:chatId chat-id})
                        battle (.-battle pvp)
                        enc (when-not (and battle (.-active battle)
                                           (= "active" (.-phase battle)))
                              (desempenho/medir! ctx "pokemon_jvm_encounter"
                                #(.getEncounter jvm #js {:chatId chat-id :playerId player-id})))
                        encontro (some-> enc .-encounter)]
                  (cond
                    (and battle (.-active battle) (= "active" (.-phase battle)))
                    (confirmar-turno-pvp!
                     jvm ctx pedido chat-id player-id battle
                     {:action "defend"})

                    (nil? encontro)
                    (envelope-jvm pedido "❓ Não há caçada, ginásio ou PvP em andamento.")

                    (.-aguardandoCaptura encontro)
                    (envelope-jvm pedido
                                  (str "🎯 O selvagem já está pronto para captura. Use "
                                       config/prefix "pk capturar."))

                    (not (.-active encontro))
                    (envelope-jvm pedido "❓ Esta batalha não está ativa.")

                    :else
                    (confirmar-turno-encontro!
                     jvm ctx pedido chat-id player-id encontro
                     {:action "defend"})))

                "batalhar"
                (p/let [pvp (.getActivePvp jvm #js {:chatId chat-id})
                        battle (.-battle pvp)
                        prefix (str config/prefix "pk")
                        ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})]
                  (cond
                    (and battle (.-active battle) (= "active" (.-phase battle)))
                    (envelope-jvm pedido
                                  (.formatPvpBattle jvm-present battle prefix
                                                    #js {:mySide (lado-pvp battle player-id)
                                                         :playerName player-name}))

                    (and battle (.-aguardando battle))
                    (if (= player-id (some-> battle .-jogadores .-x))
                      (envelope-jvm pedido
                                    (str "⏳ Seu desafio ainda aguarda adversário.\n"
                                         (.formatPvpChallenge jvm-present battle player-name prefix)))
                      (p/let [op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido) "pvp-accept")
                              data (desempenho/medir! ctx "pokemon_jvm_pvp_accept"
                                     #(.acceptPvp jvm
                                        #js {:chatId chat-id
                                             :playerId player-id
                                             :saveId (.-activeSaveId ativo)
                                             :fenceGeneration (.-fenceGeneration ativo)
                                             :operationId op-id
                                             :playerName player-name
                                             :battleId (.-battleId battle)}))]
                        (envelope-jvm pedido
                                      (.formatPvpBattle jvm-present (.-battle data) prefix
                                                        #js {:mySide (lado-pvp (.-battle data)
                                                                               player-id)
                                                             :playerName player-name}))))

                    :else
                    (p/let [op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido) "pvp-open")
                            data (desempenho/medir! ctx "pokemon_jvm_pvp_open"
                                   #(.openPvp jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id
                                           :playerName player-name}))]
                      (envelope-jvm pedido
                                    (.formatPvpChallenge jvm-present (.-battle data)
                                                         player-name prefix)))))

                "sair"
                (p/let [pvp (.getActivePvp jvm #js {:chatId chat-id})
                        battle (.-battle pvp)
                        prefix (str config/prefix "pk")]
                  (if-not (and battle (.-active battle))
                    (envelope-jvm pedido
                                  (str "❓ Não há desafio ou batalha PvP para sair. "
                                       prefix " batalhar"))
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido) "pvp-cancel")
                            data (.cancelPvp jvm
                                   #js {:chatId chat-id
                                        :playerId player-id
                                        :saveId (.-activeSaveId ativo)
                                        :fenceGeneration (.-fenceGeneration ativo)
                                        :operationId op-id
                                        :battleId (.-battleId battle)})]
                      (envelope-jvm pedido
                                    (.formatPvpBattle jvm-present (.-battle data) prefix)))))

                "clima"
                (p/let [plano (desempenho/medir! ctx "pokemon_jvm_hunt_plan"
                                 #(.getHuntPlan jvm
                                    #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido
                                (.formatWorldInfo jvm-present plano
                                                  (str config/prefix "pk"))))

                "eventos"
                (p/let [plano (desempenho/medir! ctx "pokemon_jvm_hunt_plan"
                                 #(.getHuntPlan jvm
                                    #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido
                                (.formatEventInfo jvm-present plano
                                                  (str config/prefix "pk"))))

                "raid"
                (executar-raid-jvm! jvm ctx pedido chat-id player-id player-name resto)

                "joy"
                (executar-joy-jvm! jvm ctx pedido chat-id player-id resto)

                "ovos"
                (executar-ovos-jvm! jvm ctx pedido chat-id player-id
                                    (or (first resto) "ovos") (vec (rest resto)))

                "incubar"
                (executar-ovos-jvm! jvm ctx pedido chat-id player-id
                                    "incubar" resto)

                "incubadoras"
                (executar-ovos-jvm! jvm ctx pedido chat-id player-id
                                    "incubadoras" resto)

                "loja"
                (let [sub (str/lower-case (str (or (first resto) "")))
                      item-raw (str/join " " (rest resto))]
                  (cond
                    (or (str/blank? sub) (= "ver" sub) (= "catalogo" sub) (= "catálogo" sub))
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_shop"
                                     #(.getShop jvm #js {:chatId chat-id :playerId player-id}))]
                      (envelope-jvm pedido (.formatShop jvm-present data "!loja")))

                    (or (= "detalhes" sub) (= "detalhe" sub) (= "info" sub))
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_shop"
                                     #(.getShop jvm #js {:chatId chat-id :playerId player-id}))
                            it (.findShopItem jvm-present data item-raw)]
                      (envelope-jvm pedido (.formatShopDetails jvm-present it "!loja")))

                    (= "comprar" sub)
                    (do
                      (when (str/blank? item-raw)
                        (throw (erro-contrato "❓ Use !loja comprar <item>")))
                      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido)
                                                        (str "shop-buy:" item-raw))
                              data (desempenho/medir! ctx "pokemon_jvm_shop_buy"
                                     #(.buyItem jvm
                                        #js {:chatId chat-id
                                             :playerId player-id
                                             :saveId (.-activeSaveId ativo)
                                             :fenceGeneration (.-fenceGeneration ativo)
                                             :operationId op-id
                                             :itemId item-raw}))]
                        (envelope-jvm pedido (.formatShopBuy jvm-present data))))

                    :else
                    (envelope-jvm pedido
                                  (.formatUnavailable jvm-present
                                                      (str "loja " (str/join " " resto))))))

                "mochila"
                (let [sub (str/lower-case (str (or (first resto) "")))]
                  (cond
                    (= "resgatar" sub)
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                              "mission-pending-collect")
                            data (desempenho/medir! ctx "pokemon_jvm_mission_pending"
                                   #(.collectMissionPending jvm
                                      #js {:chatId chat-id :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id}))]
                      (envelope-jvm pedido (.formatMissionPending jvm-present data "!mochila")))

                    (contains? #{"kit" "diario" "diário"} sub)
                    (envelope-jvm pedido
                                  (str "🚧 `" sub "` ainda não está no adaptador JVM "
                                       "(kit/diário). Use !mochila para ver o inventário."))

                    :else
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_inventory"
                                     #(.getInventory jvm #js {:chatId chat-id :playerId player-id}))
                            missoes (desempenho/medir! ctx "pokemon_jvm_missions"
                                      #(.getMissions jvm #js {:chatId chat-id :playerId player-id}))]
                      (aset data "pendingRewards" (.-pendingRewards missoes))
                      (envelope-jvm pedido (.formatInventory jvm-present data "!mochila")))))

                "reviver"
                (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                        n-str (first resto)
                        idx (when-not (str/blank? (str n-str))
                              (let [n (js/parseInt n-str 10)]
                                (when-not (js/isNaN n) (dec n))))
                        op-id (.stableOperationId jvm-transport
                                                  (:requestId pedido)
                                                  (str "item-reviver:" (or idx "ativo")))
                        data (desempenho/medir! ctx "pokemon_jvm_item_use"
                               #(.useItem jvm
                                  (clj->js (cond-> {:chatId chat-id
                                                    :playerId player-id
                                                    :saveId (.-activeSaveId ativo)
                                                    :fenceGeneration (.-fenceGeneration ativo)
                                                    :operationId op-id
                                                    :itemId "reviver"}
                                             (some? idx) (assoc :collectionIndex idx)))))]
                  (envelope-jvm pedido (.formatItemUse jvm-present data
                                                       (str config/prefix "pk"))))

                "equipar"
                (let [n-str (first resto)
                      item-id (second resto)]
                  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                          n (js/parseInt n-str 10)
                          idx (when-not (js/isNaN n) (dec n))
                          op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                    (str "item-equipar:" idx ":" item-id))
                          data (.useItem jvm
                                        (clj->js {:chatId chat-id :playerId player-id
                                                  :saveId (.-activeSaveId ativo)
                                                  :fenceGeneration (.-fenceGeneration ativo)
                                                  :operationId op-id :itemId item-id
                                                  :collectionIndex idx}))]
                    (envelope-jvm pedido (.formatItemUse jvm-present data
                                                         (str config/prefix "pk")))))

                "curar"
                (p/let [enc (desempenho/medir! ctx "pokemon_jvm_encounter"
                               #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))
                        encontro (.-encounter enc)]
                  (if (and encontro (.-active encontro) (not (.-aguardandoCaptura encontro)))
                    ;; Mid-caça: status do ativo + turno do selvagem (legado curar-na-cacada).
                    (let [st (some-> encontro .-pokemonX .-status)
                          item-cura (case (str st)
                                      "queimado" "atadura"
                                      "envenenado" "antidoto"
                                      "paralisado" "paralisia"
                                      "adormecido" "despertar"
                                      "congelado" "degelo"
                                      "confuso" "persim"
                                      nil)]
                      (cond
                        (nil? st)
                        (envelope-jvm pedido
                                      (str "❓ Seu Pokémon ativo não tem status para curar.\n\n"
                                           (.formatBattleState jvm-present encontro
                                                               (str config/prefix "pk"))))
                        (nil? item-cura)
                        (envelope-jvm pedido
                                      (str "❓ Status `" st "` sem item de cura mapeado."))
                        :else
                        (confirmar-turno-encontro!
                         jvm ctx pedido chat-id player-id encontro
                         {:action "item" :item-id item-cura})))
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            n-str (first resto)
                            idx (when-not (str/blank? (str n-str))
                                  (let [n (js/parseInt n-str 10)]
                                    (when-not (js/isNaN n) (dec n))))
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      (str "item-curar:" (or idx "ativo")))
                            data (desempenho/medir! ctx "pokemon_jvm_item_use"
                                   #(.useItem jvm
                                      (clj->js (cond-> {:chatId chat-id
                                                        :playerId player-id
                                                        :saveId (.-activeSaveId ativo)
                                                        :fenceGeneration (.-fenceGeneration ativo)
                                                        :operationId op-id
                                                        :kind "curar"}
                                                 (some? idx) (assoc :collectionIndex idx)))))]
                      (envelope-jvm pedido (.formatItemUse jvm-present data
                                                           (str config/prefix "pk"))))))

                ("pocao" "pocao-maxima")
                (let [item-id (if (= cmd "pocao-maxima") "pocao-maxima" "pocao")]
                  (p/let [pvp (.getActivePvp jvm #js {:chatId chat-id})
                          pvp-b (.-battle pvp)
                          enc (when-not (and pvp-b (.-active pvp-b)
                                             (= "active" (.-phase pvp-b)))
                                (desempenho/medir! ctx "pokemon_jvm_encounter"
                                  #(.getEncounter jvm #js {:chatId chat-id :playerId player-id})))
                          encontro (some-> enc .-encounter)]
                    (cond
                      (and pvp-b (.-active pvp-b) (= "active" (.-phase pvp-b)))
                      (confirmar-turno-pvp!
                       jvm ctx pedido chat-id player-id pvp-b
                       {:action "item" :item-id item-id})

                      (and encontro (.-active encontro) (not (.-aguardandoCaptura encontro)))
                      ;; Mid-caça: só no ativo; consome turno (legado pocao-na-cacada).
                      (confirmar-turno-encontro!
                       jvm ctx pedido chat-id player-id encontro
                       {:action "item" :item-id item-id})

                      :else
                      (let [n-str (first resto)
                            idx (when-not (str/blank? (str n-str))
                                  (let [n (js/parseInt n-str 10)]
                                    (when-not (js/isNaN n) (dec n))))]
                        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                op-id (.stableOperationId jvm-transport
                                                          (:requestId pedido)
                                                          (str "item-use:" item-id ":"
                                                               (or idx "ativo")))
                                data (desempenho/medir! ctx "pokemon_jvm_item_use"
                                       #(.useItem jvm
                                          (clj->js (cond-> {:chatId chat-id
                                                            :playerId player-id
                                                            :saveId (.-activeSaveId ativo)
                                                            :fenceGeneration (.-fenceGeneration ativo)
                                                            :operationId op-id
                                                            :itemId item-id}
                                                     (some? idx)
                                                     (assoc :collectionIndex idx)))))]
                          (envelope-jvm pedido
                                        (.formatItemUse jvm-present data
                                                        (str config/prefix "pk"))))))))

                "escolher"
                (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                               #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                        total (or (.-count col) 0)
                        idx (parse-indice-1based (first resto) total)
                        prefix (str config/prefix "pk")]
                  (cond
                    (zero? total)
                    (envelope-jvm pedido (str "❓ Coleção vazia. Use " prefix " iniciar."))
                    (nil? idx)
                    (envelope-jvm pedido
                                  (str "❓ Escolha um número válido: " prefix " escolher <1-"
                                       total "> (veja com " prefix " time)."))
                    :else
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      (str "set-active:" idx))
                            data (desempenho/medir! ctx "pokemon_jvm_set_active"
                                   #(.setActivePokemon jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id
                                           :collectionIndex idx}))]
                      (envelope-jvm pedido (.formatSetActive jvm-present data prefix)))))

                "evoluir"
                (let [prefix (str config/prefix "pk")
                      idx-txt (first resto)
                      item-raw (second resto)]
                  (p/let [col (desempenho/medir! ctx "pokemon_jvm_collection"
                                 #(.getCollection jvm #js {:chatId chat-id :playerId player-id}))
                          items (vec (or (.-collection col) #js []))
                          idx (parse-indice-1based idx-txt (count items))
                          poke (when (some? idx) (nth items idx nil))
                          slug (some-> poke .-species str/lower-case)
                          item-id (when item-raw (.normalizarItem pokeapi item-raw))
                          destino (when (and slug item-id)
                                    (.destinoPedra pokeapi item-id slug))]
                    (cond
                      (or (nil? poke) (nil? destino))
                      (envelope-jvm pedido
                                    (str "❓ Use " prefix " evoluir <número> <pedra>."
                                         "\nPedras compatíveis: "
                                         (or (not-empty
                                              (str/join ", "
                                                        (js->clj (.pedrasCompativeis pokeapi
                                                                                    (or slug "")))))
                                             "nenhuma nesta versão")
                                         "\nGanhe pedras nos ginásios."))
                      :else
                      (p/let [evoluido (.fetchPokemonByName pokeapi destino)
                              nivel (or (.-nivel poke) 1)
                              escalado (.escalarNivel pokeapi evoluido nivel)
                              dados (.dadosEvolucao pokeapi escalado)
                              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido)
                                                        (str "evolve:" idx ":" item-id))
                              data (desempenho/medir! ctx "pokemon_jvm_evolve"
                                     #(.evolvePokemon jvm
                                        (clj->js {:chatId chat-id
                                                  :playerId player-id
                                                  :saveId (.-activeSaveId ativo)
                                                  :fenceGeneration (.-fenceGeneration ativo)
                                                  :operationId op-id
                                                  :collectionIndex idx
                                                  :itemId item-id
                                                  :dados dados})))]
                        (envelope-jvm pedido (.formatEvolve jvm-present data prefix))))))

                "mt"
                (let [prefix (str config/prefix "pk")
                      slot-txt (first resto)
                      slot (when (re-matches #"[1-4]" (str slot-txt))
                             (dec (js/parseInt slot-txt 10)))]
                  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                          col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                          idx (or (.-ativoIndex ativo) 0)
                          poke (nth (vec (or (.-collection col) #js [])) idx nil)
                          dados (when poke (.fetchPokemonByName pokeapi (or (.-species poke) (.-nome poke))))
                          golpes (vec (or (.-golpes dados) #js []))
                          golpe (first golpes)]
                    (cond
                      (nil? poke)
                      (envelope-jvm pedido (str "❓ Sem Pokémon ativo. " prefix " time"))
                      (nil? golpe)
                      (envelope-jvm pedido "❓ Não há golpe compatível disponível para este MT.")
                      :else
                      (p/let [op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                        (str "mt:" idx ":" (or slot "vaga")))
                              data (.learnMove jvm
                                               (clj->js (cond-> {:chatId chat-id :playerId player-id
                                                                 :saveId (.-activeSaveId ativo)
                                                                 :fenceGeneration (.-fenceGeneration ativo)
                                                                 :operationId op-id :collectionIndex idx
                                                                 :golpe golpe :consumeItemId "mt"}
                                                            (some? slot) (assoc :slot slot))))]
                        (envelope-jvm pedido (.formatLearnMove jvm-present data prefix))))))

                "aprender"
                (let [prefix (str config/prefix "pk")
                      acao (str/lower-case (str (or (first resto) "")))]
                  (p/let [treinador (.getTrainer jvm #js {:chatId chat-id :playerId player-id})
                          idx (or (.-ativoIndex treinador) 0)
                          col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                          items (vec (or (.-collection col) #js []))
                          poke (when (seq items) (nth items idx nil))
                          ofertas (when poke
                                    (or (.-ofertasGolpes poke) (.-golpesOferecidos poke) #js []))
                          oferta (when (and ofertas (pos? (alength ofertas))) (aget ofertas 0))]
                    (cond
                      (nil? poke)
                      (envelope-jvm pedido (str "❓ Sem Pokémon ativo. " prefix " time"))

                      (and (str/blank? acao) (nil? oferta))
                      (envelope-jvm pedido (.formatMoveOfferMenu jvm-present poke prefix))

                      (and (str/blank? acao) oferta)
                      (envelope-jvm pedido (.formatMoveOfferMenu jvm-present poke prefix))

                      (= acao "recusar")
                      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido)
                                                        (str "refuse-offer:" idx))
                              data (.refuseOffer jvm
                                     #js {:chatId chat-id
                                          :playerId player-id
                                          :saveId (.-activeSaveId ativo)
                                          :fenceGeneration (.-fenceGeneration ativo)
                                          :operationId op-id
                                          :collectionIndex idx})]
                        (envelope-jvm pedido (.formatRefuseOffer jvm-present data prefix)))

                      (or (= acao "aceitar") (re-matches #"[1-4]" acao))
                      (if-not oferta
                        (envelope-jvm pedido (.formatMoveOfferMenu jvm-present poke prefix))
                        (let [slot (when (re-matches #"[1-4]" acao)
                                     (dec (js/parseInt acao 10)))
                              golpe (js->clj oferta :keywordize-keys true)]
                          (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                  op-id (.stableOperationId jvm-transport
                                                            (:requestId pedido)
                                                            (str "learn-offer:" idx ":"
                                                                 (or slot "open")))
                                  data (.learnMove jvm
                                         (clj->js (cond-> {:chatId chat-id
                                                           :playerId player-id
                                                           :saveId (.-activeSaveId ativo)
                                                           :fenceGeneration (.-fenceGeneration ativo)
                                                           :operationId op-id
                                                           :collectionIndex idx
                                                           :golpe golpe
                                                           :oferta true}
                                                    (some? slot) (assoc :slot slot))))]
                            (envelope-jvm pedido (.formatLearnMove jvm-present data prefix)))))

                      :else
                      (envelope-jvm pedido
                                    (str "❓ Use " prefix " aprender, "
                                         prefix " aprender <1-4>, aceitar ou recusar.")))))

                "reaprender"
                (let [prefix (str config/prefix "pk")]
                  (p/let [treinador (.getTrainer jvm #js {:chatId chat-id :playerId player-id})
                          idx (or (.-ativoIndex treinador) 0)
                          col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                          items (vec (or (.-collection col) #js []))
                          poke (when (seq items) (nth items idx nil))
                          hist (when poke (or (.-historicoGolpes poke) #js {}))
                          removidos (when poke (vec (or (.-golpesRemovidos poke) #js [])))
                          conhecidos (set (map #(or (.-slug %) (get % "slug"))
                                               (vec (or (some-> poke .-golpes) #js []))))
                          opcoes (->> (concat (js->clj (js/Object.keys (or hist #js {})))
                                             removidos)
                                      distinct
                                      (remove #(contains? conhecidos %))
                                      sort
                                      vec)
                          escolha (when (re-matches #"[1-9][0-9]*" (str (or (first resto) "")))
                                    (dec (js/parseInt (first resto) 10)))
                          slug (when (and escolha (<= 0 escolha) (< escolha (count opcoes)))
                                 (nth opcoes escolha))
                          slot-txt (second resto)
                          slot (when (re-matches #"[1-4]" (str slot-txt))
                                 (dec (js/parseInt slot-txt 10)))]
                    (cond
                      (nil? poke)
                      (envelope-jvm pedido (str "❓ Sem Pokémon ativo. " prefix " time"))
                      (empty? resto)
                      (envelope-jvm pedido
                                    (.formatRelearnMenu jvm-present poke (clj->js opcoes) prefix))
                      (nil? slug)
                      (envelope-jvm pedido
                                    (str "❓ Consulte " prefix " reaprender e use números válidos."))
                      :else
                      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido)
                                                        (str "relearn:" idx ":" slug))
                              data (.relearnMove jvm
                                     (clj->js (cond-> {:chatId chat-id
                                                       :playerId player-id
                                                       :saveId (.-activeSaveId ativo)
                                                       :fenceGeneration (.-fenceGeneration ativo)
                                                       :operationId op-id
                                                       :collectionIndex idx
                                                       :slug slug}
                                                (some? slot) (assoc :slot slot))))]
                        (envelope-jvm pedido (.formatRelearnMove jvm-present data prefix))))))

                "removergolpe"
                (let [prefix (str config/prefix "pk")
                      chave [chat-id player-id]]
                  (p/let [treinador (.getTrainer jvm #js {:chatId chat-id :playerId player-id})
                          idx (or (.-ativoIndex treinador) 0)
                          col (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                          items (vec (or (.-collection col) #js []))
                          poke (when (seq items) (nth items idx nil))
                          golpes (vec (or (some-> poke .-golpes) #js []))
                          slot (parse-indice-1based (first resto) (count golpes))]
                    (cond
                      (nil? poke)
                      (envelope-jvm pedido (str "❓ Sem Pokémon ativo. " prefix " time"))
                      (get @remocoes-pendentes chave)
                      (envelope-jvm pedido
                                    (str "⏳ Já há remoção marcada. Aguarde ou "
                                         prefix " cancelar."))
                      (< (count golpes) 2)
                      (envelope-jvm pedido
                                    (str "🚫 *" (.-nome poke) "* precisa manter ao menos um golpe."))
                      (nil? slot)
                      (envelope-jvm pedido
                                    (str "❓ Use " prefix " removergolpe <1-" (count golpes) ">."))
                      :else
                      (let [golpe (nth golpes slot)
                            nome-g (or (.-nomeExibicao golpe) (.-slug golpe) "golpe")
                            token (str (js/Date.now) "-" (rand-int 100000))
                            timer (js/setTimeout
                                   (fn []
                                     (when (= token (:token (get @remocoes-pendentes chave)))
                                       (swap! remocoes-pendentes dissoc chave)
                                       (-> (p/let [ativo (.getActiveSave jvm
                                                             #js {:chatId chat-id
                                                                  :playerId player-id})
                                                   op-id (.stableOperationId
                                                          jvm-transport
                                                          (str "rmg-" token)
                                                          (str "forget:" idx ":" slot))
                                                   _ (.forgetMove jvm
                                                        #js {:chatId chat-id
                                                             :playerId player-id
                                                             :saveId (.-activeSaveId ativo)
                                                             :fenceGeneration (.-fenceGeneration ativo)
                                                             :operationId op-id
                                                             :collectionIndex idx
                                                             :slot slot})]
                                             nil)
                                           (p/catch (fn [err]
                                                      (js/console.warn "[PokemonJVM] forget:"
                                                                       (.-message err)))))))
                                   (* 1000 segundos-remover-golpe))]
                        (swap! remocoes-pendentes assoc chave
                               {:token token :timer timer :slot slot
                                :nome-golpe nome-g :nome-pokemon (.-nome poke)})
                        (envelope-jvm pedido
                                      (str "⚠️ Removendo *" nome-g "* de *" (.-nome poke)
                                           "* em " segundos-remover-golpe "s.\n"
                                           "Mudou de ideia? " prefix " cancelar"))))))

                "cancelar"
                (let [chave [chat-id player-id]
                      pendente (get @remocoes-pendentes chave)
                      prefix (str config/prefix "pk")]
                  (if pendente
                    (do (js/clearTimeout (:timer pendente))
                        (swap! remocoes-pendentes dissoc chave)
                        (envelope-jvm pedido
                                      (str "✅ Beleza, *" (:nome-golpe pendente)
                                           "* continua com *" (:nome-pokemon pendente) "*.")))
                    (envelope-jvm pedido
                                  (str "❓ Nenhuma remoção marcada. Use " prefix
                                       " removergolpe <número>."))))

                (envelope-jvm pedido
                              (.formatUnavailable jvm-present (str/join " " args))))]
        (entregar-envelope-jvm! message ctx pedido envelope))))))

(defn executar [message comando argumentos]
  (let [ctx (desempenho/contexto-de message)
        argumentos (or argumentos "")]
    (enfileirar!
     (chat-id message)
     (fn []
       (if (jvm-configurado?)
         ;; Adaptador JVM: fail-closed; sem fallback Cassandra/legado em falha.
         (-> (p/let [pedido (contexto-pedido message comando argumentos)
                     args (tokens-para-jvm comando argumentos)
                     _ (executar-jvm! message ctx pedido args)]
               nil)
             (p/catch (fn [erro]
                        (js/console.warn "[PokemonJVM] Falha:" (or (.-code erro) "DELIVERY")
                                         (or (.-status erro) "") (.-message erro))
                        (let [texto (mensagem-falha erro)]
                          (.reply message texto)))))
         ;; Adaptador legado (default / produção).
         (-> (p/let [pedido (contexto-pedido message comando argumentos)
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
                        (js/console.warn "[PokemonHTTP] Falha:" (or (.-code erro) "DELIVERY") (or (.-status erro) "")
                                         (when (= "INVALID_RESPONSE" (.-code erro)) (.-message erro)))
                        (.reply message (mensagem-falha erro)))))))
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
                                  (config/chat-autorizado? (:chatId evento)))
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
