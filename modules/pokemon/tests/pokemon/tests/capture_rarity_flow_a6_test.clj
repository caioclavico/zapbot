(ns pokemon.tests.capture-rarity-flow-a6-test
  "Integração Datomic descartável: raridade → KO → chance → reopen."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capture :as capture]
            [pokemon.infrastructure.datomic.store :as store])
  (:import [java.nio.file Files]))

(defn- fresh
  []
  (let [dir (str (Files/createTempDirectory "cap-rar-a6-"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))
        client (persistence/client {:system "cap-rar-a6" :storage-dir dir})
        db-name (str "cap-rar-" (java.util.UUID/randomUUID))
        conn (persistence/ensure-db! client db-name)
        s (trainer/bootstrap! (store/make-store conn))]
    (trainer/create-trainer! s {:chat-id "chat-r" :player-id "ash"})
    {:store s :dir dir :client client :db-name db-name}))

(defn- reopen
  [{:keys [client db-name dir]}]
  (let [client (or client (persistence/client {:system "cap-rar-a6" :storage-dir dir}))
        conn (persistence/ensure-db! client db-name)
        s (trainer/bootstrap! (store/make-store conn))]
    {:store s :dir dir :client client :db-name db-name}))

(def ^:private ash-mon
  {:nome "Pikachu" :species "pikachu" :hp 100 :hp-atual 100
   :ataque 80 :defesa 40 :atq-esp 80 :def-esp 40 :veloc 90
   :tipos ["electric"] :nivel 20 :raridade "comum"
   :golpes [{:nome-exibicao "Thunder" :tipo "electric" :poder 90 :classe :especial}]})

(def ^:private lendario-selvagem
  {:nome "Articuno" :species "articuno" :hp 5 :hp-atual 5
   :ataque 85 :defesa 100 :atq-esp 95 :def-esp 125 :veloc 85
   :tipos ["ice" "flying"] :nivel 50 :raridade "lendario" :taxa-captura 3
   :golpes [{:nome-exibicao "Ice Beam" :tipo "ice" :poder 40 :classe :especial}]})

(def ^:private rng-hit
  {:accuracy 0 :dodge 99 :crit 99 :damage-var 0 :multi-hit 0})

(deftest wild-lendario-chance-e-dto-apos-reopen
  (let [ctx (fresh)
        store (:store ctx)
        chat "chat-r"
        player "ash"
        bid "battle-lendario"
        _ (battle-app/start-wild-battle!
           store chat player
           {:operation-id "start-l" :battle-id bid
            :pokemon-x ash-mon :pokemon-o lendario-selvagem
            :hp-x 100 :hp-o 5 :collection-index 0})
        turn (battle-app/confirm-turn!
              store chat player
              {:operation-id "win-l" :battle-id bid
               :expected-turn-index 0 :action :attack :move-index 0
               :rng rng-hit})
        b (:battle turn)
        base (long (:chance-base-captura b 0))
        enc (reads/active-encounter store chat player)
        po (get-in enc [:encounter :pokemon-o])]
    (testing "KO carimba chance de lendário (não comum)"
      (is (true? (:aguardando-captura? b)))
      (is (= "lendario" (get-in b [:pokemons :o :raridade])))
      (is (<= (get capture/raridade-chance-min "lendario")
              base
              (get capture/raridade-chance-max "lendario")))
      (is (< base (get capture/raridade-chance-min "comum"))))
    (testing "DTO Node com raridade canônica"
      (is (= "lendario" (:raridade po)))
      (is (= "snapshot" (:raridade-fonte po)))
      (is (nil? (re-find #"\?" (str (:raridade po))))))
    (testing "reopen JVM / Datomic preserva taxa"
      (let [ctx2 (reopen ctx)
            store2 (:store ctx2)
            b2 (ports/active-battle store2 chat player)
            enc2 (reads/active-encounter store2 chat player)
            base2 (long (:chance-base-captura b2 0))]
        (is (= "lendario" (get-in b2 [:pokemons :o :raridade])))
        (is (= base base2))
        (is (= "lendario" (get-in enc2 [:encounter :pokemon-o :raridade])))
        (is (= base (long (get-in enc2 [:encounter :chance-base-captura] 0))))))))
