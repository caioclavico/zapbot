(ns zapbot.moderacao
  "Comando !ban - remove um participante do grupo (requer que o bot e quem
  chamou o comando sejam administradores, ou que o número esteja em ADMIN_NUMBERS)."
  (:require [promesa.core :as p]
            [zapbot.config :as config]))

(defn- autorizado-por-config? [serialized-id]
  (contains? config/admin-numbers serialized-id))

(defn- participante [chat serialized-id]
  (first (filter #(= (.. % -id -_serialized) serialized-id) (.-participants chat))))

(defn- admin? [chat serialized-id]
  (boolean (when-let [p (participante chat serialized-id)]
             (or (.-isAdmin p) (.-isSuperAdmin p)))))

(defn- alvo-mencionado [message]
  (p/let [mencionados (.getMentions message)]
    (when (seq mencionados) (.. (first mencionados) -id -_serialized))))

(defn- alvo-citado [message]
  (if (.-hasQuotedMsg message)
    (p/let [quoted (.getQuotedMessage message)]
      (or (.-author quoted) (.-from quoted)))
    (p/resolved nil)))

(defn- resolver-alvo [message]
  (p/let [alvo (alvo-mencionado message)]
    (if alvo alvo (alvo-citado message))))

(defn banir [message]
  (p/catch
   (p/let [chat (.getChat message)]
     (if-not (.-isGroup chat)
       "⚠️ Esse comando só funciona em grupos."
       (let [autor-id (or (.-author message) (.-from message))]
         (cond
           (not (or (autorizado-por-config? autor-id) (admin? chat autor-id)))
           "🚫 Você precisa ser administrador do grupo para usar esse comando."

           (not (admin? chat (.. (.-client message) -info -wid -_serialized)))
           "🚫 Preciso ser administrador do grupo para poder remover alguém."

           :else
           (p/let [alvo (resolver-alvo message)]
             (if-not alvo
               "❓ Marque a pessoa (@numero) ou responda a mensagem dela junto com !ban."
               (p/let [_ (.removeParticipants chat #js [alvo])]
                 "✅ Pessoa removida do grupo.")))))))
   (fn [err]
     (js/console.error "Erro ao banir:" err)
     "❌ Não consegui remover a pessoa. Verifique se sou administrador do grupo.")))
