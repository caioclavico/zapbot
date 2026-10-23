(ns pokemon.tests.storage-a552-test
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]))

(deftest shiny-e-espaco-usam-datomic-local
  (let [client (persistence/client {:system (str "a552-" (java.util.UUID/randomUUID))
                                    :storage-dir (str "/tmp/a552-storage-" (java.util.UUID/randomUUID))})
        conn (persistence/ensure-db! client "core")
        s (store/make-store conn)]
    (ports/ensure-schema! s)
    (trainer/create-trainer! s {:chat-id "chat" :player-id "ash"})
    (let [p (ports/get-player s "chat" "ash")
          ctx {:chat-id "chat" :player-id "ash" :save-id "a"
               :fence-generation (:fence-generation p)}]
      (ports/add-to-collection! s ctx {:id-pokemon "shiny-1" :species "pikachu" :nome "Pikachu"
                                       :shiny true :nivel 5 :hp 20 :hp-atual 20 :xp 0 :amizade 80})
      (ports/add-to-collection! s (assoc (ports/get-player s "chat" "ash")
                                         :chat-id "chat" :player-id "ash" :save-id "a")
                                        {:id-pokemon "plain-1" :species "rattata" :nome "Rattata"
                                         :shiny false :nivel 3 :hp 15 :hp-atual 15 :xp 0 :amizade 80})
      (let [collection (reads/collection s "chat" "ash" "a")
            shop (reads/shop-catalog s "chat" "ash")]
        (is (= [true false] (mapv :shiny (:collection collection))))
        (is (= 2 (:ocupacao-pokemon shop)))
        (is (= 30 (:capacidade-pokemon shop)))))))
