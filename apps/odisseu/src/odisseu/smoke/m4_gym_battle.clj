(ns odisseu.smoke.m4-gym-battle
  "Smoke M4.8 — combate 3×3 + regressões M4.7/M3.5."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.gym-m48-test]
            [pokemon.tests.gym-m47-test]
            [pokemon.tests.gym-m35-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m48-smoke")
(def ^:private storage-rel ".datomic-local/m48-smoke")

(defn- assert! [ok msg]
  (when-not ok (throw (ex-info msg {}))))

(defn- post!
  [client base token path body]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base path)))
                (.timeout (Duration/ofSeconds 30))
                (.header "Authorization" (str "Bearer " token))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (json/write-str body)))
                (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn- get!
  [client base token path]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base path)))
                (.timeout (Duration/ofSeconds 15))
                (.header "Authorization" (str "Bearer " token))
                (.GET)
                (.build))
        res (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode res)
     :body (json/read-str (.body res))}))

(defn bridge-flow!
  []
  (let [client-db (persistence/client {:system system :storage-dir storage-rel})
        _ (persistence/delete-db! client-db "bridge-gym-battle")
        conn (persistence/ensure-db! client-db "bridge-gym-battle")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m48"
        player "ash"
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        _ (doseq [n ["A" "B" "C"]]
            (collection/adicionar-pokemon!
             s chat player
             {:nome n :hp 200 :ataque 200 :defesa 50 :atq-esp 200 :def-esp 50 :veloc 50
              :tipos ["normal"] :nivel 10
              :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40
                        :classe :fisico :slug "tackle"}]}
             200 nil))
        token "m48-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        http (HttpClient/newHttpClient)]
    (try
      (let [base (:base-url http-srv)
            ov (get! http base token
                     (str "/v1/gyms?chatId=" chat "&playerId=" player))]
        (assert! (= 200 (:status ov)) (str "gyms " ov))
        (assert! (true? (get-in ov [:body "data" "desafioHabilitado"]))
                 "desafio deve estar habilitado")
        (let [fence (long (:fence-generation (ports/get-player s chat player) 0))]
          (tx/set-gym-team!
           s {:chat-id chat :player-id player :save-id "a"
              :fence-generation fence :indices [0 1 2]})
          (let [start (post! http base token "/v1/gyms/challenge"
                             {"chatId" chat "playerId" player "saveId" "a"
                              "fenceGeneration" fence
                              "operationId" "smoke-challenge"
                              "gymId" "pedra"
                              "candidates"
                              [{"nome" "Geodude" "hp" 5 "ataque" 10 "defesa" 40
                                "atq-esp" 10 "def-esp" 40 "veloc" 40 "tipos" ["rock"]
                                "nivel" 10 "golpes" [{"nome-exibicao" "Investida"
                                                      "tipo" "normal" "poder" 40
                                                      "classe" "fisico"}]}
                               {"nome" "Sandshrew" "hp" 5 "ataque" 10 "defesa" 40
                                "atq-esp" 10 "def-esp" 40 "veloc" 40 "tipos" ["ground"]
                                "nivel" 10 "golpes" [{"nome-exibicao" "Investida"
                                                      "tipo" "normal" "poder" 40
                                                      "classe" "fisico"}]}
                               {"nome" "Onix" "hp" 5 "ataque" 10 "defesa" 40
                                "atq-esp" 10 "def-esp" 40 "veloc" 40 "tipos" ["rock"]
                                "nivel" 10 "golpes" [{"nome-exibicao" "Investida"
                                                      "tipo" "normal" "poder" 40
                                                      "classe" "fisico"}]}]})]
            (assert! (= 200 (:status start)) (str "challenge " start))
            (assert! (= "gym" (get-in start [:body "data" "battle" "kind"]))
                     "kind gym"))
          (println "OK bridge M4.8 gym challenge start")))
      (finally
        (bridge/stop! http-srv)))))

(defn -main
  [& _]
  (println "==> Unit M4.8 gym battle")
  (let [sum (t/run-tests 'pokemon.tests.gym-m48-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M4.7 gym")
  (let [sum (t/run-tests 'pokemon.tests.gym-m47-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.5 gym")
  (let [sum (t/run-tests 'pokemon.tests.gym-m35-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.8")
  (bridge-flow!)
  (println "==> OK M4.8")
  (System/exit 0))
