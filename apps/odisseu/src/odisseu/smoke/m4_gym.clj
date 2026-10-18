(ns odisseu.smoke.m4-gym
  "Smoke M4.7 — ginásios leitura + time (sem desafio WhatsApp)."
  (:require [clojure.test :as t]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.tests.gym-m47-test]
            [pokemon.tests.gym-m35-test])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m47-smoke")
(def ^:private storage-rel ".datomic-local/m47-smoke")

(defn- assert! [ok msg]
  (when-not ok (throw (ex-info msg {}))))

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
        _ (persistence/delete-db! client-db "bridge-gym")
        conn (persistence/ensure-db! client-db "bridge-gym")
        s (doto (store/make-store conn) trainer/bootstrap!)
        chat "chat-m47"
        player "ash"
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        _ (doseq [n ["A" "B" "C"]]
            (collection/adicionar-pokemon!
             s chat player
             {:nome n :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
              :tipos ["normal"] :nivel 10
              :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]}
             80 nil))
        token "m47-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        http (HttpClient/newHttpClient)]
    (try
      (let [base (:base-url http-srv)
            ov (get! http base token
                     (str "/v1/gyms?chatId=" chat "&playerId=" player))]
        (assert! (= 200 (:status ov)) (str "gyms " ov))
        (assert! (false? (get-in ov [:body "data" "desafioHabilitado"]))
                 "desafio deve estar desligado")
        (let [fence (long (:fence-generation
                           (pokemon.application.ports/get-player s chat player) 0))]
          (tx/set-gym-team!
           s {:chat-id chat :player-id player :save-id "a"
              :fence-generation fence :indices [0 1 2]}))
        (let [team (reads/gym-team s chat player)]
          (assert! (true? (:completo? team)) "time completo"))
        (println "OK bridge M4.7 gyms read + team"))
      (finally
        (bridge/stop! http-srv)))))
(defn -main
  [& _]
  (println "==> Unit M4.7 gym")
  (let [sum (t/run-tests 'pokemon.tests.gym-m47-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Regressão M3.5 gym")
  (let [sum (t/run-tests 'pokemon.tests.gym-m35-test)]
    (when (or (pos? (:fail sum 0)) (pos? (:error sum 0)))
      (System/exit 1)))
  (println "==> Smoke HTTP M4.7")
  (bridge-flow!)
  (println "==> OK M4.7")
  (System/exit 0))
