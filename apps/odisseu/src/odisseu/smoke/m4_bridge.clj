(ns odisseu.smoke.m4-bridge
  "Smoke M4.1: sobe bridge loopback, semeia dados fictícios, valida leituras HTTP,
  mede RSS/latência. Sem Node/Chromium neste runner (há script Node separado)."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as coll-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json])
  (:import [java.lang.management ManagementFactory]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers]
           [java.time Instant Duration]))

(defn- heap-stats
  []
  (let [rt (Runtime/getRuntime)
        mb (fn [n] (long (/ n (* 1024 1024))))]
    {:heap-used-mb (mb (- (.totalMemory rt) (.freeMemory rt)))
     :heap-total-mb (mb (.totalMemory rt))
     :heap-max-mb (mb (.maxMemory rt))}))

(defn- rss-mb
  []
  (try
    (let [pid (first (str/split (.getName (ManagementFactory/getRuntimeMXBean)) #"@"))
          proc (.. (ProcessBuilder. ["ps" "-o" "rss=" "-p" pid])
                   (redirectErrorStream true)
                   start)
          out (str/trim (slurp (.getInputStream proc)))]
      (.waitFor proc)
      (when (re-matches #"\d+" out)
        (long (/ (Long/parseLong out) 1024))))
    (catch Exception _ nil)))

(defn- http-get
  [client base token path]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create (str base path)))
                (.timeout (Duration/ofSeconds 5))
                (.header "Authorization" (str "Bearer " token))
                (.header "Accept" "application/json")
                (.GET)
                .build)
        t0 (System/nanoTime)
        res (.send client req (HttpResponse$BodyHandlers/ofString))
        ms (/ (double (- (System/nanoTime) t0)) 1e6)
        body (json/read-str (.body res))]
    {:status (.statusCode res) :body body :ms ms}))

(defn- assert!
  [cond msg]
  (when-not cond
    (throw (ex-info msg {}))))

(defn -main
  [& _]
  (let [system "odisseu-m41-tests"
        storage ".datomic-local/m41-tests"
        db "bridge-smoke"
        token "m41-smoke-token"
        chat "chat-smoke-m41"
        player "player-smoke"
        client-db (persistence/client {:system system :storage-dir storage})
        _ (persistence/delete-db! client-db db)
        conn (persistence/ensure-db! client-db db)
        s (doto (store/make-store conn) trainer/bootstrap!)
        _ (trainer/create-trainer! s {:chat-id chat :player-id player})
        _ (coll-app/adicionar-pokemon!
           s chat player
           {:species "Squirtle" :nome "Squirtle" :shiny true :nivel 8
            :hp 44 :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43
            :tipos ["água"] :golpes [] :raridade "comum"}
           44 nil)
        _ (coll-app/creditar-moedas! s chat player 77 "smoke-m41-coins")
        _ (coll-app/ajustar-inventario! s chat player "great-ball" 2 "smoke-m41-item")
        mem-before (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))
        http (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        base (:base-url http)
        http-client (HttpClient/newHttpClient)
        q (str "?chatId=" chat "&playerId=" player)
        health (http-get http-client base token "/health")
        trainer-r (http-get http-client base token (str "/v1/trainer" q))
        coll-r (http-get http-client base token (str "/v1/collection" q))
        save-r (http-get http-client base token (str "/v1/active-save" q))
        ;; POST deve ser rejeitado
        post-status (let [req (-> (HttpRequest/newBuilder)
                                  (.uri (URI/create (str base "/v1/trainer" q)))
                                  (.timeout (Duration/ofSeconds 5))
                                  (.header "Authorization" (str "Bearer " token))
                                  (.POST (java.net.http.HttpRequest$BodyPublishers/noBody))
                                  .build)
                          res (.send http-client req (HttpResponse$BodyHandlers/ofString))]
                      (.statusCode res))
        admin-status (let [req (-> (HttpRequest/newBuilder)
                                   (.uri (URI/create (str base "/admin/seed")))
                                   (.timeout (Duration/ofSeconds 5))
                                   (.header "Authorization" (str "Bearer " token))
                                   (.GET)
                                   .build)
                           res (.send http-client req (HttpResponse$BodyHandlers/ofString))]
                       (.statusCode res))
        mem-after (assoc (heap-stats) :rss-mb (rss-mb) :at (str (Instant/now)))]
    (try
      (assert! (= 200 (:status health)) "health")
      (assert! (true? (get-in health [:body "ok"])) "health.ok")
      (assert! (= 200 (:status trainer-r)) "trainer")
      (assert! (= "a" (get-in trainer-r [:body "data" "activeSaveId"])) "activeSaveId")
      (assert! (= 200 (:status coll-r)) "collection")
      (assert! (= 1 (get-in coll-r [:body "data" "count"])) "collection.count")
      (assert! (= "Squirtle" (get-in coll-r [:body "data" "collection" 0 "species"])) "species")
      (assert! (= 200 (:status save-r)) "active-save")
      (assert! (= 77 (get-in save-r [:body "data" "save" "coins"])) "coins")
      (let [u (http-get http-client base "wrong" (str "/v1/trainer" q))]
        (assert! (= 401 (:status u)) "trainer unauthorized"))
      (assert! (= 405 post-status) "post rejected")
      (assert! (= 404 admin-status) "admin rejected")
      (let [summary {:ok true
                     :latencies-ms {:health (:ms health)
                                    :trainer (:ms trainer-r)
                                    :collection (:ms coll-r)
                                    :active-save (:ms save-r)}
                     :memory {:before mem-before :after mem-after}
                     :base-url base
                     :java (System/getProperty "java.version")
                     :nota "JVM+Datomic+HTTP loopback; sem Node/Chromium neste smoke."}]
        (println "=== M4.1 bridge smoke ===")
        (pprint/pprint summary)
        (bridge/stop! http)
        (shutdown-agents)
        (System/exit 0))
      (catch Throwable t
        (bridge/stop! http)
        (println "=== M4.1 bridge smoke FAILED ===")
        (pprint/pprint {:ok false :error (ex-message t) :data (ex-data t)})
        (shutdown-agents)
        (System/exit 1)))))
