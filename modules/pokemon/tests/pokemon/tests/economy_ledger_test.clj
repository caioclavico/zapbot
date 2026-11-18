(ns pokemon.tests.economy-ledger-test
  "Ledger histórico vs operacional (domain + integração Datomic descartável)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.shop :as shop-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.economy :as economy]
            [pokemon.domain.errors :as err]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-economy-ledger")
(def ^:private storage-rel ".datomic-local/economy-ledger-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(deftest spendable-unknown-pending-sem-operacional-e-zero
  (is (= 0 (economy/spendable-coins
            {:origin "offline-import" :slot-id "recovery-v1"
             :legacy-version "unknown" :coins 999})))
  (is (= "unknown-pending"
         (economy/economy-status
          {:origin "offline-import" :slot-id "recovery-v1"}))))

(deftest spendable-unknown-com-operacional
  (is (= 40 (economy/spendable-coins
             {:origin "offline-import" :slot-id "recovery-v1"
              :economy-status "unknown-pending"
              :coins 999 :coins-operational 40})))
  (is (true? (economy/can-spend?
              {:origin "offline-import" :slot-id "recovery-v1"
               :economy-status "unknown-pending"
               :coins-operational 40}
              40)))
  (is (false? (economy/can-spend?
               {:origin "offline-import" :slot-id "recovery-v1"
                :economy-status "unknown-pending"
                :coins-operational 40}
               41))))

(deftest spendable-trusted-legado-usa-coins
  (is (= 120 (economy/spendable-coins {:slot-id "a" :coins 120})))
  (is (= 15 (economy/spendable-coins
             {:slot-id "a" :economy-status "trusted"
              :coins 120 :coins-operational 15}))))

(deftest operational-nao-promove-historico
  (let [s {:origin "offline-import" :slot-id "recovery-v1"
           :economy-status "unknown-pending"
           :coins 500 :coins-operational 30}]
    (is (= "unknown-pending" (economy/economy-status s)))
    (is (= 30 (economy/spendable-coins s)))
    (is (nil? (economy/coins-historical-amount s)))
    (is (= :separate-ledgers (:rule (economy/assert-not-mixing-ledgers))))))

(deftest bootstrap-trusted-copia-coins-recovery-nao
  (is (= 80 (:coins-operational
             (economy/bootstrap-state {:slot-id "a" :coins 80}))))
  (is (= 0 (:coins-operational
            (economy/bootstrap-state
             {:origin "offline-import" :slot-id "recovery-v1"
              :legacy-version "unknown" :coins 999})))))

(deftest assert-spend-recovery-placeholder
  (let [code (try
               (economy/assert-can-spend!
                {:origin "offline-import" :slot-id "recovery-v1"
                 :coins 999}
                20)
               :ok
               (catch Exception ex (err/error-code ex)))]
    (is (= :economy_unreliable code))))

(deftest integracao-novo-treinador-credito-debito
  (let [s (fresh-store! "eco-new")
        chat "chat-eco-new"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [save0 (ports/get-save s chat player "a")]
      (is (= "trusted" (:economy-status save0)))
      (is (= 0 (long (:coins-operational save0 0))))
      (is (= 0 (long (:coins-spendable save0 0)))))
    (reward-app/admin-seed-reward!
     s {:operation-id "eco-seed-1" :chat-id chat :player-id player :save-id "a"
        :coins-delta 100})
    (let [save (ports/get-save s chat player "a")
          bought (shop-app/buy! s chat player {:operation-id "eco-buy-1"
                                               :item-id "pocao" :qty 1})]
      (is (= 100 (long (:coins-operational save))))
      (is (= 100 (long (:coins save))))
      (is (= 80 (long (:coins-depois bought))))
      (is (= 80 (long (:coins-operational (ports/get-save s chat player "a"))))))
    ;; idempotência
    (let [r2 (reward-app/admin-seed-reward!
              s {:operation-id "eco-seed-1" :chat-id chat :player-id player
                 :save-id "a" :coins-delta 100})]
      (is (true? (:duplicate r2)))
      (is (= 80 (long (:coins-operational (ports/get-save s chat player "a"))))))))

(deftest integracao-recovery-unknown-nao-gasta-placeholder
  (let [s (fresh-store! "eco-rec")
        chat "chat-eco-rec"
        player "misty"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (ports/create-save! s chat player "recovery-v1")
    (let [client (persistence/client {:system system :storage-dir storage-rel})
          conn (persistence/ensure-db! client "eco-rec")
          sk (str chat "|" player "|recovery-v1")]
      (d/transact conn {:tx-data [[:db/add [:save/key sk] :save/origin "offline-import"]
                                  [:db/add [:save/key sk] :save/legacy-version "unknown"]
                                  [:db/add [:save/key sk] :save/coins 999]]}))
    (trainer/switch-save! s chat player "recovery-v1")
    (let [save (ports/get-save s chat player "recovery-v1")
          code (try
                 (shop-app/buy! s chat player {:operation-id "eco-rec-buy-bad"
                                               :item-id "pocao" :qty 1})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (false? (shop/economia-confiavel? save)))
      (is (= "unknown-pending" (:economy-status save)))
      (is (= 0 (long (:coins-spendable save 0))))
      (is (= :economy_unreliable code)))
    ;; Crédito operacional A6 — gastável; não promove histórico; não altera placeholder.
    (reward-app/admin-seed-reward!
     s {:operation-id "eco-rec-seed" :chat-id chat :player-id player
        :save-id "recovery-v1" :coins-delta 50})
    (let [save (ports/get-save s chat player "recovery-v1")
          client (persistence/client {:system system :storage-dir storage-rel})
          conn (persistence/ensure-db! client "eco-rec")
          raw (d/pull (d/db conn) '[:save/coins :save/economy-status :save/coins-operational]
                      [:save/key (str chat "|" player "|recovery-v1")])
          bought (shop-app/buy! s chat player {:operation-id "eco-rec-buy-ok"
                                               :item-id "pocao" :qty 1})]
      (is (= "unknown-pending" (:save/economy-status raw)))
      (is (= 999 (long (:save/coins raw))) "placeholder histórico intacto")
      (is (= 50 (long (:coins-operational save))))
      (is (= 30 (long (:coins-depois bought))))
      (is (= "unknown-pending"
             (:economy-status (ports/get-save s chat player "recovery-v1"))))
      (is (= 999 (long (:save/coins
                        (d/pull (d/db conn) '[:save/coins]
                                [:save/key (str chat "|" player "|recovery-v1")])))))
      (is (= 30 (long (:coins-operational
                       (ports/get-save s chat player "recovery-v1"))))))))

(deftest integracao-saldo-insuficiente-e-reinicio-jvm
  (let [db "eco-restart"
        s (fresh-store! db)
        chat "chat-eco-restart"
        player "brock"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     s {:operation-id "eco-r-seed" :chat-id chat :player-id player :save-id "a"
        :coins-delta 15})
    (let [code (try
                 (shop-app/buy! s chat player {:operation-id "eco-r-exp"
                                               :item-id "espaco-pokemon" :qty 1})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :insufficient_coins code)))
    (let [s2 (reopen-store db)
          save (ports/get-save s2 chat player "a")]
      (is (= 15 (long (:coins-operational save))))
      (is (= 15 (long (:coins-spendable save)))))))
