(ns zapbot.historico
  "Guarda em memória as últimas mensagens e os participantes conhecidos de
  cada chat, para o !resuma e o !sorteio. Evita usar chat.fetchMessages()/
  message.getChat()/chat.participants, que dependem de um serializador
  interno do WhatsApp Web (getChatModel) instável e sujeito a quebrar sem
  aviso (erro genérico \"r: r\")."
  (:require [promesa.core :as p]
            [clojure.string :as str]))

(def ^:private limite-por-chat 300)

(defonce ^:private historicos (atom {}))
(defonce ^:private participantes (atom {}))

(defn- chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- participante-id [message]
  (or (.-author message) (.-from message)))

(defn- rotulo-autor [message]
  (if (.-fromMe message)
    (p/resolved "Você")
    (-> (.getContact message)
        (p/then (fn [c] (or (.-pushname c) (.-name c) (.-number c) "Alguém")))
        (p/catch (fn [_] "Alguém")))))

(defn registrar!
  "Adiciona a mensagem ao histórico do chat e, se não for do próprio bot,
  registra quem mandou como participante conhecido do chat."
  [message]
  (when-not (str/blank? (.-body message))
    (-> (rotulo-autor message)
        (p/then (fn [autor]
                  (let [id   (chat-id message)
                        item {:autor autor :corpo (.-body message)}]
                    (swap! historicos update id
                           (fn [msgs] (vec (take-last limite-por-chat (conj (or msgs []) item)))))
                    (when-not (.-fromMe message)
                      (swap! participantes update id
                             (fnil assoc {}) (participante-id message) autor)))))
        (p/catch (fn [_] nil)))))

(defn obter
  "Retorna o histórico (vetor de {:autor :corpo}) do chat da mensagem dada."
  [message]
  (get @historicos (chat-id message) []))

(defn participantes-conhecidos
  "Retorna os participantes conhecidos do chat (vetor de {:id :nome}),
  vistos desde que o bot foi ligado."
  [message]
  (mapv (fn [[id nome]] {:id id :nome nome})
        (get @participantes (chat-id message) {})))
