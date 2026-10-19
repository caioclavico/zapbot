(ns zapbot.historico
  "Guarda em memória as últimas mensagens e os participantes conhecidos de
  cada chat, para o !resuma e o !sorteio. Evita usar chat.fetchMessages()/
  message.getChat()/chat.participants, que dependem de um serializador
  interno do WhatsApp Web (getChatModel) instável e sujeito a quebrar sem
  aviso (erro genérico \"r: r\").
  Os participantes conhecidos (nomes/ids) são persistidos via
  zapbot.armazenamento (produção) ou Datomic (staging PLATFORM_ADAPTER=datomic);
  o histórico de mensagens em si (usado só pelo !resuma) continua só em memória
  de propósito, pra não guardar conteúdo de conversa em disco."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.platform-jvm :as platform]))

(def ^:private limite-por-chat 1000)

(defonce ^:private historicos (atom {}))
(defonce ^:private participantes
  (atom (or (when-not (platform/enabled?)
              (armazenamento/obter "participantes"))
            {})))
(defonce ^:private fila-registros (atom (p/resolved nil)))

(when-not (platform/enabled?)
  (armazenamento/registrar! "participantes" participantes))

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

(defn hidratar-datomic!
  []
  (when (platform/enabled?)
    (platform/hydrate-participants! participantes)))

(defn- persistir-participante! [cid pid nome message]
  (if (platform/enabled?)
    (let [msg-id (or (some-> message .-id .-_serialized)
                     (str cid ":" pid ":" (.now js/Date)))
          op (str "participant:" msg-id)]
      (-> (platform/upsert-participant! cid pid nome op)
          (p/then (fn [_]
                    (swap! participantes update cid (fnil assoc {}) pid nome)
                    true))
          (p/catch (fn [err]
                     (js/console.error "participantes(datomic): falha ao upsert (fail-closed):" err)
                     ;; Não derruba o pipeline de mensagens; cache local já tem o nome.
                     false))))
    (do
      (swap! participantes update cid (fnil assoc {}) pid nome)
      (armazenamento/salvar! "participantes" @participantes)
      (p/resolved true))))

(defn registrar!
  "Adiciona a mensagem ao histórico do chat e, se não for do próprio bot,
  registra quem mandou como participante conhecido do chat."
  [message]
  (when-not (str/blank? (.-body message))
    (let [registro (-> @fila-registros
                       (p/catch (fn [_] nil))
                       (p/then (fn [_] (rotulo-autor message)))
                       (p/then (fn [autor]
                                 (let [id (chat-id message)
                                       item {:id (some-> message .-id .-_serialized)
                                             :autor autor :corpo (.-body message) :em (.now js/Date)}]
                                   (swap! historicos update id
                                          (fn [msgs] (vec (take-last limite-por-chat (conj (or msgs []) item)))))
                                   (if (.-fromMe message)
                                     true
                                     (persistir-participante! id (participante-id message) autor message)))))
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
  vistos desde que o bot foi ligado (e hidratados do store em staging)."
  [message]
  (mapv (fn [[id nome]] {:id id :nome nome})
        (get @participantes (chat-id message) {})))
