(ns pokemon.tests.raid-m36-test
  "M3.6 Parte B — raids: ciclo, fence, dano idempotente, recompensas."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m36-raid")
(def ^:private storage-rel ".datomic-local/m36-tests")

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

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player}))

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}
            {:nome-exibicao "Rest" :tipo "psiquico" :poder 0 :classe :status}]})

(def ^:private chefe
  {:nome "Snorlax" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
   :tipos ["normal"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- seed-mon! [s chat player]
  (collection/adicionar-pokemon! s chat player mon 200 nil))

(defn- open-and-join2!
  [s chat rid]
  (boot! s chat "ash")
  (boot! s chat "misty")
  (seed-mon! s chat "ash")
  (seed-mon! s chat "misty")
  (raid-app/open-raid!
   s chat "ash"
   {:operation-id (str "open-" rid)
    :raid-id rid :ginasio "pedra" :nome-ginasio "Pedra"
    :nivel-ginasio 10 :liga "iniciante" :chefe chefe
    :agora 1000})
  (raid-app/join-raid!
   s chat "ash"
   {:operation-id (str "join-ash-" rid) :raid-id rid
    :collection-index 0 :nome "Ash" :agora 1001})
  (raid-app/join-raid!
   s chat "misty"
   {:operation-id (str "join-misty-" rid) :raid-id rid
    :collection-index 0 :nome "Misty" :agora 1002})
  (raid-app/start-raid!
   s chat "ash"
   {:operation-id (str "start-" rid) :raid-id rid :agora 1003}))

(deftest equivalencia-constantes-raid
  (is (= 220 raid/hp-por-jogador))
  (is (= 50 raid/recompensa-moedas))
  (is (= 6 raid/recompensa-pe))
  (is (= 6 raid/recompensa-xp-pokemon))
  (is (= 35 (raid/chance-captura "raro")))
  (is (= 8 (raid/chance-captura "lendario")))
  (let [dano (raid/calcular-dano
              {:poder 80 :classe :fisico}
              {:ataque 80}
              {:hp-chefe 440 :defesa 50.0})]
    (is (<= 10 dano 160))))

(deftest ciclo-vitoria-e-recompensa
  (let [s (fresh-store! "r-win")
        chat "chat-rw"
        rid "raid-win-1"]
    (open-and-join2! s chat rid)
    (let [r0 (raid-app/get-raid s rid)]
      (is (= "combate" (:fase r0)))
      (is (= 440 (:hp-chefe r0)))
      (is (= "ash" (:vez r0))))
    ;; ataca até vencer (ataque forte)
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)]
        (when (and (< n 40) (= "combate" (:fase r)))
          (let [pid (:vez r)
                turn (:turn-index r)]
            (raid-app/attack!
             s chat pid
             {:operation-id (str "atk-" n)
              :raid-id rid
              :expected-turn-index turn
              :move-index 0
              :agora (+ 2000 n)})
            (recur (inc n))))))
    (let [r (raid-app/get-raid s rid)
          ash (ports/get-save s chat "ash" "a")
          misty (ports/get-save s chat "misty" "a")]
      (is (= "vitoria" (:fase r)))
      (is (true? (:rewards-paid? r)))
      (is (= 50 (:coins ash)))
      (is (= 6 (:pe-raids ash)))
      ;; misty só ganha se causou dano
      (if (pos? (get-in r [:participantes "misty" :dano] 0))
        (do (is (= 50 (:coins misty)))
            (is (= 6 (:pe-raids misty))))
        (do (is (= 0 (:coins misty 0)))
            (is (= 0 (:pe-raids misty 0))))))))

(deftest dano-duplicado-idempotente
  (let [s (fresh-store! "r-idem")
        chat "chat-ri"
        rid "raid-idem"]
    (open-and-join2! s chat rid)
    (let [req {:operation-id "atk-once" :raid-id rid
               :expected-turn-index 0 :move-index 0 :agora 3000}
          r1 (raid-app/attack! s chat "ash" req)
          r2 (raid-app/attack! s chat "ash" req)
          raid (raid-app/get-raid s rid)]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= (:dano r1) (get-in raid [:participantes "ash" :dano])))
      (is (= "misty" (:vez raid))))))

(deftest stale-turn-index
  (let [s (fresh-store! "r-stale-turn")
        chat "chat-st"
        rid "raid-st"]
    (open-and-join2! s chat rid)
    (let [code (try
                 (raid-app/attack!
                  s chat "ash"
                  {:operation-id "bad-turn" :raid-id rid
                   :expected-turn-index 5 :move-index 0 :agora 3000})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :stale_turn_index code)))))

(deftest switch-save-bloqueia-ataque
  (let [s (fresh-store! "r-fence")
        chat "chat-rf"
        rid "raid-fence"]
    (open-and-join2! s chat rid)
    (trainer/switch-save! s chat "ash" "b")
    (let [code (try
                 (raid-app/attack!
                  s chat "ash"
                  {:operation-id "atk-after-switch" :raid-id rid
                   :expected-turn-index 0 :move-index 0 :agora 3000})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      ;; writer no save B ≠ carimbo A, ou fence stale
      (is (contains? #{:raid_save_mismatch :stale_writer_fence :writer_fence_conflict}
                     code)))))

(deftest callback-stale-apos-freeze
  (let [s (fresh-store! "r-cb")
        chat "chat-cb"
        rid "raid-cb"]
    (open-and-join2! s chat rid)
    (let [w (writer/freeze-writer s chat "ash")]
      (trainer/switch-save! s chat "ash" "b")
      (let [code (try
                   (raid-app/attack!
                    s w
                    {:operation-id "stale-atk" :raid-id rid
                     :expected-turn-index 0 :move-index 0 :agora 3000})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code))))))

(deftest captura-pos-vitoria
  (let [s (fresh-store! "r-cap")
        chat "chat-cap"
        rid "raid-cap"]
    (open-and-join2! s chat rid)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-ball" :chat-id chat :player-id "ash" :save-id "a"
        :item {:item-id "pokebola" :qty 5}})
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)]
        (when (and (< n 40) (= "combate" (:fase r)))
          (let [pid (:vez r)]
            (raid-app/attack!
             s chat pid
             {:operation-id (str "c-atk-" n) :raid-id rid
              :expected-turn-index (:turn-index r)
              :move-index 0 :agora (+ 5000 n)})
            (recur (inc n))))))
    (let [antes (count (ports/list-collection s chat "ash" "a"))
          r (raid-app/capture!
             s chat "ash"
             {:operation-id "cap-1" :raid-id rid
              :bola "pokebola" :roll 0 :agora 9000})
          depois (ports/list-collection s chat "ash" "a")]
      (is (true? (:sucesso? r)))
      (is (= (inc antes) (count depois))))))

(deftest persistencia-reabrir-raid
  (let [db "r-persist"
        chat "chat-rp"
        rid "raid-persist"]
    (let [s (fresh-store! db)]
      (open-and-join2! s chat rid)
      (raid-app/attack!
       s chat "ash"
       {:operation-id "pers-atk" :raid-id rid
        :expected-turn-index 0 :move-index 0 :agora 4000}))
    (let [s (reopen-store db)
          r (raid-app/get-raid s rid)
          retry (raid-app/attack!
                 s chat "ash"
                 {:operation-id "pers-atk" :raid-id rid
                  :expected-turn-index 0 :move-index 0 :agora 4000})]
      (is (= "combate" (:fase r)))
      (is (pos? (get-in r [:participantes "ash" :dano])))
      (is (true? (:duplicate retry)))
      (is (= "misty" (:vez r))))))

(deftest participantes-simultaneos-join
  (let [s (fresh-store! "r-join-race")
        chat "chat-jr"
        rid "raid-jr"
        barrier (java.util.concurrent.CyclicBarrier. 2)
        latch (java.util.concurrent.CountDownLatch. 2)
        results (atom {})]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (seed-mon! s chat "ash")
    (seed-mon! s chat "misty")
    (raid-app/open-raid!
     s chat "ash"
     {:operation-id "open-jr" :raid-id rid :ginasio "pedra"
      :liga "iniciante" :chefe chefe :agora 1})
    (doseq [p ["ash" "misty"]]
      (.start
       (Thread.
        (fn []
          (try
            (.await barrier 5 java.util.concurrent.TimeUnit/SECONDS)
            (let [r (raid-app/join-raid!
                     s chat p
                     {:operation-id (str "j-" p) :raid-id rid
                      :collection-index 0 :nome p :agora 2})]
              (swap! results assoc p r))
            (catch Exception ex
              (swap! results assoc p {:error (err/error-code ex)}))
            (finally
              (.countDown latch)))))))
    (is (.await latch 15 java.util.concurrent.TimeUnit/SECONDS))
    (let [r (raid-app/get-raid s rid)]
      (is (= 2 (count (:ordem r))))
      (is (= 2 (count (:participantes r)))))))
