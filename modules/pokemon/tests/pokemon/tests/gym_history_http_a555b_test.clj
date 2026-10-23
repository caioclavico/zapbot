(ns pokemon.tests.gym-history-http-a555b-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]))

(defn- get-json [url actor]
  (let [b (HttpRequest/newBuilder (URI/create url))
        _ (.header b "Authorization" "Bearer test")
        _ (.header b "X-Actor-Player-Id" actor)
        r (.send (HttpClient/newHttpClient) (.GET b) (HttpResponse$BodyHandlers/ofString))]
    [(.statusCode r) (json/read-str (.body r))]))

(deftest ranking-history-http-authenticated
  (let [dir (str "/tmp/a555b-http-" (java.util.UUID/randomUUID))
        c (persistence/client {:system (str "a555b-http-" (java.util.UUID/randomUUID)) :storage-dir dir})
        conn (persistence/ensure-db! c "core")
        s (store/make-store conn)]
    (schema/install! conn)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (doseq [i (range 3)]
      (collection/adicionar-pokemon! s "chat" "ash"
                                     {:id-pokemon (str "http-gym-" i) :nome "Pikachu"
                                      :species "pikachu" :nivel 10 :hp 80 :hp-atual 80
                                      :ataque 200 :defesa 40 :atq-esp 200 :def-esp 40 :veloc 50
                                      :tipos ["normal"] :golpes [{:nome-exibicao "Tackle" :poder 40}]}
                                     80 nil))
    (gym/resolve-challenge! s "chat" "ash"
                             {:operation-id "http-gym-resolve" :gym-id "pedra"
                              :venceu? true :indices [0 1 2] :participacao {}
                              :nome "Ash" :dia "2026-10-17" :agora 1000 :pedra-roll 0})
    (let [server (bridge/start! {:store s :port 0 :token "test"})
          base (:base-url server)]
      (try
        (let [[status data] (get-json (str base "/v1/gyms/ranking?chatId=chat&playerId=ash&gymId=pedra") "ash")]
          (is (= 200 status))
          (is (= "pedra" (get-in data ["data" "gym-id"]))))
        (let [[status data] (get-json (str base "/v1/gyms/history?chatId=chat&playerId=ash&gymId=pedra") "ash")]
          (is (= 200 status))
          (is (= 1 (count (get-in data ["data" "history"])))))
        (finally (bridge/stop! server))))))
