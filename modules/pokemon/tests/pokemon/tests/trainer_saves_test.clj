(ns pokemon.tests.trainer-saves-test
  "Testes M2 — núcleo transacional treinador/saves (dados fictícios)."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.bridge-tx :as bridge]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m2-tests")
(def ^:private storage-rel ".datomic-local/m2-tests")

(defn- fresh-conn!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (persistence/ensure-db! client db-name)))

(defn- open-conn
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/ensure-db! client db-name)))

(defn- boot!
  [db-name]
  (let [conn (fresh-conn! db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(deftest cria-treinador-e-dois-saves
  (let [s (boot! "t-create")
        p (trainer/create-trainer! s {:chat-id "chat-ficticio-1"
                                      :player-id "player-ash"})]
    (is (= "a" (:active-save-id p)))
    (is (some? (trainer/snapshot-save s "chat-ficticio-1" "player-ash" "a")))
    (is (some? (trainer/snapshot-save s "chat-ficticio-1" "player-ash" "b")))
    (is (= 0 (:coins (:save (trainer/snapshot-save s "chat-ficticio-1" "player-ash" "a")))))
    (is (= 0 (:coins (:save (trainer/snapshot-save s "chat-ficticio-1" "player-ash" "b")))))))

(deftest saves-independentes-shiny-e-progresso
  (let [s (boot! "t-independent")
        chat "chat-ficticio-2"
        player "player-misty"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    ;; Shiny só no save A
    (trainer/admin-seed-reward!
     s {:operation-id "op-shiny-a"
        :chat-id chat :player-id player :save-id "a"
        :coins-delta 50
        :pokemon {:species "Magikarp" :shiny true :level 5 :xp 100}
        :item {:item-id "poke-ball" :qty 3}})
    ;; Progresso diferente em B (seed admin explícito — sem fence)
    (trainer/admin-seed-reward!
     s {:operation-id "op-progress-b"
        :chat-id chat :player-id player :save-id "b"
        :coins-delta 200
        :trainer-xp-delta 500
        :trainer-level 4
        :item {:item-id "great-ball" :qty 1}})
    (let [a (trainer/snapshot-save s chat player "a")
          b (trainer/snapshot-save s chat player "b")]
      (is (= 50 (:coins (:save a))))
      (is (= 200 (:coins (:save b))))
      (is (= 1 (count (:party a))))
      (is (true? (:shiny (first (:party a)))))
      (is (= "Magikarp" (:species (first (:party a)))))
      (is (empty? (:party b)))
      (is (= 1 (:trainer-level (:save a))))
      (is (= 4 (:trainer-level (:save b))))
      (is (= 500 (:trainer-xp (:save b))))
      (is (= [{:item-id "poke-ball" :qty 3}] (:inventory a)))
      (is (= [{:item-id "great-ball" :qty 1}] (:inventory b))))))

(deftest alternancia-a-b-a-preserva-saves
  (let [s (boot! "t-switch")
        chat "chat-ficticio-3"
        player "player-brock"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (trainer/admin-seed-reward!
     s {:operation-id "op-a-coins" :chat-id chat :player-id player :save-id "a"
        :coins-delta 10
        :pokemon {:species "Onix" :shiny false}})
    (trainer/admin-seed-reward!
     s {:operation-id "op-b-coins" :chat-id chat :player-id player :save-id "b"
        :coins-delta 99})
    (let [to-b (trainer/switch-save! s chat player "b")
          to-a (trainer/switch-save! s chat player "a")
          snap-a (trainer/snapshot-save s chat player "a")
          snap-b (trainer/snapshot-save s chat player "b")]
      (is (= "b" (get-in to-b [:player :active-save-id])))
      (is (= "a" (get-in to-a [:player :active-save-id])))
      (is (= 10 (:coins (:save snap-a))))
      (is (= 99 (:coins (:save snap-b))))
      (is (= 1 (count (:party snap-a))))
      (is (empty? (:party snap-b)))
      (is (>= (get-in to-a [:player :fence-generation]) 2)))))

(deftest idempotencia-recompensa
  (let [s (boot! "t-idem")
        chat "chat-ficticio-4"
        player "player-gary"
        req {:operation-id "op-reward-once"
             :chat-id chat :player-id player :save-id "a"
             :coins-delta 100
             :pokemon {:species "Eevee" :shiny false}}]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [r1 (trainer/admin-seed-reward! s req)
          r2 (trainer/admin-seed-reward! s req)
          snap (trainer/snapshot-save s chat player "a")]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 100 (:coins (:save snap))))
      (is (= 1 (count (:party snap)))))))

(deftest atualizacoes-concorrentes-activate-e-reward
  (let [s (boot! "t-concurrent")
        chat "chat-ficticio-5"
        player "player-red"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [switchers (doall
                     (for [i (range 20)]
                       (future
                         (try
                           (trainer/switch-save! s chat player (if (even? i) "b" "a"))
                           :ok
                           (catch Exception _
                             :conflict)))))
          rewards (doall
                   (for [i (range 10)]
                     (future
                       (trainer/admin-seed-reward!
                        s {:operation-id (str "op-coin-" i)
                           :chat-id chat :player-id player :save-id "a"
                           :coins-delta 1}))))
          switch-results (mapv deref switchers)
          reward-results (mapv deref rewards)
          snap (trainer/snapshot-save s chat player "a")
          active (ports/active-save-id s chat player)]
      (is (= 10 (count reward-results)))
      (is (every? #(contains? #{true false} (:duplicate %)) reward-results))
      (is (= 10 (:coins (:save snap))))
      (is (contains? #{"a" "b"} active))
      (is (pos? (count (filter #{:ok} switch-results)))))))

(deftest persistencia-apos-reabrir
  (let [db-name "t-persist"
        chat "chat-ficticio-6"
        player "player-leaf"]
    (let [s (boot! db-name)]
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (trainer/admin-seed-reward!
       s {:operation-id "op-persist"
          :chat-id chat :player-id player :save-id "a"
          :coins-delta 77
          :pokemon {:species "Bulbasaur" :shiny true}})
      (trainer/switch-save! s chat player "b"))
    ;; Novo client/conexão — simula reinício do processo JVM
    (let [s (doto (store/make-store (open-conn db-name))
              trainer/bootstrap!)
          p (ports/get-player s chat player)
          snap-a (trainer/snapshot-save s chat player "a")]
      (is (= "b" (:active-save-id p)))
      (is (= 77 (:coins (:save snap-a))))
      (is (true? (:shiny (first (:party snap-a))))))))

(deftest isolamento-dois-jogadores-e-grupo
  (let [s (boot! "t-isolate")
        chat "chat-ficticio-7"]
    (trainer/create-trainer! s {:chat-id chat :player-id "p1"})
    (trainer/create-trainer! s {:chat-id chat :player-id "p2"})
    (trainer/admin-seed-reward!
     s {:operation-id "op-p1" :chat-id chat :player-id "p1" :save-id "a"
        :coins-delta 5 :pokemon {:species "Pikachu"}})
    (trainer/admin-seed-reward!
     s {:operation-id "op-p2" :chat-id chat :player-id "p2" :save-id "a"
        :coins-delta 8})
    (ports/ensure-group! s chat "raid-ficticia")
    (let [s1 (trainer/snapshot-save s chat "p1" "a")
          s2 (trainer/snapshot-save s chat "p2" "a")
          g (ports/get-group s chat)]
      (is (= 5 (:coins (:save s1))))
      (is (= 8 (:coins (:save s2))))
      (is (= 1 (count (:party s1))))
      (is (empty? (:party s2)))
      (is (= "raid-ficticia" (:label g)))
      (is (= 5 (:coins (:save (trainer/snapshot-save s chat "p1" "a"))))))))

(deftest slots-dinamicos-lista-cria-e-renomeia-sem-mover-ativo
  (let [s (boot! "t-dynamic-slots")
        chat "chat-dynamic"
        player "player-slots"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [created (bridge/create-save-slot! s {:chat-id chat :player-id player
                                                :save-id "c"
                                                :display-name "Minha Jornada"})
          listed (bridge/list-saves s chat player)
          renamed (bridge/rename-save! s {:chat-id chat :player-id player
                                          :save-id "c"
                                          :display-name "Jornada 3"
                                          :expected-fence-generation (:fence-generation listed)})
          after (bridge/list-saves s chat player)]
      (is (true? (:created created)))
      (is (= "a" (:active-save-id listed)))
      (is (= ["a" "b" "c"] (mapv :save-id (:saves listed))))
      (is (= "Minha Jornada" (:display-name (some #(when (= "c" (:save-id %)) %) (:saves listed)))))
      (is (= :renamed (:status renamed)))
      (is (= "Jornada 3" (:display-name (some #(when (= "c" (:save-id %)) %) (:saves after)))))
      (is (= "a" (:active-save-id after))))))

(deftest slot-criacao-repetida-e-selecao-preservam-dados
  (let [s (boot! "t-dynamic-retry")
        chat "chat-dynamic-retry"
        player "player-slots-retry"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [r1 (bridge/create-save-slot! s {:chat-id chat :player-id player :save-id "c"})
          r2 (bridge/create-save-slot! s {:chat-id chat :player-id player :save-id "c"})]
      (is (true? (:created r1)))
      (is (false? (:created r2)))
      (trainer/admin-seed-reward!
       s {:operation-id "dynamic-a" :chat-id chat :player-id player :save-id "a"
          :coins-delta 11 :pokemon {:species "Pikachu"}})
      (trainer/admin-seed-reward!
       s {:operation-id "dynamic-c" :chat-id chat :player-id player :save-id "c"
          :coins-delta 33 :pokemon {:species "Eevee"}})
      (is (= "c" (get-in (trainer/switch-save! s chat player "c") [:player :active-save-id])))
      (is (= 33 (:coins (:save (trainer/snapshot-save s chat player "c")))))
      (is (= 11 (:coins (:save (trainer/snapshot-save s chat player "a")))))
      (is (= "a" (get-in (trainer/switch-save! s chat player "a") [:player :active-save-id]))))))
