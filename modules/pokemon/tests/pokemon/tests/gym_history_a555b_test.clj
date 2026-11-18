(ns pokemon.tests.gym-history-a555b-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]))
(defn- fresh []
  (let [dir (str "/tmp/a555b-gym-" (java.util.UUID/randomUUID))
        c (persistence/client {:system (str "a555b-" (java.util.UUID/randomUUID)) :storage-dir dir})
        conn (persistence/ensure-db! c "core")]
    (schema/install! conn)
    [(store/make-store conn) c conn dir]))

(deftest migration-024-is-idempotent
  (let [[_ _ conn _] (fresh)]
    (schema/install! conn)
    (schema/install! conn)
    (is (some #(= "pokemon/024-gym-history" (:odisseu.schema/migration-id (first %)))
              (d/q '[:find (pull ?e [*]) :where [?e :odisseu.schema/migration-id]]
                   (d/db conn))))))

(deftest gym-history-is-persisted-and-isolated
  (let [[s _ _ _] (fresh)]
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (trainer/create-trainer! s {:chat-id "other" :player-id "ash"})
    (doseq [i (range 3)]
      (collection/adicionar-pokemon! s "chat" "ash"
                                     {:id-pokemon (str "gym-poke-" i) :nome "Pikachu"
                                      :species "pikachu" :nivel 10 :hp 80 :hp-atual 80
                                      :ataque 200 :defesa 40 :atq-esp 200 :def-esp 40 :veloc 50
                                      :tipos ["normal"] :golpes [{:nome-exibicao "Tackle" :poder 40}]}
                                     80 nil))
    (let [fence (:fence-generation (ports/get-player s "chat" "ash"))]
      (gym-app/set-gym-team! s "chat" "ash" [0 1 2])
      (gym-app/resolve-challenge! s "chat" "ash"
                                  {:operation-id "gym-hist-resolve" :gym-id "pedra"
                                   :venceu? true :indices [0 1 2] :participacao {}
                                   :nome "Ash" :dia "2026-10-17" :agora 1000
                                   :pedra-roll 0})
      (let [ranking (reads/gym-ranking s "chat" "ash" "pedra" {:agora-ms 3000})
            history (reads/gym-history s "chat" "ash" "pedra")]
        (is (= "pedra" (:gym-id ranking)))
        (is (= 1 (count (:history history))))
        (is (true? (get-in history [:history 0 :venceu])))
        (gym-app/resolve-challenge! s "chat" "ash"
                                    {:operation-id "gym-hist-resolve" :gym-id "pedra"
                                     :venceu? true :indices [0 1 2] :participacao {}
                                     :nome "Ash" :dia "2026-10-17" :agora 1000
                                     :pedra-roll 0})
        (is (= 1 (count (:history (reads/gym-history s "chat" "ash" "pedra")))))
        (is (empty? (:ranking (reads/gym-ranking s "other" "ash" "pedra" {:agora-ms 3000}))))))))
