(ns zapbot.historico
  "Guarda em memória as últimas mensagens e os participantes conhecidos de
  cada chat, para o !resuma e o !sorteio. Evita usar chat.fetchMessages()/
  message.getChat()/chat.participants, que dependem de um serializador
  interno do WhatsApp Web (getChatModel) instável e sujeito a quebrar sem
  aviso (erro genérico \"r: r\").
  Os participantes conhecidos (nomes/ids) são persistidos via
  zapbot.armazenamento e sobrevivem a reinícios/deploys; o histórico de
  mensagens em si (usado só pelo !resuma) continua só em memória de
  propósito, pra não guardar conteúdo de conversa em disco."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]))

(def ^:private limite-por-chat 1000)

(defonce ^:private historicos (atom {}))
(defonce ^:private participantes (atom (or (armazenamento/obter "participantes") {})))
(defonce ^:private fila-registros (atom (p/resolved nil)))
(armazenamento/registrar! "participantes" participantes)

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
    (let [registro (-> @fila-registros
                       (p/catch (fn [_] nil))
                       (p/then (fn [_] (rotulo-autor message)))
                       (p/then (fn [autor]
                                 (let [id   (chat-id message)
                                       ;; O horário permite que !resuma filtre o trecho pedido,
                                       ;; sem transformar o histórico em armazenamento persistente.
                                       item {:id (some-> message .-id .-_serialized)
                                             :autor autor :corpo (.-body message) :em (.now js/Date)}]
                                   (swap! historicos update id
                                          (fn [msgs] (vec (take-last limite-por-chat (conj (or msgs []) item)))))
                                   (when-not (.-fromMe message)
                                     (swap! participantes update id
                                            (fnil assoc {}) (participante-id message) autor)
                                     (armazenamento/salvar! "participantes" @participantes)))))
                       (p/catch (fn [_] nil)))]
      (reset! fila-registros registro)
      registro)))


(defn obter
  "Retorna o histórico (vetor de {:autor :corpo}) do chat da mensagem dada."
  [message]
  (get @historicos (chat-id message) []))

(defn mensagem-anterior
  "Retorna a mensagem que precedeu `message` no histórico do chat. Exclui a
  própria mensagem pelo ID quando ela já tiver sido registrada pelo listener."
  [message]
  (let [id-atual (some-> message .-id .-_serialized)
        itens (obter message)
        itens (if id-atual
                (remove #(= id-atual (:id %)) itens)
                (if (= (.-body message) (:corpo (last itens))) (butlast itens) itens))]
    (last itens)))

(defn participantes-conhecidos
  "Retorna os participantes conhecidos do chat (vetor de {:id :nome}),
  vistos desde que o bot foi ligado."
  [message]
  (mapv (fn [[id nome]] {:id id :nome nome})
        (get @participantes (chat-id message) {})))
