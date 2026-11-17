(ns pokemon.tests.recovery-a63-export-e2e-test
  "Consome o EDN produzido pelo exportador Cassandra real e importa em Datomic Local descartável."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.export :as export]
            [pokemon.recovery :as recovery]))

(def ^:private db-name "a63-export-e2e")
(def ^:private system "odisseu-a63-export-e2e")
(def ^:private storage ".datomic-local/a63-export-e2e")

(defn- run-flow! []
  (let [path (or (System/getenv "RECOVERY_OUTPUT")
                 (throw (ex-info "missing_RECOVERY_OUTPUT" {})))
        exp (export/from-authorized-offline-dump (export/load-edn-file path))
        snapshots (:historical_snapshots exp)
        chat (or (:chat-id (first snapshots)) (System/getenv "RECOVERY_CHAT_ID"))
        player (or (:player-id (first snapshots)) (System/getenv "RECOVERY_PLAYER_ID"))
        available (recovery/discover exp chat player)
        selected (recovery/select available :both)
        token (recovery/confirmation-token chat player selected)
        client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db-name)
    (let [s (store/make-store (persistence/ensure-db! client db-name))]
      (trainer/bootstrap! s)
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (let [opts {:chat-id chat :player-id player :selected selected :token token
                  :import-batch-id "a63-export-batch"}
            first-result (recovery/confirm! s snapshots opts)
            retry (recovery/confirm! s snapshots opts)
            saves (ports/list-saves s chat player)
            active-before (ports/active-save-id s chat player)
            v1 (ports/list-collection s chat player "recovery-v1")
            v2 (ports/list-collection s chat player "recovery-v2")
            reopened (store/make-store (persistence/ensure-db! client db-name))]
        (is (= #{"recovery-v1" "recovery-v2"}
               (set (map :slot-id (:results first-result)))))
        (is (every? #(= :duplicate (:status %)) (:results retry)))
        (is (= "a" active-before))
        (is (= #{"a" "b" "recovery-v1" "recovery-v2"}
               (set (map :slot-id saves))))
        (is (= "a63-v1-pokemon" (:id-pokemon (first v1))))
        (is (= "a63-v2-pokemon" (:id-pokemon (first v2))))
        (is (= 101 (:coins (ports/get-save reopened chat player "recovery-v1"))))
        (is (= 202 (:coins (ports/get-save reopened chat player "recovery-v2"))))
        {:available available :first first-result :retry retry :active active-before}))))

(deftest exportador-para-datomic-e-reabertura
  (run-flow!))

(defn -main [& _]
  (let [r (clojure.test/run-tests 'pokemon.tests.recovery-a63-export-e2e-test)]
    (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1))))
