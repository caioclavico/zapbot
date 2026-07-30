(ns zapbot.historico
  "Guarda em memória as últimas mensagens de cada chat, para o !resuma.
  Evita usar chat.fetchMessages()/message.getChat(), que dependem de um
  serializador interno do WhatsApp Web (getChatModel) instável e sujeito a
  quebrar sem aviso (erro genérico \"r: r\")."
  (:require [promesa.core :as p]
            [clojure.string :as str]))

(def ^:private limite-por-chat 300)

(defonce ^:private historicos (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- rotulo-autor [message]
  (if (.-fromMe message)
    (p/resolved "Você")
    (-> (.getContact message)
        (p/then (fn [c] (or (.-pushname c) (.-name c) (.-number c) "Alguém")))
        (p/catch (fn [_] "Alguém")))))

(defn registrar!
  "Adiciona a mensagem ao histórico do chat correspondente, se tiver texto."
  [message]
  (when-not (str/blank? (.-body message))
    (-> (rotulo-autor message)
        (p/then (fn [autor]
                  (let [id   (chat-id message)
                        item {:autor autor :corpo (.-body message)}]
                    (swap! historicos update id
                           (fn [msgs] (vec (take-last limite-por-chat (conj (or msgs []) item))))))))
        (p/catch (fn [_] nil)))))

(defn obter
  "Retorna o histórico (vetor de {:autor :corpo}) do chat da mensagem dada."
  [message]
  (get @historicos (chat-id message) []))
