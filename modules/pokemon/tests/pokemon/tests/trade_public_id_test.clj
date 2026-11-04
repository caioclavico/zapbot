(ns pokemon.tests.trade-public-id-test
  "IDs públicos curtos, resolução legado e isolamento por chat."
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.trade :as trade]
            [pokemon.infrastructure.datomic.trade-store :as trades]
            [pokemon.tests.trade-a441-test :as base])
  (:import [java.util UUID]))

(deftest parse-e-format-public-token
  (is (= 84 (trade/parse-public-token "84")))
  (is (= 84 (trade/parse-public-token "#84")))
  (is (= 84 (trade/parse-public-token "  #84  ")))
  (is (nil? (trade/parse-public-token "0")))
  (is (nil? (trade/parse-public-token "false_chat@g.us:trade-create:1")))
  (is (= "#84" (trade/format-public-id 84))))

(deftest cria-aloca-public-id-persistente-e-resolve
  (let [{:keys [conn store]} (base/fixture* {:storage-dir (str ".datomic-local/trade-public-" (UUID/randomUUID))
                                             :system (str "pub-" (UUID/randomUUID))
                                             :db-name "trade-public"})
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        req (fn [op]
              {:operation-id op :chat-id "chat" :player-a "ash" :player-b "misty"
               :save-a "a" :save-b "a" :fence-a fa :fence-b fb :now 1000
               :pokemon-a "poke-ash" :pokemon-b "poke-misty"
               :pokemon-a-name "Sparky" :pokemon-b-name "Vee"})
        first (trades/create! conn (req "pub-create-1"))
        second (trades/create! conn (assoc (req "pub-create-2") :now 1001))
        other-chat (do (doseq [p ["ash" "misty"]]
                         (trainer/create-trainer! store {:chat-id "chat-b" :player-id p}))
                       (let [fa2 (:fence-generation (ports/get-player store "chat-b" "ash"))
                             fb2 (:fence-generation (ports/get-player store "chat-b" "misty"))]
                         (trades/create! conn {:operation-id "pub-other-chat" :chat-id "chat-b"
                                               :player-a "ash" :player-b "misty" :save-a "a" :save-b "a"
                                               :fence-a fa2 :fence-b fb2 :now 2000})))]
    (is (= 1 (:public-id first)))
    (is (= 2 (:public-id second)))
    (is (= 1 (:public-id other-chat)))
    (is (= "pub-create-1" (trades/resolve-trade-id conn "chat" "1")))
    (is (= "pub-create-1" (trades/resolve-trade-id conn "chat" "#1")))
    (is (= "pub-create-1" (trades/resolve-trade-id conn "chat" "pub-create-1")))
    (is (nil? (trades/resolve-trade-id conn "chat-b" "2")))
    (is (= "Sparky" (:pokemon-a-name (trades/get-trade conn "pub-create-1"))))
    (is (= "#1" (:public-label (trades/get-trade conn "pub-create-1"))))
    (is (= 3 (:trade-seq/next (d/pull (d/db conn) '[*] [:trade-seq/chat-id "chat"]))))
    (is (= 2 (:trade-seq/next (d/pull (d/db conn) '[*] [:trade-seq/chat-id "chat-b"]))))))

(deftest aceitar-e-completar-via-public-id
  (let [{:keys [conn store]} (base/fixture* {:storage-dir (str ".datomic-local/trade-pub-flow-" (UUID/randomUUID))
                                             :system (str "pub-flow-" (UUID/randomUUID))
                                             :db-name "trade-pub-flow"})
        _ (#'base/seed-trade-pokemon! conn store)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        created (trades/create! conn {:operation-id "legacy-looking-id" :chat-id "chat"
                                      :player-a "ash" :player-b "misty" :save-a "a" :save-b "a"
                                      :fence-a fa :fence-b fb :now 1000
                                      :pokemon-a "poke-ash" :pokemon-b "poke-misty"})
        accepted (trades/accept! conn {:operation-id "pub-accept" :trade-id "#1"
                                       :chat-id "chat" :player-id "misty" :save-id "a"
                                       :fence-generation fb :now 1001})
        completed (trades/complete! conn {:operation-id "pub-complete" :trade-id "1"
                                          :chat-id "chat" :player-id "ash" :save-id "a"
                                          :fence-generation fa :now 1002})]
    (is (= 1 (:public-id created)))
    (is (= :accepted (:state accepted)))
    (is (= :completed (:state completed)))
    (is (= :trade_not_found
           (try
             (trades/accept! conn {:operation-id "pub-missing" :trade-id "#99"
                                   :chat-id "chat" :player-id "misty" :save-id "a"
                                   :fence-generation fb :now 1003})
             :ok
             (catch Exception e (err/error-code e)))))))
