(ns pokemon.tests.bag-kit-daily-test
  "Integração kit/diário — fence, anti-farm, pending, capacidade."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bag :as bag-app]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.bag-rewards :as bag]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-bag-tests")
(def ^:private storage-rel ".datomic-local/bag-kit-daily-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- qty
  [s chat player save-id item]
  (long
   (or (->> (ports/list-inventory s chat player save-id)
            (some #(when (= item (:item-id %)) (:qty %))))
       0)))
(deftest kit-concede-10-exige-espaco-e-anti-farm
  (let [s (fresh-store! "bag-kit-1")
        chat "chat-kit"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [ok (bag-app/claim-kit! s {:chat-id chat :player-id player
                                    :operation-id "kit-1"})]
      (is (false? (:kit-already-claimed ok)))
      (is (= 10 (:qty ok)))
      (is (= 10 (qty s chat player "a" "pokebola")))
      (is (true? (:kit-inicial-resgatado (ports/get-save s chat player "b")))))
    (trainer/switch-save! s chat player "b")
    (let [again (bag-app/claim-kit! s {:chat-id chat :player-id player
                                       :operation-id "kit-2"})]
      (is (true? (:kit-already-claimed again)))
      (is (zero? (qty s chat player "b" "pokebola"))))
    (testing "idempotência mesmo operation-id"
      (let [dup (bag-app/claim-kit! s {:chat-id chat :player-id player
                                       :operation-id "kit-1"})]
        (is (true? (or (:duplicate dup) (:kit-already-claimed dup))))))))

(deftest kit-bag-full-nao-marca-flag
  (let [s (fresh-store! "bag-kit-full")
        chat "chat-full"
        player "misty"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    ;; Enche quase a mochila (50): 41 poções → 9 livres < 10
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-fill" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 41}})
    (let [code (try
                 (bag-app/claim-kit! s {:chat-id chat :player-id player
                                        :operation-id "kit-full"})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :bag_full code))
      (is (false? (:kit-inicial-resgatado (ports/get-save s chat player "a"))))
      (is (zero? (qty s chat player "a" "pokebola"))))))

(deftest diario-sequencia-pending-e-anti-farm
  (let [s (fresh-store! "bag-daily-1")
        chat "chat-day"
        player "brock"
        draw (constantly 50)] ;; só pokebola
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [d1 (bag-app/claim-daily!
              s {:chat-id chat :player-id player
                 :operation-id "day-1" :dia "2026-10-28"
                 :draw draw :timezone "America/Sao_Paulo"})]
      (is (false? (:daily-already-claimed d1)))
      (is (= 1 (:sequencia d1)))
      (is (= 3 (get (:items d1) "pokebola")))
      (is (= 3 (qty s chat player "a" "pokebola"))))
    (testing "mesmo dia → already"
      (let [again (bag-app/claim-daily!
                   s {:chat-id chat :player-id player
                      :operation-id "day-1b" :dia "2026-10-28"
                      :draw draw})]
        (is (true? (:daily-already-claimed again)))))
    (testing "dia seguinte sobe sequência; anti-farm no save B"
      (let [d2 (bag-app/claim-daily!
                s {:chat-id chat :player-id player
                   :operation-id "day-2" :dia "2026-10-29"
                   :draw draw})]
        (is (= 2 (:sequencia d2)))
        (is (= 4 (get (:items d2) "pokebola")))
        (is (= "2026-10-29" (:ultimo-bonus-diario (ports/get-save s chat player "b")))))
      (trainer/switch-save! s chat player "b")
      (let [farm (bag-app/claim-daily!
                  s {:chat-id chat :player-id player
                     :operation-id "day-2-b" :dia "2026-10-29"
                     :draw draw})]
        (is (true? (:daily-already-claimed farm)))
        (is (zero? (qty s chat player "b" "pokebola")))))))

(deftest diario-overflow-vai-para-pending
  (let [s (fresh-store! "bag-daily-pend")
        chat "chat-pend"
        player "oak"
        draw (constantly 50)]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-almost" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 48}})
    (let [d (bag-app/claim-daily!
             s {:chat-id chat :player-id player
                :operation-id "day-pend" :dia "2026-10-29"
                :draw draw})
          pend (:pending d)]
      (is (= 2 (get (:added d) "pokebola")))
      (is (= 1 (get pend "pokebola")))
      (is (pos? (long (get pend "pokebola" 0)))))))

(deftest bridge-tx-aliases
  (let [s (fresh-store! "bag-bridge")
        chat "chat-br"
        player "red"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (is (= 10 (:qty (tx/claim-starter-kit!
                     s {:chat-id chat :player-id player :operation-id "bk"}))))
    (is (map? (tx/claim-bag-daily!
               s {:chat-id chat :player-id player
                  :operation-id "bd" :dia "2026-11-01"
                  :draw (constantly 0)})))))
