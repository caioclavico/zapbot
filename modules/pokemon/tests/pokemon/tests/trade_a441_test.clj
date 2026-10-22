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

(defn fixture* [{:keys [storage-dir system db-name]
                :or {storage-dir ".datomic-local/trade-a441-tests"
                     system (str "trade-test-" (UUID/randomUUID))
                     db-name "trades"}}]
  (let [client (persistence/client {:system system :storage-dir storage-dir})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (doseq [p ["ash" "misty"]] (trainer/create-trainer! s {:chat-id "chat" :player-id p}))
    {:conn conn :store s}))

(defn- fixture [] (fixture* {}))

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

(defn- seed-trade-pokemon! [conn store]
  (let [sa (d/pull (d/db conn) '[*] [:save/key "chat|ash|a"])
        sb (d/pull (d/db conn) '[*] [:save/key "chat|misty|a"])]
    (d/transact conn {:tx-data [{:pokemon/id-pokemon "poke-ash"
                                 :pokemon/key "poke-ash"
                                 :pokemon/save (:db/id sa)
                                 :pokemon/species "pikachu"
                                 :pokemon/nickname "Sparky"
                                 :pokemon/shiny false
                                 :pokemon/xp 123
                                 :pokemon/level 7
                                 :pokemon/hp 35
                                 :pokemon/hp-atual 30
                                 :pokemon/collection-index 0}
                                {:pokemon/id-pokemon "poke-misty"
                                 :pokemon/key "poke-misty"
                                 :pokemon/save (:db/id sb)
                                 :pokemon/species "eevee"
                                 :pokemon/nickname "Vee"
                                 :pokemon/shiny true
                                 :pokemon/xp 456
                                 :pokemon/level 9
                                 :pokemon/hp 40
                                 :pokemon/hp-atual 40
                                 :pokemon/collection-index 0}]})))

(deftest transferencia-bilateral-atomica
  (let [{:keys [conn store]} (fixture)
        _ (seed-trade-pokemon! conn store)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        create {:operation-id "trade-complete-create" :chat-id "chat"
                :player-a "ash" :player-b "misty" :save-a "a" :save-b "a"
                :fence-a fa :fence-b fb :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1000}
        _ (trades/create! conn create)
        _ (trades/accept! conn {:operation-id "trade-complete-accept" :trade-id "trade-complete-create"
                                :chat-id "chat" :player-id "misty" :save-id "a"
                                :fence-generation fb :now 1001})
        result (trades/complete! conn {:operation-id "trade-complete" :trade-id "trade-complete-create"
                                       :chat-id "chat" :player-id "ash" :save-id "a"
                                       :fence-generation fa :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                                       :now 1002})
        ash-save (:db/id (d/pull (d/db conn) '[*] [:save/key "chat|ash|a"]))
        misty-save (:db/id (d/pull (d/db conn) '[*] [:save/key "chat|misty|a"]))
        pa (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"])
        pb (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-misty"])
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-complete"])]
    (is (= :completed (:state result)))
    (is (= misty-save (or (-> pa :pokemon/save :db/id) (:pokemon/save pa))))
    (is (= ash-save (or (-> pb :pokemon/save :db/id) (:pokemon/save pb))))
    (is (= "Sparky" (:pokemon/nickname pa)))
    (is (= "pikachu" (:pokemon/species pa)))
    (is (= "eevee" (:pokemon/species pb)))
    (is (= 456 (:pokemon/xp pb)))
    (is (= "trade-complete" (:operation-receipt/kind receipt)))
    (is (= :completed (:state (trades/get-trade conn "trade-complete-create"))))))

(declare accepted-trade-fixture)

(deftest troca-preserva-contagem-da-colecao
  (let [{:keys [conn store]} (accepted-trade-fixture)
        before (count (d/q '[:find ?p :where [?p :pokemon/id-pokemon]] (d/db conn)))
        fa (:fence-generation (ports/get-player store "chat" "ash"))]
    (trades/complete! conn {:operation-id "trade-count-complete" :trade-id "trade-idem-create"
                            :chat-id "chat" :player-id "ash" :save-id "a"
                            :fence-generation fa :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                            :now 1002})
    (is (= before (count (d/q '[:find ?p :where [?p :pokemon/id-pokemon]] (d/db conn)))))))

(defn- accepted-trade-fixture []
  (let [{:keys [conn store]} (fixture)
        _ (seed-trade-pokemon! conn store)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        create {:operation-id "trade-idem-create" :chat-id "chat" :player-a "ash" :player-b "misty"
                :save-a "a" :save-b "a" :fence-a fa :fence-b fb
                :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1000}]
    (trades/create! conn create)
    (trades/accept! conn {:operation-id "trade-idem-accept" :trade-id "trade-idem-create"
                          :chat-id "chat" :player-id "misty" :save-id "a"
                          :fence-generation fb :now 1001})
    {:conn conn :store store :fa fa :fb fb}))

(deftest complete-idempotente-e-hash
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        request {:operation-id "trade-idem-complete" :trade-id "trade-idem-create"
                 :chat-id "chat" :player-id "ash" :save-id "a" :fence-generation fa
                 :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002}
        first (trades/complete! conn request)
        retry (trades/complete! conn request)]
    (is (false? (:duplicate first)))
    (is (true? (:duplicate retry)))
    (is (= :completed (:state (trades/get-trade conn "trade-idem-create"))))
    (is (thrown? Exception
                 (trades/complete! conn (assoc request :now 1003))))))

(deftest complete-concorrente
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        request {:operation-id "trade-race-complete" :trade-id "trade-idem-create"
                 :chat-id "chat" :player-id "ash" :save-id "a" :fence-generation fa
                 :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002}
        outcomes (doall (pmap (fn [_]
                                (try (trades/complete! conn request)
                                     (catch Exception e e)))
                              [0 1]))
        trade (trades/get-trade conn "trade-idem-create")
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-race-complete"])]
    (is (= 2 (count outcomes)))
    (is (every? map? outcomes))
    (is (= :completed (:state trade)))
    (is (= "trade-complete" (:operation-receipt/kind receipt)))))

(deftest complete-rejeita-save-fence-revisao-e-posse-alterados
  (let [{:keys [conn store]} (accepted-trade-fixture)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        before-a (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"])]
    (d/transact conn {:tx-data [[:db/add [:player/key "chat|ash"] :player/fence-generation (inc fa)]]})
    (is (thrown? Exception
                 (trades/complete! conn {:operation-id "trade-invalid-fence" :trade-id "trade-idem-create"
                                         :chat-id "chat" :player-id "ash" :save-id "a"
                                         :fence-generation fa :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                                         :now 1002})))
    (is (= (:pokemon/save before-a)
           (:pokemon/save (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"]))))
    (d/transact conn {:tx-data [[:db/add [:pokemon/id-pokemon "poke-ash"] :pokemon/save
                                 (:db/id (d/pull (d/db conn) '[*] [:save/key "chat|misty|a"]))]]})
    (is (thrown? Exception
                 (trades/complete! conn {:operation-id "trade-invalid-owner" :trade-id "trade-idem-create"
                                         :chat-id "chat" :player-id "ash" :save-id "a"
                                         :fence-generation (inc fa) :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                                         :now 1002})))))

(deftest complete-rejeita-batalha-e-joy
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        base {:trade-id "trade-idem-create" :chat-id "chat" :player-id "ash" :save-id "a"
              :fence-generation fa :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002}]
    (d/transact conn {:tx-data [[:db/add [:player/key "chat|ash"] :player/active-battle-id "battle-1"]]})
    (is (thrown? Exception (trades/complete! conn (assoc base :operation-id "trade-battle"))))
    (is (= :accepted (:state (trades/get-trade conn "trade-idem-create"))))
    (d/transact conn {:tx-data [[:db/retract [:player/key "chat|ash"] :player/active-battle-id "battle-1"]
                                {:enfermaria-bed/key "chat|ash|a|bed-1"
                                 :enfermaria-bed/save-key "chat|ash|a"
                                 :enfermaria-bed/bed-id "bed-1"
                                 :enfermaria-bed/pokemon-edn (pr-str {:id-pokemon "poke-ash"})
                                 :enfermaria-bed/pronto-em 999999}]})
    (is (thrown? Exception (trades/complete! conn (assoc base :operation-id "trade-joy"))))
    (is (= :accepted (:state (trades/get-trade conn "trade-idem-create"))))))

(deftest complete-falha-antes-do-commit-nao-muta
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        request {:operation-id "trade-precommit-failure" :trade-id "trade-idem-create"
                 :chat-id "chat" :player-id "ash" :save-id "a"
                 :fence-generation (inc fa) :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                 :now 1002}]
    (is (thrown? Exception (trades/complete! conn request)))
    (is (= :accepted (:state (trades/get-trade conn "trade-idem-create"))))
    (is (nil? (:db/id (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-precommit-failure"]))))
    (is (= (:db/id (d/pull (d/db conn) '[*] [:save/key "chat|ash|a"]))
           (or (-> (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"]) :pokemon/save :db/id)
               (:pokemon/save (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"])))))))

(deftest complete-reconcilia-apos-commit
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        request {:operation-id "trade-postcommit-recovery" :trade-id "trade-idem-create"
                 :chat-id "chat" :player-id "ash" :save-id "a" :fence-generation fa
                 :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002}
        committed (trades/complete! conn request)
        recovered (trades/complete! conn request)
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-postcommit-recovery"])]
    (is (false? (:duplicate committed)))
    (is (true? (:duplicate recovered)))
    (is (= :completed (:state (trades/get-trade conn "trade-idem-create"))))
    (is (= "trade-complete" (:operation-receipt/kind receipt)))))

(deftest complete-recupera-excecao-apos-transact
  (let [{:keys [conn fa]} (accepted-trade-fixture)
        request {:operation-id "trade-injected-response" :trade-id "trade-idem-create"
                 :chat-id "chat" :player-id "ash" :save-id "a" :fence-generation fa
                 :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002}
        result (with-redefs-fn {#'trades/transact!
                                (fn [c tx]
                                  (let [r (datomic.client.api/transact c tx)]
                                    (throw (ex-info "simulated lost response" {:tx r}))))}
                 #(trades/complete! conn request))
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id "trade-injected-response"])]
    (is (true? (:duplicate result)))
    (is (= :completed (:state result)))
    (is (= "trade-complete" (:operation-receipt/kind receipt)))
    (is (= :completed (:state (trades/get-trade conn "trade-idem-create"))))))
