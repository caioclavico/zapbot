(ns pokemon.application.raid-auto
  "Aparições automáticas — tick recuperável + agenda (Fase 3)."
  (:require [pokemon.application.ports :as ports]
            [pokemon.domain.raid-auto :as raid-auto]))

(def format-opened raid-auto/format-opened)
(def format-started raid-auto/format-started)
(def format-cancelled raid-auto/format-cancelled)
(def format-reminder raid-auto/format-reminder)
(def plan-reminders raid-auto/plan-reminders)

(defn get-schedule
  [store chat-id]
  (or (ports/get-raid-auto-schedule store chat-id)
      (raid-auto/default-schedule chat-id 0)))

(defn upsert-schedule!
  "Atualiza agenda. enabled default false se omitido na criação."
  [store {:keys [chat-id enabled interval-ms next-at ginasio-index agora-ms]}]
  (let [agora (long (or agora-ms (System/currentTimeMillis)))
        cur (get-schedule store chat-id)
        s (raid-auto/normalize-schedule
           (cond-> cur
             (some? enabled) (assoc :enabled (boolean enabled))
             (some? interval-ms) (assoc :interval-ms (long interval-ms))
             (some? next-at) (assoc :next-at (long next-at))
             (some? ginasio-index) (assoc :ginasio-index (long ginasio-index)))
           chat-id agora)]
    (ports/upsert-raid-auto-schedule! store s)))

(defn tick!
  "Processa agendas due + expire de lobbies auto. Relógio injetável via agora-ms.
   Sem efeito se global-enabled? for false (kill switch)."
  [store {:keys [agora-ms chat-id global-enabled? limit]
          :or {global-enabled? false limit 32}}]
  (if-not global-enabled?
    {:enabled false :processed [] :note "raid-auto-tick-disabled"}
    (ports/tick-raid-auto!
     store {:agora (or agora-ms (System/currentTimeMillis))
            :chat-id chat-id
            :limit (long limit)})))

(defn pending-events
  [store {:keys [limit] :or {limit 20}}]
  (ports/list-pokemon-outbox store (long limit)))

(defn ack-event!
  [store event-id agora-ms]
  (ports/ack-pokemon-outbox!
   store event-id (or agora-ms (System/currentTimeMillis))))
