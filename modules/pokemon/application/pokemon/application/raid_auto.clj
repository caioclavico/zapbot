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
  "Atualiza agenda. enabled default false se omitido na criação.
   Cadência 12.7.2: category-intervals / daily-limit / next-categoria.
   12.8.2: distribution / window-start-hour / window-end-hour / timezone.
   Com `:safe-cadence? true` (migração 6h→1h): nunca antecipa next-at nem
   dropa especial já planejado.
   12.7.3: se next-at muda sem next-categoria explícita, invalida o intent do
   slot antigo (não carrega especial/raro para o novo due)."
  [store {:keys [chat-id enabled interval-ms next-at ginasio-index agora-ms
                 category-intervals next-categoria next-reminders?
                 daily-limit special-reserve safe-cadence?
                 distribution window-start-hour window-end-hour timezone]}]
  (let [agora (long (or agora-ms (System/currentTimeMillis)))
        cur (get-schedule store chat-id)
        next-at-changed?
        (and (not safe-cadence?)
             (some? next-at)
             (not= (long next-at) (long (:next-at cur 0))))
        patch (if safe-cadence?
                (raid-auto/safe-cadence-patch
                 cur {:interval-ms interval-ms
                      :daily-limit daily-limit
                      :special-reserve special-reserve
                      :category-intervals category-intervals
                      :next-at next-at
                      :next-categoria next-categoria
                      :distribution distribution
                      :window-start-hour window-start-hour
                      :window-end-hour window-end-hour
                      :timezone timezone
                      :agora agora})
                {})
        s (raid-auto/normalize-schedule
           (cond-> (merge cur patch)
             (some? enabled) (assoc :enabled (boolean enabled))
             (and (not safe-cadence?) (some? interval-ms))
             (assoc :interval-ms (long interval-ms))
             (and (not safe-cadence?) (some? next-at))
             (assoc :next-at (long next-at))
             (some? ginasio-index) (assoc :ginasio-index (long ginasio-index))
             (and (not safe-cadence?) (some? category-intervals))
             (assoc :category-intervals category-intervals)
             (and (not safe-cadence?) (some? next-categoria))
             (assoc :next-categoria (str next-categoria))
             (and (not safe-cadence?) next-at-changed? (nil? next-categoria))
             (assoc :next-categoria nil :next-reminders? false)
             (and (not safe-cadence?) (some? next-reminders?))
             (assoc :next-reminders? (boolean next-reminders?))
             (and (not safe-cadence?) (some? daily-limit))
             (assoc :daily-limit (long daily-limit))
             (and (not safe-cadence?) (some? special-reserve))
             (assoc :special-reserve (long special-reserve))
             (and (not safe-cadence?) (some? distribution))
             (assoc :distribution (str distribution))
             (and (not safe-cadence?) (some? window-start-hour))
             (assoc :window-start-hour (long window-start-hour))
             (and (not safe-cadence?) (some? window-end-hour))
             (assoc :window-end-hour (long window-end-hour))
             (and (not safe-cadence?) (some? timezone))
             (assoc :timezone (str timezone)))
           chat-id agora)]
    (ports/upsert-raid-auto-schedule! store s)))

(defn schedule-view
  "Agenda + diagnóstico 12.8.2 (janela/timezone/distribution).
   Contagens do dia vêm em opts {:spawns-today :specials-today} quando o
   caller as tiver (store/bridge)."
  [store chat-id agora-ms opts]
  (let [agora (long (or agora-ms (System/currentTimeMillis)))
        sch (get-schedule store chat-id)
        diag (raid-auto/schedule-diagnostics sch agora (or opts {}))]
    (assoc sch :diagnostics diag)))
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
