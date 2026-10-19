(ns pokemon.tests.migration-m55-test
  "M5.5 — Joy importada como enfermaria-bed; coleta/equivalência/isolamento."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]
            [pokemon.infrastructure.http.json :as json]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fix]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.import :as mig]
            [pokemon.migration.inventory :as inv]
            [pokemon.migration.match :as match]
            [pokemon.migration.transform :as transform])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private system "odisseu-m55-tests")
(def ^:private storage ".datomic-local/m55-tests")

(defn- boot! [db-name]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db-name)
    (doto (store/make-store (persistence/ensure-db! client db-name))
      trainer/bootstrap!)))

(defn- reopen! [db-name]
  (doto (store/make-store
         (persistence/ensure-db!
          (persistence/client {:system system :storage-dir storage})
          db-name))
    trainer/bootstrap!))

(defn- import-fixture!
  [s batch]
  (let [dump (fix/build-cassandra-dump)
        ir (transform/transform-export (export/from-fixture-dump dump))
        m (match/match-inventory (inv/inventory-dump dump))
        _ (gates/assert-importable! ir m)
        result (mig/import-ir! s ir {:import-batch-id batch
                                     :require-match-resolved? true
                                     :match-report m})]
    {:ir ir :dump dump :match m :result result}))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- http [client base token method path body]
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

(deftest joy-sete-leitos-apos-import
  (let [s (boot! "m55-beds")
        {:keys [ir]} (import-fixture! s "m55-beds-batch")
        beds (ports/list-enfermaria s fix/chat-id fix/player-id "a")
        col (ports/list-collection s fix/chat-id fix/player-id "a")
        ir-joy (->> (:pokemon (first (filter #(= "a" (:slot-id %)) (:saves ir))))
                    (filter (fn [p]
                              (let [loc (or (:location p) :equipe)]
                                (= "enfermaria"
                                   (name (if (keyword? loc) loc (keyword (str loc)))))))))
        bed-ids (set (map #(get-in % [:pokemon :id-pokemon]) beds))
        ir-ids (set (map :id-pokemon ir-joy))
        bed-prontos (set (map :pronto-em beds))
        ir-prontos (set (map :pronto-em ir-joy))
        st (reads/joy-status s fix/chat-id fix/player-id {:agora-ms 0})]
    (is (= 7 (count beds)))
    (is (= 19 (count col)))
    (is (= ir-ids bed-ids))
    (is (= ir-prontos bed-prontos))
    (is (= 7 (count (:enfermaria st))))
    (is (every? #(string? (:origin-edn (:pokemon %))) beds))
    (is (:ok? (eq/compare-ir s ir)))))

(deftest joy-collect-pronto-uma-vez-e-antes-rejeita
  (let [db "m55-collect"
        s (boot! db)
        _ (import-fixture! s "m55-collect-batch")
        beds0 (ports/list-enfermaria s fix/chat-id fix/player-id "a")
        min-pronto (apply min (map :pronto-em beds0))
        early-code (try
                     (tx/joy-collect!
                      s {:chat-id fix/chat-id :player-id fix/player-id :save-id "a"
                         :fence-generation (fence s fix/chat-id fix/player-id)
                         :operation-id "m55-early"
                         :agora-ms (dec min-pronto)})
                     (catch Exception ex (err/error-code ex)))
        ok (tx/joy-collect!
            s {:chat-id fix/chat-id :player-id fix/player-id :save-id "a"
               :fence-generation (fence s fix/chat-id fix/player-id)
               :operation-id "m55-collect-1"
               :agora-ms (inc (apply max (map :pronto-em beds0)))})
        dup (tx/joy-collect!
             s {:chat-id fix/chat-id :player-id fix/player-id :save-id "a"
                :fence-generation (fence s fix/chat-id fix/player-id)
                :operation-id "m55-collect-1"
                :agora-ms (inc (apply max (map :pronto-em beds0)))})
        beds1 (ports/list-enfermaria s fix/chat-id fix/player-id "a")
        col1 (ports/list-collection s fix/chat-id fix/player-id "a")
        s2 (reopen! db)
        beds2 (ports/list-enfermaria s2 fix/chat-id fix/player-id "a")
        col2 (ports/list-collection s2 fix/chat-id fix/player-id "a")]
    (is (= :joy_nothing_ready early-code))
    (is (= 7 (:n ok)))
    (is (true? (:duplicate dup)))
    (is (= 0 (count beds1)))
    (is (= 26 (count col1)))
    (is (every? #(= "equipe" (:location %)) col1))
    (is (every? #(nil? (:pronto-em %)) col1))
    (is (= 0 (count beds2)))
    (is (= 26 (count col2)))))

(deftest joy-isolamento-a-b-a
  (let [s (boot! "m55-aba")
        _ (import-fixture! s "m55-aba-batch")
        ids-a0 (set (map #(get-in % [:pokemon :id-pokemon])
                         (ports/list-enfermaria s fix/chat-id fix/player-id "a")))
        _ (trainer/switch-save! s fix/chat-id fix/player-id "b")
        beds-b (ports/list-enfermaria s fix/chat-id fix/player-id "b")
        joy-b (reads/joy-status s fix/chat-id fix/player-id {:agora-ms 0})
        _ (trainer/switch-save! s fix/chat-id fix/player-id "a")
        ids-a1 (set (map #(get-in % [:pokemon :id-pokemon])
                         (ports/list-enfermaria s fix/chat-id fix/player-id "a")))]
    (is (= 0 (count beds-b)))
    (is (= 0 (count (:enfermaria joy-b))))
    (is (= ids-a0 ids-a1))
    (is (= 7 (count ids-a1)))))

(deftest joy-bridge-http-lista-sete
  (let [s (boot! "m55-http")
        _ (import-fixture! s "m55-http-batch")
        token "m55-token"
        http-srv (bridge/start! {:host "127.0.0.1" :port 0 :token token :store s})
        base (:base-url http-srv)
        client (HttpClient/newHttpClient)
        q (str "?chatId=" fix/chat-id "&playerId=" fix/player-id)]
    (try
      (let [joy (http client base token "GET" (str "/v1/joy" q) nil)
            beds (get-in joy [:body "data" "enfermaria"])]
        (is (= 200 (:status joy)))
        (is (= 7 (count beds)))
        (is (every? #(contains? % "prontoEm") beds))
        (is (every? #(get-in % ["pokemon" "species"]) beds)))
      (finally
        (bridge/stop! http-srv)))))

(deftest reimport-nao-duplica-leitos
  (let [s (boot! "m55-reimp")
        {:keys [ir]} (import-fixture! s "m55-reimp-batch")
        r2 (mig/reimport-same-batch! s ir "m55-reimp-batch")
        beds (ports/list-enfermaria s fix/chat-id fix/player-id "a")]
    (is (every? #(= :duplicate (:status %)) r2))
    (is (= 7 (count beds)))
    (is (= 7 (count (set (map :bed-id beds)))))))
