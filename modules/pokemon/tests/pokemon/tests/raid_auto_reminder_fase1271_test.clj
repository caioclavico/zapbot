(ns pokemon.tests.raid-auto-reminder-fase1271-test
  "Fase 12.7.1 — lembretes T-30/15/5 com ovo (sem spoiler do chefe)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-r1271")
(def ^:private storage-rel ".datomic-local/raid-auto-r1271")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(defn- reopen!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(defn- enable!
  [s chat next-at]
  (raid-auto/upsert-schedule!
   s {:chat-id chat :enabled true :interval-ms (* 6 60 60 1000)
      :next-at next-at :ginasio-index 0 :agora-ms next-at}))

(defn- spoiler?
  [texto]
  (boolean
   (or (re-find #"(?i)chefe" (str texto))
       (re-find #"(?i)liga" (str texto))
       (re-find #"(?i)entrar auto" (str texto))
       (re-find #"(?i)(charizard|mewtwo|arceus|legendary|lendár)" (str texto)))))

;;;; Domínio puro

(deftest format-reminder-sem-spoiler
  (doseq [m [30 15 5]]
    (let [t (domain/format-reminder m)]
      (is (re-find (re-pattern (str m " minutos")) t))
      (is (false? (spoiler? t)))
      (is (str/includes? t "🥚")))))

(deftest plan-reminders-tres-janelas
  (let [due 1000000
        sch (domain/normalize-schedule
             {:enabled true :interval-ms (* 6 60 60 1000) :next-at due}
             "pilot@g.us" due)]
    (testing "antes de qualquer janela"
      (is (empty? (domain/plan-reminders sch (- due domain/reminder-offset-30-ms 1)))))
    (testing "T-30"
      (let [ps (domain/plan-reminders sch (- due domain/reminder-offset-30-ms))]
        (is (= 1 (count ps)))
        (is (= 30 (:minutes (first ps))))
        (is (= "raid-reminder-30" (:kind (first ps))))
        (is (= (domain/outbox-reminder-id "pilot@g.us" due domain/reminder-offset-30-ms)
               (:event-id (first ps))))
        (is (false? (spoiler? (:texto (first ps)))))))
    (testing "T-15: só 15 (T-30 já fora da grace anti-retroativa)"
      (let [ps (domain/plan-reminders sch (- due domain/reminder-offset-15-ms))]
        (is (= #{15} (set (map :minutes ps))))))
    (testing "T-5: só 5 (30/15 fora da grace)"
      (let [ps (domain/plan-reminders sch (- due domain/reminder-offset-5-ms))]
        (is (= #{5} (set (map :minutes ps))))))
    (testing "no horário do spawn — lembretes param"
      (is (empty? (domain/plan-reminders sch due))))
    (testing "disabled"
      (is (empty? (domain/plan-reminders (assoc sch :enabled false)
                                         (- due domain/reminder-offset-5-ms)))))
    (testing "interval curto (==lobby) não dispara lembretes (offset >= interval)"
      (let [short (domain/normalize-schedule
                   {:enabled true :interval-ms domain/min-interval-ms :next-at due}
                   "short@g.us" due)]
        (is (empty? (domain/plan-reminders short (dec due))))))
    (testing "anti-retroativo: deploy em T-20 não dispara T-30 perdido"
      (let [t20 (- due (* 20 60 1000))
            ps (domain/plan-reminders sch t20)]
        ;; fire T-30 foi há 10 min (> grace 2 min) → descartado
        (is (empty? (filter #(= 30 (:minutes %)) ps)))
        (is (empty? ps))))
    (testing "dentro da grace: ainda enfileira"
      (let [just-after-t30 (+ (- due domain/reminder-offset-30-ms) 30000)
            ps (domain/plan-reminders sch just-after-t30)]
        (is (= 1 (count ps)))
        (is (= 30 (:minutes (first ps))))))))

(deftest parse-reminder-id-roundtrip
  (let [id (domain/outbox-reminder-id "120363@g.us" 1793631600000 900000)
        p (domain/parse-reminder-id id)]
    (is (= "120363@g.us" (:chat-id p)))
    (is (= 1793631600000 (:due-at p)))
    (is (= 900000 (:offset-ms p)))
    (is (nil? (domain/parse-reminder-id "raid-opened:x")))))

;;;; Integração Datomic

(deftest lembretes-enfileiram-e-sao-idempotentes
  (let [[s _] (fresh! "r1271-idemp")
        chat "pilot-r1271@g.us"
        due 5000000
        t30 (- due domain/reminder-offset-30-ms)]
    (enable! s chat due)
    (let [a (raid-auto/tick! s {:agora-ms t30 :global-enabled? true :chat-id chat})
          ev (raid-auto/pending-events s {:limit 20})
          b (raid-auto/tick! s {:agora-ms t30 :global-enabled? true :chat-id chat})
          ev2 (raid-auto/pending-events s {:limit 20})]
      (is (= 1 (count (:reminded a))))
      (is (false? (:duplicate (first (:reminded a)))))
      (is (= 1 (count ev)))
      (is (= "raid-reminder-30" (:kind (first ev))))
      (is (false? (spoiler? (:texto (first ev)))))
      (is (true? (:duplicate (first (:reminded b)))))
      (is (= 1 (count ev2)))
      (is (nil? (raid-app/active-raid s chat))))))

(deftest lembretes-sobrevivem-restart-sem-duplicar
  (let [[s _] (fresh! "r1271-restart")
        chat "pilot-restart@g.us"
        due 8000000
        t15 (- due domain/reminder-offset-15-ms)]
    (enable! s chat due)
    ;; Enfileira T-30 na janela dele, depois T-15
    (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-30-ms)
                        :global-enabled? true :chat-id chat})
    (raid-auto/tick! s {:agora-ms t15 :global-enabled? true :chat-id chat})
    (let [before (raid-auto/pending-events s {:limit 20})
          s2 (reopen! "r1271-restart")
          again (raid-auto/tick! s2 {:agora-ms t15 :global-enabled? true :chat-id chat})
          after (raid-auto/pending-events s2 {:limit 20})]
      (is (= 2 (count before))) ;; 30 + 15
      (is (every? :duplicate (:reminded again)))
      (is (= (set (map :id before)) (set (map :id after)))))))

(deftest enabled-false-suprime-pendentes
  (let [[s _] (fresh! "r1271-off")
        chat "pilot-off@g.us"
        due 9000000
        t30 (- due domain/reminder-offset-30-ms)]
    (enable! s chat due)
    (raid-auto/tick! s {:agora-ms t30 :global-enabled? true :chat-id chat})
    (is (= 1 (count (raid-auto/pending-events s {:limit 10}))))
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled false :interval-ms (* 6 60 60 1000)
        :next-at due :agora-ms t30})
    (let [tick (raid-auto/tick! s {:agora-ms (inc t30) :global-enabled? true :chat-id chat})]
      (is (seq (:suppressed tick)))
      (is (empty? (raid-auto/pending-events s {:limit 10}))))))

(deftest grupo-desligado-nao-lembra
  (let [[s _] (fresh! "r1271-silent")
        chat "other@g.us"
        due 10000000
        t5 (- due domain/reminder-offset-5-ms)]
    ;; agenda default enabled false
    (let [tick (raid-auto/tick! s {:agora-ms t5 :global-enabled? true :chat-id chat})]
      (is (empty? (:reminded tick)))
      (is (empty? (raid-auto/pending-events s {:limit 10}))))))

(deftest encadeamento-lembrete-depois-spawn-oficial
  (let [[s _] (fresh! "r1271-chain")
        chat "pilot-chain@g.us"
        due 12000000
        t5 (- due domain/reminder-offset-5-ms)]
    (enable! s chat due)
    (doseq [off [domain/reminder-offset-30-ms
                 domain/reminder-offset-15-ms
                 domain/reminder-offset-5-ms]]
      (raid-auto/tick! s {:agora-ms (- due off) :global-enabled? true :chat-id chat}))
    (let [pre (raid-auto/pending-events s {:limit 20})]
      (is (= 3 (count pre)))
      (is (every? #(str/starts-with? (:kind %) "raid-reminder") pre))
      (doseq [e pre]
        (raid-auto/ack-event! s (:id e) t5)))
    (let [spawn (raid-auto/tick! s {:agora-ms due :global-enabled? true :chat-id chat})
          raid (raid-app/active-raid s chat)
          opened (raid-auto/pending-events s {:limit 10})]
      (is (= :spawn (:acao (first (:spawned spawn)))))
      (is (= "inscricoes" (:fase raid)))
      (is (= "auto" (:origem raid)))
      (is (= 1 (count opened)))
      (is (= "raid-opened" (:kind (first opened))))
      (is (re-find #"Chefe:" (:texto (first opened))))
      ;; lembretes não substituem o anúncio
      (is (empty? (filter #(str/starts-with? (:kind %) "raid-reminder") opened))))))
