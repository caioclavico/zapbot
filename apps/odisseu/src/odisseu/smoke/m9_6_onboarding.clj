(ns odisseu.smoke.m9-6-onboarding
  "Smoke M9.6 — onboarding HTTP + testes de domínio."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.onboarding-m96-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m96-smoke")
(def ^:private storage-rel ".datomic-local/m96-smoke")

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
        _ (persistence/delete-db! client-db "bridge-onboard")
        conn (persistence/ensure-db! client-db "bridge-onboard")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m96-http"
        player "ash"
        token "m96-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store s :allow-test-rng true})
        hc (HttpClient/newHttpClient)
        base (:base-url http-srv)]
    (try
      (let [st0 (http hc base token "GET"
                      (str "/v1/onboarding?chatId=" chat "&playerId=" player) nil)
            _ (assert! (= 200 (:status st0)) (str "onboarding get " st0))
            _ (assert! (= "not_started" (get-in st0 [:body "data" "status"]))
                       "status inicial")
            start (http hc base token "POST" "/v1/onboarding/start"
                        {"chatId" chat "playerId" player
                         "operationId" "smoke-kit"})
            _ (assert! (= 200 (:status start)) (str "start " start))
            _ (assert! (= "awaiting_starter"
                          (get-in start [:body "data" "onboarding" "status"]))
                       "após kit")
            starter (http hc base token "POST" "/v1/onboarding/starter"
                          {"chatId" chat "playerId" player
                           "operationId" "smoke-starter"
                           "choice" "2"})
            _ (assert! (= 200 (:status starter)) (str "starter " starter))
            _ (assert! (= "charmander"
                          (get-in starter [:body "data" "species"]))
                       "species")
            dup (http hc base token "POST" "/v1/onboarding/starter"
                      {"chatId" chat "playerId" player
                       "operationId" "smoke-starter"
                       "choice" "2"})
            _ (assert! (= 200 (:status dup)) (str "dup " dup))
            _ (assert! (true? (get-in dup [:body "data" "duplicate"])) "recibo")
            coll (ports/list-collection s chat player "a")
            inv (ports/list-inventory s chat player "a")
            pokebolas (->> inv (filter #(= "pokebola" (:item-id %)))
                           (map :qty) (reduce + 0))]
        (assert! (= 1 (count coll)) "coleção")
        (assert! (= 10 pokebolas) "kit")
        (let [health (http hc base token "GET" "/health" nil)
              caps (set (or (get-in health [:body "data" "capabilities"])
                            (get-in health [:body "capabilities"])
                            []))]
          (assert! (contains? caps "onboarding-start") (str "cap start " health))
          (assert! (contains? caps "onboarding-starter") (str "cap starter " health)))
        (println "OK bridge M9.6 onboarding start/starter"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M9.6 onboarding")
  (let [sum (t/run-tests 'pokemon.tests.onboarding-m96-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M9.6")
  (bridge-flow!)
  (println "==> OK M9.6")
  (System/exit 0))
