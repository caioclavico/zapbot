(ns zapbot.platform-jvm
  "Cliente da bridge JVM para bloqueio/admins/enquetes/quiz (M7.1–M7.3).
   Só staging/dev. Fail-closed: sem fallback Cassandra."
  (:require [promesa.core :as p]
            [zapbot.config :as config]))

(def ^:private client-ctor (js/require "../scripts/lib/platform-jvm-client.cjs"))

(defonce ^:private client* (atom nil))

(defn enabled? []
  config/platform-datomic-enabled?)

(defn- client! []
  (or @client*
      (let [c (.createClient client-ctor
                             #js {:baseUrl config/platform-jvm-url
                                  :token config/platform-jvm-token
                                  :connectTimeoutMs config/platform-jvm-connect-timeout-ms
                                  :timeoutMs config/platform-jvm-timeout-ms
                                  :allowPrivateHost config/platform-jvm-allow-private-host?})]
        (reset! client* c)
        c)))

(defn op-id [prefix]
  (.newOperationId (client!) prefix))

(defn fetch-blocks []
  (p/then (.getBlocks (client!))
          (fn [data] (js->clj (.-blocks data) :keywordize-keys false))))

(defn fetch-admins []
  (p/then (.getAdmins (client!))
          (fn [data] (js->clj (.-admins data) :keywordize-keys false))))

(defn set-bot! [chat-id blocked? operation-id]
  (.setBot (client!) chat-id blocked? operation-id))

(defn block-commands! [chat-id commands operation-id]
  (.blockCommands (client!) chat-id (clj->js (vec commands)) operation-id))

(defn unblock-commands! [chat-id commands operation-id]
  (.unblockCommands (client!) chat-id (clj->js (vec commands)) operation-id))

(defn remember-admin! [chat-id player-id operation-id]
  (.rememberAdmin (client!) chat-id player-id operation-id))

(defn forget-admin! [chat-id player-id operation-id]
  (.forgetAdmin (client!) chat-id player-id operation-id))

(defn known-admin? [chat-id player-ids]
  (p/then (.knownAdmin (client!) chat-id (clj->js (vec player-ids)))
          (fn [data] (boolean (.-known data)))))

(defn hydrate-blocks!
  "Carrega estado completo da bridge para o atom de bloqueio (shape interno)."
  [atom-estado transformar]
  (p/then (fetch-blocks)
          (fn [blocks]
            (reset! atom-estado (transformar (or blocks {})))
            true)))

(defn hydrate-admins!
  [atom-admins transformar]
  (p/then (fetch-admins)
          (fn [admins]
            (reset! atom-admins (transformar (or admins {})))
            true)))

(defn fetch-polls []
  (p/then (.getPolls (client!))
          (fn [data] (js->clj (.-polls data) :keywordize-keys false))))

(defn create-poll! [chat-id poll operation-id]
  (.createPoll (client!) chat-id (clj->js poll) operation-id))

(defn cast-vote! [chat-id voter-id choice operation-id]
  (.castVote (client!) chat-id voter-id choice operation-id))

(defn close-poll! [chat-id operation-id]
  (.closePoll (client!) chat-id operation-id))

(defn hydrate-polls!
  [atom-polls transformar]
  (p/then (fetch-polls)
          (fn [polls]
            (reset! atom-polls (transformar (or polls {})))
            true)))

(defn fetch-quiz-histories []
  (p/then (.getQuizHistories (client!))
          (fn [data] (js->clj (.-histories data) :keywordize-keys false))))

(defn append-quiz-question! [chat-id question operation-id]
  (.appendQuizQuestion (client!) chat-id question operation-id))

(defn hydrate-quiz-history!
  [atom-hist]
  (p/then (fetch-quiz-histories)
          (fn [histories]
            (reset! atom-hist (or histories {}))
            true)))

(defn fetch-rank []
  (p/then (.getRank (client!))
          (fn [data] (js->clj (.-chats data) :keywordize-keys false))))

(defn fetch-rank-chat [chat-id]
  (p/then (.getRankChat (client!) chat-id)
          (fn [data] (.-players data))))

(defn rank-score! [chat-id player-id name game operation-id]
  (.rankScore (client!) chat-id player-id name game operation-id))

(defn rank-penalize! [chat-id player-id operation-id]
  (.rankPenalize (client!) chat-id player-id operation-id))

(defn rank-effect! [efeito]
  (.rankEffect (client!) efeito))

(defn hydrate-rank!
  [atom-placares]
  (p/then (fetch-rank)
          (fn [chats]
            ;; chats: {chatId -> {playerId -> info}}
            (reset! atom-placares (or chats {}))
            true)))
