(ns pokemon.tests.raid-release-datomic-a6-test
  "Integração: !pk raid liberar — por jogador, idempotente, preserva recompensas."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.battle-idle-store :as idle-store]))

(def ^:private system "odisseu-raid-release-a6")
(def ^:private storage-rel ".datomic-local/raid-release-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Brick Break" :tipo "fighting" :poder 80 :classe :fisico}
            {:nome-exibicao "Rest" :tipo "psiquico" :poder 0 :classe :status}]})

(def ^:private chefe
  {:nome "Dragonite" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
   :tipos ["rock"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil)
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-ball-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "ultra-bola" :qty 5}}))

(defn- win-raid!
  [s chat rid]
  (tx/open-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "open-" rid)
      :raid-id rid :ginasio "pedra" :liga "bronze" :chefe chefe
      :agora-ms 1000})
  (tx/join-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "j-a-" rid) :collection-index 0 :nome "Ash" :agora-ms 2})
  (tx/join-raid!
   s {:chat-id chat :player-id "misty" :save-id "a"
      :fence-generation (fence s chat "misty")
      :operation-id (str "j-m-" rid) :collection-index 0 :nome "Misty" :agora-ms 3})
  (tx/start-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "st-" rid) :agora-ms 4})
  (loop [n 0]
    (let [r (raid-app/get-raid s rid)]
      (when (and (< n 40) (= "combate" (:fase r)))
        (let [pid (:vez r)]
          (tx/raid-attack!
           s {:chat-id chat :player-id pid :save-id "a"
              :fence-generation (fence s chat pid)
              :operation-id (str "atk-" rid "-" n)
              :expected-turn-index (:turn-index r)
              :move-index 0 :agora-ms (+ 10 n)})
          (recur (inc n))))))
  (raid-app/get-raid s rid))

(deftest liberar-por-jogador-preserva-recompensas
  (let [s (fresh-store! "rel-1")
        chat "c-rel"
        rid "raid-rel-1"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (let [r (win-raid! s chat rid)
          ash0 (ports/get-save s chat "ash" "a")
          misty0 (ports/get-save s chat "misty" "a")
          agora 100]
      (is (= "vitoria" (:fase r)))
      (is (pos? (:coins ash0)))
      (is (true? (raid/captura-pendente? r "ash" agora)))
      (is (true? (raid/captura-pendente? r "misty" agora)))
      (let [rel (tx/raid-release!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "rel-ash" :raid-id rid :agora-ms agora})
            r2 (raid-app/get-raid s rid)
            ash1 (ports/get-save s chat "ash" "a")
            inv (ports/list-inventory s chat "ash" "a")
            bolas (->> inv (filter #(= "ultra-bola" (:item-id %))) (map :qty) (reduce + 0))]
        (is (true? (:liberou rel)))
        (is (false? (raid/captura-pendente? r2 "ash" agora)))
        (is (true? (raid/captura-pendente? r2 "misty" agora))
            "liberação de Ash não afeta Misty")
        (is (= (:coins ash0) (:coins ash1)))
        (is (= (:pe-raids ash0) (:pe-raids ash1)))
        (is (= 5 bolas) "não consome Pokébola")
        (is (some? (ports/active-raid s chat)) "ponteiro permanece enquanto Misty captura"))
      (testing "idempotência"
        (let [dup (tx/raid-release!
                   s {:chat-id chat :player-id "ash" :save-id "a"
                      :fence-generation (fence s chat "ash")
                      :operation-id "rel-ash" :raid-id rid :agora-ms agora})]
          (is (true? (:duplicate dup)))))
      (testing "Misty libera → ponteiro limpo"
        (let [_ (tx/raid-release!
                 s {:chat-id chat :player-id "misty" :save-id "a"
                    :fence-generation (fence s chat "misty")
                    :operation-id "rel-misty" :raid-id rid :agora-ms agora})
              r3 (raid-app/get-raid s rid)]
          (is (false? (raid/algum-captura-pendente? r3 agora)))
          (is (nil? (ports/active-raid s chat)))
          (is (= (:coins misty0) (:coins (ports/get-save s chat "misty" "a")))))))))


(deftest skip-turn-datomic-preserva-pokemon-ativo-no-painel
  (let [s (fresh-store! "skip-panel-1")
        chat "c-skip"
        rid "raid-skip-panel-1"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "open-skip-panel" :raid-id rid
        :ginasio "pedra" :liga "bronze" :chefe chefe :agora-ms 1000})
    (doseq [[pid nome t] [["ash" "Ash" 2] ["misty" "Misty" 3]]]
      (tx/join-raid!
       s {:chat-id chat :player-id pid :save-id "a"
          :fence-generation (fence s chat pid)
          :operation-id (str "join-skip-" pid)
          :collection-index 0 :nome nome :agora-ms t}))
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "start-skip-panel" :agora-ms 4})
    (let [before (raid-app/get-raid s rid)
          texp (:turn-expires-at before)
          current (:vez before)]
      (is (some? texp) "raid cooperativa deve ter timeout de turno")
      (let [result (idle-store/skip-turn! (:conn s) rid texp)
            next-player (:next-player result)
            active (get-in result [:raid :participantes next-player :pokemon])
            after (raid-app/get-raid s rid)]
        (is (= current (:skipped-player result)))
        (is (not= current next-player))
        (is (= next-player (:vez after)))
        (is (= "Snorlax" (:nome active))
            "não enviar o envelope __raid-party-v1 como Pokémon")
        (is (seq (:golpes active))
            "painel de skip precisa dos golpes do Pokémon ativo")
        (is (= (get-in before [:participantes current :hp])
               (get-in after [:participantes current :hp]))
            "skip não deve provocar dano")))))
