(ns odisseu.platform.tests.m76-bugs-participantes-test
  "M7.6 — bugs + participantes em Datomic Local descartável.
   Sem produção, sem WhatsApp, sem dados reais."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private storage ".datomic-local/m76-platform")
(def ^:private system "odisseu-m76-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(deftest criar-listar-resolver-bug
  (let [s (fresh! "bugs-crud")
        b {:versao "0.0.0-syn" :chat "c@g.us" :reportado-por "u@c.us"
           :autor-mensagem "a@c.us" :mensagem "falhou" :origem "citada"
           :reportado-em 1700000001000}
        r1 (ports/create-bug! s b "op-b1")
        r2 (ports/create-bug! s b "op-b1")
        id (get-in r1 [:bug :id])]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= "1" id))
    (is (= 1 (count (ports/list-bugs s nil))))
    (is (= "aberto" (get-in r1 [:bug :status])))
    (let [res (ports/resolve-bug! s id 1700000002000 "op-res")
          res2 (ports/resolve-bug! s id 1700000002000 "op-res")]
      (is (= "resolvido" (get-in res [:bug :status])))
      (is (true? (:duplicate res2)))
      (is (= 1700000002000 (get-in res [:bug :resolvido-em]))))))

(deftest bug-isolamento-e-filtro-versao
  (let [s (fresh! "bugs-iso")
        _ (ports/create-bug! s {:versao "1.0" :chat "a@g.us" :reportado-por "u"
                                :autor-mensagem "a" :mensagem "x" :origem "anterior"
                                :reportado-em 1} "op-1")
        _ (ports/create-bug! s {:versao "2.0" :chat "b@g.us" :reportado-por "u"
                                :autor-mensagem "a" :mensagem "y" :origem "anterior"
                                :reportado-em 2} "op-2")]
    (is (= 1 (count (ports/list-bugs s "1.0"))))
    (is (= 1 (count (ports/list-bugs s "2.0"))))
    (is (= 2 (count (ports/list-bugs s nil))))))

(deftest participantes-upsert-isolamento
  (let [s (fresh! "parts")
        r1 (ports/upsert-participant! s "c@g.us" "u1@c.us" "Ash" "op-p1")
        r2 (ports/upsert-participant! s "c@g.us" "u1@c.us" "Ash" "op-p1")
        _ (ports/upsert-participant! s "c@g.us" "u2@c.us" "Misty" "op-p2")
        _ (ports/upsert-participant! s "d@g.us" "u1@c.us" "Ash-B" "op-p3")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (= {"u1@c.us" "Ash" "u2@c.us" "Misty"}
           (ports/get-chat-participants s "c@g.us")))
    (is (= {"u1@c.us" "Ash-B"}
           (ports/get-chat-participants s "d@g.us")))))

(deftest participantes-renomeia-com-novo-op
  (let [s (fresh! "rename")
        _ (ports/upsert-participant! s "c@g.us" "u@c.us" "Ash" "op-a")
        _ (ports/upsert-participant! s "c@g.us" "u@c.us" "Ash Ketchum" "op-b")]
    (is (= "Ash Ketchum" (get (ports/get-chat-participants s "c@g.us") "u@c.us")))))

(deftest persistencia-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/create-bug! s1 {:versao "v" :chat "c" :reportado-por "u"
                                 :autor-mensagem "a" :mensagem "m" :origem "citada"
                                 :reportado-em 1} "op-br")
        _ (ports/upsert-participant! s1 "c@g.us" "u@c.us" "Ash" "op-pr")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)]
    (is (= "m" (:mensagem (ports/get-bug s2 "1"))))
    (is (= "Ash" (get (ports/get-chat-participants s2 "c@g.us") "u@c.us")))))

(deftest import-equivalence
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)]
    (is (true? (get-in report [:bugs :equivalent?])) report)
    (is (true? (get-in report [:participantes :equivalent?])) report)
    (is (= "aberto" (:status (ports/get-bug s "1"))))
    (is (= "Ash" (get (ports/get-chat-participants s "chat-m70-a@g.us")
                      "551100000001@c.us")))))
