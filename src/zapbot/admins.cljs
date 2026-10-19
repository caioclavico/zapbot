(ns zapbot.admins
  "Registro de admins conhecidos por chat, alimentado por eventos reais do
  WhatsApp (group_admin_changed) em vez de depender só de chat.participants/
  getChatModel do WhatsApp Web - que já vimos falhar de forma persistente
  com um erro genérico (\"r: r\") em certos grupos (ver zapbot.bloqueio).
  Não substitui a checagem ao vivo (que continua sendo a fonte mais
  atualizada quando funciona) - serve de fallback pra quando ela falha, e é
  alimentado tanto pelos eventos quanto por checagens ao vivo bem-sucedidas.

  M7.1: em staging com PLATFORM_ADAPTER=datomic, persiste via bridge JVM
  (fail-closed, sem dual-write Cassandra)."
  (:require [promesa.core :as p]
            [zapbot.armazenamento :as armazenamento]
            [zapbot.platform-jvm :as platform]))

(defn- transformar [dados]
  (into {}
        (map (fn [[cid ids]] [cid (set ids)]))
        dados))

(defonce ^:private admins
  (atom (transformar
         (when-not (platform/enabled?)
           (armazenamento/obter "admins-conhecidos")))))

(when-not (platform/enabled?)
  (armazenamento/registrar! "admins-conhecidos" admins transformar))

(defn- persistir-cassandra! []
  (armazenamento/salvar! "admins-conhecidos"
                         (into {} (map (fn [[cid ids]] [cid (vec ids)])) @admins)))

(defn- apply-admins-wire! [wire]
  (when wire
    (reset! admins (transformar (js->clj wire :keywordize-keys false)))))

(defn hidratar-datomic!
  []
  (when (platform/enabled?)
    (platform/hydrate-admins! admins transformar)))

(defn lembrar-admin!
  "Marca `id` como admin conhecido do chat `cid`."
  [cid id]
  (when (and cid id)
    (if (platform/enabled?)
      (-> (platform/remember-admin! cid id (platform/op-id (str "promote:" cid ":" id)))
          (p/then (fn [data]
                    (apply-admins-wire! (.-admins data))
                    true))
          (p/catch (fn [err]
                     (js/console.error "admins(datomic): falha ao lembrar admin (fail-closed):" err)
                     (p/rejected err))))
      (do (swap! admins update cid (fnil conj #{}) id)
          (persistir-cassandra!)
          (p/resolved true)))))

(defn esquecer-admin!
  "Remove `id` dos admins conhecidos do chat `cid`."
  [cid id]
  (when (and cid id)
    (if (platform/enabled?)
      (-> (platform/forget-admin! cid id (platform/op-id (str "demote:" cid ":" id)))
          (p/then (fn [data]
                    (apply-admins-wire! (.-admins data))
                    true))
          (p/catch (fn [err]
                     (js/console.error "admins(datomic): falha ao esquecer admin (fail-closed):" err)
                     (p/rejected err))))
      (do (swap! admins update cid (fnil disj #{}) id)
          (persistir-cassandra!)
          (p/resolved true)))))

(defn admin-conhecido?
  "true se algum dos `ids` já é um admin conhecido do chat `cid`.
   Em modo Datomic consulta a bridge (fonte da verdade); o atom é cache."
  [cid ids]
  (if (platform/enabled?)
    (platform/known-admin? cid ids)
    (p/resolved (boolean (some (get @admins cid #{}) ids)))))

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
