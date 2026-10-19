(ns odisseu.platform.tests.m74-rank-test
  "M7.4 — contratos rank em Datomic Local descartável.
   Sem produção, sem WhatsApp, sem dados reais."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.rank :as rank]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private storage ".datomic-local/m74-platform")
(def ^:private system "odisseu-m74-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(deftest pontuar-acumula-e-ordena
  (let [s (fresh! "score")
        _ (ports/score-point! s "chat-a@g.us" "u1@c.us" "Ash" "velha" "op-v1")
        _ (ports/score-point! s "chat-a@g.us" "u1@c.us" "Ash" "quiz" "op-q1")
        _ (ports/score-point! s "chat-a@g.us" "u2@c.us" "Misty" "naval" "op-n1")
        top (rank/top-players (ports/get-chat-rank s "chat-a@g.us") 10)]
    (is (= 2 (get-in (ports/get-chat-rank s "chat-a@g.us") ["u1@c.us" "pontos"])))
    (is (= 1 (get-in (ports/get-chat-rank s "chat-a@g.us") ["u1@c.us" "jogos" "velha"])))
    (is (= "Ash" (get (first top) "nome")))
    (is (= "Misty" (get (second top) "nome")))))

(deftest isolamento-por-chat
  (let [s (fresh! "iso")
        _ (ports/score-point! s "chat-a@g.us" "u@c.us" "A" "velha" "op-a")
        _ (ports/score-point! s "chat-b@g.us" "u@c.us" "B" "velha" "op-b")]
    (is (= 1 (get-in (ports/get-chat-rank s "chat-a@g.us") ["u@c.us" "pontos"])))
    (is (= 1 (get-in (ports/get-chat-rank s "chat-b@g.us") ["u@c.us" "pontos"])))
    (is (= "A" (get-in (ports/get-chat-rank s "chat-a@g.us") ["u@c.us" "nome"])))))

(deftest idempotencia-e-eventos-distintos
  (let [s (fresh! "idem")
        r1 (ports/score-point! s "chat-i@g.us" "u@c.us" "U" "quiz" "op-same")
        r2 (ports/score-point! s "chat-i@g.us" "u@c.us" "U" "quiz" "op-same")
        r3 (ports/score-point! s "chat-i@g.us" "u@c.us" "U" "quiz" "op-other")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= 1 (get-in (:player r2) ["pontos"])))
    (is (= 2 (get-in (:player r3) ["pontos"])))
    (is (thrown-with-msg? Exception #"platform_receipt_payload_mismatch"
          (ports/score-point! s "chat-i@g.us" "u@c.us" "U" "velha" "op-same")))))

(deftest penalizar-e-idempotente
  (let [s (fresh! "pen")
        _ (ports/score-point! s "chat-p@g.us" "u@c.us" "U" "velha" "op-s")
        p1 (ports/penalize-point! s "chat-p@g.us" "u@c.us" "op-pen")
        p2 (ports/penalize-point! s "chat-p@g.us" "u@c.us" "op-pen")
        p3 (ports/penalize-point! s "chat-p@g.us" "u@c.us" "op-pen2")]
    (is (true? (:penalized p1)))
    (is (true? (:duplicate p2)))
    (is (true? (:penalized p2)))
    (is (false? (:penalized p3)))
    (is (= 0 (get-in (ports/get-chat-rank s "chat-p@g.us") ["u@c.us" "pontos"])))))

(deftest efeito-pokemon-dedupe
  (let [s (fresh! "eff")
        e {:id "eff-1" :type "rank.increment" :chatId "chat-e@g.us"
           :playerId "u@c.us" :playerName "Ash" :game "pokemon"}
        r1 (ports/apply-rank-effect! s e)
        r2 (ports/apply-rank-effect! s e)]
    (is (true? (:resultado r1)))
    (is (true? (:duplicate r2)))
    (is (= 1 (get-in (ports/get-chat-rank s "chat-e@g.us") ["u@c.us" "pontos"])))))

(deftest persistencia-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/score-point! s1 "chat-r@g.us" "ash@c.us" "Ash" "naval" "op-r")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)]
    (is (= 1 (get-in (ports/get-chat-rank s2 "chat-r@g.us") ["ash@c.us" "pontos"])))))

(deftest import-export-equivalence
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)]
    (is (true? (get-in report [:rank :equivalent?])) report)
    (is (= 3 (get-in imported [:rank "chat-m70-a@g.us" "551100000001@c.us" "pontos"])))
    (let [imported2 (pipe/import-ir! s ir)
          report2 (pipe/equivalence-report ir imported2)]
      (is (true? (get-in report2 [:rank :equivalent?])) report2))))
