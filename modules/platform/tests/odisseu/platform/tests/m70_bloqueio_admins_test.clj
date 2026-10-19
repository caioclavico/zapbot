(ns odisseu.platform.tests.m70-bloqueio-admins-test
  "M7.0 — contratos bloqueio/admins em Datomic Local descartável.
   Sem produção, sem WhatsApp, sem dados reais."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [odisseu.platform.application.ports :as ports]
            [odisseu.platform.domain.admins :as admins]
            [odisseu.platform.domain.bloqueio :as bloqueio]
            [odisseu.platform.infrastructure.migration.compare :as compare]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.persistence.datomic :as persistence])
  (:import [java.nio.file Files FileVisitResult SimpleFileVisitor]))

(def ^:private storage ".datomic-local/m70-platform")
(def ^:private system "odisseu-m70-platform")
(def ^:private fixture "fixtures/m7-0/cassandra-dump-synthetic.edn")

(defn- rm-rf! [path]
  (let [root (io/file path)]
    (when (.exists root)
      (Files/walkFileTree
       (.toPath root)
       (proxy [SimpleFileVisitor] []
         (visitFile [f _] (Files/delete f) FileVisitResult/CONTINUE)
         (postVisitDirectory [d _] (Files/delete d) FileVisitResult/CONTINUE))))))

(defn- fresh! [db]
  (pipe/open-disposable-store! storage system db))

(deftest dump-recusa-nao-sintetico
  (let [bad (io/file "target/m7-0-bad-dump.edn")]
    (io/make-parents bad)
    (spit bad "{:meta {:synthetic false} :modules {}}")
    (is (thrown-with-msg? Exception #"dump_not_synthetic"
          (pipe/load-synthetic-dump "target/m7-0-bad-dump.edn")))))

(deftest isolamento-por-chat
  (let [s (fresh! "iso")
        _ (ports/block-commands! s "chat-a@g.us" ["naval"] "op-a1")
        _ (ports/block-commands! s "chat-b@g.us" ["quiz"] "op-b1")
        a (ports/get-chat-block s "chat-a@g.us")
        b (ports/get-chat-block s "chat-b@g.us")]
    (is (contains? (:comandos a) "naval"))
    (is (not (contains? (:comandos a) "quiz")))
    (is (contains? (:comandos b) "quiz"))
    (is (not (contains? (:comandos b) "naval")))
    (ports/remember-admin! s "chat-a@g.us" "user-1@c.us" "adm-a")
    (is (ports/known-admin? s "chat-a@g.us" ["user-1@c.us"]))
    (is (not (ports/known-admin? s "chat-b@g.us" ["user-1@c.us"])))))

(deftest idempotencia-operation-id
  (let [s (fresh! "idem")
        r1 (ports/set-bot-blocked! s "chat-x@g.us" true "op-bot-1")
        r2 (ports/set-bot-blocked! s "chat-x@g.us" true "op-bot-1")
        r3 (ports/block-commands! s "chat-x@g.us" ["naval" "quiz"] "op-cmd-1")
        r4 (ports/block-commands! s "chat-x@g.us" ["naval" "quiz"] "op-cmd-1")]
    (is (false? (:duplicate r1)))
    (is (true? (:duplicate r2)))
    (is (true? (:bot? (get (:state r2) "chat-x@g.us"))))
    (is (false? (:duplicate r3)))
    (is (true? (:duplicate r4)))
    (is (= #{"naval" "quiz"} (:comandos (get (:state r4) "chat-x@g.us"))))
    (is (thrown-with-msg? Exception #"platform_receipt_payload_mismatch"
          (ports/set-bot-blocked! s "chat-x@g.us" false "op-bot-1")))))

(deftest persistencia-apos-reopen
  (let [db "reopen"
        s1 (fresh! db)
        _ (ports/block-commands! s1 "chat-r@g.us" ["pokemon"] "op-r1")
        _ (ports/remember-admin! s1 "chat-r@g.us" "ash@c.us" "op-ra")
        client (persistence/client {:system system :storage-dir storage})
        conn (persistence/ensure-db! client db)
        s2 (odisseu.platform.infrastructure.datomic.store/make-store conn)]
    (is (contains? (:comandos (ports/get-chat-block s2 "chat-r@g.us")) "pokemon"))
    (is (ports/known-admin? s2 "chat-r@g.us" ["ash@c.us"]))))

(deftest promote-demote-admins
  (let [s (fresh! "adm")
        _ (ports/remember-admin! s "chat-d@g.us" "a@c.us" "p1")
        _ (ports/remember-admin! s "chat-d@g.us" "b@c.us" "p2")
        _ (ports/forget-admin! s "chat-d@g.us" "a@c.us" "d1")
        st (ports/get-admin-state s)]
    (is (not (contains? (get st "chat-d@g.us") "a@c.us")))
    (is (contains? (get st "chat-d@g.us") "b@c.us"))
    (let [dup (ports/forget-admin! s "chat-d@g.us" "a@c.us" "d1")]
      (is (true? (:duplicate dup))))))

(deftest import-export-equivalence
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "imp")
        imported (pipe/import-ir! s ir)
        report (pipe/equivalence-report ir imported)]
    (is (get-in dump [:meta :synthetic]))
    (is (contains? (set (:inventory-keys ir)) "bloqueio"))
    (is (contains? (set (:inventory-keys ir)) "rank"))
    (is (true? (get-in report [:bloqueio :equivalent?])) report)
    (is (true? (get-in report [:admins :equivalent?])) report)
    (is (true? (get-in report [:enquetes :equivalent?])) report)
    (is (true? (get-in report [:quiz-historico :equivalent?])) report)
    ;; chat C com lista vazia de admins: normalize remove ou mantém?
    (is (bloqueio/comando-bloqueado?
         (bloqueio/normalize-state (:bloqueio ir))
         "chat-m70-c@g.us" "pokemon"))))

(deftest reimport-nao-duplica-comandos
  (let [dump (pipe/load-synthetic-dump fixture)
        ir (pipe/export-modules dump)
        s (fresh! "reimp")
        _ (pipe/import-ir! s ir)
        _ (pipe/import-ir! s ir)
        st (ports/get-block-state s)
        cmds (:comandos (get st "chat-m70-a@g.us"))]
    (is (= #{"naval" "quiz"} cmds))
    (is (= 2 (count (get (ports/get-admin-state s) "chat-m70-a@g.us"))))))

(deftest dominio-normalizers-roundtrip
  (let [raw {"c1" {"bot?" true "comandos" ["Naval" "quiz"]}}
        norm (bloqueio/normalize-state raw)
        back (bloqueio/state->persistivel norm)]
    (is (true? (get-in norm ["c1" :bot?])))
    (is (= #{"Naval" "quiz"} (get-in norm ["c1" :comandos])))
    (is (compare/equivalent?
         (compare/normalize-bloqueio raw)
         (compare/normalize-bloqueio back))))
  (let [raw {"c1" ["u2@c.us" "u1@c.us"]}
        norm (admins/normalize-state raw)
        back (admins/state->persistivel norm)]
    (is (= #{"u1@c.us" "u2@c.us"} (get norm "c1")))
    (is (compare/equivalent?
         (compare/normalize-admins raw)
         (compare/normalize-admins back)))))

(deftest storage-proibido
  (is (thrown-with-msg? Exception #"forbidden_storage"
        (pipe/open-disposable-store! ".datomic-local/m53-real-offline" "x" "y"))))
