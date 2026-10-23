(ns pokemon.tests.profile-a551-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.profile :as profile]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.profile-store :as profile-store]))

(deftest amizade-usa-faixas-do-legado
  (is (= "ainda se conhecendo" (:label (profile/friendship-band 70))))
  (is (= "amigo" (:label (profile/friendship-band 100))))
  (is (= "grande amigo" (:label (profile/friendship-band 160))))
  (is (= "melhor amigo" (:label (profile/friendship-band 220)))))

(deftest titulos-respeitam-requisitos
  (is (empty? (profile/available-titles {})))
  (is (= "primeira-vitoria"
         (:id (first (profile/available-titles {:vitorias-treinador 1})))))
  (is (= "capturador"
         (:id (first (profile/available-titles {:maior-sequencia-capturas 3})))))
  (is (nil? (profile/title-at {} 0))))

(deftest familia-do-professor-preserva-cadeia-legada
  (is (= "pidgey" (profile/family "Pidgeotto")))
  (is (= "pikachu" (profile/family "pikachu"))))

(deftest migration-021-e-professor-persistem-em-datomic-local
  (let [storage (str "/tmp/a551-profile-" (java.util.UUID/randomUUID))
        client (persistence/client {:system (str "a551-" (java.util.UUID/randomUUID))
                                    :storage-dir storage})
        conn (persistence/ensure-db! client "core")
        store (store/make-store conn)]
    (ports/ensure-schema! store)
    (ports/ensure-schema! store)
    (is (some? (d/pull (d/db conn) '[:db/ident] :save/professor-cards-edn)))
    (trainer/create-trainer! store {:chat-id "chat" :player-id "ash"})
    (let [p (ports/get-player store "chat" "ash")
          w {:chat-id "chat" :player-id "ash" :save-id "a"
             :fence-generation (:fence-generation p)}]
      (ports/add-to-collection! store w {:id-pokemon "p1" :species "pidgey"
                                         :nome "Pidgey" :nivel 5 :xp 0
                                         :xp-desde-nivel 0 :hp 20 :hp-atual 20
                                         :amizade 70 :in-party true})
      (let [p2 (ports/get-player store "chat" "ash")
            ctx (assoc w :fence-generation (:fence-generation p2)
                       :operation-id "prof-send-1")
            sent (profile-store/professor-send! conn ctx 0 1000)
            confirmed (profile-store/professor-confirm!
                       conn (assoc ctx :operation-id "prof-confirm-1") (:token (:summary sent)) 1001)]
        (is (= "p1" (:id-pokemon (:summary confirmed))))
        (is (empty? (ports/list-collection store "chat" "ash" "a")))
        (is (= {"pidgey" 1}
               (:cards (profile-store/professor-cards conn "chat" "ash" "a"))))))))
