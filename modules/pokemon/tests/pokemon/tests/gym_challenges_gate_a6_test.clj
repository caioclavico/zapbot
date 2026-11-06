(ns pokemon.tests.gym-challenges-gate-a6-test
  "Gate POKEMON_GYM_CHALLENGES_ENABLED — contenção sem derrubar consultas."
  (:require [clojure.test :refer [deftest is testing]]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.infrastructure.datomic.store :as store]
            [odisseu.persistence.datomic :as persistence]))

(def ^:private system "odisseu-gym-challenges-gate-a6")
(def ^:private storage-rel ".datomic-local/gym-challenges-gate-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(deftest challenges-enabled-default-true
  (is (true? (gym/challenges-enabled?))))

(deftest challenges-enabled-false-env
  (with-redefs [gym/challenges-enabled? (fn []
                                          (let [v "false"]
                                            (not (#{"0" "false" "no" "off"} v))))]
    (is (false? (gym/challenges-enabled?)))))

(deftest overview-respeita-gate
  (let [s (fresh-store! "gate-ov")
        chat "c" player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (with-redefs [gym/challenges-enabled? (constantly false)]
      (let [ov (reads/gyms-overview s chat player {})]
        (is (false? (:desafio-habilitado ov)))
        (is (some? (:desafio-bloqueio ov)))))
    (with-redefs [gym/challenges-enabled? (constantly true)]
      (is (true? (:desafio-habilitado (reads/gyms-overview s chat player {})))))))

(deftest start-challenge-bloqueado-pelo-gate
  (let [s (fresh-store! "gate-start")
        chat "c" player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (doseq [n ["A" "B" "C"]]
      (collection/adicionar-pokemon!
       s chat player
       {:nome n :hp 80 :ataque 50 :defesa 40 :atq-esp 50 :def-esp 40 :veloc 40
        :tipos ["normal"] :nivel 10
        :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]}
       80 nil))
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (long (:fence-generation (ports/get-player s chat player) 0))
        :indices [0 1 2]})
    (with-redefs [gym/challenges-enabled? (constantly false)]
      (let [code (try
                   (tx/start-gym-challenge!
                    s {:chat-id chat :player-id player :save-id "a"
                       :fence-generation (long (:fence-generation (ports/get-player s chat player) 0))
                       :operation-id "blocked"
                       :gym-id "pedra"
                       :candidates [{:nome "G" :hp 10 :hp-atual 10 :ataque 1 :defesa 1
                                     :atq-esp 1 :def-esp 1 :veloc 1 :tipos ["rock"] :nivel 1
                                     :golpes [{:nome-exibicao "X" :tipo "rock" :poder 10 :classe :fisico}]}
                                    {:nome "G2" :hp 10 :hp-atual 10 :ataque 1 :defesa 1
                                     :atq-esp 1 :def-esp 1 :veloc 1 :tipos ["rock"] :nivel 1
                                     :golpes [{:nome-exibicao "X" :tipo "rock" :poder 10 :classe :fisico}]}
                                    {:nome "G3" :hp 10 :hp-atual 10 :ataque 1 :defesa 1
                                     :atq-esp 1 :def-esp 1 :veloc 1 :tipos ["rock"] :nivel 1
                                     :golpes [{:nome-exibicao "X" :tipo "rock" :poder 10 :classe :fisico}]}]})
                   (catch Exception ex (err/error-code ex)))]
        (is (= :gym_challenges_disabled code))))))
