(ns odisseu.smoke.m4-hunt
  "Smoke M4.5: caça → batalha → captura + regressões."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.hunt-m45-test]
            [pokemon.tests.encounter-m44-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(defn- http
  [client base token method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 15))
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
  (when-not cond (throw (ex-info msg {}))))

(defn- bridge-flow!
  []
  (let [system "odisseu-m45-smoke"
        storage ".datomic-local/m45-smoke"
        db "bridge-hunt"
        token "m45-smoke-token"
        chat "chat-smoke-m45"
        player "player-smoke"
        client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db db)
        conn (persistence/ensure-db! client-db db)
        s (doto (store/make-store conn) trainer/bootstrap!)
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        _ (coll-app/adicionar-pokemon!
           s chat player
           {:species "Squirtle" :nome "Squirtle" :nivel 10
            :hp 100 :ataque 200 :defesa 70 :atq-esp 200 :def-esp 65 :veloc 50
            :tipos ["water"] :habilidade "torrent"
            :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe "especial"}]}
           100 nil)
        _ (reward-app/admin-seed-reward!
           s {:operation-id "smoke-balls-m45"
              :chat-id chat :player-id player :save-id "a"
              :item {:item-id "pokebola" :qty 5}})
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store s :allow-test-rng true})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        get! (fn [path] (http client base token "GET" path nil))
        post! (fn [path body] (http client base token "POST" path body))
        cand {"nome" "Magikarp" "species" "magikarp" "slug-especie" "magikarp"
              "hp" 5 "ataque" 10 "defesa" 1 "atq-esp" 10 "def-esp" 1 "veloc" 80
              "tipos" ["water"] "nivel" 1 "taxa-captura" 255
              "golpes" [{"slug" "splash" "nome-exibicao" "Splash" "tipo" "normal"
                         "poder" 0 "classe" "status"}]}]
    (try
      (let [plan (get! (str "/v1/hunt/plan?chatId=" chat "&playerId=" player
                            "&areaId=praia&rollTipos=50&rollSurto=99"))
            _ (assert! (= 200 (:status plan)) (str "plan " plan))
            data (get-in plan [:body "data"])
            fence (get data "fenceGeneration")
            start (post! "/v1/hunt/start"
                         {"chatId" chat "playerId" player "saveId" "a"
                          "fenceGeneration" fence
                          "operationId" "smoke-hunt"
                          "areaId" (get-in data ["area" "id"])
                          "climaId" (get-in data ["clima" "id"])
                          "tiposEncontro" (get data "tiposEncontro")
                          "surto" false
                          "poderAlvo" (get data "poderAlvo")
                          "shinyRoll" 1
                          "candidates" [cand]})
            _ (assert! (= 200 (:status start)) (str "start " start))
            bid (get-in start [:body "data" "battleId"])
            turn (post! "/v1/battles/turn"
                        {"chatId" chat "playerId" player "saveId" "a"
                         "fenceGeneration" fence
                         "operationId" "smoke-win-hunt"
                         "battleId" bid
                         "expectedTurnIndex" 0
                         "action" "attack"
                         "moveIndex" 0
                         "rng" {"damage-var" 10 "crit" 99 "flee" 99 "accuracy" 0
                                "contagio" 99 "status-golpe" 99 "wild-move" 0
                                "impede-para" 99}})
            _ (assert! (true? (get-in turn [:body "data" "aguardandoCaptura"]))
                       (str "win " turn))
            cap (post! "/v1/encounter/capture"
                       {"chatId" chat "playerId" player "saveId" "a"
                        "fenceGeneration" fence
                        "operationId" "smoke-cap-hunt"
                        "battleId" bid
                        "bola" "pokebola"
                        "roll" 0})
            _ (assert! (true? (get-in cap [:body "data" "capturou"]))
                       (str "cap " cap))
            retry (post! "/v1/hunt/start"
                         {"chatId" chat "playerId" player "saveId" "a"
                          "fenceGeneration" fence
                          "operationId" "smoke-hunt"
                          "areaId" (get-in data ["area" "id"])
                          "tiposEncontro" (get data "tiposEncontro")
                          "surto" false
                          "poderAlvo" (get data "poderAlvo")
                          "shinyRoll" 1
                          "candidates" [cand]})]
        (assert! (true? (get-in retry [:body "data" "duplicate"]))
                 (str "idem " retry))
        (println "OK bridge M4.5 hunt→battle→capture"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.5 hunt")
  (let [sum (t/run-tests 'pokemon.tests.hunt-m45-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M4.4 encounter")
  (let [sum (t/run-tests 'pokemon.tests.encounter-m44-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.5")
  (bridge-flow!)
  (println "==> OK M4.5")
  (System/exit 0))
