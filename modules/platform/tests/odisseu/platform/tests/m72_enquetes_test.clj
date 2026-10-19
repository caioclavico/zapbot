(ns odisseu.platform.tests.m72-enquetes-test
  "M7.2 — contratos enquetes em Datomic Local descartável.
   Sem produção, sem WhatsApp, sem dados reais."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.enquetes :as enquetes]
            [odisseu.platform.infrastructure.migration.compare :as compare]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private storage ".datomic-local/m72-platform")
(def ^:private system "odisseu-m72-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(deftest criar-votar-fechar
  (let [s (fresh! "cvf")
        poll {:pergunta "Café ou chá?" :opcoes ["Café" "Chá"] :prefix "!"}
        c (ports/create-poll! s "chat-a@g.us" poll "op-c1")
        _ (ports/cast-vote! s "chat-a@g.us" "u1@c.us" 1 "op-v1")
        _ (ports/cast-vote! s "chat-a@g.us" "u2@c.us" 2 "op-v2")
        mid (ports/get-chat-poll s "chat-a@g.us")
        closed (ports/close-poll! s "chat-a@g.us" "op-x1")]
    (is (false? (:duplicate c)))
    (is (= "Café ou chá?" (:pergunta (:poll c))))
    (is (= 1 (get-in mid [:votos "u1@c.us"])))
    (is (= 2 (get-in mid [:votos "u2@c.us"])))
    (is (true? (:closed closed)))
    (is (= "Café ou chá?" (:pergunta (:poll closed))))
    (is (nil? (ports/get-chat-poll s "chat-a@g.us")))
    (is (thrown-with-msg? Exception #"poll_not_found"
          (ports/close-poll! s "chat-a@g.us" "op-x2")))))

(deftest isolamento-por-chat
  (let [s (fresh! "iso")
        _ (ports/create-poll! s "chat-a@g.us"
                              {:pergunta "A?" :opcoes ["1" "2"] :prefix "!"} "op-a")
        _ (ports/create-poll! s "chat-b@g.us"
                              {:pergunta "B?" :opcoes ["x" "y"] :prefix "!"} "op-b")
        _ (ports/cast-vote! s "chat-a@g.us" "u@c.us" 1 "op-va")]
    (is (= "A?" (:pergunta (ports/get-chat-poll s "chat-a@g.us"))))
    (is (= "B?" (:pergunta (ports/get-chat-poll s "chat-b@g.us"))))
    (is (= 1 (get-in (ports/get-chat-poll s "chat-a@g.us") [:votos "u@c.us"])))
    (is (empty? (:votos (ports/get-chat-poll s "chat-b@g.us"))))))

(deftest voto-substitui-e-idempotente
  (let [s (fresh! "vote")
        _ (ports/create-poll! s "chat-v@g.us"
                              {:pergunta "Q?" :opcoes ["a" "b" "c"] :prefix "!"} "op-c")
        r1 (ports/cast-vote! s "chat-v@g.us" "u@c.us" 1 "op-v")
        r2 (ports/cast-vote! s "chat-v@g.us" "u@c.us" 1 "op-v")
        r3 (ports/cast-vote! s "chat-v@g.us" "u@c.us" 3 "op-v2")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= 1 (get-in (:poll r2) [:votos "u@c.us"])))
    (is (= 3 (get-in (:poll r3) [:votos "u@c.us"])))
    (is (thrown-with-msg? Exception #"platform_receipt_payload_mismatch"
          (ports/cast-vote! s "chat-v@g.us" "u@c.us" 2 "op-v")))))

(deftest criar-duplicada-e-idempotente
  (let [s (fresh! "dup")
        poll {:pergunta "Q?" :opcoes ["a" "b"] :prefix "!"}
        r1 (ports/create-poll! s "chat-d@g.us" poll "op-c")
        r2 (ports/create-poll! s "chat-d@g.us" poll "op-c")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (thrown-with-msg? Exception #"poll_already_open"
          (ports/create-poll! s "chat-d@g.us" poll "op-c2")))))

(deftest fechar-idempotente-preserva-snapshot
  (let [s (fresh! "close")
        _ (ports/create-poll! s "chat-c@g.us"
                              {:pergunta "Fim?" :opcoes ["sim" "nao"] :prefix "!"} "op-c")
        _ (ports/cast-vote! s "chat-c@g.us" "u@c.us" 1 "op-v")
        x1 (ports/close-poll! s "chat-c@g.us" "op-x")
        x2 (ports/close-poll! s "chat-c@g.us" "op-x")]
    (is (false? (:duplicate x1)))
    (is (true? (:duplicate x2)))
    (is (= "Fim?" (:pergunta (:poll x2))))
    (is (= 1 (get-in (:poll x2) [:votos "u@c.us"])))))

(deftest persistencia-apos-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/create-poll! s1 "chat-r@g.us"
                              {:pergunta "Persist?" :opcoes ["s" "n"] :prefix "!"} "op-c")
        _ (ports/cast-vote! s1 "chat-r@g.us" "ash@c.us" 2 "op-v")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)
        p (ports/get-chat-poll s2 "chat-r@g.us")]
    (is (= "Persist?" (:pergunta p)))
    (is (= 2 (get-in p [:votos "ash@c.us"])))))

(deftest import-export-equivalence
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)]
    (is (true? (get-in report [:enquetes :equivalent?])) report)
    (is (= "Preferem café ou chá?"
           (get-in imported [:enquetes "chat-m70-a@g.us" "pergunta"])))
    (is (= 1 (get-in imported [:enquetes "chat-m70-a@g.us" "votos" "551100000001@c.us"])))
    ;; reimport
    (let [imported2 (pipe/import-ir! s ir)
          report2 (pipe/equivalence-report ir imported2)]
      (is (true? (get-in report2 [:enquetes :equivalent?])) report2))))

(deftest validacao-opcoes
  (let [s (fresh! "val")]
    (is (thrown-with-msg? Exception #"poll_invalid"
          (ports/create-poll! s "chat-v@g.us"
                              {:pergunta "x" :opcoes ["só"] :prefix "!"} "op-1")))
    (is (enquetes/valid-create?
         {:pergunta "ok" :opcoes ["a" "b"] :prefix "!"}))))
