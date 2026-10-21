(ns pokemon.tests.trade-a441-test
  (:require [clojure.test :refer [deftest is]]
            [pokemon.domain.errors :as err]
            [pokemon.domain.trade :as trade]))

(def base {:id "t-1" :chat-id "c" :player-a "ash" :player-b "misty" :save-a "a" :save-b "a" :now 1000})

(deftest proposal-state-machine-and-isolation
  (let [p (trade/create! base)]
    (is (= :invited (:state p)))
    (is (= 301000 (:expires-at p)))
    (is (= :accepted (:state (trade/transition! p "misty" :accepted 1001))))
    (is (= :trade_forbidden (try (trade/transition! p "gary" :accepted 1001) :ok
                                 (catch Exception e (err/error-code e)))))
    (is (= :invalid_trade_transition (try (trade/transition! p "ash" :completed 1001) :ok
                                          (catch Exception e (err/error-code e)))))
    (is (= :trade_expired (try (trade/transition! p "ash" :accepted 301000) :ok
                                (catch Exception e (err/error-code e)))))))

(deftest self-trade-is-rejected
  (is (= :invalid_trade (try (trade/create! (assoc base :player-b "ash")) :ok
                             (catch Exception e (err/error-code e))))))
