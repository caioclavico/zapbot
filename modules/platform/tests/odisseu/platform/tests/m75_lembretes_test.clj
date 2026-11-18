(ns odisseu.platform.tests.m75-lembretes-test
  "M7.5 — lembretes Datomic: claim/lease/fence, sem WhatsApp real."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.lembretes :as lembretes]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private storage ".datomic-local/m75-platform")
(def ^:private system "odisseu-m75-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(defn- mk
  [id chat em & {:keys [texto criador]}]
  {:id id :chat chat :texto (or texto "x") :criador criador :em em})

(deftest criar-consultar-listar
  (let [s (fresh! "crud")
        now 1700000000000
        r (ports/create-reminder! s (mk "L-1" "c@g.us" (+ now 60000) :texto "caçar"
                                        :criador "u@c.us")
                                  "op-c1")]
    (is (false? (:duplicate r)))
    (is (= "pending" (get-in r [:reminder :status])))
    (is (= "caçar" (:texto (ports/get-reminder s "L-1"))))
    (is (= 1 (count (ports/list-reminders s "c@g.us"))))
    (is (empty? (ports/list-reminders s "other@g.us")))))

(deftest due-futuro-e-vencido
  (let [s (fresh! "due")
        now 1700000000000
        _ (ports/create-reminder! s (mk "L-f" "c@g.us" (+ now 10000)) "op-f")
        _ (ports/create-reminder! s (mk "L-d" "c@g.us" (- now 1000)) "op-d")
        due (ports/due-reminders s now 10)]
    (is (= ["L-d"] (mapv :id due)))
    (is (empty? (ports/due-reminders s (- now 5000) 10)))))

(deftest cancelamento
  (let [s (fresh! "cancel")
        _ (ports/create-reminder! s (mk "L-x" "c@g.us" 9999999999999) "op-x")
        c1 (ports/cancel-reminder! s "L-x" "c@g.us" "op-cx")
        c2 (ports/cancel-reminder! s "L-x" "c@g.us" "op-cx")]
    (is (true? (:cancelled c1)))
    (is (= "cancelled" (get-in c1 [:reminder :status])))
    (is (true? (:duplicate c2)))
    (is (empty? (ports/list-reminders s "c@g.us")))
    (is (thrown-with-msg? Exception #"reminder_wrong_chat"
          (ports/cancel-reminder! s "L-x" "outro@g.us" "op-bad")))))

(deftest dois-pollers-concorrentes
  (let [s (fresh! "race")
        now 1700000000000
        _ (ports/create-reminder! s (mk "L-race" "c@g.us" (- now 1)) "op-r")
        ;; corrida real via futures (CAS no Datomic)
        results (doall
                 (map deref
                      (map (fn [w]
                             (future (ports/claim-reminder! s "L-race" w 30000 now)))
                           ["worker-a" "worker-b" "worker-c"])))
        winners (filter :claimed results)
        losers (remove :claimed results)]
    (is (= 1 (count winners)))
    (is (= 1 (:fence (first winners))))
    (is (= 2 (count losers)))
    (is (every? #(contains? #{"cas_conflict" "not_claimable"} (:reason %)) losers))))

(deftest lease-expirado-reclaim
  (let [s (fresh! "lease")
        t0 1700000000000
        _ (ports/create-reminder! s (mk "L-lease" "c@g.us" (- t0 1)) "op-l")
        c1 (ports/claim-reminder! s "L-lease" "w1" 5000 t0)
        ;; lease até t0+5000; em t0+6000 está expirado
        c2 (ports/claim-reminder! s "L-lease" "w2" 5000 (+ t0 6000))]
    (is (true? (:claimed c1)))
    (is (true? (:claimed c2)))
    (is (= 2 (:fence c2)))
    (is (= "w2" (get-in c2 [:reminder :claimant])))
    ;; fence antigo rejeitado
    (let [done (ports/complete-reminder! s "L-lease" "w1" 1)]
      (is (false? (:completed done)))
      (is (= "fence_mismatch" (:reason done))))
    (is (true? (:completed (ports/complete-reminder! s "L-lease" "w2" 2))))))

(deftest crash-apos-claim-antes-envio
  (let [s (fresh! "crash-pre")
        t0 1700000000000
        _ (ports/create-reminder! s (mk "L-pre" "c@g.us" (- t0 1)) "op-p")
        _ (ports/claim-reminder! s "L-pre" "dead" 1000 t0)
        ;; simula crash: lease expira, outro worker reclama
        c2 (ports/claim-reminder! s "L-pre" "alive" 30000 (+ t0 2000))]
    (is (true? (:claimed c2)))
    (is (= "alive" (get-in c2 [:reminder :claimant])))))

(deftest crash-apos-envio-antes-confirm
  "Envio WA 'ocorreu' mas complete não: lease/fence ainda claimed → reclaim
   pode reenviar (at-least-once; documentado)."
  (let [s (fresh! "crash-post")
        t0 1700000000000
        _ (ports/create-reminder! s (mk "L-post" "c@g.us" (- t0 1)) "op-po")
        c1 (ports/claim-reminder! s "L-post" "w1" 1000 t0)
        ;; sem complete; lease expira
        c2 (ports/claim-reminder! s "L-post" "w2" 30000 (+ t0 2000))]
    (is (true? (:claimed c1)))
    (is (true? (:claimed c2)))
    (is (= 2 (:fence c2)))))

(deftest falha-temporaria-whatsapp
  (let [s (fresh! "fail")
        t0 1700000000000
        _ (ports/create-reminder! s (mk "L-fail" "c@g.us" (- t0 1)) "op-f")
        c (ports/claim-reminder! s "L-fail" "w" 30000 t0)
        f (ports/fail-reminder! s "L-fail" "w" (:fence c) "wa_timeout")]
    (is (true? (:failed f)))
    (is (= "failed" (get-in f [:reminder :status])))
    (is (= 1 (get-in f [:reminder :fail-count])))
    ;; failed volta a ser claimable
    (let [c2 (ports/claim-reminder! s "L-fail" "w2" 30000 (+ t0 1))]
      (is (true? (:claimed c2))))))

(deftest retry-idempotente-criacao
  (let [s (fresh! "idem")
        rem (mk "L-idem" "c@g.us" 2000000000000 :texto "x")
        r1 (ports/create-reminder! s rem "op-same")
        r2 (ports/create-reminder! s rem "op-same")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= 1 (count (ports/get-reminder-state s))))
    (is (thrown-with-msg? Exception #"platform_receipt_payload_mismatch"
          (ports/create-reminder! s (assoc rem :texto "y") "op-same")))))

(deftest isolamento-chats
  (let [s (fresh! "iso")
        _ (ports/create-reminder! s (mk "La" "a@g.us" 9000000000000000 :texto "a") "op-a")
        _ (ports/create-reminder! s (mk "Lb" "b@g.us" 9000000000000000 :texto "b") "op-b")]
    (is (= ["La"] (mapv :id (ports/list-reminders s "a@g.us"))))
    (is (= ["Lb"] (mapv :id (ports/list-reminders s "b@g.us"))))))

(deftest persistencia-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/create-reminder! s1 (mk "L-re" "c@g.us" 9000000000000000 :texto "keep") "op-re")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)]
    (is (= "keep" (:texto (ports/get-reminder s2 "L-re"))))
    (is (= 9000000000000000 (:em (ports/get-reminder s2 "L-re"))))))

(deftest import-equivalence-sem-auto-exec
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)
        rem (ports/get-reminder s "rem-m70-1")
        ;; import NÃO marca claimed; due existe mas nenhum poller JVM dispara WA
        due (ports/due-reminders s 2000000000000 10)]
    (is (true? (get-in report [:lembretes :equivalent?])) report)
    (is (= "pending" (:status rem)))
    (is (= 1893456000000 (:em rem)))
    (is (some #(= "rem-m70-1" (:id %)) due))
    (is (nil? (:claimant rem)))))

(deftest cancel-seguro-apos-claim
  (let [s (fresh! "cancel-claim")
        t0 1700000000000
        _ (ports/create-reminder! s (mk "L-cc" "c@g.us" (- t0 1)) "op-cc")
        _ (ports/claim-reminder! s "L-cc" "w" 30000 t0)
        c (ports/cancel-reminder! s "L-cc" "c@g.us" "op-cancel")]
    (is (true? (:cancelled c)))
    (is (= "cancelled" (get-in c [:reminder :status])))
    (is (false? (:completed (ports/complete-reminder! s "L-cc" "w" 1))))))
