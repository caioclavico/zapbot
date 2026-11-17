(ns pokemon.tests.raid-auto-intent-fase1273-test
  "Fase 12.7.3 — planejamento antecipado de intent/ovos antes de T-30."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-r1273")
(def ^:private storage-rel ".datomic-local/raid-auto-r1273")

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
  [s chat next-at & {:keys [interval-ms next-categoria daily-limit special-reserve]}]
  (raid-auto/upsert-schedule!
   s (cond-> {:chat-id chat
              :enabled true
              :interval-ms (or interval-ms domain/default-interval-ms)
              :next-at next-at
              :ginasio-index 0
              :agora-ms next-at
              :daily-limit (or daily-limit 12)
              :special-reserve (or special-reserve 2)}
       next-categoria (assoc :next-categoria next-categoria))))

(defn- brt-epoch
  [y m d h min]
  (.toEpochMilli
   (.toInstant
    (.atZone (java.time.LocalDateTime/of (int y) (int m) (int d) (int h) (int min))
             domain/brt-zone))))

(defn- remind-kinds
  [tick]
  (->> (:reminded tick)
       (filter #(= :remind (:acao %)))
       (map :kind)
       set))

;;;; Domínio puro

(deftest needs-intent-planning-antes-de-t30
  (let [due (brt-epoch 2026 11 2 23 0)
        sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/default-interval-ms
              :next-at due}
             "c" due)]
    (testing "fora do lead — ainda não planeja"
      (is (false? (domain/needs-intent-planning? sch (- due (* 90 60 1000))))))
    (testing "com mais de 30min (dentro do lead 40min)"
      (is (true? (domain/needs-intent-planning? sch (- due (* 40 60 1000))))))
    (testing "dentro da janela T-30"
      (is (true? (domain/needs-intent-planning? sch (- due domain/reminder-offset-30-ms)))))
    (testing "já tem categoria — não re-sorteia"
      (is (false? (domain/needs-intent-planning?
                   (assoc sch :next-categoria "lendario")
                   (- due (* 40 60 1000))))))
    (testing "disabled / due passado"
      (is (false? (domain/needs-intent-planning? (assoc sch :enabled false)
                                                 (- due (* 40 60 1000)))))
      (is (false? (domain/needs-intent-planning? sch due))))))

(deftest plan-slot-intent-preserva-especial
  (let [due (brt-epoch 2026 11 2 12 0)
        sch (domain/normalize-schedule
             {:enabled true :next-at due :next-categoria "lendario"
              :interval-ms domain/default-interval-ms}
             "c" due)
        intent (domain/plan-slot-intent sch {} {})]
    (is (= due (:next-at intent)))
    (is (= "lendario" (:categoria intent)))
    (is (true? (:reminders? intent)))))

(deftest plan-slot-intent-noite-escolhe-especial
  (let [due (brt-epoch 2026 11 2 23 0)
        sch (domain/normalize-schedule
             {:enabled true :next-at due
              :interval-ms domain/default-interval-ms}
             "c" due)
        intent (domain/plan-slot-intent sch {} {})]
    (is (domain/special-category? (:categoria intent)))
    (is (true? (:reminders? intent)))))

(deftest intent-matches-next-at
  (is (true? (domain/intent-matches-next-at? {:next-at 10} 10)))
  (is (false? (domain/intent-matches-next-at? {:next-at 10} 11)))
  (is (false? (domain/intent-matches-next-at? nil 10))))

;;;; Integração Datomic local (descartável)

(deftest primeira-raid-apos-enable-planeja-intent
  (let [db-name "i1273-enable-1"
        [s _] (fresh! db-name)
        chat "enable@g.us"
        due (brt-epoch 2026 11 2 23 0)
        t40 (- due domain/intent-planning-lead-ms)]
    (enable! s chat due)
    (is (nil? (:next-categoria (raid-auto/get-schedule s chat))))
    (let [tick (raid-auto/tick! s {:agora-ms t40 :chat-id chat
                                   :global-enabled? true :limit 8})
          sch (raid-auto/get-schedule s chat)]
      (is (seq (:planned tick)))
      (is (string? (:next-categoria sch)))
      (is (= due (:next-at sch)))
      (is (empty? (filter #(= :spawn (:acao %)) (:spawned tick))))
      (is (empty? (remind-kinds tick))))))

(deftest primeira-raid-apos-alterar-next-at
  (let [db-name "i1273-resched-1"
        [s _] (fresh! db-name)
        chat "resched@g.us"
        due1 (brt-epoch 2026 11 2 23 0)
        due2 (brt-epoch 2026 11 3 1 0)]
    (enable! s chat due1)
    (raid-auto/tick! s {:agora-ms (- due1 domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (let [cat1 (:next-categoria (raid-auto/get-schedule s chat))]
      (is (string? cat1))
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled true :next-at due2
          :interval-ms domain/default-interval-ms
          :agora-ms due2 :daily-limit 12 :special-reserve 2})
      (let [sch-mid (raid-auto/get-schedule s chat)]
        ;; intent antigo não casa com novo nextAt
        (is (nil? (:next-categoria sch-mid)))
        (is (= due2 (:next-at sch-mid))))
      (let [tick (raid-auto/tick! s {:agora-ms (- due2 domain/intent-planning-lead-ms)
                                     :chat-id chat :global-enabled? true :limit 8})
            sch2 (raid-auto/get-schedule s chat)]
        (is (seq (:planned tick)))
        (is (string? (:next-categoria sch2)))
        (is (= due2 (:next-at sch2)))
        (is (empty? (remind-kinds tick)))))))

(deftest planejamento-com-mais-de-30min
  (let [db-name "i1273-early-1"
        [s _] (fresh! db-name)
        chat "early@g.us"
        due (brt-epoch 2026 11 2 23 0)
        t40 (- due (* 40 60 1000))]
    (enable! s chat due)
    (let [tick (raid-auto/tick! s {:agora-ms t40 :chat-id chat
                                   :global-enabled? true :limit 8})
          sch (raid-auto/get-schedule s chat)]
      (is (>= (- due t40) (* 40 60 1000)))
      (is (seq (:planned tick)))
      (is (domain/special-category? (:next-categoria sch)))
      (is (true? (:next-reminders? sch)))
      (is (empty? (remind-kinds tick))))))

(deftest planejamento-dentro-da-janela-t30
  (let [db-name "i1273-late-1"
        [s _] (fresh! db-name)
        chat "late@g.us"
        due (brt-epoch 2026 11 2 23 0)
        t20 (- due (* 20 60 1000))]
    (enable! s chat due)
    (let [tick (raid-auto/tick! s {:agora-ms t20 :chat-id chat
                                   :global-enabled? true :limit 8})
          sch (raid-auto/get-schedule s chat)
          kinds (remind-kinds tick)]
      (is (seq (:planned tick)))
      (is (domain/special-category? (:next-categoria sch)))
      ;; T-30 já passou há 10min (> grace 2min) — só futuros válidos
      (is (not (contains? kinds "raid-reminder-30")))
      (is (empty? kinds)))))

(deftest tres-lembretes-raid-especial
  (let [db-name "i1273-eggs-1"
        [s _] (fresh! db-name)
        chat "eggs@g.us"
        due (brt-epoch 2026 11 2 23 0)]
    (enable! s chat due)
    (raid-auto/tick! s {:agora-ms (- due domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (is (domain/special-category? (:next-categoria (raid-auto/get-schedule s chat))))
    (let [r30 (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-30-ms)
                                  :chat-id chat :global-enabled? true :limit 8})
          r15 (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-15-ms)
                                  :chat-id chat :global-enabled? true :limit 8})
          r5 (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-5-ms)
                                 :chat-id chat :global-enabled? true :limit 8})]
      (is (= #{"raid-reminder-30"} (remind-kinds r30)))
      (is (= #{"raid-reminder-15"} (remind-kinds r15)))
      (is (= #{"raid-reminder-5"} (remind-kinds r5)))
      (doseq [m [30 15 5]]
        (let [t (domain/format-reminder m)]
          (is (re-find #"🥚" t))
          (is (not (re-find #"(?i)(lendár|mític|chefe|mewtwo)" t))))))))

(deftest raro-epico-sem-lembretes
  (let [db-name "i1273-easy-1"
        [s _] (fresh! db-name)
        chat "easy@g.us"
        ;; dia BRT → pick tende a épico/raro
        due (brt-epoch 2026 11 2 12 0)]
    (enable! s chat due)
    (raid-auto/tick! s {:agora-ms (- due domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (let [sch (raid-auto/get-schedule s chat)]
      (is (contains? #{"raro" "epico"} (:next-categoria sch)))
      (is (not (domain/special-category? (:next-categoria sch))))
      (is (false? (boolean (:next-reminders? sch)))))
    (let [r30 (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-30-ms)
                                  :chat-id chat :global-enabled? true :limit 8})]
      (is (empty? (remind-kinds r30))))))

(deftest restart-entre-t30-e-spawn
  (let [db-name "i1273-restart-1"
        [s _] (fresh! db-name)
        chat "restart@g.us"
        due (brt-epoch 2026 11 2 23 0)
        t30 (- due domain/reminder-offset-30-ms)]
    (enable! s chat due)
    (raid-auto/tick! s {:agora-ms (- due domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (let [cat (:next-categoria (raid-auto/get-schedule s chat))]
      (is (domain/special-category? cat))
      (raid-auto/tick! s {:agora-ms t30 :chat-id chat
                          :global-enabled? true :limit 8})
      (let [s2 (reopen! db-name)
            sch2 (raid-auto/get-schedule s2 chat)
            r2 (raid-auto/tick! s2 {:agora-ms t30 :chat-id chat
                                    :global-enabled? true :limit 8})
            rem2 (filter #(= :remind (:acao %)) (:reminded r2))]
        (is (= cat (:next-categoria sch2)))
        (is (= due (:next-at sch2)))
        (is (every? :duplicate rem2))
        (let [spawned (raid-auto/tick! s2 {:agora-ms due :chat-id chat
                                           :global-enabled? true :limit 8})
              rows (filter #(= :spawn (:acao %)) (:spawned spawned))]
          (is (seq rows))
          (is (= cat (get-in (first rows) [:raid :boss-categoria]))))))))
(deftest reagendamento-com-intent-existente
  (let [db-name "i1273-reput-1"
        [s _] (fresh! db-name)
        chat "reput@g.us"
        due1 (brt-epoch 2026 11 2 23 0)
        due2 (+ due1 (* 2 60 60 1000))]
    (enable! s chat due1 :next-categoria "lendario")
    (is (= "lendario" (:next-categoria (raid-auto/get-schedule s chat))))
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :next-at due2
        :interval-ms domain/default-interval-ms :agora-ms due2})
    (is (nil? (:next-categoria (raid-auto/get-schedule s chat))))
    (raid-auto/tick! s {:agora-ms (- due2 domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (let [sch (raid-auto/get-schedule s chat)
          pending (raid-auto/pending-events s {:limit 20})]
      (is (= due2 (:next-at sch)))
      (is (string? (:next-categoria sch)))
      ;; sem outbox retroativo do due1
      (is (empty? (filter #(str/includes? (str (:id %)) (str due1)) pending))))))

(deftest desativacao-nao-lembra-nem-planeja
  (let [db-name "i1273-off-1"
        [s _] (fresh! db-name)
        chat "off@g.us"
        due (brt-epoch 2026 11 2 23 0)]
    (enable! s chat due :next-categoria "mitico")
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled false :agora-ms (- due (* 40 60 1000))})
    (let [tick (raid-auto/tick! s {:agora-ms (- due domain/reminder-offset-30-ms)
                                   :chat-id chat :global-enabled? true :limit 8})]
      (is (empty? (:planned tick)))
      (is (empty? (remind-kinds tick)))
      (is (empty? (filter #(= :spawn (:acao %)) (:spawned tick))))
      (is (false? (:enabled (raid-auto/get-schedule s chat)))))))

(deftest idempotencia-intent-e-mensagens
  (let [db-name "i1273-idemp-1"
        [s _] (fresh! db-name)
        chat "idemp@g.us"
        due (brt-epoch 2026 11 2 23 0)
        t40 (- due domain/intent-planning-lead-ms)
        t30 (- due domain/reminder-offset-30-ms)]
    (enable! s chat due)
    (let [p1 (raid-auto/tick! s {:agora-ms t40 :chat-id chat
                                 :global-enabled? true :limit 8})
          cat (:next-categoria (raid-auto/get-schedule s chat))
          p2 (raid-auto/tick! s {:agora-ms t40 :chat-id chat
                                 :global-enabled? true :limit 8})]
      (is (seq (:planned p1)))
      (is (empty? (:planned p2)))
      (is (= cat (:next-categoria (raid-auto/get-schedule s chat))))
      (let [r1 (raid-auto/tick! s {:agora-ms t30 :chat-id chat
                                   :global-enabled? true :limit 8})
            r2 (raid-auto/tick! s {:agora-ms t30 :chat-id chat
                                   :global-enabled? true :limit 8})]
        (is (= #{"raid-reminder-30"} (remind-kinds r1)))
        (is (every? :duplicate (filter #(= :remind (:acao %)) (:reminded r2))))))))

(deftest categoria-planejada-usada-no-spawn
  (let [db-name "i1273-spawn-1"
        [s _] (fresh! db-name)
        chat "spawncat@g.us"
        due (brt-epoch 2026 11 2 23 0)]
    (enable! s chat due)
    (raid-auto/tick! s {:agora-ms (- due domain/intent-planning-lead-ms)
                        :chat-id chat :global-enabled? true :limit 8})
    (let [planned (:next-categoria (raid-auto/get-schedule s chat))
          tick (raid-auto/tick! s {:agora-ms due :chat-id chat
                                   :global-enabled? true :limit 8})
          spawned (filter #(= :spawn (:acao %)) (:spawned tick))]
      (is (domain/special-category? planned))
      (is (seq spawned))
      (is (= planned (get-in (first spawned) [:raid :boss-categoria])))
      ;; pós-spawn: intent do PRÓXIMO slot (pode diferir)
      (let [sch (raid-auto/get-schedule s chat)]
        (is (> (:next-at sch) due))
        (is (string? (:next-categoria sch)))))))

(deftest especial-reservado-nao-e-sobrescrito
  (let [db-name "i1273-reserve-1"
        [s _] (fresh! db-name)
        chat "reserve@g.us"
        due (brt-epoch 2026 11 2 12 0)]
    (enable! s chat due :next-categoria "mitico")
    (let [tick (raid-auto/tick! s {:agora-ms (- due domain/intent-planning-lead-ms)
                                   :chat-id chat :global-enabled? true :limit 8})
          sch (raid-auto/get-schedule s chat)]
      (is (empty? (:planned tick)))
      (is (= "mitico" (:next-categoria sch)))
      (is (true? (:next-reminders? sch))))))
