(ns pokemon.tests.team-a552b-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.collection :as collection]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.infrastructure.datomic.store :as store]))

(defn- fresh []
  (let [client (persistence/client {:system (str "a552b-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a552b-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "teams")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (doseq [[i n] (map-indexed vector ["A" "B" "C" "D"])]
      (collection/adicionar-pokemon! s "chat" "ash"
                                     {:id-pokemon (str "team-poke-" i) :species (str/lower-case n)
                                      :nome n :nivel 5 :hp 20 :hp-atual 20 :xp 0}
                                     20 nil))
    s))

(deftest named-team-persists-by-pokemon-id
  (let [s (fresh)
        p (ports/get-player s "chat" "ash")
        ctx {:chat-id "chat" :player-id "ash" :save-id "a"
             :fence-generation (:fence-generation p)}
        req {:operation-id "team-save-1" :name "Liga 1" :indices [0 1 2]}]
    (is (string? (-> (ports/save-named-team! s ctx req) :pokemon-ids first)))
    (is (true? (:duplicate (ports/save-named-team! s ctx req))))
    (let [teams (ports/list-named-teams s "chat" "ash" "a")]
      (is (= 1 (count teams)))
      (is (= [0 1 2] (:indices (first teams))))
      (is (true? (:available? (first teams)))))
    (is (= "iniciante"
           (:league-id (ports/select-league! s ctx {:operation-id "league-select-1"
                                                     :league-id "iniciante"}))))
    (is (= "liga" (:destination (ports/use-named-team! s ctx {:operation-id "team-use-1"
                                                                  :name "Liga 1" :destination "liga"}))))
    (is (= "bronze"
           (:league-id (ports/select-league! s ctx {:operation-id "league-select-2"
                                                     :league-id "bronze"}))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/use-named-team! s ctx {:operation-id "team-use-ineligible"
                                                :name "Liga 1" :destination "liga"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ports/select-league! s ctx {:operation-id "league-select-invalid"
                                               :league-id "master"})))
    (is (:deleted (ports/delete-named-team! s ctx {:operation-id "team-del-1" :name "Liga 1"})))
    (is (empty? (ports/list-named-teams s "chat" "ash" "a")))
    (let [csv (reads/team-csv s "chat" "ash" "a")]
      (is (= "text/csv" (:mime-type csv)))
      (is (= "meu-time-pokemon.csv" (:filename csv)))
      (is (.startsWith ^String (:base64 csv) "77u/")))))
