(ns pokemon.tests.raid-m49-test
  "M4.9 — bridge de raids (join/attack/capture) + regressões de contrato."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m49-tests")
(def ^:private storage-rel ".datomic-local/m49-tests")

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
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}
            {:nome-exibicao "Rest" :tipo "psiquico" :poder 0 :classe :status}]})

(def ^:private chefe
  {:nome "Snorlax" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
   :tipos ["normal"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil)
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-ball-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "ultra-bola" :qty 3}}))

(defn- open!
  [s chat rid]
  (tx/open-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "open-" rid)
      :raid-id rid :ginasio "pedra" :liga "bronze" :chefe chefe
      :agora-ms 1000}))

(deftest leitura-raid-ativa
  (let [s (fresh-store! "r49-read")
        chat "chat-r"]
    (boot! s chat "ash")
    (is (nil? (:raid (reads/active-raid s chat "ash" {}))))
    (open! s chat "raid-r")
    (let [dto (reads/active-raid s chat "ash" {:agora-ms 1001})]
      (is (= "inscricoes" (get-in dto [:raid :fase])))
      (is (= "bronze" (get-in dto [:raid :liga])))
      (is (false? (get-in dto [:raid :inscrito?]))))))

(deftest join-auto-e-liga
  (let [s (fresh-store! "r49-join")
        chat "chat-j"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open! s chat "raid-j")
    (let [j (tx/join-raid!
             s {:chat-id chat :player-id "ash" :save-id "a"
                :fence-generation (fence s chat "ash")
                :operation-id "join-auto" :auto? true :nome "Ash"
                :agora-ms 1002})]
      (is (= 1 (get-in j [:raid :n-participantes]))))
    ;; nv.20 vs liga iniciante → mismatch
    (let [bad (try
                (raid-app/open-raid!
                 s chat "ash"
                 {:operation-id "open-bad" :raid-id "raid-bad"
                  :ginasio "agua" :liga "iniciante" :chefe chefe :agora 2000})
                (raid-app/join-raid!
                 s chat "misty"
                 {:operation-id "join-bad" :raid-id "raid-bad"
                  :collection-index 0 :agora 2001})
                (catch Exception ex (err/error-code ex)))]
      (is (= :raid_liga_mismatch bad)))))

(deftest ciclo-bridge-vitoria-captura-idempotente
  (let [s (fresh-store! "r49-win")
        chat "chat-w"
        rid "raid-w"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open! s chat rid)
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "j-a" :collection-index 0 :nome "Ash" :agora-ms 2})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "j-m" :collection-index 0 :nome "Misty" :agora-ms 3})
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "st" :agora-ms 4})
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)]
        (when (and (< n 40) (= "combate" (:fase r)))
          (let [pid (:vez r)]
            (tx/raid-attack!
             s {:chat-id chat :player-id pid :save-id "a"
                :fence-generation (fence s chat pid)
                :operation-id (str "atk-" n)
                :expected-turn-index (:turn-index r)
                :move-index 0 :agora-ms (+ 10 n)})
            (recur (inc n))))))
    (let [r (raid-app/get-raid s rid)
          ash (ports/get-save s chat "ash" "a")]
      (is (= "vitoria" (:fase r)))
      (is (= 50 (:coins ash)))
      (is (= 6 (:pe-raids ash))))
    ;; retry do mesmo ataque (operationId) após vitória — recebimento idempotente
    (let [dup (tx/raid-attack!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "atk-0"
                  :expected-turn-index 0 :move-index 0 :agora-ms 10})]
      (is (true? (:duplicate dup))))
    (let [antes (count (ports/list-collection s chat "ash" "a"))
          cap (tx/raid-capture!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "cap-1" :bola "ultra-bola" :roll 0
                  :agora-ms 100})
          cap2 (tx/raid-capture!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "cap-1" :bola "ultra-bola" :roll 0
                   :agora-ms 100})]
      (is (true? (:capturou cap)))
      (is (true? (:duplicate cap2)))
      (is (= (inc antes) (count (ports/list-collection s chat "ash" "a")))))))

(deftest switch-ab-bloqueia-ataque
  (let [s (fresh-store! "r49-ab")
        chat "chat-ab"
        rid "raid-ab"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open! s chat rid)
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "ja" :collection-index 0 :agora-ms 2})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jm" :collection-index 0 :agora-ms 3})
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "st-ab" :agora-ms 4})
    (trainer/switch-save! s chat "ash" "b")
    (let [r (raid-app/get-raid s rid)
          code (try
                 (tx/raid-attack!
                  s {:chat-id chat :player-id "ash" :save-id "b"
                     :fence-generation (fence s chat "ash")
                     :operation-id "atk-ab"
                     :expected-turn-index (:turn-index r)
                     :move-index 0 :agora-ms 5})
                 (catch Exception ex (err/error-code ex)))]
      (is (contains? #{:raid_save_mismatch :stale_writer_fence} code)))))

(deftest participante-sem-dano-sem-recompensa
  (is (false? (raid/elegivel-recompensa? {:dano 0})))
  (is (true? (raid/elegivel-recompensa? {:dano 1}))))
