(ns zapbot.admins
  "Registro de admins conhecidos por chat, alimentado por eventos reais do
  WhatsApp (group_admin_changed) em vez de depender só de chat.participants/
  getChatModel do WhatsApp Web - que já vimos falhar de forma persistente
  com um erro genérico (\"r: r\") em certos grupos (ver zapbot.bloqueio).
  Não substitui a checagem ao vivo (que continua sendo a fonte mais
  atualizada quando funciona) - serve de fallback pra quando ela falha, e é
  alimentado tanto pelos eventos quanto por checagens ao vivo bem-sucedidas."
  (:require [zapbot.armazenamento :as armazenamento]))

(defn- carregar []
  (into {}
        (map (fn [[cid ids]] [cid (set ids)]))
        (armazenamento/obter "admins-conhecidos")))

(defonce ^:private admins (atom (carregar)))

(defn- persistir! []
  (armazenamento/salvar! "admins-conhecidos"
                         (into {} (map (fn [[cid ids]] [cid (vec ids)])) @admins)))

(defn lembrar-admin!
  "Marca `id` como admin conhecido do chat `cid`."
  [cid id]
  (when (and cid id)
    (swap! admins update cid (fnil conj #{}) id)
    (persistir!)))

(defn esquecer-admin!
  "Remove `id` dos admins conhecidos do chat `cid`."
  [cid id]
  (when (and cid id)
    (swap! admins update cid (fnil disj #{}) id)
    (persistir!)))

(defn admin-conhecido?
  "true se algum dos `ids` já é um admin conhecido do chat `cid`."
  [cid ids]
  (boolean (some (get @admins cid #{}) ids)))

(defn processar-evento-promocao!
  "Listener de group_admin_changed (ver zapbot.core) - `notification` é o
  GroupNotification cru do whatsapp-web.js, que vem direto do stanza da
  mensagem (não passa pelo getChatModel instável)."
  [notification]
  (let [cid  (.-chatId notification)
        tipo (.-type notification)
        ids  (js->clj (.-recipientIds notification))]
    (doseq [id ids]
      (case tipo
        "promote" (lembrar-admin! cid id)
        "demote"  (esquecer-admin! cid id)
        nil))))
