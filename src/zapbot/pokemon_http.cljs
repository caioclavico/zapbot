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
(def ^:private ^js node-maintenance (js/require "../scripts/lib/a6-maintenance.cjs"))
(def ^:private pokeapi (js/require "../scripts/lib/pokemon-pokeapi.cjs"))
(def ^:private pokedex-species (js/require "../scripts/lib/pokemon-pokedex-species.cjs"))
(def ^:private pokedex-pilot (js/require "../scripts/lib/pokedex-species-pilot.cjs"))
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
  (let [codigo (str/upper-case (str (or (some-> erro .-code) "")))]
    (if (= codigo "BATTLE_PENDING")
      "⚠️ Há batalha/encontro em andamento neste save — não dá para iniciar outra caçada agora."
      (if (jvm-configurado?)
        (.formatError jvm-present erro)
        (case (.-code erro)
          ("TIMEOUT" "CONNECT_TIMEOUT")
          "⏳ O serviço Pokémon demorou para responder. O comando pode ter sido processado; confira o estado antes de repetir uma compra, troca ou captura."
          "CONFIG" "⚠️ O serviço Pokémon ainda não está configurado. Os outros comandos continuam disponíveis."
          "LIMIT" "⚠️ A resposta Pokémon excedeu o limite de tamanho. Tente consultar a coleção em texto: !pk time txt."
          "⚠️ Não consegui concluir a resposta do Pokémon. O comando pode ter sido processado; confira o estado antes de repetir uma ação. Os outros comandos continuam disponíveis.")))))

(defn- entregar-envelope-jvm! [message ctx pedido envelope]
  (let [resposta (assoc (js->clj envelope :keywordize-keys true)
                        :requestId (:requestId pedido))]
    (entregar! nil (fn [conteudo opcoes] (.reply message conteudo nil opcoes))
               ctx (str "jvm:" (:requestId pedido)) resposta)))

(defn- envelope-jvm
  "Texto obrigatório; imagem e mentions opcionais. Efeitos vazios (não são TX Datomic)."
  ([pedido texto] (envelope-jvm pedido texto nil nil))
  ([pedido texto imagem] (envelope-jvm pedido texto imagem nil))
  ([pedido texto imagem mentions]
   (let [extras #js {:effects #js []}]
     (when imagem
       (aset extras "image" imagem)
       (aset extras "caption" texto))
     (when (seq mentions)
       (aset extras "mentions" (clj->js (vec mentions))))
     (.envelope jvm-present (:requestId pedido) texto extras))))

(defn- especie-pokedex-top-level?
  "Top-level !pokedex/!dex/!pdx = ficha de espécie (master). !pk pokedex = coleção."
  [comando]
  (contains? #{"pokedex" "dex" "pdx"} (str/lower-case (str comando))))

(defn- especie-pokedex-autorizada?
  [message]
  (.pilotAuthorized pokedex-pilot (chat-id message)
                    #js {:pilotOnly config/pokedex-species-pilot-only?
                         :pilotChatId config/pokedex-species-pilot-chat-id}))

(defn- executar-especie-pokedex!
  "Pokédex de espécie via PokeAPI no Node (paridade master) + moldura vermelha.
   Respeita POKEDEX_SPECIES_PILOT_ONLY (default true) — demais chats recebem aviso."
  [message ctx pedido entrada]
  (if-not (especie-pokedex-autorizada? message)
    (entregar-envelope-jvm! message ctx pedido
                            (envelope-jvm pedido
                                          (.foraDoPilotoMessage pokedex-pilot config/prefix)))
    (p/let [resultado (desempenho/medir! ctx "pokemon_jvm_species_pokedex"
                         #(.lookup pokedex-species (str (or entrada "")) config/bot-name))
            texto (or (.-text resultado) "")
            poke (.-pokemon resultado)
            img (when poke
                  (render-seguro! ctx "pokemon_jvm_media_pokedex"
                    #(.renderPokedexCard jvm-media poke
                                         #js {:filename "pokedex.png"})))
            envelope (envelope-jvm pedido texto img)]
      (entregar-envelope-jvm! message ctx pedido envelope))))


(defn- nome-pokemon-colecao [p]
  (or (some-> p .-nome str not-empty)
      (some-> p .-nickname str not-empty)
      (when-let [sp (some-> p .-species str not-empty)]
        (str (str/upper-case (subs sp 0 1)) (subs sp 1)))
      nil))

(defn- trade-token-publico [data]
  (let [label (or (some-> data .-publicLabel str not-empty)
                  (when-let [n (some-> data .-publicId)]
                    (str "#" n)))]
    (when label (str/replace label #"^#" ""))))

(defn- trade-ref-arg [raw]
  (let [tok (-> (str (or raw "")) str/trim (str/replace #"^#" ""))]
    (when-not (str/blank? tok) tok)))

(defn- trades-abertas [trades]
  (->> (array-seq (or trades #js []))
       (filter (fn [tr]
                 (contains? #{"invited" "accepted" "selecting"
                              "awaiting-confirmations" "awaiting_confirmations"}
                            (str/lower-case (str (or (.-state tr) ""))))))))

(defn- escolher-trade-unica [trades player-id acao]
  (let [abertas (trades-abertas trades)
        elegiveis
        (filter
         (fn [tr]
           (let [st (str/lower-case (str (or (.-state tr) "")))
                 a (str (or (.-playerA tr) (.-player-a tr) ""))
                 b (str (or (.-playerB tr) (.-player-b tr) ""))]
             (case acao
               "aceitar" (and (= st "invited") (= player-id b))
               "recusar" (and (= st "invited") (= player-id b))
               "confirmar" (and (= st "accepted") (= player-id a))
               "cancelar" (and (contains? #{"invited" "accepted" "selecting"
                                            "awaiting-confirmations" "awaiting_confirmations"} st)
                               (or (= player-id a) (= player-id b)))
               false)))
         abertas)]
    (when (= 1 (count elegiveis)) (first elegiveis))))

(defn- render-seguro!
  "Render de mídia pós-TX. Qualquer falha → nil (nunca reexecuta gameplay)."
  [ctx metrica f]
  (-> (desempenho/medir! ctx metrica f)
      (p/catch (fn [err]
                 (js/console.error (str "mídia JVM (" metrica "):") err)
                 nil))))

(defn- codigo-jvm [erro]
  (str/upper-case (str (or (some-> erro .-code) ""))))

(defn- stale-writer-fence? [erro]
  (= "STALE_WRITER_FENCE" (codigo-jvm erro)))

(defn- hunt-start-body [chat-id player-id ativo op-id plano cands]
  #js {:chatId chat-id
       :playerId player-id
       :saveId (.-activeSaveId ativo)
       :fenceGeneration (.-fenceGeneration ativo)
       :operationId op-id
       :areaId (.-id (.-area plano))
       :climaId (.-id (.-clima plano))
       :tiposEncontro (.-tiposEncontro plano)
       :surto (.-surto plano)
       :poderAlvo (.-poderAlvo plano)
       :candidates cands})

(defn- preparar-caca-apos-pokeapi!
  "Fence/save frescos após pokeapi. Se o slot ativo mudou desde o plano, replaneja
  e revalida elegibilidade — não inicia caça planejada para outro save."
  [jvm pokeapi ctx chat-id player-id area-pedida plano cands]
  (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})]
    (if (= (str (.-activeSaveId ativo)) (str (.-activeSaveId plano)))
      {:plano plano :cands cands :ativo ativo :bloqueio nil}
      (p/let [plano2 (desempenho/medir! ctx "pokemon_jvm_hunt_replan"
                         #(.getHuntPlan jvm
                            #js {:chatId chat-id
                                 :playerId player-id
                                 :areaId area-pedida}))
              bloqueio (.formatHuntEligibility jvm-present plano2)]
        (if bloqueio
          {:plano plano2 :cands nil :ativo ativo :bloqueio bloqueio}
          (p/let [cands2 (desempenho/medir! ctx "pokemon_jvm_pokeapi_replan"
                             #(.buildHuntCandidates pokeapi plano2))
                  ;; Fence de novo após o segundo pokeapi (mesmo TOCTOU).
                  ativo2 (.getActiveSave jvm #js {:chatId chat-id
                                                  :playerId player-id})]
            (if (not= (str (.-activeSaveId ativo2)) (str (.-activeSaveId plano2)))
              ;; Slot ainda oscilando — não inicia com contexto cruzado.
              {:plano plano2 :cands nil :ativo ativo2
               :bloqueio (str "⚠️ O save ativo mudou durante a caça. "
                              "Confira !pk saves e tente de novo.")}
              {:plano plano2 :cands cands2 :ativo ativo2 :bloqueio nil})))))))

(defn- start-hunt-jvm!
  "POST /v1/hunt/start com fence fresco. No máx. 1 retry em STALE_WRITER_FENCE
  (reload + revalidação). Não retenta WRITER_FENCE_CONFLICT nem erros desconhecidos.
  Mesmo operationId em todas as tentativas (idempotência).
  Retorno: {:bloqueio s} | {:plano p :data d}."
  [jvm pokeapi ctx chat-id player-id area-pedida plano cands op-id]
  (p/let [prep (preparar-caca-apos-pokeapi!
                jvm pokeapi ctx chat-id player-id area-pedida plano cands)]
    (if-let [bloqueio (:bloqueio prep)]
      {:bloqueio bloqueio}
      (-> (desempenho/medir! ctx "pokemon_jvm_hunt_start"
            #(.startHunt jvm (hunt-start-body chat-id player-id
                                              (:ativo prep) op-id
                                              (:plano prep) (:cands prep))))
          (p/then (fn [data]
                    {:plano (:plano prep) :data data}))
          (p/catch
           (fn [erro]
             (if-not (stale-writer-fence? erro)
               (p/rejected erro)
               ;; Uma única retomada controlada: estado fresco + pré-condições.
               (p/let [prep2 (preparar-caca-apos-pokeapi!
                              jvm pokeapi ctx chat-id player-id
                              area-pedida (:plano prep) (:cands prep))]
                 (if-let [bloqueio2 (:bloqueio prep2)]
                   {:bloqueio bloqueio2}
                   (p/let [data2 (desempenho/medir! ctx "pokemon_jvm_hunt_start_retry"
                                    #(.startHunt jvm
                                       (hunt-start-body chat-id player-id
                                                        (:ativo prep2) op-id
                                                        (:plano prep2)
                                                        (:cands prep2))))]
                     {:plano (:plano prep2) :data data2}))))))))))

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
                      (.formatJoyStatus jvm-present data prefix))
              tema (.resolveJoyTheme jvm-media texto)
              img (when tema
                    (render-seguro! ctx "pokemon_jvm_media_joy"
                      #(.renderStaticAsset jvm-media tema
                                           #js {:filename (str tema ".png")})))]
        (envelope-jvm pedido texto img))
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
                ;; Duração efetiva vem do JVM (pronto-em / config); não hardcodar 30 min.
                primeiro (first enviados)
                texto (if (= 1 (count enviados))
                        (.formatJoySend jvm-present primeiro prefix)
                        (let [nomes (->> enviados
                                         (map #(some-> % .-pokemon .-nome))
                                         (filter some?)
                                         (str/join ", "))
                              rest-ms (or (some-> primeiro .-restanteMs)
                                          (some-> primeiro .-restante-ms))
                              secs-cfg (or (some-> primeiro .-tempoTratamentoSegundos)
                                           (some-> primeiro .-tempo-tratamento-segundos))
                              payload #js {:pokemon #js {:nome (if (str/blank? nomes) "Pokémon" nomes)}
                                           :restanteMs rest-ms
                                           :tempoTratamentoSegundos secs-cfg}]
                          (.formatJoySend jvm-present payload prefix)))
                tema (.resolveJoyTheme jvm-media texto)
                img (when tema
                      (render-seguro! ctx "pokemon_jvm_media_joy"
                        #(.renderStaticAsset jvm-media tema
                                             #js {:filename (str tema ".png")})))]
          (envelope-jvm pedido texto img))))))

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
      (or (nil? sub) (str/blank? (str sub)) (= "status" sub))
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                       #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatRaidStatus jvm-present data prefix)))

      (= "abrir" sub)
      (let [token (str/lower-case (str/trim (str (or (first resto) ""))))]
        (if (str/blank? token)
          (envelope-jvm pedido (.formatRaidOpenHelp jvm-present prefix))
          (let [ginasio (.resolveRaidGym jvm-present token)]
            (when-not ginasio
              (throw (erro-contrato
                      (str "❓ Ginásio inválido. Use " prefix " raid abrir para ver a lista."))))
            (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                    op-id (.stableOperationId jvm-transport
                                              (:requestId pedido)
                                              (str "raid-open:" ginasio))
                    data (desempenho/medir! ctx "pokemon_jvm_raid_open"
                           #(.openRaid jvm
                              #js {:chatId chat-id
                                   :playerId player-id
                                   :saveId (.-activeSaveId ativo)
                                   :fenceGeneration (.-fenceGeneration ativo)
                                   :operationId op-id
                                   :ginasio ginasio}))]
              (envelope-jvm pedido (.formatRaidOpened jvm-present data prefix))))))

      (= "cancelar" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-cancel")
              data (desempenho/medir! ctx "pokemon_jvm_raid_cancel"
                     #(.cancelRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))]
        (envelope-jvm pedido (.formatRaidCancelled jvm-present data prefix)))

      (= "time" sub)
      (p/let [data (desempenho/medir! ctx "pokemon_jvm_raid_eligible"
                       #(.getRaidEligible jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido (.formatRaidEligible jvm-present data prefix)))

      (= "entrar" sub)
      ;; Default: Pokémon *ativo* (escolher). `auto` = melhor apto. `<n>` = índice.
      (let [raw (str/lower-case (str/trim (str (or (first resto) "ativo"))))
            auto? (= "auto" raw)
            use-active? (or (str/blank? raw) (= "ativo" raw) (= "active" raw)
                            (= "escolhido" raw))
            idx (when-not (or auto? use-active?)
                  (let [n (js/parseInt raw 10)]
                    (when-not (js/isNaN n) (dec n))))]
        (when (and (not auto?) (not use-active?) (or (nil? idx) (neg? idx)))
          (throw (erro-contrato
                  (str "❓ Use " prefix " raid entrar  ·  "
                       prefix " raid entrar auto  ·  "
                       prefix " raid entrar <n>"))))
        (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                mode (cond auto? "auto" use-active? "ativo" :else (str idx))
                op-id (.stableOperationId jvm-transport
                                          (:requestId pedido)
                                          (str "raid-join:" mode))
                data (desempenho/medir! ctx "pokemon_jvm_raid_join"
                       #(.joinRaid jvm
                          (clj->js (cond-> {:chatId chat-id
                                            :playerId player-id
                                            :saveId (.-activeSaveId ativo)
                                            :fenceGeneration (.-fenceGeneration ativo)
                                            :operationId op-id
                                            :nome player-name}
                                     auto? (assoc :auto true)
                                     use-active? (assoc :useActive true)
                                     (and (not auto?) (not use-active?))
                                     (assoc :collectionIndex idx)))))]
          (envelope-jvm pedido (.formatRaidJoin jvm-present data prefix))))

      (= "sair" sub)
      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido) "raid-leave")
              data (desempenho/medir! ctx "pokemon_jvm_raid_leave"
                     #(.leaveRaid jvm
                        #js {:chatId chat-id
                             :playerId player-id
                             :saveId (.-activeSaveId ativo)
                             :fenceGeneration (.-fenceGeneration ativo)
                             :operationId op-id}))]
        (envelope-jvm pedido (.formatRaidLeave jvm-present data prefix)))

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
                             :operationId op-id}))
              img (render-seguro! ctx "pokemon_jvm_media_raid_start"
                    #(.renderRaidBattleCard jvm-media
                       #js {:ginasio (or (.-nomeGinasio (.-raid data))
                                         (.-ginasio (.-raid data)))
                            :chefe (some-> (.-raid data) .-chefe)
                            :atacante (some-> (.-raid data) .-vezPokemon)
                            :treinador (or (.-vezNome (.-raid data)) player-name)
                            :hpChefe (.-hpChefe (.-raid data))
                            :hpMax (.-hpMax (.-raid data))
                            :hpAtacante (.-vezHp (.-raid data))
                            :fase (.-fase (.-raid data))
                            :turnIndex (.-turnIndex (.-raid data))}))]
        (envelope-jvm pedido (.formatRaidStart jvm-present data prefix) img))

      (= "atacar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not raid
                  (throw (erro-contrato "❓ Nenhuma raid ativa neste chat.")))
              ;; Aceita `suaVez` (contrato) e `suaVez?` (legado camelCase com ?).
              ;; Também confere igualdade literal player-id === vez (identidade canônica).
              sua-vez? (or (true? (.-suaVez raid))
                           (true? (aget raid "suaVez?"))
                           (and player-id (.-vez raid) (= player-id (.-vez raid))))
              _ (when-not sua-vez?
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
                             :moveIndex move-idx}))
              img (render-seguro! ctx "pokemon_jvm_media_raid_atk"
                    #(.renderRaidBattleCard jvm-media
                       #js {:ginasio (or (.-nomeGinasio (.-raid data))
                                         (.-ginasio (.-raid data)))
                            :chefe (some-> (.-raid data) .-chefe)
                            :atacante (or (.-vezPokemon (.-raid data))
                                          (some-> raid .-vezPokemon))
                            :treinador (or (.-vezNome (.-raid data))
                                           (.-vezNome raid)
                                           player-name)
                            :hpChefe (.-hpChefe (.-raid data))
                            :hpMax (.-hpMax (.-raid data))
                            :hpAtacante (or (.-vezHp (.-raid data))
                                            (.-vezHp raid))
                            :fase (.-fase (.-raid data))
                            :turnIndex (.-turnIndex (.-raid data))}))
              present (.coerceRaidPresent jvm-present
                                          (.formatRaidAttack jvm-present data prefix))]
        (envelope-jvm pedido (.-text present) img
                      (js->clj (or (.-mentions present) #js []))))

      ;; fruta / frambo / fruta frambo — espelha caça selvagem: prepare + UI completa
      (or (= "frambo" sub) (= "fruta" sub))
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not (and raid (.-capturaPendente raid))
                  (throw (erro-contrato "❓ Captura de raid indisponível agora.")))
              ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
              op-id (.stableOperationId jvm-transport
                                        (:requestId pedido)
                                        (str "raid-frambo:" (.-raidId raid)))
              _prep (desempenho/medir! ctx "pokemon_jvm_raid_frambo"
                      #(.raidFrambo jvm
                         #js {:chatId chat-id
                              :playerId player-id
                              :saveId (.-activeSaveId ativo)
                              :fenceGeneration (.-fenceGeneration ativo)
                              :operationId op-id
                              :raidId (.-raidId raid)}))
              ;; Re-fetch: chances reais + estoque após prepare (formatter compartilhado).
              raid-ui (desempenho/medir! ctx "pokemon_jvm_raid_active"
                         #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))]
        (envelope-jvm pedido
                      (.formatRaidCaptureUi jvm-present raid-ui prefix
                                            #js {:afterFrambo true})))

      (= "capturar" sub)
      (p/let [raid-data (desempenho/medir! ctx "pokemon_jvm_raid_active"
                           #(.getActiveRaid jvm #js {:chatId chat-id :playerId player-id}))
              raid (.-raid raid-data)
              _ (when-not (and raid (.-capturaPendente raid))
                  (throw (erro-contrato "❓ Captura de raid indisponível agora.")))]
        (if (str/blank? (str/join " " resto))
          (envelope-jvm pedido (.formatRaidCaptureUi jvm-present raid-data prefix))
          (let [tokens (->> resto (map str) (remove str/blank?) vec)
                frambo? (boolean (some #(contains? #{"frambo" "framb" "razz"}
                                                   (str/lower-case %))
                                       tokens))
                bola (->> tokens
                          (remove #(contains? #{"frambo" "framb" "razz"}
                                              (str/lower-case %)))
                          (str/join " "))
                _ (when (str/blank? bola)
                    (throw (erro-contrato
                            (str "❓ Use " prefix
                                 " raid capturar 1|2|3"
                                 " (ou pokebola|grande-bola|ultra-bola)"))))]
            (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                    op-id (.stableOperationId jvm-transport
                                              (:requestId pedido)
                                              (str "raid-cap:" (.-raidId raid) ":"
                                                   bola (when frambo? ":frambo")))
                    data (desempenho/medir! ctx "pokemon_jvm_raid_capture"
                           #(.raidCapture jvm
                              (clj->js (cond-> {:chatId chat-id
                                                :playerId player-id
                                                :saveId (.-activeSaveId ativo)
                                                :fenceGeneration (.-fenceGeneration ativo)
                                                :operationId op-id
                                                :raidId (.-raidId raid)
                                                :bola bola}
                                         frambo? (assoc :frambo true)))))
                    img (render-seguro! ctx "pokemon_jvm_media_raid_cap"
                          #(.renderCaptureBall jvm-media
                              #js {:bola (or (.-bola data) "pokebola")
                                   :capturou (boolean (.-capturou data))
                                   :fugiu false}))]
              (envelope-jvm pedido (.formatRaidCapture jvm-present data prefix) img)))))

      (or (= "ajuda" sub) (= "help" sub) (= "?" sub))
      (envelope-jvm pedido (.formatHelp jvm-present "raid" prefix))

      :else
      (envelope-jvm pedido
                    (str "❓ Comando de raid não reconhecido.\n"
                         "Use " prefix " ajuda raid para consultar os comandos disponíveis.")))))

(defn- lado-pvp
  "Retorna \"x\"|\"o\"|nil a partir de jogadores no DTO da batalha."
  [battle player-id]
  (let [jog (some-> battle .-jogadores)
        ;; DTO pode expor suaVez; prioriza jogadores canônicos.
        x-id (when jog (or (.-x jog) (aget jog "x")))
        o-id (when jog (or (.-o jog) (aget jog "o")))]
    (cond
      (and player-id (= player-id x-id)) "x"
      (and player-id (= player-id o-id)) "o"
      :else nil)))

(defn- sua-vez-pvp?
  [battle player-id]
  (let [side (lado-pvp battle player-id)
        vez (some-> battle .-vez)]
    (boolean (or (true? (some-> battle .-suaVez))
                 (and side vez (= side vez))))))

(defn- mencao-vez-pvp
  "Player-id de quem deve jogar agora (para @ no WhatsApp)."
  [battle]
  (let [jog (some-> battle .-jogadores)
        vez (some-> battle .-vez)]
    (when (and jog vez)
      (cond
        (= vez "x") (or (.-x jog) (aget jog "x"))
        (= vez "o") (or (.-o jog) (aget jog "o"))
        :else nil))))

(defn- envelope-jvm-pvp
  [pedido texto imagem battle]
  (let [mention (mencao-vez-pvp battle)
        extras #js {:effects #js []}]
    (when imagem
      (aset extras "image" imagem)
      (aset extras "caption" texto))
    (when mention
      (aset extras "mentions" #js [mention]))
    (.envelope jvm-present (:requestId pedido) texto extras)))

(defn- lado-vez-pvp
  "Lado canônico de quem deve jogar agora (\"x\"|\"o\"|nil)."
  [battle]
  (let [vez (some-> battle .-vez)]
    (when (or (= vez "x") (= vez "o")) vez)))

(defn- hp-js
  "Par {atual,max} a partir do DTO de batalha (mapa :hp ou hpAtual no Pokémon)."
  [pokemon side-hp]
  (let [max-hp (some-> pokemon .-hp)
        atual (or (some-> pokemon .-hpAtual)
                  (when (number? side-hp) side-hp)
                  (when (and side-hp (.-atual side-hp)) (.-atual side-hp))
                  max-hp)]
    (when (and (number? max-hp) (pos? max-hp) (number? atual))
      #js {:atual atual :max max-hp})))

(defn- render-arena-pvp!
  "Arena estilo caça com assentos fixos: X à esquerda, O à direita.
   Nunca espelha por turno — evita barras/sprites trocando de lugar."
  [ctx battle _side {:keys [texto efeito mostrar-entrada?]}]
  (let [hp (some-> battle .-hp)
        px (.-pokemonX battle)
        po (.-pokemonO battle)
        hp-x (hp-js px (when hp (or (.-x hp) (aget hp "x"))))
        hp-o (hp-js po (when hp (or (.-o hp) (aget hp "o"))))
        nomes (some-> battle .-nomes)
        rotulo-x (or (when nomes (or (.-x nomes) (aget nomes "x")))
                     (some-> px .-nome))
        rotulo-o (or (when nomes (or (.-o nomes) (aget nomes "o")))
                     (some-> po .-nome))]
    (render-seguro! ctx "pokemon_jvm_media_pvp"
      #(.renderHuntArena jvm-media
          #js {:area (or (some-> battle .-area)
                         #js {:id "pvp" :nome "PvP" :emoji "⚔️"})
               :pokemonX px
               :pokemonO po
               :fugiu false
               :aguardandoCaptura false
               :caption texto
               :efeito efeito
               :mostrarEntrada (boolean mostrar-entrada?)
               :hpX hp-x
               :hpO hp-o
               :rotuloX rotulo-x
               :rotuloO rotulo-o}))))

(defn- responder-pvp!
  "Texto + arena + @vez. `entrada?` true quando o 2º aceita / luta começa."
  [ctx pedido battle player-id {:keys [entrada? efeito]}]
  (let [prefix (str config/prefix "pk")
        side (lado-pvp battle player-id)
        texto (.formatPvpBattle jvm-present battle prefix
                                #js {:mySide side
                                     :suaVez (sua-vez-pvp? battle player-id)
                                     :playerName nil})]
    (p/let [img (render-arena-pvp! ctx battle (lado-vez-pvp battle)
                                   {:texto texto
                                    :efeito efeito
                                    :mostrar-entrada? (boolean entrada?)})]
      (envelope-jvm-pvp pedido texto img battle))))

(defn- confirmar-turno-pvp!
  [jvm ctx pedido chat-id player-id battle
   {:keys [action move-index item-id]}]
  (let [prefix (str config/prefix "pk")
        side0 (lado-pvp battle player-id)]
    (cond
      (nil? side0)
      (p/resolved (envelope-jvm pedido "❓ Você não está nesta batalha PvP."))

      (not (sua-vez-pvp? battle player-id))
      (responder-pvp! ctx pedido battle player-id {:entrada? false})

      :else
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
              side (or (lado-pvp b player-id) side0)
              sua-vez? (sua-vez-pvp? b player-id)
              texto (.formatPvpTurn jvm-present data prefix
                                    #js {:mySide side
                                         :suaVez sua-vez?
                                         :rewards (.-rewards data)})
              efeito (.efeitoDeTurno jvm-media data)
              img (render-arena-pvp! ctx b side
                                     {:texto texto
                                      :efeito efeito
                                      :mostrar-entrada? false})]
        (envelope-jvm-pvp pedido texto img b)))))

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

(defn- normalizar-missao-token
  "Remove acentos/caixa — aceita diaria(s), semanal(is), evento(s)."
  [s]
  (-> (str (or s ""))
      str/lower-case
      (.normalize "NFD")
      (str/replace #"[\u0300-\u036f]" "")
      str/trim))

(defn- executar-missoes-jvm!
  "Consulta e resgate de missões; período, prêmio e estado pertencem ao Core."
  [jvm ctx pedido chat-id player-id resto]
  (let [prefix (str config/prefix "pk")
        primeiro (normalizar-missao-token (first resto))
        diarias? (contains? #{"diaria" "diarias" "dia" "daily"} primeiro)
        semanais? (contains? #{"semanal" "semanais" "semana" "weekly"} primeiro)
        eventos? (contains? #{"evento" "eventos" "event"} primeiro)
        acao (if (or diarias? semanais? eventos?) (second resto) (first resto))
        scope (cond semanais? "weekly" diarias? "daily" eventos? "event" :else "all")]
    (p/let [missoes (desempenho/medir! ctx "pokemon_jvm_missions"
                         #(.getMissions jvm #js {:chatId chat-id :playerId player-id}))]
      (if-not (= "resgatar" (normalizar-missao-token acao))
        (p/let [texto (.formatMissions jvm-present missoes scope prefix)
                img (render-seguro! ctx "pokemon_jvm_media_missao"
                      #(.renderStaticAsset jvm-media "missao"
                                           #js {:filename "missao.png"}))]
          (envelope-jvm pedido texto img))
        (if eventos?
          (let [ativos (filter #(or (= "active" (str (.-status %)))
                                    (true? (.-available %)))
                               (js->clj (or (.-events missoes) #js []) :keywordize-keys true))
                ;; Preferir ocorrência temática (tem occurrence-id); legado Novo Recomeço não.
                occ-id (some (fn [ev]
                               (or (:occurrence-id ev) (:occurrenceId ev)))
                             ativos)]
            (if-not occ-id
              (envelope-jvm pedido
                            (str "🎉 Nenhum evento temático ativo para resgatar. "
                                 "Consulte `" prefix " missoes evento`."))
              (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                      op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                 (str "mission-claim:event:" occ-id))
                      data (desempenho/medir! ctx "pokemon_jvm_mission_claim_event"
                             #(.claimEventMissions jvm #js {:chatId chat-id :playerId player-id
                                                            :saveId (.-activeSaveId ativo)
                                                            :fenceGeneration (.-fenceGeneration ativo)
                                                            :operationId op-id
                                                            :occurrenceId occ-id}))]
                (envelope-jvm pedido (.formatMissionClaim jvm-present data "event" prefix)))))
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
                                                       (if weekly? "weekly" "daily") prefix))))))))

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
        trade? (contains? #{"troca" "trocas"} primeiro-arg)
        recovery? (= "recuperar" primeiro-arg)
        save-management? (contains? #{"novo" "renomear"} primeiro-arg)
        ;; A negociação só é exposta por !pk troca(s). O token já vem nos args
        ;; recebidos de pokemon/jogar e não integra o contrato HTTP.
        trade-args (cond
                     (= "trocas" primeiro-arg) []
                     trade? (vec (rest args))
                     :else args)
        help-req (.resolveHelpRequest jvm-present (clj->js args))
        resolved-cmd (cond
                       trade? nil
                       recovery? nil
                       help-req "ajuda"
                       save-management? "save-management"
                       :else (.resolveCommand jvm-present (clj->js args)))
        cmd (if (and (= "time" resolved-cmd)
                     (contains? #{"salvar" "usar" "ver" "excluir" "apagar" "remover"}
                                (str/lower-case (str (or (first (rest args)) "")))))
              "times"
              resolved-cmd)
        resto (if (re-matches #"[1-4]" primeiro)
                [primeiro]
                (vec (rest args)))]
    (if (and help-req (not recovery?))
      (entregar-envelope-jvm!
       message ctx pedido
       (envelope-jvm pedido
                     (.formatHelp jvm-present
                                  (or (.-topic help-req) "")
                                  (str config/prefix "pk"))))
    (if recovery?
      (p/let [sub (str/lower-case (str (or (second args) "")))
              texto
              (cond
                (contains? #{"confirmar" "confirm"} sub)
                (p/let [data (.confirmRecovery jvm #js {:chatId chat-id :playerId player-id})]
                  (str "✅ Solicitação confirmada (" (or (.-choice data) "?") ").\n"
                       "Status: aguardando importação administrativa.\n"
                       "Nenhum save foi importado ou ativado ainda.\n"
                       "Depois da importação use !pk saves / !pk save."))

                (str/blank? sub)
                (p/let [data (.getRecoveryAvailable jvm #js {:chatId chat-id :playerId player-id})
                        ready? (boolean (.-catalogReady data))
                        versoes (array-seq (or (.-versions data) #js []))
                        disponiveis (array-seq (or (.-available data) #js []))]
                  (if-not ready?
                    (str "🗂️ Recuperação histórica indisponível no momento.\n"
                         "O catálogo offline autorizado ainda não foi carregado.\n"
                         "Nenhum progresso foi alterado.")
                    (if (empty? versoes)
                      (str "🗂️ Nenhum save histórico V1/V2 vinculado com segurança à sua conta.\n"
                           "A identidade vem só do WhatsApp (chat + jogador); "
                           "não use IDs digitados.\n"
                           "Nenhum progresso foi alterado.")
                      (str "🗂️ SAVES HISTÓRICOS DISPONÍVEIS\n"
                           (str/join "\n"
                                     (map (fn [p]
                                            (str "• " (str/upper-case (str (or (.-version p) "?")))
                                                 " → slot " (or (.-slotId p) "?")
                                                 " | Pokémon: " (or (.-pokemonCount p) 0)
                                                 (when (some? (.-coins p))
                                                   (str " | moedas: " (.-coins p)))))
                                          disponiveis))
                           (when (.-aliasApplied data)
                             "\n(identidade histórica resolvida via alias autorizado)")
                           "\n\nUse !pk recuperar v1, !pk recuperar v2 ou !pk recuperar ambos.\n"
                           "Depois: !pk recuperar confirmar\n"
                           "A importação final é administrativa; o save ativo não muda."))))

                (contains? #{"v1" "v2" "ambos" "both"} sub)
                (let [choice (if (= sub "ambos") "both" (if (= sub "both") "both" sub))]
                  (p/let [data (.requestRecovery jvm #js {:chatId chat-id
                                                          :playerId player-id
                                                          :choice choice})
                          selected (array-seq (or (.-selected data) #js []))]
                    (str "🗂️ Prévia " (str/upper-case (str (or (.-choice data) choice)))
                         " registrada.\n"
                         (str/join "\n"
                                   (map (fn [p]
                                          (str "• " (str/upper-case (str (or (.-version p) "?")))
                                               " | Pokémon: " (or (.-pokemonCount p) 0)))
                                        selected))
                         "\n\nConfirme com !pk recuperar confirmar (válido por ~10 min).\n"
                         "Nenhum save foi importado ou ativado.")))

                :else
                (p/resolved "❓ Use !pk recuperar, !pk recuperar v1|v2|ambos ou !pk recuperar confirmar."))]
        (entregar-envelope-jvm! message ctx pedido (envelope-jvm pedido texto)))
      (if trade?
      (p/let [envelope
              (let [prefix (str config/prefix "pk")
                    sub (str/lower-case (str (or (first trade-args) "")))
                    rest-args (vec (rest trade-args))
                    op-id (fn [suffix] (.stableOperationId jvm-transport (:requestId pedido) suffix))
                    ativo! #(.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                    col! #(.getCollection jvm #js {:chatId chat-id :playerId player-id})
                    indice-poke! (fn [texto col]
                                   (let [arr (or (.-collection col) #js [])
                                         idx (parse-indice-1based texto (alength arr))]
                                     (when (nil? idx)
                                       (throw (erro-contrato "❓ Índice de Pokémon inválido.")))
                                     (aget arr idx)))
                    resolver-ref! (fn [acao raw]
                                    (p/let [listed (.listTrades jvm #js {:chatId chat-id :playerId player-id})
                                            trades (or (.-trades listed) #js [])
                                            token (trade-ref-arg raw)]
                                      (if token
                                        token
                                        (if-let [unica (escolher-trade-unica trades player-id acao)]
                                          (or (trade-token-publico unica)
                                              (str (or (.-id unica) (.-tradeId unica))))
                                          (throw (erro-contrato
                                                  (str "❓ Informe o número da proposta: `"
                                                       prefix " troca " acao " <n>`.\n"
                                                       "Veja as pendentes com `" prefix " trocas`.")))))))
                    body-op (fn [action trade-id]
                              (p/let [a (ativo!)]
                                #js {:chatId chat-id :playerId player-id :tradeId trade-id
                                     :saveId (.-activeSaveId a) :fenceGeneration (.-fenceGeneration a)
                                     :operationId (op-id action)}))]
                (cond
                  (or (str/blank? sub) (= sub "lista") (= sub "listar"))
                  (p/let [data (.listTrades jvm #js {:chatId chat-id :playerId player-id})]
                    (envelope-jvm pedido (.formatTradeList jvm-present (.-trades data) #js {:prefix prefix})))

                  (= sub "aceitar")
                  (p/let [ref (resolver-ref! "aceitar" (first rest-args))
                          b (body-op "trade-accept" ref)
                          data (.acceptTrade jvm b)
                          texto (.formatTradeAccepted jvm-present data #js {:prefix prefix})]
                    (envelope-jvm pedido texto nil
                                  (array-seq (.tradeMentions jvm-present
                                                             (or (.-playerA data) (.-player-a data))
                                                             (or (.-playerB data) (.-player-b data))
                                                             player-id))))

                  (= sub "confirmar")
                  (p/let [ref (resolver-ref! "confirmar" (first rest-args))
                          b (body-op "trade-complete" ref)
                          data (.completeTrade jvm b)
                          texto (.formatTradeCompleted jvm-present data #js {:prefix prefix})]
                    (envelope-jvm pedido texto nil
                                  (array-seq (.tradeMentions jvm-present
                                                             (or (.-playerA data) (.-player-a data))
                                                             (or (.-playerB data) (.-player-b data))
                                                             player-id))))

                  (= sub "recusar")
                  (p/let [ref (resolver-ref! "recusar" (first rest-args))
                          b (body-op "trade-refuse" ref)
                          data (.refuseTrade jvm b)]
                    (envelope-jvm pedido (.formatTradeRefused jvm-present data)))

                  (= sub "cancelar")
                  (p/let [ref (resolver-ref! "cancelar" (first rest-args))
                          b (body-op "trade-cancel" ref)
                          data (.cancelTrade jvm b)]
                    (envelope-jvm pedido (.formatTradeCancelled jvm-present data)))

                  (and (= 3 (count trade-args)) (re-matches #"[1-9][0-9]*" (first trade-args)))
                  (let [[meu outro] trade-args
                        mentions (vec (get-in pedido [:context :mentionedIds]))
                        target (first mentions)]
                    (when-not (= 1 (count mentions))
                      (throw (erro-contrato "❓ Mencione exatamente um treinador.")))
                    (p/let [a (ativo!) ca (col!)
                            at (.getActiveSave jvm #js {:chatId chat-id :playerId target})
                            ct (.getCollection jvm #js {:chatId chat-id :playerId target})
                            poke-a (indice-poke! meu ca)
                            poke-b (indice-poke! outro ct)
                            pokemon-a (or (.-pokemonId poke-a) (.-id poke-a) (.-uuid poke-a))
                            pokemon-b (or (.-pokemonId poke-b) (.-id poke-b) (.-uuid poke-b))
                            nome-a (nome-pokemon-colecao poke-a)
                            nome-b (nome-pokemon-colecao poke-b)
                            body (clj->js {:chatId chat-id :playerId player-id
                                           :playerA player-id :playerB target
                                           :saveA (.-activeSaveId a) :saveB (.-activeSaveId at)
                                           :fenceGeneration (.-fenceGeneration a)
                                           :fenceA (.-fenceGeneration a) :fenceB (.-fenceGeneration at)
                                           :pokemonA pokemon-a :pokemonB pokemon-b
                                           :operationId (op-id (str "trade-create:" target ":" meu ":" outro))})
                            _ (when nome-a (aset body "pokemonAName" nome-a))
                            _ (when nome-b (aset body "pokemonBName" nome-b))
                            data (.createTrade jvm body)
                            texto (.formatTradeCreated jvm-present data
                                                       #js {:prefix prefix
                                                            :fromPlayerId player-id
                                                            :toPlayerId target})]
                      (envelope-jvm pedido texto nil [player-id target])))

                  :else
                  (throw (erro-contrato
                          (str "❓ Use `" prefix " troca <seu nº> <nº do outro> @treinador`\n"
                               "ou `" prefix " troca aceitar|confirmar|recusar|cancelar <n>`\n"
                               "ou `" prefix " trocas`.")))))]
        (entregar-envelope-jvm! message ctx pedido envelope))
      (if-not cmd
      (entregar-envelope-jvm!
       message ctx pedido
       (envelope-jvm pedido (.formatUnavailable jvm-present (str/join " " args))))
      (p/let [envelope
              (case cmd
                "ajuda"
                (envelope-jvm pedido
                              (.formatHelp jvm-present
                                           (str (or (first resto) ""))
                                           (str config/prefix "pk")))

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
                      (envelope-jvm pedido (.formatTitles jvm-present data (str config/prefix "pk"))))))

                "professor"
                (let [prefix (str config/prefix "pk")
                      sub (str/lower-case (str (or (first resto) "")))
                      restp (vec (rest resto))
                      ;; !pk professor enviar 1 | 1,2,3 → índices 0-based da coleção.
                      idxs-enviar
                      (when (= sub "enviar")
                        (->> (str/split (str/join " " restp) #"[,\s]+")
                             (map #(js/parseInt % 10))
                             (filter #(and (not (js/isNaN %)) (pos? %)))
                             (map dec)
                             distinct
                             sort
                             vec))
                      numero (cond
                               (= sub "usar") (first restp)
                               (= sub "confirmar") (first restp)
                               :else nil)
                      idx-usar (let [n (js/parseInt (str numero) 10)]
                                 (when (and (not (js/isNaN n)) (pos? n)) (dec n)))]
                  (cond
                    (or (str/blank? sub)
                        (contains? #{"ajuda" "help" "?"} sub)
                        (not (contains? #{"enviar" "confirmar" "usar" "cancelar"
                                          "cartoes" "cartões" "saldo" "doces" "doce"} sub)))
                    (envelope-jvm pedido (.formatProfessorHelp jvm-present prefix))

                    (contains? #{"cartoes" "cartões" "saldo" "doces" "doce"} sub)
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.getProfessorCards jvm #js {:chatId chat-id :playerId player-id
                                                           :saveId (.-activeSaveId a)})]
                      (envelope-jvm pedido (.formatProfessorCards jvm-present d prefix)))

                    (= sub "cancelar")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.professorCancel jvm #js {:chatId chat-id :playerId player-id
                                                         :saveId (.-activeSaveId a)
                                                         :fenceGeneration (.-fenceGeneration a)
                                                         :operationId (.stableOperationId jvm-transport
                                                                         (:requestId pedido) "prof-cancel")})]
                      (envelope-jvm pedido (.formatProfessor jvm-present d prefix)))

                    (= sub "confirmar")
                    (if (str/blank? (str numero))
                      (envelope-jvm pedido (.formatProfessorHelp jvm-present prefix))
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              d (.professorConfirm jvm #js {:chatId chat-id :playerId player-id
                                                            :saveId (.-activeSaveId a)
                                                            :fenceGeneration (.-fenceGeneration a)
                                                            :token (str numero)
                                                            :now (js/Date.now)
                                                            :operationId (.stableOperationId jvm-transport
                                                                            (:requestId pedido) "prof-confirm")})]
                        (envelope-jvm pedido (.formatProfessor jvm-present d prefix))))

                    (= sub "usar")
                    (if (nil? idx-usar)
                      (envelope-jvm pedido (str "❓ Número inválido. Consulte " prefix " tm."))
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              d (.professorUse jvm #js {:chatId chat-id :playerId player-id
                                                        :saveId (.-activeSaveId a)
                                                        :fenceGeneration (.-fenceGeneration a)
                                                        :collectionIndex idx-usar
                                                        :operationId (.stableOperationId jvm-transport
                                                                        (:requestId pedido)
                                                                        (str "prof-use:" (inc idx-usar)))})]
                        (envelope-jvm pedido (.formatProfessor jvm-present d prefix))))

                    ;; enviar (um ou vários)
                    (empty? idxs-enviar)
                    (envelope-jvm pedido (str "❓ Número inválido. Use " prefix " professor enviar 1 ou "
                                               prefix " professor enviar 1,2,3."))

                    :else
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-key (str "prof-send:" (str/join "," (map inc idxs-enviar)))
                            d (.professorSend jvm #js {:chatId chat-id :playerId player-id
                                                       :saveId (.-activeSaveId a)
                                                       :fenceGeneration (.-fenceGeneration a)
                                                       :collectionIndexes (clj->js idxs-enviar)
                                                       :collectionIndex (first idxs-enviar)
                                                       :now (js/Date.now)
                                                       :operationId (.stableOperationId jvm-transport
                                                                       (:requestId pedido)
                                                                       op-key)})]
                      (envelope-jvm pedido (.formatProfessor jvm-present d prefix)))))

                "treinador"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_trainer"
                                #(.getTrainer jvm #js {:chatId chat-id :playerId player-id}))
                        texto (.formatTrainer jvm-present data player-name)
                        ativo (.-ativo data)
                        img (render-seguro! ctx "pokemon_jvm_media_trainer"
                              #(.renderTrainerCard jvm-media ativo
                                                   #js {:filename "treinador-pokemon.png"
                                                        :playerName player-name}))]
                  (envelope-jvm pedido texto img))

                "time"
                (let [prefix (str config/prefix "pk")
                      marcadores-txt #{"txt" "texto"}
                      modo-texto? (boolean (some marcadores-txt resto))
                      resto* (vec (remove marcadores-txt resto))]
                  (cond
                    (contains? #{"csv" "planilha"} (str/lower-case (str (or (first resto*) ""))))
                    (p/let [d (.getTeamCsv jvm #js {:chatId chat-id :playerId player-id})
                            doc #js {:base64 (.-base64 d)
                                     :mimeType (or (.-mimeType d) "text/csv")
                                     :filename (or (.-filename d) "odisseu-time-pokemon.csv")}
                            texto (or (.-text d) "🎒 Seu time em CSV (odisseu-time-pokemon.csv).")]
                      (.envelope jvm-present (:requestId pedido) texto
                                  #js {:document doc :effects #js []}))

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
                            tokens (clj->js resto*)
                            texto-completo (.formatCollection jvm-present data tokens prefix)
                            caption-curta (.formatCollectionCardCaption jvm-present data tokens prefix)
                            page (.collectionPageSlice jvm-present data tokens)
                            slice (.-slice page)
                            img (when (and (not modo-texto?)
                                           slice
                                           (pos? (alength slice)))
                                  (render-seguro! ctx "pokemon_jvm_media_time"
                                    #(.renderTeamGrid jvm-media slice
                                                      #js {:filename "meu-time-pokemon.png"
                                                           :title "Meu Time Pokémon"
                                                           :trainerLevel (or (.-trainerLevel data) 1)
                                                           :ativoIndex (or (.-ativoIndex data) 0)})))
                            ;; Card-only: legenda curta (sem listagem). Texto completo só em `txt`
                            ;; ou quando o PNG falhar (fallback).
                            texto (cond
                                    modo-texto? texto-completo
                                    img caption-curta
                                    :else texto-completo)]
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
                                                  :itemId "espaco-pokemon" :qty 1})]
                    (envelope-jvm pedido (str "✅ +50 vagas Pokémon compradas. "
                                               "Consulte " config/prefix "pk espaco.")))
                  (p/let [data (desempenho/medir! ctx "pokemon_jvm_space"
                                  #(.getShop jvm #js {:chatId chat-id :playerId player-id}))]
                    (envelope-jvm pedido (.formatSpace jvm-present data (str config/prefix "pk")))))

                "times"
                (let [sub (str/lower-case (str (or (first resto) "")))
                      restp (vec (rest resto))
                      op-id (fn [suffix] (.stableOperationId jvm-transport (:requestId pedido) suffix))
                      team-name (fn [xs] (str/trim (str/join " " xs)))]
                  (cond
                    (str/blank? sub)
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.listTeams jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)})]
                      (envelope-jvm pedido (.formatTeams jvm-present d (str config/prefix "pk"))))
                    (= sub "salvar")
                    (let [raw (last restp) nome (team-name (butlast restp))
                          nums (str/split (str raw) #",")]
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              c (.getCollection jvm #js {:chatId chat-id :playerId player-id})
                              total (alength (or (.-collection c) #js []))
                              idxs (mapv #(parse-indice-1based (str/trim %) total) nums)]
                        (when (or (str/blank? nome) (not= 3 (count idxs)) (some nil? idxs))
                          (throw (erro-contrato (str "❓ Use " config/prefix "pk times salvar <nome> <1,2,3>."))))
                        (p/let [d (.saveTeam jvm #js {:chatId chat-id :playerId player-id
                                                       :saveId (.-activeSaveId a) :fenceGeneration (.-fenceGeneration a)
                                                       :operationId (op-id (str "team-save:" nome))
                                                       :name nome :indices (clj->js idxs)})]
                          (envelope-jvm pedido (str "✅ Escalação *" nome "* salva.")))))
                    (= sub "usar")
                    (let [dest (if (#{"liga" "ginasio" "ginásio"} (str/lower-case (str (last restp))))
                                 (str/lower-case (str (last restp))) "liga")
                          nome (team-name (if (= dest "liga") restp (butlast restp)))]
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              d (.useTeam jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                   :fenceGeneration (.-fenceGeneration a) :operationId (op-id (str "team-use:" nome ":" dest))
                                                   :name nome :destination (if (= dest "ginásio") "ginasio" dest)})]
                        (envelope-jvm pedido (str "✅ Escalação *" nome "* preparada para " dest "."))))
                    (contains? #{"excluir" "apagar" "remover"} sub)
                    (let [nome (team-name restp)]
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              d (.deleteTeam jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                      :fenceGeneration (.-fenceGeneration a) :operationId (op-id (str "team-delete:" nome))
                                                      :name nome})]
                        (envelope-jvm pedido (str "🗑️ Escalação *" nome "* excluída."))))
                    (= sub "ver")
                    (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            d (.listTeams jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)})]
                      (envelope-jvm pedido (.formatTeams jvm-present d (str config/prefix "pk"))))
                    :else (throw (erro-contrato (str "❓ Use " config/prefix "pk times salvar|usar|excluir.")))))

                "liga"
                (let [league-map {"iniciante" [1 10] "bronze" [11 25] "prata" [26 40]
                                  "ouro" [41 60] "diamante" [61 100]}
                      selected (str/lower-case (str (or (first resto) "")))]
                  (if (str/blank? selected)
                    (envelope-jvm pedido
                                  (str "🏆 *Ligas*\n"
                                       (str/join "\n" (map (fn [[n [lo hi]]] (str "• " n ": níveis " lo "–" hi)) league-map))
                                       "\n\nUse " config/prefix "pk liga <nome>."))
                    (if-not (contains? league-map selected)
                      (envelope-jvm pedido "❓ Liga inválida. Consulte !pk liga.")
                      (p/let [a (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              d (.selectLeague jvm #js {:chatId chat-id :playerId player-id :saveId (.-activeSaveId a)
                                                        :fenceGeneration (.-fenceGeneration a)
                                                        :operationId (.stableOperationId jvm-transport (:requestId pedido) (str "league:" selected))
                                                        :leagueId selected})]
                        (envelope-jvm pedido (str "✅ Liga " selected " selecionada. Use " config/prefix "pk times usar <nome> liga."))))))

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
                                      (render-seguro! ctx "pokemon_jvm_media_pokedex"
                                        #(.renderPokedexCard jvm-media poke
                                                             #js {:filename "pokedex.png"})))]
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
                (if (empty? resto)
                  (p/let [data (desempenho/medir! ctx "pokemon_jvm_active"
                                  #(.getActiveSave jvm #js {:chatId chat-id :playerId player-id}))]
                    (envelope-jvm pedido (.formatActiveSave jvm-present data)))
                  (p/let [listed (.listSaves jvm #js {:chatId chat-id :playerId player-id})
                          selected (.resolveSaveSelection jvm-present
                                                          (or (.-saves listed) #js [])
                                                          (first resto))]
                    (when-not selected
                      (throw (erro-contrato "❓ Save inválido. Use !pk saves para consultar.")))
                    (let [data (desempenho/medir! ctx "pokemon_jvm_activate"
                               #(.activateSave jvm #js {:chatId chat-id
                                                         :playerId player-id
                                                         :saveId (or (.-saveId selected) (.-slotId selected))
                                                         :expectedFenceGeneration (.-fenceGeneration listed)}))]
                      (envelope-jvm pedido (.formatActivate jvm-present data)))))

                "saves"
                (p/let [data (desempenho/medir! ctx "pokemon_jvm_saves"
                                #(.listSaves jvm #js {:chatId chat-id :playerId player-id}))]
                  (envelope-jvm pedido (.formatSavesList jvm-present data
                                                         (str config/prefix "pk"))))

                "save-management"
                (let [sub primeiro-arg]
                  (cond
                    (= sub "novo")
                    (do
                      (when-not (= "save" (str/lower-case (str (first resto))))
                        (throw (erro-contrato "❓ Use: !pk novo save [nome]")))
                      (let [desired-name (->> (rest resto)
                                              (remove str/blank?)
                                              (str/join " ")
                                              str/trim)
                            op-id (.stableOperationId jvm-transport (:requestId pedido) "save-create")]
                        (p/let [listed (.listSaves jvm #js {:chatId chat-id :playerId player-id})
                                slot-id (or (.nextSaveSlotId jvm-present
                                                              (or (.-saves listed) #js []))
                                            (throw (erro-contrato
                                                    "❓ Limite de saves atingido.")))
                                payload (clj->js
                                         (cond-> {:chatId chat-id
                                                  :playerId player-id
                                                  :saveId slot-id
                                                  :operationId op-id}
                                           (not (str/blank? desired-name))
                                           (assoc :displayName desired-name)))
                                data (.createSave jvm payload)
                                label (or (.-displayName data)
                                          (when-not (str/blank? desired-name) desired-name)
                                          (.-saveId data)
                                          (.-slotId data)
                                          slot-id)]
                          (envelope-jvm pedido
                                        (str "✅ Save criado: *" label "*.\n"
                                             "Use `!pk save " label "` para selecionar.\n"
                                             "(O save ativo não foi alterado.)")))))

                    (= sub "renomear")
                    (let [tokens (vec resto)]
                      (when-not (and (= "save" (str/lower-case (str (first tokens))))
                                     (>= (count tokens) 3))
                        (throw (erro-contrato
                                "❓ Use: !pk renomear save <nome|número|id> <novo-nome>")))
                      (let [target (second tokens)
                            name (str/join " " (drop 2 tokens))]
                        (p/let [listed (.listSaves jvm #js {:chatId chat-id :playerId player-id})
                                selected (.resolveSaveSelection jvm-present
                                                                (or (.-saves listed) #js [])
                                                                target)]
                          (when-not selected
                            (throw (erro-contrato "❓ Save inválido. Use !pk saves para consultar.")))
                          (let [data (.renameSave jvm #js {:chatId chat-id :playerId player-id
                                                           :saveId (or (.-saveId selected) (.-slotId selected))
                                                           :displayName name
                                                           :expectedFenceGeneration (.-fenceGeneration listed)
                                                           :operationId (.stableOperationId jvm-transport
                                                                                             (:requestId pedido)
                                                                                             (str "save-rename:"
                                                                                                  (or (.-saveId selected)
                                                                                                      (.-slotId selected))))})
                                shown (or (.-displayName data) name)]
                            (envelope-jvm pedido
                                          (str "✅ Save renomeado para *" shown "*.\n"
                                               "Use `!pk save " shown "` / `!pk saves`."))))))

                    :else
                    (throw (erro-contrato
                            "❓ Use: !pk novo save [nome] ou !pk renomear save <nome> <novo-nome>"))))

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
                  (when (str/blank? (str slot))
                    (throw (erro-contrato "Use: !pk ativar <nome|número|id>")))
                  (p/let [antes (.listSaves jvm #js {:chatId chat-id :playerId player-id})
                          selected (.resolveSaveSelection jvm-present
                                                          (or (.-saves antes) #js [])
                                                          slot)]
                    (when-not selected
                      (throw (erro-contrato "❓ Save inválido. Use !pk saves para consultar.")))
                    (p/let [data (desempenho/medir! ctx "pokemon_jvm_activate"
                                   #(.activateSave jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (or (.-saveId selected) (.-slotId selected))
                                           :expectedFenceGeneration (.-fenceGeneration antes)}))]
                      (envelope-jvm pedido (.formatActivate jvm-present data)))))

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
                      args-gin (vec (rest resto))
                      prefix-pk (str config/prefix "pk")]
                  (cond
                    (contains? #{"ajuda" "help" "?"} (str/lower-case (str (or (first resto) ""))))
                    (envelope-jvm pedido (.formatHelp jvm-present "ginasio" prefix-pk))

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
                                  ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                                  op-id (.stableOperationId jvm-transport
                                                            (:requestId pedido)
                                                            (str "gym-challenge:" gym-id))
                                  ;; NPC: sorteio canônico JVM (pool) na criação — não a cada turno.
                                  ;; Ocupado por jogador: sem candidates (time do defensor no store).
                                  cands (if ocup
                                          #js []
                                          (p/let [rolled (desempenho/medir! ctx "pokemon_jvm_gym_npc_roll"
                                                           #(.rollGymNpcSpecies jvm
                                                              #js {:gymId gym-id :seed op-id}))
                                                  spp (.-species rolled)]
                                            (desempenho/medir! ctx "pokemon_jvm_gym_npc"
                                              #(.buildGymNpcTeam pokeapi spp))))
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
                                  hp-map (some-> battle .-hp)
                                  px (some-> battle .-pokemonX)
                                  po (some-> battle .-pokemonO)
                                  img (render-seguro! ctx "pokemon_jvm_media_gym"
                                        #(.renderHuntArena jvm-media
                                            #js {:area nil
                                                 :pokemonX px
                                                 :pokemonO po
                                                 :fugiu false
                                                 :aguardandoCaptura false
                                                 :caption texto
                                                 :mostrarEntrada true
                                                 :hpX (when hp-map
                                                        #js {:atual (.-x hp-map)
                                                             :max (or (some-> px .-hp)
                                                                      (.-x hp-map))})
                                                 :hpO (when hp-map
                                                        #js {:atual (.-o hp-map)
                                                             :max (or (some-> po .-hp)
                                                                      (.-o hp-map))})}))]
                            (envelope-jvm pedido texto img)))))

                    (contains? #{"ranking" "historico"} sub)
                    (let [gym-id (when (seq args-gin)
                                   (str/lower-case (str (first args-gin))))
                          fetch (fn [gid]
                                  (if (= "ranking" sub)
                                    (.getGymRanking jvm #js {:chatId chat-id :playerId player-id
                                                             :gymId gid})
                                    (.getGymHistory jvm #js {:chatId chat-id :playerId player-id
                                                             :gymId gid})))]
                      (if gym-id
                        (p/let [data (desempenho/medir! ctx "pokemon_jvm_gym_stats"
                                                        #(fetch gym-id))]
                          (envelope-jvm pedido
                                        (if (= "ranking" sub)
                                          (.formatGymRanking jvm-present data)
                                          (.formatGymHistory jvm-present data))))
                        (p/let [overview (desempenho/medir! ctx "pokemon_jvm_gyms"
                                                            #(.listGyms jvm #js {:chatId chat-id
                                                                                  :playerId player-id}))
                                rows (js->clj (.-gyms overview) :keywordize-keys true)
                                results (p/all (map (fn [row]
                                                      (desempenho/medir! ctx "pokemon_jvm_gym_stats"
                                                                          (fn [] (fetch (:id row)))))
                                                    rows))]
                          (envelope-jvm pedido
                                        (str/join "\n\n"
                                                  (map #(if (= "ranking" sub)
                                                          (.formatGymRanking jvm-present %)
                                                          (.formatGymHistory jvm-present %))
                                                       results))))))

                    (contains? #{"abrir" "entrar" "iniciar" "sair" "cancelar" "atacar" "capturar"} sub)
                    ;; Mesmos fluxos que !pk raide <sub> … (abrir N sem menu prévio).
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
                (let [area-pedida (not-empty (str/join " " resto))]
                  (if (contains? #{"ajuda" "help" "?"} (str/lower-case (str (or area-pedida ""))))
                    (envelope-jvm pedido
                                  (.formatHelp jvm-present "cacar" (str config/prefix "pk")))
                  (p/let [plano (desempenho/medir! ctx "pokemon_jvm_hunt_plan"
                                   #(.getHuntPlan jvm
                                      #js {:chatId chat-id
                                           :playerId player-id
                                           :areaId area-pedida}))
                          bloqueio (.formatHuntEligibility jvm-present plano)]
                    (if bloqueio
                      (envelope-jvm pedido bloqueio)
                      (p/let [cands (desempenho/medir! ctx "pokemon_jvm_pokeapi"
                                       #(.buildHuntCandidates pokeapi plano))
                              op-id (.stableOperationId jvm-transport
                                                        (:requestId pedido) "hunt-start")
                              ;; Fence fresco pós-pokeapi; 1 retry só em stale.
                              started (start-hunt-jvm!
                                       jvm pokeapi ctx chat-id player-id
                                       (or area-pedida (some-> plano .-area .-id))
                                       plano cands op-id)]
                        (if-let [bloqueio2 (:bloqueio started)]
                          (envelope-jvm pedido bloqueio2)
                          (p/let [plano-final (:plano started)
                                  data (:data started)
                                  texto (.formatHuntStart jvm-present plano-final data
                                                          (str config/prefix "pk"))
                                  battle (.-battle data)
                                  img (render-seguro! ctx "pokemon_jvm_media_hunt"
                                        #(.renderHuntArena jvm-media
                                            #js {:area (or (.-area data)
                                                           (.-area plano-final))
                                                 :pokemonX (some-> battle .-pokemonX)
                                                 :pokemonO (or (.-selvagem data)
                                                               (some-> battle .-pokemonO))
                                                 :fugiu false
                                                 :aguardandoCaptura false
                                                 :caption texto
                                                 :mostrarEntrada true}))]
                            (envelope-jvm pedido texto img))))))))

                "fruta"
                (p/let [enc (desempenho/medir! ctx "pokemon_jvm_encounter"
                               #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))
                        encontro (.-encounter enc)]
                  (cond
                    (nil? encontro)
                    (envelope-jvm pedido
                                  (str "❓ Não há encontro de caça para usar Frambo. "
                                       "Em raids: " config/prefix "pk raide frambo"))

                    (not (.-aguardandoCaptura encontro))
                    (envelope-jvm pedido
                                  "⚔️ Derrote o Pokémon selvagem antes de usar a Fruta Frambo.")

                    :else
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                      (str "encounter-frambo:"
                                                           (.-battleId encontro)))
                            _ (desempenho/medir! ctx "pokemon_jvm_encounter_frambo"
                                #(.prepareEncounterFrambo jvm
                                   #js {:chatId chat-id
                                        :playerId player-id
                                        :saveId (.-activeSaveId ativo)
                                        :fenceGeneration (.-fenceGeneration ativo)
                                        :operationId op-id
                                        :battleId (.-battleId encontro)}))
                            enc2 (desempenho/medir! ctx "pokemon_jvm_encounter"
                                   #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))
                            texto (.formatCaptureMenu jvm-present enc2
                                                      (str config/prefix "pk"))]
                      (envelope-jvm pedido texto))))

                "capturar"
                (p/let [enc (desempenho/medir! ctx "pokemon_jvm_encounter"
                               #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))]
                  (if (empty? resto)
                    (envelope-jvm pedido
                                  (.formatCaptureMenu jvm-present enc
                                                      (str config/prefix "pk")))
                    (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                            tokens (mapv str/lower-case resto)
                            frambo? (boolean (some #(contains? #{"frambo" "framb" "razz" "fruta"} %)
                                                   tokens))
                            bola-tokens (remove #(contains? #{"frambo" "framb" "razz" "fruta"} %)
                                                tokens)
                            op-id (.stableOperationId jvm-transport
                                                      (:requestId pedido)
                                                      (str "encounter-capture:"
                                                           (str/join "-" resto)))
                            body (clj->js {:chatId chat-id
                                           :playerId player-id
                                           :saveId (.-activeSaveId ativo)
                                           :fenceGeneration (.-fenceGeneration ativo)
                                           :operationId op-id
                                           :bola (str/join " " bola-tokens)
                                           :battleId (some-> (.-encounter enc) .-battleId)
                                           :frambo frambo?})
                            data (desempenho/medir! ctx "pokemon_jvm_capture"
                                   #(.attemptEncounterCapture jvm body))
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
                    ;; turnIndex 0 = luta acabou de começar → entrada com os dois.
                    (responder-pvp! ctx pedido battle player-id
                                    {:entrada? (or (nil? (.-turnIndex battle))
                                                   (zero? (js/Number (.-turnIndex battle))))})

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
                                             :battleId (.-battleId battle)}))
                              b (.-battle data)]
                        ;; Aceite: imagem vs com os dois Pokémon + menu da vez.
                        (responder-pvp! ctx pedido b player-id {:entrada? true})))

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
                (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                        enc (desempenho/medir! ctx "pokemon_jvm_encounter"
                              #(.getEncounter jvm #js {:chatId chat-id :playerId player-id}))
                        encontro (some-> enc .-encounter)
                        bid (some-> encontro .-battleId)
                        op-id (.stableOperationId jvm-transport
                                                  (:requestId pedido) "battle-leave")
                        data (desempenho/medir! ctx "pokemon_jvm_battle_leave"
                               #(.leaveBattle jvm
                                  (clj->js (cond-> {:chatId chat-id
                                                    :playerId player-id
                                                    :saveId (.-activeSaveId ativo)
                                                    :fenceGeneration (.-fenceGeneration ativo)
                                                    :operationId op-id}
                                             bid (assoc :battleId bid)))))]
                  (envelope-jvm pedido
                                (.formatLeaveResult jvm-present data
                                                    (str config/prefix "pk"))))

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
                                     #(.getShop jvm #js {:chatId chat-id :playerId player-id}))
                            texto (.formatShop jvm-present data "!loja")
                            img (render-seguro! ctx "pokemon_jvm_media_loja"
                                  #(.renderStaticAsset jvm-media "loja"
                                                       #js {:filename "loja-pokemon.png"}))]
                      (envelope-jvm pedido texto img))

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
                    (contains? #{"ajuda" "help" "?"} sub)
                    (envelope-jvm pedido
                                  (.formatHelp jvm-present "mochila" (str config/prefix "pk")))

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
                    (if-not config/pokemon-bag-kit-daily-enabled?
                      (envelope-jvm pedido
                                    (str "🚧 `" sub "` aguarda backfill das flags recovery "
                                         "(POKEMON_BAG_KIT_DAILY_ENABLED). Use !mochila para ver o inventário."))
                      (p/let [ativo (.getActiveSave jvm #js {:chatId chat-id :playerId player-id})
                              kit? (= "kit" sub)
                              op-id (.stableOperationId jvm-transport (:requestId pedido)
                                                                (if kit?
                                                                  "bag-kit"
                                                                  (str "bag-daily:"
                                                                       ;; dia resolvido na JVM; op estável por request
                                                                       (or (:requestId pedido) "x"))))
                              data (desempenho/medir! ctx
                                     (if kit? "pokemon_jvm_bag_kit" "pokemon_jvm_bag_daily")
                                     #(if kit?
                                        (.claimBagKit jvm
                                          #js {:chatId chat-id :playerId player-id
                                               :operationId op-id})
                                        (.claimBagDaily jvm
                                          #js {:chatId chat-id :playerId player-id
                                               :operationId op-id})))]
                        (envelope-jvm pedido
                                      (if kit?
                                        (.formatBagKit jvm-present data "!mochila")
                                        (.formatBagDaily jvm-present data "!mochila")))))

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
        (entregar-envelope-jvm! message ctx pedido envelope))))))))

(defn executar [message comando argumentos]
  (let [ctx (desempenho/contexto-de message)
        argumentos (or argumentos "")]
    (enfileirar!
     (chat-id message)
     (fn []
       (if (jvm-configurado?)
         ;; Adaptador JVM: fail-closed; sem fallback Cassandra/legado em falha.
         ;; Top-level !pokedex é espécie (PokeAPI no Node), não a coleção pessoal.
         (-> (p/let [pedido (contexto-pedido message comando argumentos)
                     _ (if (especie-pokedex-top-level? comando)
                         (executar-especie-pokedex! message ctx pedido argumentos)
                         (executar-jvm! message ctx pedido
                                        (tokens-para-jvm comando argumentos)))]
               nil)
             (p/catch (fn [erro]
                        (js/console.warn "[PokemonJVM] Falha:" (or (.-code erro) "DELIVERY")
                                         (or (.-status erro) "") (.-message erro)
                                         (when (.-details erro)
                                           (js/JSON.stringify (.-details erro))))
                        (let [texto (mensagem-falha erro)]
                          (.reply message texto)))))
         ;; Adaptador legado: pokemon-service trata !pokedex como espécie.
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

(defn- log-raid-auto-delivery! [event fields]
  (js/console.warn
   (js/JSON.stringify
    (clj->js (merge {:event event :ts (.now js/Date)} fields)))))

(defn- evento-sistema-outbox?
  "Anúncios/lembretes de raide automática / eventos temáticos não têm playerId."
  [evento]
  (let [kind (str (or (:kind evento) ""))]
    (or (str/starts-with? kind "raid-reminder")
        (#{"raid-opened" "raid-started" "raid-cancelled"
           "thematic-event-started" "thematic-event-ended"} kind))))

(defn- entregar-evento-jvm!
  "Retorna a promise do sendMessage — ack JVM só depois do WhatsApp aceitar.
   Aceita outbox de raide (:texto) e notificações idle (:text / payload).
   Lembretes raid-reminder-* → PNG ovo local + legenda.
   raid-opened → PNG do chefe (revelação) + legenda; fallback texto."
  [evento]
  (if-let [whatsapp @cliente-whatsapp]
    (let [texto (or (:texto evento)
                    (:text evento)
                    (get-in evento [:payload :text])
                    (str "⏳ Tempo limite atingido! A batalha foi encerrada por inatividade"
                         " (" config/pokemon-battle-idle-timeout-seconds
                         "s sem ação válida). Sem recompensas de vitória."))
          mention-ids (or (when-let [xs (:mentions evento)]
                            (when (seq xs) (vec xs)))
                          (when-let [pid (or (:playerId evento) (:player-id evento))]
                            [pid]))
          mentions (when (seq mention-ids) (clj->js mention-ids))
          opts (clj->js (cond-> {} mentions (assoc :mentions mentions)))
          kind (str (or (:kind evento) ""))
          enviar-imagem!
          (fn [media-info fallback-name]
            (if media-info
              (let [media (MessageMedia. (or (.-mimeType media-info) "image/png")
                                         (.-base64 media-info)
                                         (or (.-filename media-info) fallback-name))]
                (.sendMessage whatsapp (:chatId evento) media
                              (js/Object.assign #js {} opts
                                                #js {:caption texto})))
              (.sendMessage whatsapp (:chatId evento) texto opts)))]
      (cond
        (.isRaidReminderKind jvm-media kind)
        (-> (p/let [media-info (.renderRaidEggReminder jvm-media kind)
                    _ (enviar-imagem! media-info "raid-egg.png")]
              nil)
            (p/catch (fn [erro]
                       (js/console.warn "[PokemonJVM] Lembrete raid imagem falhou; texto:"
                                        (or (.-message erro) erro))
                       (.sendMessage whatsapp (:chatId evento) texto opts))))

        (.isRaidOpenedKind jvm-media kind)
        (-> (p/let [media-js (clj->js (or (:media evento) {}))
                    media-info (.renderRaidOpenedReveal jvm-media media-js)
                    _ (enviar-imagem! media-info "raid-revelada.png")]
              nil)
            (p/catch (fn [erro]
                       (js/console.warn "[PokemonJVM] Revelação raid imagem falhou; texto:"
                                        (or (.-message erro) erro))
                       (.sendMessage whatsapp (:chatId evento) texto opts))))

        ;; raid-started: cartão visual desde o 1º frame (sprites rehidratados no media).
        (= "raid-started" kind)
        (-> (p/let [payload (or (:payload evento) {})
                    raid (or (:raid payload) (:raid evento) {})
                    media-info (.renderRaidBattleCard jvm-media
                                 #js {:ginasio (or (:nomeGinasio raid) (:nome-ginasio raid)
                                                   (:ginasio raid) "")
                                      :chefe (clj->js (or (:chefe raid) {}))
                                      :atacante (clj->js (or (:vezPokemon raid) (:vez-pokemon raid) {}))
                                      :treinador (or (:vezNome raid) (:vez-nome raid) "")
                                      :hpChefe (:hpChefe raid)
                                      :hpMax (:hpMax raid)
                                      :hpAtacante (or (:vezHp raid) (:vez-hp raid))
                                      :fase (or (:fase raid) "combate")
                                      :turnIndex (or (:turnIndex raid) (:turn-index raid) 0)})
                    _ (enviar-imagem! media-info "raid-batalha.png")]
              nil)
            (p/catch (fn [erro]
                       (js/console.warn "[PokemonJVM] raid-started imagem falhou; texto:"
                                        (or (.-message erro) erro))
                       (.sendMessage whatsapp (:chatId evento) texto opts))))

        :else
        (.sendMessage whatsapp (:chatId evento) texto opts)))
    (p/rejected (js/Error. "WhatsApp client ausente no envio de evento JVM."))))

(defn- consultar-eventos-jvm! []
  (when (and @cliente-whatsapp (jvm-configurado?)
             (compare-and-set! consultando-eventos? false true))
    (-> (p/let [jvm (cliente-jvm!)
                resposta (.pendingEvents jvm #js {:limit 50})
                eventos (js->clj (.-events resposta) :keywordize-keys true)]
          (when-not (vector? eventos) (throw (erro-contrato "Lista de eventos JVM inválida.")))
          (reduce
           (fn [anterior evento]
             (p/then anterior
                     (fn [_]
                       (when (and @cliente-whatsapp
                                  (config/chat-autorizado? (:chatId evento))
                                  ;; Outbox de sistema (raid auto/lembrete) não tem playerId.
                                  (or (evento-sistema-outbox? evento)
                                      (config/usuario-autorizado?
                                       (or (:playerId evento) (:player-id evento)))))
                         (enfileirar!
                          (:chatId evento)
                          #(-> (p/let [_ (when-not (and (string? (:id evento)) (string? (:chatId evento)))
                                           (throw (erro-contrato "Identidade de evento JVM inválida.")))
                                       ;; At-least-once: envia, depois ack. Falha entre os dois
                                       ;; pode reenviar a mesma mensagem (id estável; sem nova op de jogo).
                                       _ (entregar-evento-jvm! evento)
                                       _ (.ackEvent (cliente-jvm!) (:id evento) #js {})]
                                 nil)
                               (p/catch (fn [erro]
                                          (log-raid-auto-delivery!
                                           "raid_auto_delivery_failed"
                                           {:chatId (:chatId evento)
                                            :eventId (:id evento)
                                            :kind (:kind evento)
                                            :result (or (.-code erro) "DELIVERY")})
                                          (throw erro))))
                          nil)))))
           (p/resolved nil) eventos))
        (p/catch (fn [erro]
                   (js/console.warn "[PokemonJVM] Eventos pendentes, nova tentativa no próximo ciclo:"
                                    (or (.-code erro) "DELIVERY"))))
        (p/finally #(reset! consultando-eventos? false)))))

(defn- tick-raid-auto-jvm! []
  (when (and (jvm-configurado?) config/pokemon-raid-auto-tick?)
    (-> (p/let [jvm (cliente-jvm!)
                _ (.tickRaidAuto jvm #js {})]
          nil)
        (p/catch (fn [erro]
                   (js/console.warn "[PokemonJVM] Tick raid-auto falhou:"
                                    (or (.-code erro) "TICK") (.-message erro)))))))

(defn- tick-thematic-events-jvm! []
  "Tick de eventos temáticos — no-op se flag off no JVM."
  (when (jvm-configurado?)
    (-> (p/let [jvm (cliente-jvm!)
                _ (.tickThematicEvents jvm #js {})]
          nil)
        (p/catch (fn [erro]
                   (js/console.warn "[PokemonJVM] Tick thematic-events falhou:"
                                    (or (.-code erro) "TICK") (.-message erro)))))))

(defn- consultar-eventos-interno! []
  (cond
    (jvm-configurado?)
    (do (tick-raid-auto-jvm!)
        (tick-thematic-events-jvm!)
        (consultar-eventos-jvm!))

    (and @cliente-whatsapp (configurado?) (compare-and-set! consultando-eventos? false true))
    (-> (p/let [http (cliente!)
                resposta (.pendingEvents http)
                eventos (js->clj (.-events resposta) :keywordize-keys true)]
          (when-not (vector? eventos) (throw (erro-contrato "Lista de eventos Pokémon inválida.")))
          (reduce
           (fn [anterior evento]
             (p/then anterior
                     (fn [_]
                       (when (and @cliente-whatsapp
                                  (config/chat-autorizado? (:chatId evento))
                                  (config/usuario-autorizado?
                                   (or (:playerId evento) (:player-id evento))))
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

(defn- consultar-eventos! []
  ;; O poller de raids/eventos também é produtor assíncrono. O lease local cobre
  ;; toda a consulta, envio e ACK, permitindo o drain antes do gate Datomic.
  (when (.begin node-maintenance)
    (try
      (let [result (consultar-eventos-interno!)]
        (if (and result (fn? (.-then ^js result)))
          (p/finally result #(.end node-maintenance))
          (do (.end node-maintenance) result)))
      (catch :default err
        (.end node-maintenance)
        (js/console.warn "[PokemonHTTP] Poller falhou de forma isolada:" (or (.-code err) "POLL"))
        nil))))

(defn parar! []
  (reset! cliente-whatsapp nil)
  (when-let [timer @relogio-eventos] (js/clearInterval timer))
  (reset! relogio-eventos nil))

(defn iniciar! [client]
  (parar!)
  (reset! cliente-whatsapp client)
  (when (or (configurado?) (jvm-configurado?))
    (let [timer (js/setInterval consultar-eventos! config/pokemon-event-poll-ms)]
      (.unref timer)
      (reset! relogio-eventos timer))
    (consultar-eventos!)))
