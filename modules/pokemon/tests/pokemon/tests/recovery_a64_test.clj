(ns pokemon.tests.recovery-a64-test
  "A6.4: catálogo offline, alias de identidade, confirmação do jogador e import admin."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.recovery-catalog :as catalog]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fixtures]
            [pokemon.recovery :as recovery]))

(def ^:private chat "recovery-a64-chat")
(def ^:private player "recovery-a64-player@c.us")
(def ^:private current-player "recovery-a64-current@lid")
(def ^:private system "odisseu-a64-tests")
(def ^:private storage ".datomic-local/a64-tests")
(def ^:private import-token "a64-import-secret")

(defn- boot! [db]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db)
    (let [s (store/make-store (persistence/ensure-db! client db))]
      (trainer/bootstrap! s)
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (trainer/create-trainer! s {:chat-id chat :player-id current-player})
      s)))

(defn- snapshots []
  (let [base (first (:historical_snapshots (fixtures/build-cassandra-dump)))]
    [(assoc base :snapshot-id "a64-v1" :legacy-version "v1"
            :chat-id chat :player-id player)
     (assoc base :snapshot-id "a64-v2" :legacy-version "v2"
            :chat-id chat :player-id player)]))

(defn- make-catalog
  ([] (make-catalog nil))
  ([aliases]
   (let [exp (assoc (export/synthetic-default)
                    :historical_snapshots (snapshots)
                    :meta (cond-> {:authorized-offline-copy true :synthetic true}
                            aliases (assoc :identity-aliases aliases)))]
     {:export exp
      :path "synthetic"
      :loaded-at (System/currentTimeMillis)
      :import-token import-token
      :requests (atom {})})))

(deftest alias-resolve-e-previa-publica
  (let [cat (make-catalog {(str chat "|" current-player)
                           {:chat-id chat :player-id player}})
        disc (catalog/discover cat chat current-player)]
    (is (true? (:catalog-ready disc)))
    (is (true? (:alias-applied? disc)))
    (is (= ["v1" "v2"] (:versions disc)))
    (is (every? #(nil? (:snapshot-id %)) (:available disc)))
    (is (every? #(nil? (:content-hash %)) (:available disc)))
    (is (empty? (:versions (catalog/discover cat chat "outro@lid"))))))

(deftest fluxo-jogador-e-import-admin-preserva-ativo
  (let [s (boot! "a64-flow")
        cat (make-catalog)
        before-active (ports/active-save-id s chat player)
        req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v1})
        confirmed (catalog/player-confirm!
                   cat {:chat-id chat :player-id player
                        :request-id (:request-id req)})
        imported (catalog/admin-import!
                  cat s {:request-id (:request-id req)
                         :import-token import-token
                         :import-batch-id "a64-batch-v1"})]
    (is (= :pending-player-confirm (:status req)))
    (is (= :awaiting-admin (:status confirmed)))
    (is (= :imported (:status imported)))
    (is (= before-active (:active-save-id imported)))
    (is (= #{"a" "b" "recovery-v1"}
           (set (map :slot-id (ports/list-saves s chat player)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recovery_import_unauthorized"
                          (catalog/admin-import!
                           cat s {:request-id (:request-id req)
                                  :import-token "wrong"})))))

(deftest alias-importa-no-jogador-atual
  (let [s (boot! "a64-alias")
        cat (make-catalog {(str chat "|" current-player)
                           {:chat-id chat :player-id player}})
        req (catalog/register-request!
             cat {:chat-id chat :player-id current-player :choice :both})
        _ (catalog/player-confirm!
           cat {:chat-id chat :player-id current-player
                :request-id (:request-id req)})
        imported (catalog/admin-import!
                  cat s {:request-id (:request-id req)
                         :import-token import-token
                         :import-batch-id "a64-batch-alias"})]
    (is (= #{"a" "b" "recovery-v1" "recovery-v2"}
           (set (map :slot-id (ports/list-saves s chat current-player)))))
    (is (= "a" (:active-save-id imported)))
    (is (empty? (filter #(re-find #"recovery" (:slot-id %))
                        (ports/list-saves s chat player))))))

(deftest confirmacao-exige-pedido-do-dono
  (let [cat (make-catalog)
        req (catalog/register-request! cat {:chat-id chat :player-id player :choice :v2})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recovery_request_owner_mismatch"
                          (catalog/player-confirm!
                           cat {:chat-id chat :player-id "intruso@c.us"
                                :request-id (:request-id req)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"recovery_request_not_confirmed"
                          (catalog/admin-import!
                           cat (boot! "a64-early")
                           {:request-id (:request-id req)
                            :import-token import-token})))))

(deftest resolve-identity-sem-alias-usa-igualdade-exata
  (let [exp (assoc (export/synthetic-default) :historical_snapshots (snapshots))]
    (is (= {:chat-id chat :player-id player :alias-applied? false}
           (recovery/resolve-identity exp chat player)))
    (is (= {:chat-id chat :player-id "x@lid" :alias-applied? false}
           (recovery/resolve-identity exp chat "x@lid")))))
