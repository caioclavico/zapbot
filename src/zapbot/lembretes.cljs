(ns zapbot.lembretes
  "Lembretes agendados, persistidos por chat no Cassandra."
  (:require [clojure.string :as str]
            [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.config :as config]))

(defn- normalizar-lembretes [dados]
  (into {}
        (map (fn [[id item]]
               [id {:id (or (:id item) (get item "id"))
                    :chat (or (:chat item) (get item "chat"))
                    :texto (or (:texto item) (get item "texto"))
                    :em (or (:em item) (get item "em"))}]))
        (or dados {})))

(defonce ^:private lembretes (atom (normalizar-lembretes (armazenamento/obter "lembretes"))))
(defonce ^:private enviando (atom #{}))
(defonce ^:private verificador (atom nil))
(armazenamento/registrar! "lembretes" lembretes normalizar-lembretes)

(defn- chat-id [message] (if (.-fromMe message) (.-to message) (.-from message)))
(defn- salvar! [] (armazenamento/salvar! "lembretes" @lembretes))

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
      (let [id (str (.now js/Date) "-" (.toString (js/Math.random) 36))
            item {:id id :chat (chat-id message) :texto texto :em (+ (.now js/Date) ms)}]
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
  (let [item (get @lembretes (str/trim id))]
    (if (and item (= (:chat item) (chat-id message)))
      (do (swap! lembretes dissoc (:id item)) (salvar!) (str "✅ Lembrete cancelado: " (:texto item)))
      "❓ Não encontrei esse lembrete neste chat. Use !lembretes para ver os IDs.")))

(defn- verificar! [client]
  (doseq [[id item] @lembretes
          :when (and (<= (:em item) (.now js/Date)) (not (contains? @enviando id)))]
    (swap! enviando conj id)
    (-> (.sendMessage client (:chat item) (str "⏰ *Lembrete*\n" (:texto item)))
        (p/then (fn [_] (swap! lembretes dissoc id) (salvar!) (swap! enviando disj id)))
        (p/catch (fn [err] (js/console.error "Erro ao enviar lembrete:" err) (swap! enviando disj id))))))

(defn iniciar! [client]
  "Inicia uma única checagem periódica após o WhatsApp estar conectado."
  (when-not @verificador
    (verificar! client)
    (reset! verificador (js/setInterval #(verificar! client) 15000))))
