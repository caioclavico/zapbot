(ns pokemon.tests.profile-http-a551-test
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]))

(defn- request [client method url token body actor]
  (let [b (doto (HttpRequest/newBuilder (URI/create url))
            (.header "Authorization" (str "Bearer " token))
            (.header "X-Actor-Player-Id" actor))
        b (if (= method "GET") (.GET b)
              (.method b method (HttpRequest$BodyPublishers/ofString body)))]
    (.send client (.build b) (HttpResponse$BodyHandlers/ofString))))

(deftest endpoints-perfil-professor-autorizados
  (let [client-db (persistence/client {:system (str "a551-http-" (java.util.UUID/randomUUID))
                                       :storage-dir (str "/tmp/a551-http-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client-db "core")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (let [p (ports/get-player s "chat" "ash")
          w {:chat-id "chat" :player-id "ash" :save-id "a" :fence-generation (:fence-generation p)}]
      (ports/add-to-collection! s w {:id-pokemon "http-p1" :species "pidgey" :nome "Pidgey"
                                     :nivel 5 :hp 20 :hp-atual 20 :amizade 70 :xp 0
                                     :xp-desde-nivel 0 :in-party true})
      (let [server (bridge/start! {:store s :port 0 :token "test"})
            base (:base-url server)
            h (HttpClient/newHttpClient)
            ok (request h "GET" (str base "/v1/profile/friendship?chatId=chat&playerId=ash&saveId=a&collectionIndex=0") "test" nil "ash")
            denied (request h "GET" (str base "/v1/profile/friendship?chatId=chat&playerId=ash&saveId=a&collectionIndex=0") "test" nil "misty")
            sent (request h "POST" (str base "/v1/professor/send") "test"
                          (str "{\"chatId\":\"chat\",\"playerId\":\"ash\",\"saveId\":\"a\",\"fenceGeneration\":" (:fence-generation p) ",\"collectionIndex\":0,\"operationId\":\"http-prof-send\",\"now\":1000}") "ash")]
        (try
          (is (= 200 (.statusCode ok)))
          (is (= 400 (.statusCode denied)))
          (is (= 200 (.statusCode sent)))
          (is (re-find #"token" (.body sent)))
          (finally ((:stop! server))))))))
