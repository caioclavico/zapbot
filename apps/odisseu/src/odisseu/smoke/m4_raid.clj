(ns odisseu.smoke.m4-raid
  "Smoke M4.9 — raids na bridge + regressões M3.6/M4.8."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.collection :as collection]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.raid-m49-test]
            [pokemon.tests.raid-m36-test]
            [pokemon.tests.gym-m48-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m49-smoke")
(def ^:private storage-rel ".datomic-local/m49-smoke")

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

(def ^:private chefe
  {"nome" "Snorlax" "hp" 100 "ataque" 50 "defesa" 50 "atq-esp" 50 "def-esp" 50
   "veloc" 30 "tipos" ["normal"] "nivel" 1 "raridade" "raro"
   "golpes" [{"nome-exibicao" "Tackle" "tipo" "normal" "poder" 40 "classe" "fisico"}]})

(defn bridge-flow!
  []
  (let [client-db (persistence/client {:system system :storage-dir storage-rel})
        _ (persistence/delete-db! client-db "bridge-raid")
        conn (persistence/ensure-db! client-db "bridge-raid")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m49"
        _ (doseq [p ["ash" "misty"]]
            (trainer/create-trainer! s {:chat-id chat :player-id p})
            (collection/adicionar-pokemon!
             s chat p
             {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50
              :veloc 40 :tipos ["normal"] :nivel 20 :raridade "raro"
              :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80
                        :classe :fisico}]}
             200 nil))
        token "m49-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token
                                 :store s :allow-test-rng true})
        hc (HttpClient/newHttpClient)
        base (:base-url http-srv)
        fence-ash (long (:fence-generation (ports/get-player s chat "ash") 0))
        fence-misty (long (:fence-generation (ports/get-player s chat "misty") 0))]
    (try
      (let [open (http hc base token "POST" "/v1/raids/open"
                       {"chatId" chat "playerId" "ash" "saveId" "a"
                        "fenceGeneration" fence-ash
                        "operationId" "smoke-open"
                        "ginasio" "pedra" "liga" "bronze" "chefe" chefe})
            _ (assert! (= 200 (:status open)) (str "open " open))
            join-a (http hc base token "POST" "/v1/raids/join"
                         {"chatId" chat "playerId" "ash" "saveId" "a"
                          "fenceGeneration" fence-ash
                          "operationId" "smoke-ja" "auto" true "nome" "Ash"})
            join-m (http hc base token "POST" "/v1/raids/join"
                         {"chatId" chat "playerId" "misty" "saveId" "a"
                          "fenceGeneration" fence-misty
                          "operationId" "smoke-jm" "auto" true "nome" "Misty"})
            _ (assert! (= 200 (:status join-a)) (str "join-a " join-a))
            _ (assert! (= 200 (:status join-m)) (str "join-m " join-m))
            start (http hc base token "POST" "/v1/raids/start"
                        {"chatId" chat "playerId" "ash" "saveId" "a"
                         "fenceGeneration" fence-ash
                         "operationId" "smoke-start"})
            active (http hc base token "GET"
                         (str "/v1/raids/active?chatId=" chat "&playerId=ash")
                         nil)]
        (assert! (= 200 (:status start)) (str "start " start))
        (assert! (= 200 (:status active)) (str "active " active))
        (assert! (= "combate" (get-in active [:body "data" "raid" "fase"]))
                 "fase combate")
        (println "OK bridge M4.9 raid open/join/start"))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.9 raid")
  (let [sum (t/run-tests 'pokemon.tests.raid-m49-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.6 raid")
  (let [sum (t/run-tests 'pokemon.tests.raid-m36-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M4.8 gym battle")
  (let [sum (t/run-tests 'pokemon.tests.gym-m48-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.9")
  (bridge-flow!)
  (println "==> OK M4.9")
  (System/exit 0))
