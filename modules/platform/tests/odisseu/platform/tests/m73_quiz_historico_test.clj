(ns odisseu.platform.tests.m73-quiz-historico-test
  "M7.3 — contratos quiz-historico em Datomic Local descartável.
   Sem produção, sem WhatsApp, sem dados reais. Rank não migrado."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.quiz-historico :as quiz-hist]
            [odisseu.platform.infrastructure.migration.compare :as compare]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private storage ".datomic-local/m73-platform")
(def ^:private system "odisseu-m73-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(deftest append-e-consulta
  (let [s (fresh! "app")
        r1 (ports/append-quiz-question! s "chat-a@g.us" "P1?" "op-1")
        r2 (ports/append-quiz-question! s "chat-a@g.us" "P2?" "op-2")
        h (ports/get-chat-quiz-history s "chat-a@g.us")]
    (is (false? (:duplicate r1)))
    (is (= ["P1?" "P2?"] h))
    (is (= ["P1?" "P2?"] (:history r2)))))

(deftest isolamento-por-chat
  (let [s (fresh! "iso")
        _ (ports/append-quiz-question! s "chat-a@g.us" "A?" "op-a")
        _ (ports/append-quiz-question! s "chat-b@g.us" "B?" "op-b")]
    (is (= ["A?"] (ports/get-chat-quiz-history s "chat-a@g.us")))
    (is (= ["B?"] (ports/get-chat-quiz-history s "chat-b@g.us")))
    (is (= [] (ports/get-chat-quiz-history s "chat-c@g.us")))))

(deftest retencao-max-12
  (let [s (fresh! "max")]
    (doseq [i (range 15)]
      (ports/append-quiz-question! s "chat-m@g.us" (str "Q" i)
                                   (str "op-m-" i)))
    (let [h (ports/get-chat-quiz-history s "chat-m@g.us")]
      (is (= quiz-hist/historico-max (count h)))
      (is (= "Q3" (first h)))
      (is (= "Q14" (last h))))))

(deftest idempotencia-operation-id
  (let [s (fresh! "idem")
        r1 (ports/append-quiz-question! s "chat-i@g.us" "Mesma?" "op-i")
        r2 (ports/append-quiz-question! s "chat-i@g.us" "Mesma?" "op-i")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= ["Mesma?"] (:history r2)))
    (is (= 1 (count (ports/get-chat-quiz-history s "chat-i@g.us"))))
    (is (thrown-with-msg? Exception #"platform_receipt_payload_mismatch"
          (ports/append-quiz-question! s "chat-i@g.us" "Outra?" "op-i")))))

(deftest persistencia-apos-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/append-quiz-question! s1 "chat-r@g.us" "Persist?" "op-r")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)]
    (is (= ["Persist?"] (ports/get-chat-quiz-history s2 "chat-r@g.us")))))

(deftest import-export-equivalence
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)]
    (is (true? (get-in report [:quiz-historico :equivalent?])) report)
    (is (= ["Capital do Brasil?" "Maior planeta?"]
           (get-in imported [:quiz-historico "chat-m70-a@g.us"])))
    (let [imported2 (pipe/import-ir! s ir)
          report2 (pipe/equivalence-report ir imported2)]
      (is (true? (get-in report2 [:quiz-historico :equivalent?])) report2))))

(deftest historico-vazio
  (let [s (fresh! "empty")]
    (is (= [] (ports/get-chat-quiz-history s "chat-x@g.us")))
    (is (empty? (ports/get-quiz-history-state s)))))
