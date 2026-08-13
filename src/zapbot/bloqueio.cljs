(ns zapbot.bloqueio
  "Comandos !bloquear/!desbloquear - admins do grupo (ou números em
  ADMIN_NUMBERS) podem desligar o bot inteiro num chat, bloquear
  comandos específicos (ex.: !naval), ou todos os jogos de uma vez
  (!bloquear jogos), sem precisar mexer no .env.
  Estado persistido via zapbot.armazenamento (sobrevive a reinicios/deploys)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [clojure.set :as set]
            [zapbot.config :as config]
            [zapbot.armazenamento :as armazenamento]))

;; nunca podem ser bloqueados, pra nunca travar o chat sem saída
(def ^:private protegidos #{"bloquear" "desbloquear"})

;; !bloquear jogos / !desbloquear jogos afeta todos de uma vez (inclui apelidos, ex.: stop)
(def ^:private jogos-comandos #{"bola8" "sorteio" "velha" "naval" "adedonha" "stop" "quiz" "pokemon"})

(defn- carregar-estado []
  (into {}
        (map (fn [[cid info]]
               [cid {:bot? (boolean (get info "bot?"))
                     :comandos (set (get info "comandos"))}]))
        (armazenamento/obter "bloqueio")))

(defn- estado->persistivel [estado]
  (into {}
        (map (fn [[cid info]]
               [cid {"bot?" (boolean (:bot? info))
                     "comandos" (vec (:comandos info))}]))
        estado))

(defonce ^:private estado (atom (carregar-estado)))

(defn- persistir! []
  (armazenamento/salvar! "bloqueio" (estado->persistivel @estado)))

(defn chat-id [message]
  (if (.-fromMe message) (.-to message) (.-from message)))

(defn- autorizado-por-config? [serialized-id]
  (contains? config/admin-numbers serialized-id))

(defn- participante [chat serialized-id]
  (first (filter #(= (.. % -id -_serialized) serialized-id) (.-participants chat))))

(defn- admin-do-grupo? [chat serialized-id]
  (boolean (when-let [p (participante chat serialized-id)]
             (or (.-isAdmin p) (.-isSuperAdmin p)))))

(defn- autorizado? [message]
  (let [autor-id (or (.-author message) (.-from message))]
    (if (autorizado-por-config? autor-id)
      (p/resolved true)
      (-> (p/let [chat (.getChat message)]
            (if (.-isGroup chat)
              (admin-do-grupo? chat autor-id)
              false))
          (p/catch (fn [_] false))))))

(defn bot-bloqueado? [cid]
  (boolean (get-in @estado [cid :bot?])))

(defn comando-bloqueado? [cid comando]
  (contains? (get-in @estado [cid :comandos] #{}) comando))

(defn- formatar-lista [cid]
  (let [{:keys [bot? comandos]} (get @estado cid)]
    (str "📋 *Status nesse chat:*\n"
         "Bot: " (if bot? "🔇 bloqueado" "🔊 ativo") "\n"
         "Comandos bloqueados: " (if (seq comandos)
                                    (str/join ", " (map #(str config/prefix %) comandos))
                                    "nenhum"))))

(defn- bloquear! [cid alvo]
  (cond
    (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] true)
        (persistir!)
        (str "🔇 Bot bloqueado nesse chat. Use " config/prefix "desbloquear para reativar."))

    (= alvo "jogos")
    (do (swap! estado update-in [cid :comandos] (fnil into #{}) jogos-comandos)
        (persistir!)
        "🔇 Todos os jogos foram bloqueados nesse chat.")

    :else
    (do (swap! estado update-in [cid :comandos] (fnil conj #{}) alvo)
        (persistir!)
        (str "🔇 " config/prefix alvo " bloqueado nesse chat."))))

(defn- desbloquear! [cid alvo]
  (cond
    (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] false)
        (persistir!)
        "🔊 Bot reativado nesse chat.")

    (= alvo "jogos")
    (do (swap! estado update-in [cid :comandos] (fnil set/difference #{}) jogos-comandos)
        (persistir!)
        "🔊 Todos os jogos foram liberados nesse chat.")

    :else
    (do (swap! estado update-in [cid :comandos] (fnil disj #{}) alvo)
        (persistir!)
        (str "🔊 " config/prefix alvo " liberado nesse chat."))))

(defn processar-comando
  "cmd é \"bloquear\" ou \"desbloquear\"; args é o resto do texto (vazio ou
  \"tudo\" = bot inteiro, \"jogos\" = todos os jogos de uma vez, \"listar\" =
  mostra o status, ou o nome de um comando)."
  [message cmd args]
  (p/let [ok? (autorizado? message)]
    (let [cid (chat-id message)
          arg (let [a (-> args str/trim str/lower-case)] (if (str/blank? a) "tudo" a))]
      (cond
        (not ok?)
        "🚫 Você precisa ser administrador do grupo para usar esse comando."

        (= arg "listar")
        (formatar-lista cid)

        (contains? protegidos arg)
        (str "🚫 " config/prefix arg " não pode ser bloqueado.")

        (= cmd "bloquear")
        (bloquear! cid arg)

        :else
        (desbloquear! cid arg)))))
