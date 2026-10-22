(ns pokemon.tests.trade-http-a443-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.tests.trade-a441-test :as trade-test]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers HttpClient]
           [java.util UUID]))

(defn- request [base path method body token actor]
  (let [b (HttpRequest/newBuilder (URI/create (str base path)))
        _ (when token (.header b "Authorization" (str "Bearer " token)))
        _ (when actor (.header b "X-Actor-Player-Id" actor))
        _ (if (= method "GET") (.GET b)
              (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))
        r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode r) (json/read-str (.body r))]))

(deftest http-trades-auth-and-create-list
  (let [dir (str ".datomic-local/http-trade-" (UUID/randomUUID))
        {:keys [conn store]} (trade-test/fixture* {:storage-dir dir :system (str "http-" (UUID/randomUUID)) :db-name "http-trades"})
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        server (bridge/start! {:store store :port 0 :token "test"})
        base (:base-url server)
        body {:chatId "chat" :playerId "ash" :playerA "ash" :playerB "misty"
              :saveA "a" :saveB "a" :fenceA fa :fenceB fb :fenceGeneration fa
              :operationId "http-create" :now 1000}]
    (try
      (is (= 401 (first (request base "/v1/trades" "GET" nil nil "ash"))))
      (is (= 401 (first (request base "/v1/trades" "GET" nil "bad" "ash"))))
      (is (= 400 (first (request base "/v1/trades" "GET" nil "test" nil))))
      (is (= 400 (first (request base "/v1/trades" "GET" nil "test" "misty"))))
      (let [[status response] (request base "/v1/trades" "POST" body "test" "ash")]
        (is (= 200 status))
        (is (true? (get response "ok"))))
      (let [[status response] (request base "/v1/trades?chatId=chat&playerId=ash" "GET" nil "test" "ash")]
        (is (= 200 status))
        (is (seq (get-in response ["data" "trades"]))))
      (is (= 400 (first (request base "/v1/trades" "POST" (assoc body :playerId "misty") "test" "ash"))))
      (finally (bridge/stop! server)))))

(deftest http-trades-flux-completo
  (let [dir (str ".datomic-local/http-trade-flow-" (UUID/randomUUID))
        {:keys [conn store]} (trade-test/fixture* {:storage-dir dir :system (str "http-flow-" (UUID/randomUUID)) :db-name "http-flow"})
        _ (#'trade-test/seed-trade-pokemon! conn store)
        fa (:fence-generation (ports/get-player store "chat" "ash"))
        fb (:fence-generation (ports/get-player store "chat" "misty"))
        server (bridge/start! {:store store :port 0 :token "test"})
        base (:base-url server)
        create {:chatId "chat" :playerId "ash" :playerA "ash" :playerB "misty"
                :saveA "a" :saveB "a" :fenceA fa :fenceB fb :fenceGeneration fa
                :pokemonA "poke-ash" :pokemonB "poke-misty" :operationId "http-flow-create" :now 1000}]
    (try
      (is (= 200 (first (request base "/v1/trades" "POST" create "test" "ash"))))
      (is (= 200 (first (request base "/v1/trades/accept" "POST"
                                {:chatId "chat" :playerId "misty" :tradeId "http-flow-create"
                                 :operationId "http-flow-accept" :saveId "a" :fenceGeneration fb :now 1001}
                                "test" "misty"))))
      (let [[status response] (request base "/v1/trades/complete" "POST"
                                       {:chatId "chat" :playerId "ash" :tradeId "http-flow-create"
                                        :operationId "http-flow-complete" :saveId "a" :fenceGeneration fa
                                        :pokemonA "poke-ash" :pokemonB "poke-misty" :now 1002}
                                       "test" "ash")]
        (is (= 200 status))
        (is (= "completed" (get-in response ["data" "state"]))))
      (is (= 200 (first (request base "/v1/trades/complete" "POST"
                                {:chatId "chat" :playerId "ash" :tradeId "http-flow-create"
                                 :operationId "http-flow-complete" :saveId "a" :fenceGeneration fa
                                 :pokemonA "poke-ash" :pokemonB "poke-misty" :now 1002}
                                "test" "ash"))))
      (is (= (:db/id (d/pull (d/db conn) '[*] [:save/key "chat|misty|a"]))
             (or (-> (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"]) :pokemon/save :db/id)
                 (:pokemon/save (d/pull (d/db conn) '[*] [:pokemon/id-pokemon "poke-ash"])))))
      (finally (bridge/stop! server)))))
