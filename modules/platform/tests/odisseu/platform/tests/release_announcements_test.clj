(ns odisseu.platform.tests.release-announcements-test
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.platform.domain.release-announcements :as release]))

(deftest deterministic-key-and-event
  (let [input {:version "12.8.7" :content-hash "abc" :chat-id "pilot@g.us"}]
    (is (= "12.8.7:abc:pilot@g.us" (release/idempotency-key input)))
    (is (= {:type :release :release-version "12.8.7" :content-hash "abc"
            :chat-id "pilot@g.us" :idempotency-key "12.8.7:abc:pilot@g.us"
            :eligibility :eligible} (release/event input)))))

(deftest all-gates-and-allowlist-required
  (let [base {:enabled? true :jvm-healthy? true :node-healthy? true
              :datomic-accessible? true :whatsapp-ready? true
              :version-confirmed? true :rollback-complete? true}]
    (is (false? (release/eligible? base)))
    (is (true? (release/eligible? (assoc base :authorized? true))))
    (doseq [gate [:jvm-healthy? :node-healthy? :datomic-accessible?
                  :whatsapp-ready? :version-confirmed? :rollback-complete?]]
      (is (false? (release/eligible? (assoc base :authorized? true gate false))) gate))))

(deftest invalid-key-cannot-create-event
  (is (nil? (release/idempotency-key {:version "12.8.7" :content-hash "" :chat-id "pilot@g.us"})))
  (is (nil? (release/event {:version "12.8.7" :content-hash "abc" :chat-id nil}))))
