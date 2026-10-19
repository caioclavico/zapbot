(ns zapbot.lembretes
  "Lembretes agendados. Produção: Cassandra + poller local.
   Staging (PLATFORM_ADAPTER=datomic): Datomic + claim/lease/fence via bridge.
   Sem dual-write; sem twin pollers."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]
            [zapbot.platform-jvm :as platform]))

(defn- normalizar-lembretes [dados]
  (into {}
        (map (fn [[id item]]
               [id {:id (or (:id item) (get item "id"))
                    :chat (or (:chat item) (get item "chat"))
                    :texto (or (:texto item) (get item "texto"))
                    :criador (or (:criador item) (get item "criador"))
                    :em (or (:em item) (get item "em"))
                    :status (or (:status item) (get item "status") "pending")
                    :fence (or (:fence item) (get item "fence") 0)}]))
        (or dados {})))

(defonce ^:private lembretes
  (atom (normalizar-lembretes
         (when-not (platform/enabled?)
           (armazenamento/obter "lembretes")))))

(defonce ^:private enviando (atom #{}))
(defonce ^:private verificador (atom nil))
(defonce ^:private claimant-id
  (atom (str "node-" (or (.-pid js/process) "0") "-" (.now js/Date))))

(when-not (platform/enabled?)
  (armazenamento/registrar! "lembretes" lembretes normalizar-lembretes))

(defn- chat-id [message] (if (.-fromMe message) (.-to message) (.-from message)))
(defn- criador-id [message]
  (or (.-author message)
      (when-not (.-fromMe message) (.-from message))))

(defn- salvar-cassandra! []
  (armazenamento/salvar! "lembretes" @lembretes))

(defn- codigo-id []
  (let [tempo (str/upper-case (.toString (.now js/Date) 36))
        tempo (subs tempo (max 0 (- (count tempo) 4)))
        aleatorio (str/upper-case (.toString (js/Math.floor (* (js/Math.random) 46656)) 36))
        aleatorio (subs (str "000" aleatorio) (- (count (str "000" aleatorio)) 3))]
    (str "L-" tempo "-" aleatorio)))

(defn- novo-id []
  (loop [id (codigo-id)]
    (if (contains? @lembretes id) (recur (codigo-id)) id)))

(defn- nome-para-mencao [id]
  (first (str/split (or id "") #"@")))

(defn- duracao-ms [texto]
  (when-let [[_ n u] (re-matches #"(\d+)\s*(s|seg|segs|segundo|segundos|m|min|mins|minuto|minutos|h|hora|horas|d|dia|dias)"
                                  (str/lower-case (str/trim (or texto ""))))]
    (let [unidade (str/lower-case u)
          fator (cond
                  (contains? #{"s" "seg" "segs" "segundo" "segundos"} unidade) 1000
                  (contains? #{"m" "min" "mins" "minuto" "minutos"} unidade) (* 60 1000)
                  (contains? #{"h" "hora" "horas"} unidade) (* 60 60 1000)
                  :else (* 24 60 60 1000))
          ms (* (js/parseInt n 10) fator)]
      (when (and (pos? ms) (<= ms (* 365 24 60 60 1000))) ms))))

(defn- wire->item [wire]
  (when wire
    (let [m (js->clj wire :keywordize-keys false)]
      {:id (str (or (get m "id") ""))
       :chat (str (or (get m "chat") ""))
       :texto (str (or (get m "texto") ""))
       :criador (when-let [c (get m "criador")] (when-not (str/blank? c) c))
       :em (long (or (get m "em") 0))
       :status (str (or (get m "status") "pending"))
       :fence (long (or (get m "fence") 0))})))

(defn- apply-reminder-wire! [wire]
  (when-let [item (wire->item wire)]
    (if (#{"completed" "cancelled"} (:status item))
      (swap! lembretes dissoc (:id item))
      (swap! lembretes assoc (:id item) item))))

(defn hidratar-datomic!
  []
  (when (platform/enabled?)
    (platform/hydrate-reminders! lembretes normalizar-lembretes)))

(defn- criar-cassandra! [message args]
  (let [[duracao & partes] (str/split (str/trim args) #"\s+")
        texto (str/trim (str/join " " partes))
        ms (duracao-ms duracao)]
    (cond
      (nil? ms) "❓ Use: !lembrete <tempo> <mensagem>. Ex.: !lembrete 10s caçar; !lembrete 30m reunião; !lembrete 2h estudar."
      (str/blank? texto) "❓ Escreva o que devo lembrar. Ex.: !lembrete 30m reunião."
      :else
      (let [id (novo-id)
            item {:id id :chat (chat-id message) :texto texto :criador (criador-id message) :em (+ (.now js/Date) ms)}]
        (swap! lembretes assoc id item)
        (salvar-cassandra!)
        (str "⏰ Lembrete criado para daqui a *" duracao "*: " texto "\nID: " id)))))

(defn- criar-datomic! [message args]
  (let [[duracao & partes] (str/split (str/trim args) #"\s+")
        texto (str/trim (str/join " " partes))
        ms (duracao-ms duracao)]
    (cond
      (nil? ms)
      (p/resolved "❓ Use: !lembrete <tempo> <mensagem>. Ex.: !lembrete 10s caçar; !lembrete 30m reunião; !lembrete 2h estudar.")

      (str/blank? texto)
      (p/resolved "❓ Escreva o que devo lembrar. Ex.: !lembrete 30m reunião.")

      :else
      (let [id (novo-id)
            item {:id id
                  :chatId (chat-id message)
                  :texto texto
                  :criador (criador-id message)
                  :em (+ (.now js/Date) ms)}
            op (platform/op-id (str "reminder-create:" id))]
        (-> (platform/create-reminder! item op)
            (p/then (fn [data]
                      (apply-reminder-wire! (.-reminder data))
                      (str "⏰ Lembrete criado para daqui a *" duracao "*: " texto "\nID: " id)))
            (p/catch (fn [err]
                       (js/console.error "lembretes(datomic): falha ao criar (fail-closed):" err)
                       (p/rejected err))))))))

(defn criar! [message args]
  (if (platform/enabled?)
    (criar-datomic! message args)
    (p/resolved (criar-cassandra! message args))))

(defn- listar-texto [cid]
  (let [itens (->> @lembretes vals
                   (filter #(= (:chat %) cid))
                   (filter #(contains? #{"pending" "claimed" "failed" nil} (:status %)))
                   (sort-by :em))]
    (if (empty? itens)
      "⏰ Não há lembretes pendentes neste chat."
      (str "⏰ *Lembretes pendentes*\n\n"
           (str/join "\n" (map #(str "• " (:texto %) " — " (.toLocaleString (js/Date. (:em %))) "\n  ID: " (:id %)) itens))))))

(defn listar [message]
  (let [cid (chat-id message)]
    (if (platform/enabled?)
      (-> (platform/fetch-reminders cid)
          (p/then (fn [rows]
                    (doseq [r rows] (apply-reminder-wire! r))
                    (listar-texto cid)))
          (p/catch (fn [err]
                     (js/console.error "lembretes(datomic): falha ao listar (fail-closed):" err)
                     (p/rejected err))))
      (p/resolved (listar-texto cid)))))

(defn- cancelar-cassandra! [message id]
  (let [id (str/lower-case (str/trim id))
        item (some (fn [[codigo lembrete]]
                     (when (= id (str/lower-case codigo)) lembrete))
                   @lembretes)]
    (if (and item (= (:chat item) (chat-id message)))
      (do (swap! lembretes dissoc (:id item)) (salvar-cassandra!) (str "✅ Lembrete cancelado: " (:texto item)))
      "❓ Não encontrei esse lembrete neste chat. Use !lembretes para ver os IDs.")))

(defn- cancelar-datomic! [message id]
  (let [id* (str/lower-case (str/trim id))
        item (some (fn [[codigo lembrete]]
                     (when (= id* (str/lower-case codigo)) lembrete))
                   @lembretes)
        cid (chat-id message)]
    (if (and item (= (:chat item) cid))
      (let [op (platform/op-id (str "reminder-cancel:" (:id item)))]
        (-> (platform/cancel-reminder! (:id item) cid op)
            (p/then (fn [data]
                      (apply-reminder-wire! (.-reminder data))
                      (str "✅ Lembrete cancelado: " (:texto item))))
            (p/catch (fn [err]
                       (let [code (.-code err)]
                         (if (= code "operation_not_found")
                           "❓ Não encontrei esse lembrete neste chat. Use !lembretes para ver os IDs."
                           (do (js/console.error "lembretes(datomic): falha ao cancelar (fail-closed):" err)
                               (p/rejected err))))))))
      (p/resolved "❓ Não encontrei esse lembrete neste chat. Use !lembretes para ver os IDs."))))

(defn cancelar! [message id]
  (if (platform/enabled?)
    (cancelar-datomic! message id)
    (p/resolved (cancelar-cassandra! message id))))

(defn- texto-envio [item]
  (let [criador (:criador item)]
    (str "⏰ *Lembrete do tio Odisseu*\n"
         (if criador (str "@" (nome-para-mencao criador) ", ") "")
         (:texto item))))

(defn- verificar-cassandra! [client]
  (doseq [[id item] @lembretes
          :when (and (<= (:em item) (.now js/Date))
                     (not (contains? @enviando id))
                     (config/chat-autorizado? (:chat item)))]
    (swap! enviando conj id)
    (let [criador (:criador item)
          texto (texto-envio item)]
      (-> (if criador
            (.sendMessage client (:chat item) texto #js {:mentions #js [criador]})
            (.sendMessage client (:chat item) texto))
          (p/then (fn [_] (swap! lembretes dissoc id) (salvar-cassandra!) (swap! enviando disj id)))
          (p/catch (fn [err] (js/console.error "Erro ao enviar lembrete:" err) (swap! enviando disj id)))))))

(def ^:private lease-ms 30000)

(defn- enviar-claimed! [client item fence]
  "Política: claim → send → complete. Resultado incerto do WA pode duplicar em retry."
  (let [id (:id item)
        who @claimant-id
        criador (:criador item)
        texto (texto-envio item)
        send! (if criador
                #(.sendMessage client (:chat item) texto #js {:mentions #js [criador]})
                #(.sendMessage client (:chat item) texto))]
    (-> (send!)
        (p/then (fn [_]
                  (platform/complete-reminder! id who fence)))
        (p/then (fn [data]
                  (apply-reminder-wire! (.-reminder data))
                  (swap! enviando disj id)
                  true))
        (p/catch (fn [err]
                   (js/console.error "lembretes(datomic): envio/complete falhou:" err)
                   (-> (platform/fail-reminder! id who fence (or (.-message err) "send_failed"))
                       (p/then (fn [data]
                                 (apply-reminder-wire! (.-reminder data))
                                 (swap! enviando disj id)
                                 false))
                       (p/catch (fn [ferr]
                                  (js/console.error "lembretes(datomic): fail-reminder falhou:" ferr)
                                  (swap! enviando disj id)
                                  false))))))))

(defn- verificar-datomic! [client]
  "Único poller em staging: due → claim (CAS) → WA → complete/fail.
   Nunca usa Cassandra nem o set enviando-only like legado."
  (-> (platform/due-reminders (.now js/Date) 20)
      (p/then
       (fn [due]
         (p/all
          (mapv
           (fn [raw]
             (let [item (wire->item raw)
                   id (:id item)]
               (cond
                 (contains? @enviando id)
                 (p/resolved false)

                 (not (config/chat-autorizado? (:chat item)))
                 (p/resolved false)

                 :else
                 (do
                   (swap! enviando conj id)
                   (-> (platform/claim-reminder! id @claimant-id lease-ms (.now js/Date))
                       (p/then (fn [res]
                                 (if (.-claimed res)
                                   (enviar-claimed! client (or (wire->item (.-reminder res)) item)
                                                    (.-fence res))
                                   (do (swap! enviando disj id) false))))
                       (p/catch (fn [err]
                                  (js/console.error "lembretes(datomic): claim falhou:" err)
                                  (swap! enviando disj id)
                                  false)))))))
           (or due [])))))
      (p/catch (fn [err]
                 (js/console.error "lembretes(datomic): due falhou:" err)))))

(defn iniciar! [client]
  "Inicia uma única checagem periódica após o WhatsApp estar conectado.
   Em datomic: só o poller com claim/lease (Cassandra poller desligado)."
  (when-not @verificador
    (if (platform/enabled?)
      (do
        (verificar-datomic! client)
        (reset! verificador (js/setInterval #(verificar-datomic! client) 1000)))
      (do
        (verificar-cassandra! client)
        (reset! verificador (js/setInterval #(verificar-cassandra! client) 1000))))))
