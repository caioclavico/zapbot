(ns odisseu.smoke.m4-capture
  "Smoke M4.4: encontro → captura via bridge HTTP loopback + regressões unitárias."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.encounter-m44-test]
            [pokemon.tests.battle-m34-test]
            [pokemon.tests.capture-m33-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- http
  [client base token method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 8))
                 (.header "Authorization" (str "Bearer " token))
                 (.header "Accept" "application/json"))
        bldr (if body
               (-> bldr
                   (.header "Content-Type" "application/json")
                   (.method method (HttpRequest$BodyPublishers/ofString
                                    (json/write-str body))))
               (.method bldr method (HttpRequest$BodyPublishers/noBody)))
        res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn- assert!
  [cond msg]
  (when-not cond
    (throw (ex-info msg {}))))

(defn- bridge-flow!
  []
  (let [system "odisseu-m44-smoke"
        storage ".datomic-local/m44-smoke"
        db "bridge-capture"
        token "m44-smoke-token"
        chat "chat-smoke-m44"
        player "player-smoke"
        client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db db)
        conn (persistence/ensure-db! client-db db)
        s (doto (store/make-store conn) trainer/bootstrap!)
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        _ (coll-app/adicionar-pokemon!
           s chat player
           {:species "Squirtle" :nome "Squirtle" :nivel 10
            :hp 100 :ataque 60 :defesa 70 :atq-esp 60 :def-esp 65 :veloc 50
            :tipos ["water"] :habilidade "torrent"
            :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 40 :classe "especial"}]}
           100 nil)
        _ (reward-app/admin-seed-reward!
           s {:operation-id "smoke-balls"
              :chat-id chat :player-id player :save-id "a"
              :item {:item-id "pokebola" :qty 5}})
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        get! (fn [path] (http client base token "GET" path nil))
        post! (fn [path body] (http client base token "POST" path body))]
    (try
      (let [saves (get! (str "/v1/saves?chatId=" chat "&playerId=" player))
            fence (get-in saves [:body "data" "fenceGeneration"])
            start (post! "/v1/battles/start"
                         {"chatId" chat "playerId" player "saveId" "a"
                          "fenceGeneration" fence
                          "operationId" "smoke-start"
                          "battleId" "smoke-bid"
                          "collectionIndex" 0
                          "hpX" 100 "hpO" 1
                          "pokemonX" {"nome" "Squirtle" "hp" 100 "ataque" 200 "defesa" 70
                                      "atq-esp" 200 "def-esp" 65 "veloc" 50
                                      "tipos" ["water"] "nivel" 10
                                      "golpes" [{"nome-exibicao" "Jato" "tipo" "water"
                                                 "poder" 80 "classe" "especial"}]}
                          "pokemonO" {"nome" "Magikarp" "raridade" "comum" "hp" 1
                                      "ataque" 10 "defesa" 1 "atq-esp" 15 "def-esp" 1
                                      "veloc" 80 "tipos" ["water"] "nivel" 5
                                      "golpes" [{"nome-exibicao" "Splash" "tipo" "normal"
                                                 "poder" 0 "classe" "status"}]}})
            _ (assert! (= 200 (:status start)) (str "start " start))
            turn (post! "/v1/battles/turn"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" fence
                         "operationId" "smoke-win"
                         "battleId" "smoke-bid"
                         "expectedTurnIndex" 0
                         "action" "attack"
                         "moveIndex" 0
                         "rng" {"damage-var" 10 "crit" 99 "flee" 99 "accuracy" 0
                                "contagio" 99 "status-golpe" 99 "wild-move" 0
                                "impede-para" 99}})
            _ (assert! (= 200 (:status turn)) (str "turn " turn))
            _ (assert! (true? (get-in turn [:body "data" "aguardandoCaptura"]))
                       (str "aguardando " turn))
            enc (get! (str "/v1/encounter?chatId=" chat "&playerId=" player))
            _ (assert! (some? (get-in enc [:body "data" "encounter"])) (str "enc " enc))
            cap (post! "/v1/encounter/capture"
                       {"chatId" chat "playerId" player "saveId" "a"
                        "fenceGeneration" fence
                        "operationId" "smoke-cap"
                        "battleId" "smoke-bid"
                        "bola" "pokebola"
                        "roll" 0})
            _ (assert! (= 200 (:status cap)) (str "cap " cap))
            _ (assert! (true? (get-in cap [:body "data" "capturou"])) (str "capturou " cap))
            retry (post! "/v1/encounter/capture"
                         {"chatId" chat "playerId" player "saveId" "a"
                          "fenceGeneration" fence
                          "operationId" "smoke-cap"
                          "battleId" "smoke-bid"
                          "bola" "pokebola"
                          "roll" 0})
            _ (assert! (true? (get-in retry [:body "data" "duplicate"]))
                       (str "idem " retry))
            col (get! (str "/v1/collection?chatId=" chat "&playerId=" player))]
        (assert! (= 2 (get-in col [:body "data" "count"])) (str "colecao " col))
        (println "OK bridge M4.4 encounter/capture"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.4 encounter")
  (let [sum (t/run-tests 'pokemon.tests.encounter-m44-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.4 battle")
  (let [sum (t/run-tests 'pokemon.tests.battle-m34-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.3 capture")
  (let [sum (t/run-tests 'pokemon.tests.capture-m33-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.4")
  (bridge-flow!)
  (println "==> OK M4.4")
  (System/exit 0))
