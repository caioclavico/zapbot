(ns odisseu.smoke.m4-joy-eggs
  "Smoke M4.10 — Joy + ovos na bridge + regressões M3.7."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.collection :as collection]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.joy :as joy]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.joy-eggs-m410-test]
            [pokemon.tests.adventure-m37-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m410-smoke")
(def ^:private storage-rel ".datomic-local/m410-smoke")

(defn- assert! [ok msg]
  (when-not ok (throw (ex-info msg {}))))

(defn- http
  [client base token method path body]
  (let [bldr (-> (HttpRequest/newBuilder)
                 (.uri (URI/create (str base path)))
                 (.timeout (Duration/ofSeconds 30))
                 (.header "Authorization" (str "Bearer " token)))
        bldr (if body
               (-> bldr
                   (.header "Content-Type" "application/json")
                   (.method method (HttpRequest$BodyPublishers/ofString
                                   (json/write-str body))))
               (.method bldr method (HttpRequest$BodyPublishers/noBody)))
        res (.send client (.build bldr) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn bridge-flow!
  []
  (let [client-db (persistence/client {:system system :storage-dir storage-rel})
        _ (persistence/delete-db! client-db "bridge-joy")
        conn (persistence/ensure-db! client-db "bridge-joy")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m410"
        _ (trainer/create-trainer! s {:chat-id chat :player-id "ash"})
        _ (collection/adicionar-pokemon!
           s chat "ash"
           {:nome "Rattata" :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20
            :veloc 40 :tipos ["normal"] :nivel 5 :raridade "comum"
            :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40
                      :classe :fisico}]}
           10 "envenenado")
        token "m410-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store s :allow-test-rng true})
        hc (HttpClient/newHttpClient)
        base (:base-url http-srv)
        fence (long (:fence-generation (ports/get-player s chat "ash") 0))
        t0 (System/currentTimeMillis)]
    (try
      (let [joy (http hc base token "GET"
                      (str "/v1/joy?chatId=" chat "&playerId=ash") nil)
            _ (assert! (= 200 (:status joy)) (str "joy " joy))
            _ (assert! (pos? (count (get-in joy [:body "data" "feridos"])))
                       "feridos")
            send (http hc base token "POST" "/v1/joy/send"
                       {"chatId" chat "playerId" "ash" "saveId" "a"
                        "fenceGeneration" fence
                        "operationId" "smoke-js"
                        "collectionIndex" 0
                        "agoraMs" t0})
            _ (assert! (= 200 (:status send)) (str "send " send))
            grant (http hc base token "POST" "/v1/adventure/eggs/grant"
                        {"chatId" chat "playerId" "ash" "saveId" "a"
                         "fenceGeneration" fence
                         "operationId" "smoke-g"
                         "eggId" "egg-smoke" "category" "comum"
                         "species" "Pichu" "agoraMs" t0})
            _ (assert! (= 200 (:status grant)) (str "grant " grant))
            incu (http hc base token "POST" "/v1/adventure/eggs/incubate"
                       {"chatId" chat "playerId" "ash" "saveId" "a"
                        "fenceGeneration" fence
                        "operationId" "smoke-i"
                        "eggId" "egg-smoke" "agoraMs" t0})
            _ (assert! (= 200 (:status incu)) (str "incu " incu))
            adv (http hc base token "GET"
                      (str "/v1/adventure?chatId=" chat "&playerId=ash&agoraMs=" t0)
                      nil)
            due (+ t0 (* 2 60 60 1000))
            hatch (http hc base token "POST" "/v1/adventure/eggs/hatch"
                        {"chatId" chat "playerId" "ash" "saveId" "a"
                         "fenceGeneration" fence
                         "operationId" "smoke-h"
                         "eggId" "egg-smoke" "hatchId" "h-smoke"
                         "agoraMs" due})
            collect (http hc base token "POST" "/v1/joy/collect"
                          {"chatId" chat "playerId" "ash" "saveId" "a"
                           "fenceGeneration" fence
                           "operationId" "smoke-jc"
                           "agoraMs" (+ t0 joy/tempo-tratamento-ms)})]
        (assert! (= 200 (:status adv)) (str "adv " adv))
        (assert! (= 200 (:status hatch)) (str "hatch " hatch))
        (assert! (= 200 (:status collect)) (str "collect " collect))
        (assert! (= 1 (get-in collect [:body "data" "n"])) "collect n")
        (println "OK bridge M4.10 joy/ovos"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.10 joy/ovos")
  (let [sum (t/run-tests 'pokemon.tests.joy-eggs-m410-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.7 adventure/joy")
  (let [sum (t/run-tests 'pokemon.tests.adventure-m37-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.10")
  (bridge-flow!)
  (println "==> OK M4.10")
  (System/exit 0))
