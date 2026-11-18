(ns odisseu.platform.tests.release-store-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [odisseu.platform.infrastructure.migration.pipeline :as pipe]
            [odisseu.platform.infrastructure.datomic.release-store :as store]))

(defn- fresh []
  (let [suffix (str (java.util.UUID/randomUUID))]
    (pipe/open-disposable-store! (str ".datomic-local/m88-release-test-" suffix)
                                 (str "odisseu-m88-test-" suffix)
                                 (str "release-test-" suffix))))

(deftest release-store-cas-retry-and-recovery
  (let [s (fresh) c (:conn s) key "12.8.8:hash:pilot@g.us"
        a {:type :release :release-version "12.8.8" :content-hash "hash"
           :chat-id "pilot@g.us" :idempotency-key key :eligibility :eligible}
        created (store/create-release-announcement! c a)]
    (is (false? (:duplicate created)))
    (is (true? (:duplicate (store/create-release-announcement! c a))))
    (let [claims (doall (map deref (map #(future (store/claim-release-announcement! c key % 1000 1000)) ["a" "b"])))
          wins (filter :claimed claims)]
      (is (= 1 (count wins)))
      (let [w (first wins)]
        (is (false? (:completed (store/complete-release-announcement! c key "other" (:fence w)))))
        (is (true? (:completed (store/complete-release-announcement! c key (:claimant (:announcement w)) (:fence w)))))
        (is (= :completed (:status (store/find-release-announcement c key))))))))

(deftest release-store-failure-can-reclaim-after-lease
  (let [s (fresh) c (:conn s) key "12.8.8:hash:pilot2@g.us"
        _ (store/create-release-announcement! c {:type :release :release-version "12.8.8" :content-hash "hash" :chat-id "pilot2@g.us" :idempotency-key key :eligibility :eligible})
        w1 (store/claim-release-announcement! c key "dead" 10 1000)
        w2 (store/claim-release-announcement! c key "alive" 10 2000)]
    (is (:claimed w1))
    (is (:claimed w2))
    (is (= 2 (:fence w2)))
    (is (:failed (store/fail-release-announcement! c key "alive" 2 "wa_timeout")))))

(deftest message-correlation-is-fenced-and-indexed
  (let [s (fresh) c (:conn s) key "12.8.13:m:pilot3@g.us" now (System/currentTimeMillis)
        _ (store/create-release-announcement! c {:type :release :release-version "12.8.13" :content-hash "m" :chat-id "pilot3@g.us" :idempotency-key key :eligibility :eligible})
        w (store/claim-release-announcement! c key "w" 30000 now)
        started (store/begin-release-announcement-send! c key "w" (:fence w))
        a (store/associate-message! c key "w" (:fence w) "wam-1")]
    (is (:started started))
    (is (= :uncertain (:status (:announcement started))))
    (is (= "wam-1" (:message-id a)))
    (is (= "w" (:message-claimant a)))
    (is (= (:fence w) (:message-fence a)))
    (is (= "wam-1" (:message-id (store/find-by-message-id c "wam-1"))))
    (is (= "ack_insufficient" (:reason (store/confirm-message! c "wam-1" 0 "w" (:fence w)))))
    (is (:confirmed (store/confirm-message! c "wam-1" 1 "w" (:fence w))))
    (is (:duplicate (store/confirm-message! c "wam-1" 3)))
    (is (= "unknown_message" (:reason (store/confirm-message! c "other" 3))))))

(deftest uncertain-message-is-not-reclaimed-and-requires-current-owner
  (let [s (fresh) c (:conn s) key "12.8.13:old-message:pilot4@g.us" now (System/currentTimeMillis)
        _ (store/create-release-announcement! c {:type :release :release-version "12.8.13" :content-hash "old-message" :chat-id "pilot4@g.us" :idempotency-key key :eligibility :eligible})
        first-claim (store/claim-release-announcement! c key "worker-1" 30000 now)
        _ (store/begin-release-announcement-send! c key "worker-1" (:fence first-claim))
        _ (store/associate-message! c key "worker-1" (:fence first-claim) "wam-old")
        second-claim (store/claim-release-announcement! c key "worker-2" 30000 (+ now 40000))]
    (is (false? (:claimed second-claim)))
    (is (= "fence_mismatch" (:reason (store/confirm-message! c "wam-old" 1 "worker-2" 2))))
    (is (false? (:completed (store/complete-release-announcement! c key "worker-2" 2))))
    (is (= :uncertain (:status (store/find-release-announcement c key))))))

(deftest send-started-uncertain-is-never-reclaimed-for-blind-resend
  (let [s (fresh) c (:conn s) key "12.8.13:uncertain:pilot5@g.us" now (System/currentTimeMillis)
        _ (store/create-release-announcement! c {:type :release :release-version "12.8.13" :content-hash "uncertain" :chat-id "pilot5@g.us" :idempotency-key key :eligibility :eligible})
        first-claim (store/claim-release-announcement! c key "worker" 30000 now)
        started (store/begin-release-announcement-send! c key "worker" (:fence first-claim))
        retry (store/claim-release-announcement! c key "retry" 30000 (+ now 40000))
        row (store/find-release-announcement c key)]
    (is (:started started))
    (is (false? (:claimed retry)))
    (is (= "not_claimable" (:reason retry)))
    (is (= :uncertain (:status row)))
    (is (nil? (:message-id row)))
    (is (pos? (:send-started-at row)))))
