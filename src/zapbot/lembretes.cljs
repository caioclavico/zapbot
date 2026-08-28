(ns zapbot.lembretes
  "Lembretes agendados, persistidos por chat no Cassandra."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]))

(defn- normalizar-lembretes [dados]
  (into {}
        (map (fn [[id item]]
               [id {:id (or (:id item) (get item "id"))
                    :chat (or (:chat item) (get item "chat"))
                    :texto (or (:texto item) (get item "texto"))
                    :criador (or (:criador item) (get item "criador"))
                    :em (or (:em item) (get item "em"))}]))
        (or dados {})))

(defonce ^:private lembretes (atom (normalizar-lembretes (armazenamento/obter "lembretes"))))
(defonce ^:private enviando (atom #{}))
(defonce ^:private verificador (atom nil))
(armazenamento/registrar! "lembretes" lembretes normalizar-lembretes)

(defn- chat-id [message] (if (.-fromMe message) (.-to message) (.-from message)))
(defn- criador-id [message]
  ;; Em grupos, até mensagem do próprio número pode ter `author` válido.
  ;; Só evitamos o fallback para `from` nesse caso, pois ele é o id do grupo.
  (or (.-author message)
      (when-not (.-fromMe message) (.-from message))))
(defn- salvar! [] (armazenamento/salvar! "lembretes" @lembretes))

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
  (when-let [[_ n u] (re-matches #"(\d+)\s*(m|min|mins|minuto|minutos|h|hora|horas|d|dia|dias)"
                                  (str/lower-case (str/trim (or texto ""))))]
    (let [unidade (str/lower-case u)
          fator (cond
                  (contains? #{"m" "min" "mins" "minuto" "minutos"} unidade) (* 60 1000)
                  (contains? #{"h" "hora" "horas"} unidade) (* 60 60 1000)
                  :else (* 24 60 60 1000))
          ms (* (js/parseInt n 10) fator)]
      (when (<= ms (* 365 24 60 60 1000)) ms))))

(defn criar! [message args]
  (let [[duracao & partes] (str/split (str/trim args) #"\s+")
        texto (str/trim (str/join " " partes))
        ms (duracao-ms duracao)]
    (cond
      (nil? ms) "❓ Use: !lembrete <tempo> <mensagem>. Ex.: !lembrete 30m reunião; !lembrete 2h estudar."
      (str/blank? texto) "❓ Escreva o que devo lembrar. Ex.: !lembrete 30m reunião."
      :else
      (let [id (novo-id)
            item {:id id :chat (chat-id message) :texto texto :criador (criador-id message) :em (+ (.now js/Date) ms)}]
        (swap! lembretes assoc id item)
        (salvar!)
        (str "⏰ Lembrete criado para daqui a *" duracao "*: " texto "\nID: " id)))))

(defn listar [message]
  (let [itens (->> @lembretes vals (filter #(= (:chat %) (chat-id message))) (sort-by :em))]
    (if (empty? itens)
      "⏰ Não há lembretes pendentes neste chat."
      (str "⏰ *Lembretes pendentes*\n\n"
           (str/join "\n" (map #(str "• " (:texto %) " — " (.toLocaleString (js/Date. (:em %))) "\n  ID: " (:id %)) itens))))))

(defn cancelar! [message id]
  (let [id (str/lower-case (str/trim id))
        item (some (fn [[codigo lembrete]]
                     (when (= id (str/lower-case codigo)) lembrete))
                   @lembretes)]
    (if (and item (= (:chat item) (chat-id message)))
      (do (swap! lembretes dissoc (:id item)) (salvar!) (str "✅ Lembrete cancelado: " (:texto item)))
      "❓ Não encontrei esse lembrete neste chat. Use !lembretes para ver os IDs.")))

(defn- verificar! [client]
  (doseq [[id item] @lembretes
          :when (and (<= (:em item) (.now js/Date)) (not (contains? @enviando id)))]
    (swap! enviando conj id)
    (let [criador (:criador item)
          texto (str "⏰ *Lembrete do tio Odisseu*\n"
                     (if criador (str "@" (nome-para-mencao criador) ", ") "")
                     (:texto item))]
      (-> (if criador
            ;; Mesmo formato usado por !sorteio, que o whatsapp-web.js
            ;; converte em uma menção real (não apenas texto com @).
            (.sendMessage client (:chat item) texto #js {:mentions #js [criador]})
            (.sendMessage client (:chat item) texto))
        (p/then (fn [_] (swap! lembretes dissoc id) (salvar!) (swap! enviando disj id)))
        (p/catch (fn [err] (js/console.error "Erro ao enviar lembrete:" err) (swap! enviando disj id)))))))

(defn iniciar! [client]
  "Inicia uma única checagem periódica após o WhatsApp estar conectado."
  (when-not @verificador
    (verificar! client)
    (reset! verificador (js/setInterval #(verificar! client) 15000))))
