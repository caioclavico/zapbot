(ns pokemon.tests.xp-reconciliation-cas-a6-test
  "No real saves. Exercise atomic CAS + receipt through the SAME JVM Datomic
   connection using ephemeral local Datomic database."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.xp-reconciliation :as recon]))

(def ^:private id "abb8955f-7ebe-4084-a634-fd74cfa9bf62")

(defn- seeded []
  (let [uuid (str (java.util.UUID/randomUUID))
        client (persistence/client {:system (str "a6-reconcile-" uuid)
                                    :storage-dir (str "/tmp/a6-reconcile-" uuid)})
        conn (persistence/ensure-db! client "a6-recon")
        st (store/make-store conn)]
    (ports/ensure-schema! st)
    (trainer/create-trainer! st {:chat-id "g-reconcile" :player-id "trainer"})
    (collection/adicionar-pokemon!
      st "g-reconcile" "trainer"
      {:id-pokemon id :species "charmander" :nome "Charmander"
       :nivel 6 :xp 0 :xp-desde-nivel 0 :xp-total 45
       :xp-progress-version "flat-9-v2"
       :hp 24 :hp-atual 17 :ataque 14 :defesa 12
       :atq-esp 16 :def-esp 13 :veloc 15 :shiny false
       :amizade 70}
      6 nil)
    (let [before (d/pull (d/db conn) '[*] [:pokemon/id-pokemon id])]
      (d/transact conn
                  {:tx-data (cond-> [[:db/add [:pokemon/id-pokemon id] :pokemon/xp 21]
                                     [:db/add [:pokemon/id-pokemon id] :pokemon/xp-desde-nivel 21]]
                              (some? (:pokemon/xp-progress-version before))
                              (conj [:db/retract [:pokemon/id-pokemon id]
                                     :pokemon/xp-progress-version
                                     (:pokemon/xp-progress-version before)]))}))
    conn))

(defn- pokemon [conn]
  (d/pull (d/db conn) '[*] [:pokemon/id-pokemon id]))

(defn- receipt [conn]
  (d/pull (d/db conn) '[*]
          [:operation-receipt/id
           (str "a6-v3-charmander-compensation:" id ":v1")]))

(deftest test-reconciliation-once-and-idempotent-retry
  (let [conn (seeded)
        before (pokemon conn)
        preview (recon/preview conn id id)
        first (recon/reconcile! conn id id (:snapshot-sha256 preview))
        after (pokemon conn)
        duplicate (recon/reconcile! conn id id (:snapshot-sha256 preview))
        r (receipt conn)]
    (is (= :ready (:status preview)))
    (is (= :applied (:status first)))
    (is (= :duplicate (:status duplicate)))
    (is (= 6 (:pokemon/level after)))
    (is (= 8 (:pokemon/xp after)))
    (is (= 8 (:pokemon/xp-desde-nivel after)))
    (is (= 53 (:pokemon/xp-total after)))
    (is (= "flat-9-v2" (:pokemon/xp-progress-version after)))
    (is (= 21 (:pokemon/xp-legacy-residual after)))
    (is (= 6 (:pokemon/xp-legacy-nivel after)))
    (is (= "flat9-compensation-v3" (:operation-receipt/kind r)))
    (is (= (select-keys before [:pokemon/hp :pokemon/hp-atual
                                :pokemon/ataque :pokemon/defesa
                                :pokemon/atq-esp :pokemon/def-esp
                                :pokemon/veloc :pokemon/species])
           (select-keys after [:pokemon/hp :pokemon/hp-atual
                               :pokemon/ataque :pokemon/defesa
                               :pokemon/atq-esp :pokemon/def-esp
                               :pokemon/veloc :pokemon/species])))))

(deftest test-fail-closed-stale-hash-and-state
  (let [conn (seeded)
        preview (recon/preview conn id id)
        wrong (apply str (repeat 64 "0"))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (recon/reconcile! conn id id wrong)))
    (is (nil? (:db/id (receipt conn))))
    (d/transact conn {:tx-data
                      [[:db/add [:pokemon/id-pokemon id] :pokemon/xp-desde-nivel 22]]})
    (is (thrown? clojure.lang.ExceptionInfo
                 (recon/reconcile! conn id id (:snapshot-sha256 preview))))
    (is (= 6 (:pokemon/level (pokemon conn))))
    (is (= 22 (:pokemon/xp-desde-nivel (pokemon conn))))
    (is (nil? (:db/id (receipt conn))))))

(deftest test-deny-other-ids-and-after-commit-conflicting-receipt
  (let [conn (seeded)
        preview (recon/preview conn id id)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (recon/preview conn id "other-id")))
    (recon/reconcile! conn id id (:snapshot-sha256 preview))
    (d/transact conn {:tx-data
                      [[:db/add [:operation-receipt/id
                                  (str "a6-v3-charmander-compensation:" id ":v1")]
                        :operation-receipt/payload-hash "different-hash"]]})
    (is (thrown? clojure.lang.ExceptionInfo
                 (recon/reconcile! conn id id (:snapshot-sha256 preview))))))

(deftest test-simultaneous-retry-does-not-double-apply
  (let [conn (seeded)
        preview (recon/preview conn id id)
        f1 (future (recon/reconcile! conn id id (:snapshot-sha256 preview)))
        f2 (future (recon/reconcile! conn id id (:snapshot-sha256 preview)))
        out [@f1 @f2]]
    (is (= #{:applied :duplicate} (set (map :status out))))
    (is (= 8 (:pokemon/xp-desde-nivel (pokemon conn))))
    (is (= 6 (:pokemon/level (pokemon conn))))))

(deftest test-frozen-writer-readonly-audit-transitions-single-block-to-pass
  (let [conn (seeded)
        before (recon/audit conn id id)
        preview (recon/preview conn id id)
        applied (recon/reconcile! conn id id (:snapshot-sha256 preview))
        after (recon/audit conn id id)]
    (is (= "BLOCKED" (:status before)))
    (is (= 1 (:blocked before)))
    (is (= id (get-in before [:blockedDetails 0 :id])))
    (is (= "flat9-ambiguous-legacy-xp"
           (get-in before [:blockedDetails 0 :reason])))
    (is (= :applied (:status applied)))
    (is (= "PASS" (:status after)))
    (is (zero? (:blocked after)))
    (is (= (:pokemonCount before) (:pokemonCount after)))
    (is (= (:protectedSha256 before) (:protectedSha256 after)))
    (is (true? (:reconciliationReceiptVerified after)))
    (is (= 6 (:targetLevel after)))
    (is (= 8 (:targetResidual after)))))
