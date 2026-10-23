(ns pokemon.tests.recovery-a63-test
  "A6.3: descoberta offline e importação seletiva em Datomic Local descartável."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.fixtures :as fixtures]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(def ^:private chat "recovery-a63-chat")
(def ^:private player "recovery-a63-player@c.us")
(def ^:private system "odisseu-a63-tests")
(def ^:private storage ".datomic-local/a63-tests")

(defn- boot! [db]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db)
    (let [s (store/make-store (persistence/ensure-db! client db))]
      (trainer/bootstrap! s)
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      s)))

(defn- snapshots []
  (let [base (first (:historical_snapshots (fixtures/build-cassandra-dump)))]
    [(assoc base :snapshot-id "a63-v1" :legacy-version "v1"
           :chat-id chat :player-id player)
     (assoc base :snapshot-id "a63-v2" :legacy-version "v2"
           :chat-id chat :player-id player)]))

(defn- throws-message? [re f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (re-find re (ex-message e))))))

(deftest descoberta-exata-e-selecao
  (let [exp (assoc (export/synthetic-default) :historical_snapshots (snapshots))
        available (recovery/discover exp chat player)]
    (is (= ["v1" "v2"] (mapv :version available)))
    (is (= ["a63-v1"] (mapv :snapshot-id (recovery/select available :v1))))
    (is (= 2 (count (recovery/select available :both))))
    (is (empty? (recovery/discover exp "other-chat" player)))
    (is (throws-message? #"recovery_selection_invalid"
                         #(recovery/select available :v3)))))

(deftest importacao-seletiva-preserva-save-ativo
  (let [s (boot! "a63-selective")
        ss (snapshots)
        selected (recovery/select (recovery/discover
                                   (assoc (export/synthetic-default) :historical_snapshots ss)
                                   chat player) :v1)
        token (recovery/confirmation-token chat player selected)
        result (recovery/confirm! s [(first ss)]
                                  {:chat-id chat :player-id player
                                   :selected selected :token token
                                   :import-batch-id "a63-batch-v1"})]
    (is (= "a" (:active-save-id result)))
    (is (= #{"a" "b" "recovery-v1"}
           (set (map :slot-id (ports/list-saves s chat player)))))
    (is (= "recovery-v1" (:save-id (ports/get-receipt s
                                                      "m50-import|a63-batch-v1|recovery-a63-chat|recovery-a63-player@c.us|recovery-v1"))))
    (is (= 119 (count (ports/list-collection s chat player "recovery-v1"))))))

(deftest retry-e-falha-nao-cria-slot-alternativo
  (let [s (boot! "a63-retry")
        ss (snapshots)
        available (recovery/discover
                   (assoc (export/synthetic-default) :historical_snapshots ss) chat player)
        token (recovery/confirmation-token chat player available)
        opts {:chat-id chat :player-id player :selected available :token token
              :import-batch-id "a63-batch-both"}
        first-result (recovery/confirm! s ss opts)
        retry (recovery/confirm! s ss opts)]
    (is (every? #(= :imported (:status %)) (:results first-result)))
    (is (every? #(= :duplicate (:status %)) (:results retry)))
    (is (= #{"recovery-v1" "recovery-v2"}
           (set (map :slot-id (:results retry)))))
    (is (throws-message? #"recovery_confirmation_mismatch"
                         #(recovery/confirm! s ss (assoc opts :token "tampered"))))
    (is (throws-message? #"recovery_selection_payload_mismatch"
                         #(recovery/confirm! s [(assoc (first ss) :snapshot-id "other")]
                                              opts)))))

(deftest nenhum-save-historico-nao-e-inventado
  (let [exp (assoc (export/synthetic-default) :historical_snapshots [])]
    (is (empty? (recovery/discover exp chat player)))
    (is (throws-message? #"recovery_save_not_found"
                         #(recovery/select [] :both)))))

(deftest somente-versoes-existentes-e-dados-incompativeis
  (let [v1 (first (snapshots))
        base (export/synthetic-default)
        only-v1 (recovery/discover (assoc base :historical_snapshots [v1]) chat player)
        foreign (assoc v1 :snapshot-id "foreign" :chat-id "outro-chat")
        unsupported (assoc v1 :snapshot-id "legacy-unknown" :legacy-version "v3")]
    (is (= ["v1"] (mapv :version only-v1)))
    (is (empty? (recovery/discover (assoc base :historical_snapshots [foreign]) chat player)))
    (is (empty? (recovery/discover (assoc base :historical_snapshots [unsupported]) chat player)))
    (is (throws-message? #"recovery_save_not_found"
                         #(recovery/select only-v1 :v2)))))
