(ns pokemon.tests.multigroup-unification-a6-test
  "A6 — unificação multigrupo: agenda pós-inicial, bloqueios, isolamento."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-multigroup-a6")
(def ^:private storage-rel ".datomic-local/multigroup-a6")

(def ^:private grupo-a "grupo-a@g.us")
(def ^:private grupo-b "grupo-b@g.us")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- ensure-trainer!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(deftest ensure-schedule-apos-onboarding-idempotente
  (let [s (fresh! "mg-onboard")
        _ (ensure-trainer! s grupo-a "ash")
        r1 (raid-auto/ensure-schedule-after-onboarding! s grupo-a)
        r2 (raid-auto/ensure-schedule-after-onboarding! s grupo-a)
        sch (raid-auto/get-schedule s grupo-a)]
    (is (true? (:enabled r1)))
    (is (false? (:already r1)))
    (is (true? (:already r2)))
    (is (true? (:enabled sch)))
    (is (> (long (:next-at sch 0)) (System/currentTimeMillis)))))

(deftest segundo-grupo-agenda-independente
  (let [s (fresh! "mg-two")
        _ (ensure-trainer! s grupo-a "ash")
        _ (ensure-trainer! s grupo-b "misty")
        a (raid-auto/ensure-schedule-after-onboarding! s grupo-a)
        b (raid-auto/ensure-schedule-after-onboarding! s grupo-b)
        sa (raid-auto/get-schedule s grupo-a)
        sb (raid-auto/get-schedule s grupo-b)]
    (is (true? (:enabled a)))
    (is (true? (:enabled b)))
    (is (not= (:chat-id sa) (:chat-id sb)))
    (is (= grupo-a (:chat-id sa)))
    (is (= grupo-b (:chat-id sb)))
    ;; Não copia next-at literal entre grupos (podem coincidir por relógio, mas
    ;; agendas são entidades distintas).
    (is (some? (:next-at sa)))
    (is (some? (:next-at sb)))))

(deftest reconcile-starters-idempotente
  (let [s (fresh! "mg-recon")
        _ (ensure-trainer! s grupo-a "ash")
        _ (ensure-trainer! s grupo-b "misty")
        ;; Sem starter: reconcile não inventa agenda.
        r0 (raid-auto/reconcile-schedules-for-starters! s)
        _ (is (map? r0))
        _ (is (zero? (count (filter :enabled
                                    (map :schedule (:results r0))))))
        ;; Escolhe inicial em A → schedule habilitada (onboarding + reconcile).
        c (tx/choose-starter!
           s {:chat-id grupo-a :player-id "ash"
              :operation-id "mg-starter-a" :choice "1"})
        _ (is (some? c))
        r1 (raid-auto/reconcile-schedules-for-starters! s)
        r2 (raid-auto/reconcile-schedules-for-starters! s)
        sa (raid-auto/get-schedule s grupo-a)
        sb (raid-auto/get-schedule s grupo-b)]
    (is (true? (:enabled sa)))
    (is (false? (boolean (:enabled sb))))
    (is (= (count (:results r1)) (count (:results r2))))
    ;; Segundo reconcile não cria segunda agenda / não quebra next-at futuro.
    (is (= (:next-at sa) (:next-at (raid-auto/get-schedule s grupo-a))))))

(deftest bloqueio-impede-spawn-e-lembrete-só-no-grupo
  (let [s (fresh! "mg-block")
        agora 5000
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-a :enabled true
              :interval-ms domain/min-interval-ms
              :next-at agora :ginasio-index 0 :agora-ms agora})
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-b :enabled true
              :interval-ms domain/min-interval-ms
              :next-at agora :ginasio-index 0 :agora-ms agora})
        tick (raid-auto/tick!
              s {:agora-ms agora
                 :global-enabled? true
                 :limit 32
                 :blocked-chat-ids [grupo-a]})
        spawned (filter #(= :spawn (:acao %)) (:spawned tick))
        spawned-chats (set (map :chat-id spawned))
        deferred-chats (set (map :chat-id (:deferred-admin-block tick)))
        events (raid-auto/pending-events s {:limit 20})
        event-chats (set (map :chat-id events))]
    (is (contains? deferred-chats grupo-a))
    (is (not (contains? spawned-chats grupo-a)))
    (is (contains? spawned-chats grupo-b))
    (is (not (contains? event-chats grupo-a)))
    (is (contains? event-chats grupo-b))
    ;; Outbox sem cruzamento: cada evento aponta ao chat correto
    (doseq [ev events]
      (is (#{"grupo-a@g.us" "grupo-b@g.us"} (:chat-id ev)))
      (when (= (:chat-id ev) grupo-b)
        (is (string? (:id ev)))))))

(deftest desbloquear-nao-dispara-atrasados-em-rajada
  (let [s (fresh! "mg-unblock")
        agora 5000
        past 1000
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-a :enabled true
              :interval-ms domain/min-interval-ms
              :next-at past :ginasio-index 0 :agora-ms past})
        ;; Enquanto bloqueado: defere (avança next-at) sem spawn
        t1 (raid-auto/tick!
            s {:agora-ms agora :global-enabled? true
               :blocked-chat-ids [grupo-a]})
        sch1 (raid-auto/get-schedule s grupo-a)
        _ (is (empty? (filter #(= grupo-a (:chat-id %)) (:spawned t1))))
        _ (is (seq (:deferred-admin-block t1)))
        _ (is (> (long (:next-at sch1)) agora))
        ;; Desbloqueado no mesmo instante: não pega o slot já deferido
        t2 (raid-auto/tick!
            s {:agora-ms agora :global-enabled? true
               :blocked-chat-ids []})
        spawned-now (filter #(= grupo-a (:chat-id %)) (:spawned t2))]
    (is (empty? spawned-now))
    (is (= (:next-at sch1) (:next-at (raid-auto/get-schedule s grupo-a))))))

(deftest cancelar-sistema-lobby
  (let [raid {:raid-id "r1" :fase "inscricoes" :chat-id grupo-a
              :ordem ["ash"] :criador "ash" :expira 9999}
        cancelled (pokemon.domain.raid/cancelar-sistema raid 1000)]
    (is (= "cancelada" (:fase cancelled)))
    (is (= 1000 (:expira cancelled)))))
