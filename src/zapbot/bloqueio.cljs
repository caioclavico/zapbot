(ns zapbot.bloqueio
  "Comandos !bloquear/!desbloquear - admins do grupo (ou números em
  ADMIN_NUMBERS) podem desligar o bot inteiro num chat, ou bloquear
  comandos específicos (ex.: !naval), sem precisar mexer no .env.
  Estado em memória por chat (não sobrevive a reinício do bot)."
  (:require [promesa.core :as p]
            [clojure.string :as str]
            [zapbot.config :as config]))

;; nunca podem ser bloqueados, pra nunca travar o chat sem saída
(def ^:private protegidos #{"bloquear" "desbloquear"})

(defonce ^:private estado (atom {}))

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
  (if (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] true)
        (str "🔇 Bot bloqueado nesse chat. Use " config/prefix "desbloquear para reativar."))
    (do (swap! estado update-in [cid :comandos] (fnil conj #{}) alvo)
        (str "🔇 " config/prefix alvo " bloqueado nesse chat."))))

(defn- desbloquear! [cid alvo]
  (if (= alvo "tudo")
    (do (swap! estado assoc-in [cid :bot?] false)
        "🔊 Bot reativado nesse chat.")
    (do (swap! estado update-in [cid :comandos] (fnil disj #{}) alvo)
        (str "🔊 " config/prefix alvo " liberado nesse chat."))))

(defn processar-comando
  "cmd é \"bloquear\" ou \"desbloquear\"; args é o resto do texto (vazio ou
  \"tudo\" = bot inteiro, \"listar\" = mostra o status, ou o nome de um comando)."
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
