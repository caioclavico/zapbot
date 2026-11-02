(ns pokemon.application.thematic-events
  "Agenda/tick/claim de eventos temáticos (12.8.1). Flag off → no-op."
  (:require [clojure.string :as str]
            [pokemon.application.ports :as ports]
            [pokemon.domain.errors :as err]
            [pokemon.domain.events :as events]
            [pokemon.domain.model :as model]
            [pokemon.domain.ops :as ops]))

(defn active
  [store chat-id agora-ms]
  (ports/thematic-active-events store chat-id (long (or agora-ms (System/currentTimeMillis)))))

(defn history
  [store chat-id limit]
  (ports/thematic-event-history store chat-id (or limit 20)))

(defn schedule
  [store chat-id]
  (ports/get-thematic-schedule store chat-id))

(defn set-enabled!
  [store chat-id enabled? agora-ms]
  (ports/set-thematic-schedule-enabled! store chat-id (boolean enabled?)
                                        (long (or agora-ms (System/currentTimeMillis)))))

(defn admin-schedule!
  "Agenda next-at / force catalog / duration-h / enable. Sem efeito sem auth na bridge."
  [store {:keys [chat-id next-at catalog-id enabled? agora-ms duration-h]}]
  (when (str/blank? (str chat-id))
    (throw (err/error :invalid_query {:required ["chatId"]})))
  (when (and catalog-id (nil? (events/by-id (str catalog-id))))
    (throw (err/error :invalid_query {:catalog-id catalog-id})))
  (when (and (some? duration-h) (or (not (number? duration-h)) (<= (long duration-h) 0)))
    (throw (err/error :invalid_query {:duration-h duration-h})))
  (ports/admin-schedule-thematic!
   store chat-id
   {:next-at (long (or next-at agora-ms (System/currentTimeMillis)))
    :catalog-id (some-> catalog-id str)
    :enabled? enabled?
    :duration-h (when (some? duration-h) (long duration-h))}))

(defn cancel!
  [store occurrence-id]
  (ports/cancel-thematic-occurrence! store occurrence-id))

(defn tick!
  "Tick recuperável por chat (ou allowlist). Flag off → skipped."
  [store {:keys [agora-ms chat-id chats draw limit]
          :or {limit 32}}]
  (if-not (events/thematic-events-enabled?)
    {:enabled false :processed [] :note "thematic-events-flag-off"}
    (ports/tick-thematic-events!
     store {:agora (long (or agora-ms (System/currentTimeMillis)))
            :chat-id chat-id
            :chats chats
            :draw (or draw 0)
            :limit (long limit)})))

(defn claim!
  [store writer request]
  (ops/validate-mutation! (merge request writer))
  (model/validate-create-player! writer)
  (when (or (not (string? (:occurrence-id request)))
            (str/blank? (:occurrence-id request)))
    (throw (err/error :invalid_query {:required ["occurrenceId"]})))
  (ports/claim-event-missions! store writer request))
