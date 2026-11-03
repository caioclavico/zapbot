(ns pokemon.tests.raid-auto-cadence-fase1282-test
  "Fase 12.8.2 — distribuição uniforme 08–23, lastSpawnId, lembretes fantasmas."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-auto-r1282")
(def ^:private storage-rel ".datomic-local/raid-auto-r1282")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(defn- brt-epoch
  [y m d h min]
  (.toEpochMilli
   (.toInstant
    (.atZone (java.time.LocalDateTime/of (int y) (int m) (int d) (int h) (int min))
             domain/brt-zone))))

(defn- uniform-sch
  [chat next-at & [extra]]
  (domain/normalize-schedule
   (merge {:enabled true
           :interval-ms domain/default-interval-ms
           :next-at next-at
           :daily-limit 12
           :special-reserve 2
           :distribution "uniform"
           :window-start-hour 8
           :window-end-hour 23
           :timezone "America/Sao_Paulo"
           :ginasio-index 0}
          extra)
   chat next-at))

;;;; Slots / janela

(deftest day-slots-uniform-12-na-janela-8-23
  (let [day (brt-epoch 2026 11 3 12 0)
        sch (uniform-sch "c" day)
        slots (domain/day-slots day sch)
        fmt (fn [ms]
              (let [z (.atZone (java.time.Instant/ofEpochMilli (long ms))
                               domain/brt-zone)]
                (format "%02d:%02d" (.getHour z) (.getMinute z))))]
    (is (= 12 (count slots)))
    (is (= "08:00" (fmt (first slots))))
    (is (= "21:45" (fmt (last slots))))
    (is (= ["08:00" "09:15" "10:30" "11:45" "13:00" "14:15"
            "15:30" "16:45" "18:00" "19:15" "20:30" "21:45"]
           (mapv fmt slots)))
    (doseq [s slots]
      (is (domain/day-window-brt? s sch)))))

(deftest nenhum-spawn-fora-da-janela-uniform
  (let [due (brt-epoch 2026 11 3 2 0) ;; 02:00 BRT
        sch (uniform-sch "c" due)
        plan (domain/plan-spawn sch nil due {})]
    (is (= :skip-outside-window (:acao plan)))
    (is (domain/day-window-brt? (:next-at plan) sch))
    (is (= (brt-epoch 2026 11 3 8 0) (:next-at plan)))))

(deftest skip-missed-sem-catch-up
  (let [due (brt-epoch 2026 11 3 8 0)
        agora (brt-epoch 2026 11 3 11 0) ;; 3h atrasado
        sch (uniform-sch "c" due)
        plan (domain/plan-spawn sch nil agora {})]
    (is (= :skip-missed (:acao plan)))
    (is (= (brt-epoch 2026 11 3 11 45) (:next-at plan)))
    (is (> (:next-at plan) agora))))

(deftest advance-uniform-nao-comprime
  (let [due (brt-epoch 2026 11 3 8 0)
        sch (uniform-sch "c" due)
        next (domain/advance-next-at sch due (* 5 60 1000))]
    (is (= (brt-epoch 2026 11 3 9 15) next))))

(deftest reset-diario-apos-cap
  (let [due (brt-epoch 2026 11 3 21 45)
        sch (uniform-sch "c" due)
        plan (domain/plan-spawn sch nil due
                                {:spawns-today 12 :specials-today 2
                                 :last-by-categoria {"raro" due}})]
    (is (= :skip-daily-cap (:acao plan)))
    (is (= (brt-epoch 2026 11 4 8 0) (:next-at plan)))))

;;;; Elegibilidade / reserva

(deftest special-reserve-nao-bloqueia-comum
  (let [noon (brt-epoch 2026 11 3 14 15)
        sch (uniform-sch "c" noon)
        ;; Reserva esgotada, lendário due → comum
        cat (domain/pick-categoria noon
                                   {"epico" noon}
                                   {:spawns-today 5 :specials-today 2
                                    :daily-limit 12 :special-reserve 2
                                    :distribution "uniform"
                                    :window-start-hour 8 :window-end-hour 23})]
    (is (= "raro" cat)))
  (let [noon (brt-epoch 2026 11 3 14 15)
        ;; lendário due (sem last), mas reserva esgotada → comum
        resolved (domain/resolve-categoria
                  "lendario" noon {"epico" noon}
                  {:spawns-today 5 :specials-today 2
                   :daily-limit 12 :special-reserve 2
                   :distribution "uniform"
                   :window-start-hour 8 :window-end-hour 23})]
    (is (= "raro" (:categoria resolved)))
    (is (= :special-reserve-exhausted (:fallback-reason resolved)))))

(deftest fallback-categoria-em-cooldown
  (let [noon (brt-epoch 2026 11 3 12 0)
        resolved (domain/resolve-categoria
                  "epico" noon {"epico" noon}
                  {:spawns-today 1 :specials-today 0
                   :daily-limit 12 :special-reserve 2
                   :distribution "uniform"
                   :window-start-hour 8 :window-end-hour 23})]
    (is (= "raro" (:categoria resolved)))
    (is (= :category-cooldown (:fallback-reason resolved)))))

;;;; Lembretes fantasmas

(deftest lembrete-bloqueado-quando-cap-cheio
  (let [due (brt-epoch 2026 11 3 18 0)
        sch (uniform-sch "c" due {:next-categoria "lendario"
                                  :next-reminders? true})
        t30 (- due domain/reminder-offset-30-ms)]
    (is (empty? (domain/plan-reminders sch t30 {:spawns-today 12})))
    (is (seq (domain/plan-reminders sch t30 {:spawns-today 3})))))

(deftest lembrete-nao-aceita-explicit-true-em-raro
  (let [due (brt-epoch 2026 11 3 18 0)
        sch (uniform-sch "c" due {:next-categoria "raro"
                                  :next-reminders? true})]
    (is (false? (domain/reminders-allowed? sch)))
    (is (empty? (domain/plan-reminders sch (- due domain/reminder-offset-30-ms))))))

(deftest lembrete-fora-da-janela-uniform
  (let [due (brt-epoch 2026 11 3 2 0)
        sch (uniform-sch "c" due {:next-categoria "mitico"})]
    (is (false? (domain/reminder-opportunity-viable? sch (- due 1000))))))

;;;; lastSpawnId preservado em skip (integração)

(deftest last-spawn-id-preservado-em-skip-busy
  (let [[s _] (fresh! "skip-last-spawn")
        chat "pilot-1282-skip@g.us"
        t0 (brt-epoch 2026 11 3 8 0)
        _ (raid-auto/upsert-schedule!
           s {:chat-id chat :enabled true :next-at t0
              :interval-ms domain/default-interval-ms
              :distribution "uniform"
              :window-start-hour 8 :window-end-hour 23
              :daily-limit 12 :special-reserve 2
              :agora-ms t0})
        tick1 (raid-auto/tick! s {:agora-ms t0 :chat-id chat
                                  :global-enabled? true :limit 4})
        sch1 (raid-auto/get-schedule s chat)
        last1 (:last-spawn-id sch1)
        ;; Ainda no lobby (raro=5min): due do próximo slot ainda não; força
        ;; plan skip-busy via tick com next-at artificialmente vencido cedo.
        _ (is (some? last1))
        _ (raid-auto/upsert-schedule!
           s {:chat-id chat :enabled true
              :next-at (+ t0 (* 2 60 1000))
              :agora-ms (+ t0 (* 2 60 1000))})
        tick2 (raid-auto/tick! s {:agora-ms (+ t0 (* 2 60 1000))
                                  :chat-id chat
                                  :global-enabled? true :limit 4})
        sch2 (raid-auto/get-schedule s chat)
        skipped (filter #(contains? #{:skip-busy} (:acao %))
                        (or (:spawned tick2) []))]
    (is (seq (:spawned tick1)))
    (is (seq skipped) "lobby ativo deve gerar skip-busy")
    (is (= last1 (:last-spawn-id sch2)))
    (is (not= (:spawn-id (first skipped)) (:last-spawn-id sch2)))
    (is (= last1 (:last-raid-id sch1)))))

;;;; Simulação 7 dias

(deftest simulacao-sete-dias-uniforme
  (let [start (brt-epoch 2026 11 3 8 0)
        sim (domain/simulate-cadence
             {:start-ms start
              :days 7
              :daily-limit 12
              :special-reserve 2
              :distribution "uniform"
              :window-start-hour 8
              :window-end-hour 23
              :timezone "America/Sao_Paulo"})]
    (is (= "uniform" (:distribution sim)))
    (is (= 12 (count (:sample-day-slots sim))))
    (is (<= (:max-por-dia sim) 12))
    (is (>= (:total-spawns sim) (* 7 8))) ;; pelo menos maioria dos slots
    (is (<= (:total-spawns sim) (* 7 12)))
    ;; Nenhuma hora fora 8–22
    (doseq [[h _] (:por-hora-brt sim)]
      (is (<= 8 h 22)))
    (is (zero? (get (:skip-counts sim) :skip-outside-window 0)))
    (testing "não concentra tudo de manhã — há spawns após 14h"
      (is (some (fn [[h n]] (and (>= h 14) (pos? n))) (:por-hora-brt sim))))))

(deftest distribution-default-continua-interval
  (let [sch (domain/normalize-schedule
             {:enabled true :next-at 1000 :interval-ms domain/default-interval-ms}
             "c" 1000)]
    (is (= "interval" (:distribution sch)))
    (is (= 22 (:window-end-hour sch)))))

;;;; Transição interval → uniform (meio do dia / cap / meia-noite)

(deftest ativacao-uniform-meio-do-dia-sem-catch-up
  (let [agora (brt-epoch 2026 11 3 14 20) ;; após slot 14:15
        ;; Já houve 6 spawns no dia; agenda interval apontava para "agora"
        interval-sch (domain/normalize-schedule
                      {:enabled true :next-at agora
                       :interval-ms domain/default-interval-ms
                       :daily-limit 12 :special-reserve 2
                       :distribution "interval"
                       :spawns-today 6}
                      "c" agora)
        uniform (domain/normalize-schedule
                 (assoc interval-sch
                        :distribution "uniform"
                        :window-start-hour 8
                        :window-end-hour 23
                        :timezone "America/Sao_Paulo"
                        ;; ops: nextAt = próximo slot futuro elegível
                        :next-at (domain/next-slot-at-or-after agora
                                  (assoc interval-sch
                                         :distribution "uniform"
                                         :window-start-hour 8
                                         :window-end-hour 23
                                         :daily-limit 12)))
                 "c" agora)
        next (:next-at uniform)
        plan (domain/plan-spawn uniform nil agora
                                {:spawns-today 6 :specials-today 1})]
    (is (= "uniform" (:distribution uniform)))
    (is (= (brt-epoch 2026 11 3 15 30) next))
    (is (= :noop (:acao plan)) "não dispara imediatamente no meio do slot")
    ;; Quando o slot 15:30 vencer, ainda há orçamento (6<12)
    (let [due next
          p2 (domain/plan-spawn (assoc uniform :next-at due) nil due
                                {:spawns-today 6 :specials-today 1
                                 :rng (constantly 0.01)})]
      (is (= :spawn (:acao p2)))
      (is (= (brt-epoch 2026 11 3 16 45) (:next-at p2))))))

(deftest ativacao-uniform-com-cap-ja-atingido
  (let [agora (brt-epoch 2026 11 3 16 0)
        next (domain/next-slot-at-or-after agora
                                          {:distribution "uniform"
                                           :window-start-hour 8
                                           :window-end-hour 23
                                           :daily-limit 12})
        sch (uniform-sch "c" next)
        plan (domain/plan-spawn sch nil next
                                {:spawns-today 12 :specials-today 2})]
    (is (= :skip-daily-cap (:acao plan)))
    (is (= (brt-epoch 2026 11 4 8 0) (:next-at plan)))
    (is (zero? (domain/slot-index-on-day (:next-at plan) sch)))))

(deftest ativacao-proxima-meia-noite-brt
  (let [agora (brt-epoch 2026 11 3 22 30) ;; após último slot 21:45
        next (domain/next-slot-at-or-after agora
                                          {:distribution "uniform"
                                           :window-start-hour 8
                                           :window-end-hour 23
                                           :daily-limit 12})]
    (is (= (brt-epoch 2026 11 4 8 0) next))))

(deftest rollback-uniform-para-interval
  (let [due (brt-epoch 2026 11 3 15 30)
        uni (uniform-sch "c" due)
        back (domain/normalize-schedule
              (assoc uni :distribution "interval" :window-end-hour 22)
              "c" due)
        next (domain/advance-next-at back due (* 5 60 1000))]
    (is (= "interval" (:distribution back)))
    (is (= 22 (:window-end-hour back)))
    ;; intervalo legado: due+1h
    (is (= (+ due (* 60 60 1000)) next))))

(deftest diagnostics-separa-campos
  (let [due (brt-epoch 2026 11 3 18 0)
        sch (uniform-sch "c" due {:next-categoria "lendario"
                                  :last-spawn-id "raid-auto:c:111"
                                  :last-raid-id "raid-auto:c:111"})
        diag (domain/schedule-diagnostics sch (- due 1000)
                                          {:spawns-today 4 :specials-today 1
                                           :last-skip-reason :skip-busy})]
    (is (= "uniform" (:distribution diag)))
    (is (= "America/Sao_Paulo" (:timezone diag)))
    (is (= 8 (:slots-remaining diag)))
    (is (= 1 (:special-remaining diag)))
    (is (= "lendario" (:next-categoria diag)))
    (is (= "raid-auto:c:111" (:last-spawn-id diag)))
    (is (= :skip-busy (:last-skip-reason diag)))
    (is (vector? (:slots-today diag)))
    (is (= 12 (count (:slots-today diag))))))
