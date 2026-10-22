(ns pokemon.tests.trade-process-recovery
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.tests.trade-a441-test :as t]
            [pokemon.infrastructure.datomic.trade-store :as trades]))

(def system "trade-a442-process")
(def db-name "trades-process")

(defn write! [storage marker]
  (let [{:keys [conn store]} (t/fixture* {:storage-dir storage :system system :db-name db-name})
        _ (#'t/seed-trade-pokemon! conn store)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        create {:operation-id "process-create" :chat-id "chat" :player-a "ash" :player-b "misty"
                :save-a "a" :save-b "a" :fence-a fa :fence-b fb
                :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1000}]
    (trades/create! conn create)
    (trades/accept! conn {:operation-id "process-accept" :trade-id "process-create"
                          :chat-id "chat" :player-id "misty" :save-id "a" :fence-generation fb :now 1001})
    (trades/complete! conn {:operation-id "process-complete" :trade-id "process-create"
                            :chat-id "chat" :player-id "ash" :save-id "a" :fence-generation fa
                            :pokemon-a "poke-ash" :pokemon-b "poke-misty" :now 1002})
    (spit marker (pr-str {:trade-id "process-create" :operation-id "process-complete"}))
    true))

(defn verify! [storage marker]
  (let [{:keys [conn]} (t/fixture* {:storage-dir storage :system system :db-name db-name})
        {:keys [trade-id operation-id]} (edn/read-string (slurp marker))
        trade (trades/get-trade conn trade-id)
        retry (trades/complete! conn {:operation-id operation-id :trade-id trade-id
                                      :chat-id "chat" :player-id "ash" :save-id "a"
                                      :fence-generation 0 :pokemon-a "poke-ash" :pokemon-b "poke-misty"
                                      :now 1003})
        receipt (d/pull (d/db conn) '[*] [:operation-receipt/id operation-id])]
    (assert (= :completed (:state trade)))
    (assert (:db/id receipt))
    (assert (:duplicate retry))
    true))

(defn -main [& [op storage marker]]
  (case op
    "write" (write! storage marker)
    "verify" (verify! storage marker)
    (throw (ex-info "expected write or verify" {:op op}))))
