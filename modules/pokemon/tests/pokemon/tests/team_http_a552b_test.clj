(ns pokemon.tests.team-http-a552b-test
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.util UUID]))

(defn- call [base path method body actor]
  (let [b (HttpRequest/newBuilder (URI/create (str base path)))
        _ (.header b "Authorization" "Bearer test")
        _ (.header b "X-Actor-Player-Id" actor)
        _ (if (= method "GET") (.GET b)
              (.POST b (HttpRequest$BodyPublishers/ofString (json/write-str body))))
        r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode r) (json/read-str (.body r))]))

(deftest named-team-http-authenticated-flow
  (let [dir (str "/tmp/a552b-http-" (UUID/randomUUID))
        client (persistence/client {:system (str "a552b-http-" (UUID/randomUUID)) :storage-dir dir})
        conn (persistence/ensure-db! client "teams-http")
        s (store/make-store conn)]
    (schema/install! conn)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (doseq [i (range 3)]
      (collection/adicionar-pokemon! s "chat" "ash"
                                     {:id-pokemon (str "http-team-" i) :species "pikachu"
                                      :nome "Pikachu" :nivel 5 :hp 20 :hp-atual 20} 20 nil))
    (let [fence (:fence-generation (ports/get-player s "chat" "ash"))
          server (bridge/start! {:store s :port 0 :token "test"})
          base (:base-url server)
          body {:chatId "chat" :playerId "ash" :saveId "a" :fenceGeneration fence
                :operationId "team-http-save" :name "Liga" :indices [0 1 2]}]
      (try
        (is (= 400 (first (call base "/v1/teams?chatId=chat&playerId=ash&saveId=a" "GET" nil "misty"))))
        (is (= 200 (first (call base "/v1/teams/save" "POST" body "ash"))))
        (let [[status data] (call base "/v1/teams?chatId=chat&playerId=ash&saveId=a" "GET" nil "ash")]
          (is (= 200 status))
          (is (= "liga" (get-in data ["data" "teams" 0 "name"]))))
        (is (= 200 (first (call base "/v1/teams/use" "POST"
                                  (assoc body :operationId "team-http-use" :destination "liga") "ash"))))
        (is (= 200 (first (call base "/v1/teams/delete" "POST"
                                  (assoc body :operationId "team-http-delete") "ash"))))
        (finally (bridge/stop! server))))))
