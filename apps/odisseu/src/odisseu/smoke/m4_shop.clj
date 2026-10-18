(ns odisseu.smoke.m4-shop
  "Smoke M4.11 — loja/itens na bridge."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.shop-m411-test]
            [pokemon.tests.joy-eggs-m410-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m411-smoke")
(def ^:private storage-rel ".datomic-local/m411-smoke")

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
        _ (persistence/delete-db! client-db "bridge-shop")
        conn (persistence/ensure-db! client-db "bridge-shop")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m411"
        _ (trainer/create-trainer! s {:chat-id chat :player-id "ash"})
        _ (reward-app/admin-seed-reward!
           s {:operation-id "seed-m411" :chat-id chat :player-id "ash"
              :save-id "a" :coins-delta 100})
        token "m411-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store s :allow-test-rng true})
        hc (HttpClient/newHttpClient)
        base (:base-url http-srv)
        fence (long (:fence-generation (ports/get-player s chat "ash") 0))]
    (try
      (let [shop (http hc base token "GET"
                       (str "/v1/shop?chatId=" chat "&playerId=ash") nil)
            _ (assert! (= 200 (:status shop)) (str "shop " shop))
            buy (http hc base token "POST" "/v1/shop/buy"
                      {"chatId" chat "playerId" "ash" "saveId" "a"
                       "fenceGeneration" fence
                       "operationId" "smoke-buy" "itemId" "pocao"})
            _ (assert! (= 200 (:status buy)) (str "buy " buy))
            inv (http hc base token "GET"
                      (str "/v1/inventory?chatId=" chat "&playerId=ash") nil)]
        (assert! (= 200 (:status inv)) (str "inv " inv))
        (assert! (= 80 (get-in buy [:body "data" "coins"])) "saldo")
        (assert! (pos? (count (get-in inv [:body "data" "itens"]))) "itens")
        (println "OK bridge M4.11 shop/buy/inventory"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.11 shop")
  (let [sum (t/run-tests 'pokemon.tests.shop-m411-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M4.10 joy/ovos")
  (let [sum (t/run-tests 'pokemon.tests.joy-eggs-m410-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.11")
  (bridge-flow!)
  (println "==> OK M4.11")
  (System/exit 0))
