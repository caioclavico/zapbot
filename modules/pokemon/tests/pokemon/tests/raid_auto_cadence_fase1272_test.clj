(ns pokemon.tests.raid-auto-cadence-fase1272-test
  "Fase 12.7.2 — cadência por categoria, lembretes só especiais, anti-conflito."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-r1272")
(def ^:private storage-rel ".datomic-local/raid-auto-r1272")

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
  [s chat next-at & {:keys [interval-ms next-categoria category-intervals
                            daily-limit special-reserve safe-cadence?]}]
  (raid-auto/upsert-schedule!
   s (cond-> {:chat-id chat
              :enabled true
              :interval-ms (or interval-ms domain/default-interval-ms)
              :next-at next-at
              :ginasio-index 0
              :agora-ms next-at}
       next-categoria (assoc :next-categoria next-categoria)
       category-intervals (assoc :category-intervals category-intervals)
       (some? daily-limit) (assoc :daily-limit daily-limit)
       (some? special-reserve) (assoc :special-reserve special-reserve)
       safe-cadence? (assoc :safe-cadence? true))))

(defn- brt-epoch
  [y m d h min]
  (.toEpochMilli
   (.toInstant
    (.atZone (java.time.LocalDateTime/of (int y) (int m) (int d) (int h) (int min))
             domain/brt-zone))))

;;;; Domínio puro

(deftest category-intervals-default
  (is (= (* 1 60 60 1000) (get domain/category-interval-ms-default "raro")))
  (is (= (* 3 60 60 1000) (get domain/category-interval-ms-default "epico")))
  (is (= (* 12 60 60 1000) (get domain/category-interval-ms-default "lendario")))
  (is (= (* 24 60 60 1000) (get domain/category-interval-ms-default "mitico")))
  (is (= (* 60 60 1000) domain/default-interval-ms)))

(deftest pick-categoria-dia-prioriza-facil
  (let [noon-brt (brt-epoch 2026 11 2 12 0)]
    (is (domain/day-window-brt? noon-brt))
    (testing "tudo due → épico (dia)"
      (is (= "epico" (domain/pick-categoria noon-brt {}))))
    (testing "épico em cooldown → raro"
      (is (= "raro"
             (domain/pick-categoria noon-brt {"epico" noon-brt}))))
    (testing "especial overdue entra mesmo de dia"
      (let [last-leg (- noon-brt (* 20 60 60 1000))]
        (is (= "lendario"
               (domain/pick-categoria noon-brt
                                      {"lendario" last-leg
                                       "mitico" noon-brt
                                       "epico" noon-brt
                                       "raro" noon-brt})))))))

(deftest pick-categoria-noite-prioriza-especial
  (let [night (brt-epoch 2026 11 2 23 0)]
    (is (not (domain/day-window-brt? night)))
    (is (= "mitico" (domain/pick-categoria night {})))
    (is (= "lendario"
           (domain/pick-categoria night {"mitico" night})))
    (is (= "epico"
           (domain/pick-categoria night
                                  {"mitico" night
                                   "lendario" night})))))

(deftest reminders-so-especiais-ou-legado
  (testing "raro 1h — sem lembrete"
    (let [due (* 60 60 1000)
          sch (domain/normalize-schedule
               {:enabled true :interval-ms domain/default-interval-ms
                :next-at due :next-categoria "raro"
                :next-reminders? false}
               "c" 0)]
      (is (false? (domain/reminders-allowed? sch)))
      (is (empty? (domain/plan-reminders sch (- due domain/reminder-offset-5-ms))))))
  (testing "lendário — com lembrete"
    (let [due (* 12 60 60 1000)
          sch (domain/normalize-schedule
               {:enabled true :interval-ms domain/default-interval-ms
                :next-at due :next-categoria "lendario"}
               "c" 0)
          ps (domain/plan-reminders sch (- due domain/reminder-offset-30-ms))]
      (is (true? (domain/reminders-allowed? sch)))
      (is (= 1 (count ps)))
      (is (= "raid-reminder-30" (:kind (first ps))))))
  (testing "legado 6h sem next-categoria — preserva 12.7.1"
    (let [due (* 6 60 60 1000)
          sch (domain/normalize-schedule
               {:enabled true :interval-ms (* 6 60 60 1000) :next-at due}
               "c" 0)]
      (is (nil? (:next-categoria sch)))
      (is (true? (domain/reminders-allowed? sch)))
      (is (= 1 (count (domain/plan-reminders
                       sch (- due domain/reminder-offset-15-ms))))))))

(deftest skip-busy-preserva-especial
  (let [due 1000000
        sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/default-interval-ms
              :next-at due :next-categoria "lendario" :ginasio-index 0}
             "c" due)
        active {:raid-id "r1" :fase "combate" :origem "manual"}
        busy (domain/plan-spawn sch active due
                                {:preserve-next-categoria "lendario"
                                 :last-by-categoria {}})]
    (is (= :skip-busy (:acao busy)))
    (is (= "lendario" (:next-categoria busy)))
    (is (true? (:next-reminders? busy)))
    (is (= (domain/advance-next-at sch due) (:next-at busy)))))

(deftest plan-spawn-forca-categoria-do-intent
  (let [due 2000000
        sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/default-interval-ms
              :next-at due :ginasio-index 0}
             "c" due)
        plan (domain/plan-spawn sch nil due
                                {:forced-categoria "raro"
                                 :rng (constantly 0.01)
                                 :last-by-categoria {}})]
    (is (= :spawn (:acao plan)))
    (is (= "raro" (:boss-categoria plan)))
    (is (map? (:intent plan)))
    (is (string? (get-in plan [:intent :categoria])))))

(deftest category-intervals-configuraveis
  (let [custom {"raro" 1000 "epico" 2000 "lendario" 3000 "mitico" 4000}
        n (domain/normalize-category-intervals custom)]
    (is (= 1000 (get n "raro")))
    (is (= :raid_auto_category_interval_invalid
           (try (domain/normalize-category-intervals {"raro" 0})
                (catch Exception ex (:pokemon/error (ex-data ex))))))))

;;;; Integração (Datomic local descartável)

(deftest spawn-grava-intent-e-idempotente-pos-restart
  (let [db-name "cadence-intent-1"
        [s _] (fresh! db-name)
        chat "120363427684548487@g.us"
        due 5000000
        t1 (do (enable! s chat due)
               (raid-auto/tick! s {:agora-ms due :chat-id chat
                                   :global-enabled? true :limit 8}))
        spawned (filter #(= :spawn (:acao %)) (:spawned t1))
        sch (raid-auto/get-schedule s chat)
        s2 (reopen! db-name)
        t2 (raid-auto/tick! s2 {:agora-ms due :chat-id chat
                                :global-enabled? true :limit 8})
        again (filter #(= :spawn (:acao %)) (:spawned t2))]
    (is (seq spawned))
    (is (> (:next-at sch) due))
    (is (string? (:next-categoria sch)))
    (is (empty? again))
    (is (= (:next-at sch)
           (:next-at (raid-auto/get-schedule s2 chat))))))

(deftest raro-nao-gera-lembrete-lendario-gera
  (let [db-name "cadence-remind-1"
        [s _] (fresh! db-name)
        chat "pilot-remind@g.us"
        due (+ (System/currentTimeMillis) (* 60 60 1000))
        t30 (- due domain/reminder-offset-30-ms)]
    (enable! s chat due :next-categoria "raro")
    (let [r (raid-auto/tick! s {:agora-ms t30 :chat-id chat
                                :global-enabled? true :limit 8})]
      (is (empty? (filter #(= :remind (:acao %)) (:reminded r)))))
    (enable! s chat due :next-categoria "lendario")
    (let [r2 (raid-auto/tick! s {:agora-ms t30 :chat-id chat
                                 :global-enabled? true :limit 8})
          rem (filter #(= :remind (:acao %)) (:reminded r2))
          s2 (reopen! db-name)
          r3 (raid-auto/tick! s2 {:agora-ms t30 :chat-id chat
                                  :global-enabled? true :limit 8})
          rem2 (filter #(= :remind (:acao %)) (:reminded r3))]
      (is (seq rem))
      (is (every? #(str/starts-with? (str (:kind %)) "raid-reminder") rem))
      (is (every? :duplicate rem2)))))

(deftest skip-busy-nao-cancela-raid-ativa
  (let [db-name "cadence-busy-1"
        [s _] (fresh! db-name)
        chat "busy@g.us"
        due 9000000
        due2 (+ due 60000)]
    (enable! s chat due :next-categoria "epico")
    (raid-auto/tick! s {:agora-ms due :chat-id chat
                        :global-enabled? true :limit 8})
    (let [sch1 (raid-auto/get-schedule s chat)
          next1 (:next-at sch1)]
      (raid-auto/upsert-schedule!
       s {:chat-id chat :enabled true :next-at due2 :agora-ms due2
          :interval-ms domain/default-interval-ms
          :next-categoria "lendario"})
      (let [t2 (raid-auto/tick! s {:agora-ms due2 :chat-id chat
                                   :global-enabled? true :limit 8})
            skipped (filter #(= :skip-busy (:acao %)) (:spawned t2))
            sch2 (raid-auto/get-schedule s chat)]
        (is (seq skipped))
        (is (= "lendario" (:next-categoria sch2)))
        (is (> (:next-at sch2) due2))
        (is (not= next1 (:next-at sch2)))))))

;;;; Readiness 12.7.2 — limite diário / migração / simulação

(deftest daily-cap-skip-e-reserva-especial
  (let [noon (brt-epoch 2026 11 3 14 0)
        sch (domain/normalize-schedule
             {:enabled true :interval-ms domain/default-interval-ms
              :next-at noon :daily-limit 12 :special-reserve 2
              :ginasio-index 0}
             "c" noon)
        ;; Cap cheio, sem especial planejado → skip-daily-cap
        capped (domain/plan-spawn sch nil noon
                                  {:spawns-today 12 :specials-today 1
                                   :last-by-categoria {"raro" noon}})]
    (is (= :skip-daily-cap (:acao capped)))
    (is (>= (:next-at capped) (domain/next-day-morning-brt noon)))
    ;; Reserva: restante 2 + especial due + 0 especiais hoje → pick lendario
    (is (= "lendario"
           (domain/pick-categoria noon
                                  {"epico" noon "raro" noon
                                   "mitico" noon}
                                  {:spawns-today 10 :specials-today 0
                                   :daily-limit 12 :special-reserve 2})))))

(deftest safe-cadence-preserva-next-at-e-especial
  (let [future-at (brt-epoch 2026 11 2 12 0)
        agora (brt-epoch 2026 11 2 9 0)
        cur (domain/normalize-schedule
             {:enabled true :interval-ms (* 6 60 60 1000)
              :next-at future-at :next-categoria "lendario"
              :revision 3}
             "pilot@g.us" agora)
        patch (domain/safe-cadence-patch
               cur {:interval-ms (* 60 60 1000)
                    :daily-limit 12
                    :special-reserve 2
                    :next-at agora ;; tentativa de antecipar — rejeitada
                    :agora agora})]
    (is (= (* 60 60 1000) (:interval-ms patch)))
    (is (= 12 (:daily-limit patch)))
    (is (= future-at (:next-at patch)))
    (is (= "lendario" (:next-categoria patch)))
    (is (true? (:next-reminders? patch)))))

(deftest lembretes-nunca-spoiler-chefe
  (doseq [m [30 15 5]]
    (let [t (domain/format-reminder m)]
      (is (re-find #"🥚" t))
      (is (not (re-find #"(?i)chefe" t)))
      (is (not (re-find #"(?i)(mewtwo|arceus|charizard|lendár)" t)))
      (is (not (re-find #"(?i)entrar auto" t))))))

(deftest simulacao-sete-dias-limite-12
  (let [start (brt-epoch 2026 11 3 8 0)
        sim (domain/simulate-cadence
             {:start-ms start :days 7 :daily-limit 12 :special-reserve 2})
        by (:por-categoria sim)
        wa (:wa-messages-per-day-est sim)]
    (is (= 7 (count (:por-dia sim))))
    (is (= 12 (:max-por-dia sim)))
    (is (every? #(= 12 %) (vals (:por-dia sim))))
    (is (= 84 (:total-spawns sim)))
    ;; especiais não starve: ≥1 lendário e ≥1 mítico na semana
    (is (pos? (long (get by "lendario" 0))))
    (is (pos? (long (get by "mitico" 0))))
    (is (pos? (long (get by "raro" 0))))
    (is (pos? (long (get by "epico" 0))))
    ;; ~30 msgs/dia com cap 12 (fáceis×2 + especiais×5)
    (is (<= 24 (:total-est wa) 36))
    (is (= 12 (:opened wa)))))

(deftest interval-change-nao-spawna-retroativo
  (let [db-name "cadence-migrate-1"
        [s _] (fresh! db-name)
        chat "migrate@g.us"
        future (brt-epoch 2026 11 5 15 0)
        agora (brt-epoch 2026 11 5 10 0)]
    (enable! s chat future :interval-ms (* 6 60 60 1000)
             :next-categoria "lendario")
    (raid-auto/upsert-schedule!
     s {:chat-id chat
        :interval-ms (* 60 60 1000)
        :daily-limit 12
        :special-reserve 2
        :safe-cadence? true
        :agora-ms agora
        :next-at agora})
    (let [sch (raid-auto/get-schedule s chat)
          tick (raid-auto/tick! s {:agora-ms agora :chat-id chat
                                   :global-enabled? true :limit 8})]
      (is (= future (:next-at sch)))
      (is (= "lendario" (:next-categoria sch)))
      (is (= (* 60 60 1000) (:interval-ms sch)))
      (is (empty? (filter #(= :spawn (:acao %)) (:spawned tick)))))))
