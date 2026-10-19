(ns odisseu.platform.infrastructure.http.handlers
  "Rotas HTTP /v1/platform/* — bloqueio e admins (sem domínio Pokémon)."
  (:require [clojure.string :as str]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [pokemon.domain.errors :as err]))

(defn- g [m & ks]
  (some #(get m %) ks))

(defn- require-str! [v label]
  (when (or (nil? v) (and (string? v) (str/blank? v)))
    (throw (err/error :invalid_query {:required [label]})))
  (str v))

(defn- require-op! [body]
  (require-str! (g body "operationId" :operationId) "operationId"))

(defn- block->wire
  [cid {:keys [bot? comandos]}]
  {"chatId" (str cid)
   "bot?" (boolean bot?)
   "comandos" (vec (sort (map str (or comandos #{}))))})

(defn- state-blocks->wire
  "Lista de entradas — chatId como valor (não chave), evita camelCase em JID."
  [st]
  (->> st
       (map (fn [[cid info]] (block->wire cid info)))
       (sort-by #(get % "chatId"))
       vec))

(defn- state-admins->wire [st]
  (->> st
       (map (fn [[cid ids]]
              {"chatId" (str cid)
               "playerIds" (vec (sort (map str ids)))}))
       (sort-by #(get % "chatId"))
       vec))

(defn handle
  "Retorna mapa :status + :data ou lança err/error.
   nil se o path não for de platform (caller continua)."
  [platform-store method path qs body]
  (when (and platform-store (str/starts-with? path "/v1/platform"))
    (cond
      (= path "/v1/platform/blocks")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-block-state platform-store)]
          {:status 200
           :data (if cid
                   {"block" (block->wire cid (ports/get-chat-block platform-store cid))}
                   {"blocks" (state-blocks->wire st)})}))

      (= path "/v1/platform/blocks/bot")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              blocked? (boolean (g body "blocked" :blocked))
              op (require-op! body)
              res (ports/set-bot-blocked! platform-store cid blocked? op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "blocks" (state-blocks->wire (:state res))
                  "block" (block->wire cid (get (:state res) cid
                                                {:bot? blocked? :comandos #{}}))}}))

      (= path "/v1/platform/blocks/commands")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              action (str/lower-case (str (or (g body "action" :action) "")))
              cmds (or (g body "commands" :commands) [])
              op (require-op! body)
              _ (when-not (#{ "block" "unblock"} action)
                  (throw (err/error :invalid_query {:required ["action=block|unblock"]})))
              _ (when-not (sequential? cmds)
                  (throw (err/error :invalid_query {:required ["commands"]})))
              cmds* (mapv str cmds)
              protegidos (filter bloqueio/protegidos (map str/lower-case cmds*))
              _ (when (seq protegidos)
                  (throw (err/error :operation_forbidden {:commands protegidos})))
              res (if (= "block" action)
                    (ports/block-commands! platform-store cid cmds* op)
                    (ports/unblock-commands! platform-store cid cmds* op))]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "blocks" (state-blocks->wire (:state res))
                  "block" (block->wire cid (get (:state res) cid
                                                {:bot? false :comandos #{}}))}}))

      (= path "/v1/platform/admins")
      (do
        (when (not= "GET" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (not-empty (g qs "chatId"))
              st (ports/get-admin-state platform-store)]
          {:status 200
           :data (if cid
                   {"chatId" cid
                    "playerIds" (vec (sort (map str (get st cid #{}))))}
                   {"admins" (state-admins->wire st)})}))

      (= path "/v1/platform/admins/remember")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              op (require-op! body)
              res (ports/remember-admin! platform-store cid pid op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "admins" (state-admins->wire (:state res))}}))

      (= path "/v1/platform/admins/forget")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              pid (require-str! (g body "playerId" :playerId) "playerId")
              op (require-op! body)
              res (ports/forget-admin! platform-store cid pid op)]
          {:status 200
           :data {"duplicate" (boolean (:duplicate res))
                  "admins" (state-admins->wire (:state res))}}))

      (= path "/v1/platform/admins/known")
      (do
        (when (not= "POST" method)
          (throw (err/error :method_not_allowed {:method method})))
        (let [cid (require-str! (g body "chatId" :chatId) "chatId")
              ids (or (g body "playerIds" :playerIds) [])
              _ (when-not (sequential? ids)
                  (throw (err/error :invalid_query {:required ["playerIds"]})))
              known? (ports/known-admin? platform-store cid ids)]
          {:status 200
           :data {"chatId" cid "known" (boolean known?)}}))

      :else
      (throw (err/error :operation_not_found {:path path})))))
