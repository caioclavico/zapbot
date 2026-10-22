(ns pokemon.tests.trade-a441-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.trade :as trade]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.trade-store :as trades])
  (:import [java.util UUID]))

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

(defn- fixture []
  (let [client (persistence/client {:system (str "trade-test-" (UUID/randomUUID))
                                    :storage-dir ".datomic-local/trade-a441-tests"})
        conn (persistence/ensure-db! client "trades")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (doseq [p ["ash" "misty"]] (trainer/create-trainer! s {:chat-id "chat" :player-id p}))
    {:conn conn :store s}))

(deftest create-persiste-recibo-e-reabre
  (let [{:keys [conn store]} (fixture)
        req {:operation-id "trade-create-1" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a (:fence-generation (ports/get-player store "chat" "ash"))
             :fence-b (:fence-generation (ports/get-player store "chat" "misty")) :now 1000}
        first (trades/create! conn req)
        retry (trades/create! conn req)
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-create-1"])]
    (is (= "trade-create-1" (:id first)))
    (is (false? (:duplicate first)))
    (is (true? (:duplicate retry)))
    (is (= "trade-create" (:operation-receipt/kind receipt)))
    (is (string? (:operation-receipt/payload-hash receipt)))
    (is (= :invited (:state (trades/get-trade conn "trade-create-1"))))))

(deftest create-rejeita-retry-incompativel-e-fencing-invalido
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req {:operation-id "trade-conflict" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000}]
    (trades/create! conn req)
    (is (thrown? Exception (trades/create! conn (assoc req :now 1001))))
    (is (thrown? Exception (trades/create! conn (assoc req :operation-id "trade-bad-fence" :fence-a (inc fa)))))
    (is (thrown? Exception (trades/create! conn (assoc req :operation-id "trade-self" :player-b "ash"))))))

(deftest transition-persistente-e-autorizacao
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req {:operation-id "trade-transition-create" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000}
        _ (trades/create! conn req)
        accepted (trades/accept! conn {:operation-id "trade-accept" :trade-id "trade-transition-create"
                                       :chat-id "chat" :player-id "misty" :save-id "a"
                                       :fence-generation fb :now 1001})]
    (is (= :accepted (:state accepted)))
    (is (thrown? Exception
                 (trades/accept! conn {:operation-id "trade-accept-bad" :trade-id "trade-transition-create"
                                       :chat-id "chat" :player-id "ash" :save-id "a"
                                       :fence-generation fa :now 1002})))
    (is (= :accepted (:state (trades/accept! conn {:operation-id "trade-accept" :trade-id "trade-transition-create"
                                                    :chat-id "chat" :player-id "misty" :save-id "a"
                                                    :fence-generation fb :now 1001}))))))

(deftest transicao-expirada-e-reabertura
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req {:operation-id "trade-expire-create" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000}
        _ (trades/create! conn req)
        expired (trades/expire! conn {:operation-id "trade-expire" :trade-id "trade-expire-create"
                                      :chat-id "chat" :player-id "misty" :save-id "a"
                                      :fence-generation fb :now 301000})]
    (is (= :expired (:state expired)))
    (is (thrown? Exception
                 (trades/accept! conn {:operation-id "trade-after-expiry" :trade-id "trade-expire-create"
                                       :chat-id "chat" :player-id "misty" :save-id "a"
                                       :fence-generation fb :now 301001})))))

(deftest recusa-e-cancelamento-persistentes
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))]
    (trades/create! conn {:operation-id "trade-refuse-create" :chat-id "chat"
                          :player-a "ash" :player-b "misty" :save-a "a" :save-b "a"
                          :fence-a fa :fence-b fb :now 1000})
    (is (= :refused (:state (trades/refuse! conn {:operation-id "trade-refuse"
                                                   :trade-id "trade-refuse-create" :chat-id "chat"
                                                   :player-id "misty" :save-id "a"
                                                   :fence-generation fb :now 1001}))))
    (trades/create! conn {:operation-id "trade-cancel-create" :chat-id "chat"
                          :player-a "ash" :player-b "misty" :save-a "a" :save-b "a"
                          :fence-a fa :fence-b fb :now 2000})
    (is (= :cancelled (:state (trades/cancel! conn {:operation-id "trade-cancel"
                                                     :trade-id "trade-cancel-create" :chat-id "chat"
                                                     :player-id "ash" :save-id "a"
                                                     :fence-generation fa :now 2001}))))))

(deftest concorrencia-idempotente-e-transicoes-incompativeis
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req {:operation-id "trade-concurrent-create" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000}
        results (doall (pmap (fn [_] (try (trades/create! conn req) (catch Exception e e))) (range 2)))
        trade (trades/get-trade conn "trade-concurrent-create")
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-concurrent-create"])]
    (is (= 2 (count (filter map? results))))
    (is (= :invited (:state trade)))
    (is (= "trade-create" (:operation-receipt/kind receipt)))
    (trades/create! conn (assoc req :operation-id "trade-concurrent-transition-create"))
    (let [accept {:operation-id "trade-accept-race" :trade-id "trade-concurrent-transition-create"
                  :chat-id "chat" :player-id "misty" :save-id "a" :fence-generation fb :now 1001}
          refuse (assoc accept :operation-id "trade-refuse-race")
          outcomes (doall (pmap #(try (if (zero? %) (trades/accept! conn accept)
                                                       (trades/refuse! conn refuse))
                                      (catch Exception e e)) (range 2)))
          final (trades/get-trade conn "trade-concurrent-transition-create")]
      (is (= 1 (count (filter map? outcomes))))
      (is (contains? #{:accepted :refused} (:state final))))))

(deftest fencing-invalida-transicao
  (let [{:keys [conn store]} (fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req {:operation-id "trade-fence-create" :chat-id "chat" :player-a "ash" :player-b "misty"
             :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000}]
    (trades/create! conn req)
    (is (thrown? Exception
                 (trades/accept! conn {:operation-id "trade-fence-accept" :trade-id "trade-fence-create"
                                       :chat-id "chat" :player-id "misty" :save-id "a"
                                       :fence-generation (inc fb) :now 1001})))))
