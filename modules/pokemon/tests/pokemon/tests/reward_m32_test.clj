(ns pokemon.tests.reward-m32-test
  "M3.2 — segurança transacional de recompensas de gameplay."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m32-tests")
(def ^:private storage-rel ".datomic-local/m32-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- reopen-store
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        conn (persistence/ensure-db! client db-name)
        s (store/make-store conn)]
    (trainer/bootstrap! s)
    s))

(deftest recompensa-no-save-ativo
  (let [s (fresh-store! "r-active")
        chat "chat-r1"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [r (reward-app/apply-gameplay-reward-on-active!
             s chat player
             {:operation-id "op-win-1"
              :coins-delta 25
              :item {:item-id "poke-ball" :qty 2}})
          save (ports/get-save s chat player "a")
          inv (ports/list-inventory s chat player "a")]
      (is (false? (:duplicate r)))
      (is (= "gameplay" (:channel r)))
      (is (= 25 (:coins save)))
      (is (= [{:item-id "poke-ball" :qty 2}] inv)))))

(deftest callback-antigo-a-apos-switch-b
  (let [s (fresh-store! "r-stale")
        chat "chat-r2"
        player "misty"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [writer-a (writer/freeze-writer s chat player)
          _ (trainer/switch-save! s chat player "b")
          code (try
                 (reward-app/apply-gameplay-reward!
                  s writer-a
                  {:operation-id "op-stale-a" :coins-delta 50})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :stale_writer_fence code))
      (is (= 0 (:coins (ports/get-save s chat player "a"))))
      (is (= 0 (:coins (ports/get-save s chat player "b")))))))

(deftest retry-idempotente
  (let [s (fresh-store! "r-idem")
        chat "chat-r3"
        player "brock"
        req {:operation-id "op-idem" :coins-delta 40}]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [r1 (reward-app/apply-gameplay-reward-on-active! s chat player req)
          r2 (reward-app/apply-gameplay-reward-on-active! s chat player req)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= (:payload-hash r1) (:payload-hash r2)))
      (is (= 40 (:coins (ports/get-save s chat player "a")))))))

(deftest mesmo-operation-id-payload-diferente
  (let [s (fresh-store! "r-mismatch")
        chat "chat-r4"
        player "gary"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/apply-gameplay-reward-on-active!
     s chat player {:operation-id "op-once" :coins-delta 10})
    (let [code (try
                 (reward-app/apply-gameplay-reward-on-active!
                  s chat player {:operation-id "op-once" :coins-delta 99})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :receipt_payload_mismatch code))
      (is (= 10 (:coins (ports/get-save s chat player "a")))))))

(deftest duas-recompensas-concorrentes
  (let [s (fresh-store! "r-conc")
        chat "chat-r5"
        player "red"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (let [futs (doall
                (for [i (range 8)]
                  (future
                    (reward-app/apply-gameplay-reward-on-active!
                     s chat player
                     {:operation-id (str "op-c-" i) :coins-delta 1}))))
          results (mapv deref futs)]
      (is (every? #(false? (:duplicate %)) results))
      (is (= 8 (:coins (ports/get-save s chat player "a")))))))

(deftest isolamento-outro-jogador
  (let [s (fresh-store! "r-iso")
        chat "chat-r6"]
    (trainer/create-trainer! s {:chat-id chat :player-id "p1"})
    (trainer/create-trainer! s {:chat-id chat :player-id "p2"})
    (reward-app/apply-gameplay-reward-on-active!
     s chat "p1" {:operation-id "op-p1" :coins-delta 7})
    (reward-app/apply-gameplay-reward-on-active!
     s chat "p2" {:operation-id "op-p2" :coins-delta 3})
    (is (= 7 (:coins (ports/get-save s chat "p1" "a"))))
    (is (= 3 (:coins (ports/get-save s chat "p2" "a"))))))

(deftest persistencia-apos-reabrir
  (let [db "r-persist"
        chat "chat-r7"
        player "leaf"]
    (let [s (fresh-store! db)]
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      (reward-app/apply-gameplay-reward-on-active!
       s chat player
       {:operation-id "op-persist-g"
        :coins-delta 15
        :pokemon {:species "Oddish" :shiny false :level 2}})
      (trainer/switch-save! s chat player "b"))
    (let [s (reopen-store db)
          save-a (ports/get-save s chat player "a")
          party (ports/list-party s chat player "a")
          retry (reward-app/apply-gameplay-reward-on-active!
                 ;; B ativo: retry do op de A não deve aplicar em B;
                 ;; mas o recibo global já existe → duplicate no save A original
                 s chat player
                 {:operation-id "op-persist-g" :coins-delta 15
                  :pokemon {:species "Oddish" :shiny false :level 2}})]
      ;; Save ativo é B; retry com mesmo payload encontra recibo → duplicate
      (is (true? (:duplicate retry)))
      (is (= "b" (ports/active-save-id s chat player)))
      (is (= 15 (:coins save-a)))
      (is (= 1 (count party)))
      (is (= 0 (:coins (ports/get-save s chat player "b")))))))

(deftest grant-reward-legado-bloqueado
  (let [s (fresh-store! "r-block")
        chat "chat-r8"
        player "oak"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (is (= :use_explicit_reward_api
           (try
             (trainer/grant-reward! s {:operation-id "x" :chat-id chat
                                       :player-id player :save-id "a"
                                       :coins-delta 1})
             (catch Exception ex (err/error-code ex)))))))
