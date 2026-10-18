(ns pokemon.tests.encounter-m44-test
  "M4.4 — captura real sobre encontro (aguardando-captura) com fence/idempotência."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m44-tests")
(def ^:private storage-rel ".datomic-local/m44-tests")

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

(def ^:private squirtle
  {:nome "Squirtle" :hp 100 :ataque 60 :defesa 70 :atq-esp 60 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10 :habilidade "torrent"
   :golpes [{:nome-exibicao "Jato de Agua" :tipo "water" :poder 40 :classe :especial}]})

(def ^:private magikarp
  {:nome "Magikarp" :raridade "comum"
   :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 5
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-balls-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 10}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil))

(defn- force-win!
  [s chat player battle-id]
  (battle-app/start-wild-battle!
   s chat player
   {:operation-id (str "start-" battle-id)
    :battle-id battle-id
    :pokemon-x squirtle
    :pokemon-o (assoc magikarp :hp 5)
    :hp-x 100 :hp-o 5
    :collection-index 0})
  (battle-app/confirm-turn!
   s chat player
   {:operation-id (str "win-" battle-id)
    :battle-id battle-id
    :expected-turn-index 0
    :action :attack
    :move-index 0
    :rng {:damage-var 10 :crit 99 :flee 99 :accuracy 0
          :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99
          :cura-sono 99 :cura-gelo 99 :cura-confusao 99}}))

(defn- ball-qty
  [s chat player]
  (->> (ports/list-inventory s chat player "a")
       (filter #(= "pokebola" (:item-id %)))
       (map :qty)
       (first)
       (long)))

(deftest ponteiro-persiste-aguardando-captura
  (let [s (fresh-store! "enc-ptr")
        chat "chat-ptr"
        player "ash"]
    (boot! s chat player)
    (force-win! s chat player "bid-ptr")
    (let [b (battle-app/active-battle s chat player)]
      (is (some? b))
      (is (true? (:aguardando-captura? b)))
      (is (false? (:active? b)))
      (is (some? (:chance-base-captura b)))
      (is (zero? (long (:tentativas-captura b 0)))))))

(deftest captura-sucesso-consome-uma-bola
  (let [s (fresh-store! "enc-ok")
        chat "chat-ok"
        player "ash"]
    (boot! s chat player)
    (force-win! s chat player "bid-ok")
    (let [before (ball-qty s chat player)
          coins0 (:coins (ports/get-save s chat player "a"))
          r (capture-app/attempt-encounter-capture!
             s chat player
             {:operation-id "cap-ok"
              :battle-id "bid-ok"
              :bola "pokebola"
              :roll 0})
          after (ball-qty s chat player)
          save (ports/get-save s chat player "a")
          colecao (ports/list-collection s chat player "a")]
      (is (true? (:capturou? r)))
      (is (false? (:fugiu? r)))
      (is (= (dec before) after))
      (is (= 2 (count colecao)))
      (is (= "Magikarp" (:nome (second colecao))))
      (is (= 1 (:sequencia-capturas save)))
      (is (> (:coins save) coins0))
      (is (nil? (battle-app/active-battle s chat player))))))

(deftest captura-fuga-quebra-sequencia
  (let [s (fresh-store! "enc-flee")
        chat "chat-flee"
        player "misty"]
    (boot! s chat player)
    (force-win! s chat player "bid-flee")
    (let [r (capture-app/attempt-encounter-capture!
             s chat player
             {:operation-id "cap-flee"
              :battle-id "bid-flee"
              :bola "pokebola"
              :roll 99
              :fuga-roll 0})]
      (is (false? (:capturou? r)))
      (is (true? (:fugiu? r)))
      (is (= 0 (:sequencia-capturas (ports/get-save s chat player "a"))))
      (is (= 1 (count (ports/list-collection s chat player "a"))))
      (is (nil? (battle-app/active-battle s chat player))))))

(deftest falha-mantem-encontro-e-consome-bola
  (let [s (fresh-store! "enc-miss")
        chat "chat-miss"
        player "brock"]
    (boot! s chat player)
    (force-win! s chat player "bid-miss")
    (let [before (ball-qty s chat player)
          r (capture-app/attempt-encounter-capture!
             s chat player
             {:operation-id "cap-miss"
              :battle-id "bid-miss"
              :bola "pokebola"
              :roll 99
              :fuga-roll 99})
          b (battle-app/active-battle s chat player)]
      (is (false? (:capturou? r)))
      (is (false? (:fugiu? r)))
      (is (= 1 (:tentativa r)))
      (is (= (dec before) (ball-qty s chat player)))
      (is (true? (:aguardando-captura? b)))
      (is (= 1 (long (:tentativas-captura b)))))))

(deftest retry-idempotente-e-persistencia
  (let [db "enc-idem"
        chat "chat-idem"
        player "red"
        req {:operation-id "cap-once"
             :battle-id "bid-idem"
             :bola "pokebola"
             :roll 0}]
    (let [s (fresh-store! db)]
      (boot! s chat player)
      (force-win! s chat player "bid-idem")
      (let [r1 (capture-app/attempt-encounter-capture! s chat player req)
            r2 (capture-app/attempt-encounter-capture! s chat player req)]
        (is (false? (:duplicate r1)))
        (is (true? (:duplicate r2)))
        (is (= 9 (ball-qty s chat player)))
        (is (= 2 (count (ports/list-collection s chat player "a"))))))
    (let [s (reopen-store db)
          retry (capture-app/attempt-encounter-capture! s chat player req)]
      (is (true? (:duplicate retry)))
      (is (= 2 (count (ports/list-collection s chat player "a"))))
      (is (= 9 (ball-qty s chat player))))))

(deftest a-b-bloqueado-durante-encontro
  (let [s (fresh-store! "enc-ab")
        chat "chat-ab"
        player "leaf"]
    (boot! s chat player)
    (force-win! s chat player "bid-ab")
    (let [code (try
                 (trainer/switch-save! s chat player "b")
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :battle_pending code))
      (is (= "a" (ports/active-save-id s chat player))))))

(deftest callback-stale-apos-encerrar
  (let [s (fresh-store! "enc-stale")
        chat "chat-stale"
        player "green"]
    (boot! s chat player)
    (force-win! s chat player "bid-stale")
    (let [writer-a (writer/freeze-writer s chat player)]
      (capture-app/attempt-encounter-capture!
       s chat player
       {:operation-id "cap-then-switch"
        :battle-id "bid-stale"
        :bola "pokebola"
        :roll 0})
      (trainer/switch-save! s chat player "b")
      (let [code (try
                   (capture-app/attempt-encounter-capture!
                    s writer-a
                    {:operation-id "stale-cap"
                     :battle-id "bid-stale"
                     :bola "pokebola"
                     :roll 0})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :stale_writer_fence code))))))

(deftest concorrencia-tentativas
  (let [s (fresh-store! "enc-race")
        chat "chat-race"
        player "blue"]
    (boot! s chat player)
    (force-win! s chat player "bid-race")
    (let [futs (doall
                (for [i (range 4)]
                  (future
                    (try
                      (capture-app/attempt-encounter-capture!
                       s chat player
                       {:operation-id (str "race-" i)
                        :battle-id "bid-race"
                        :bola "pokebola"
                        :roll 99
                        :fuga-roll 0})
                      (catch Exception ex
                        {:error (err/error-code ex)})))))
          results (mapv deref futs)
          oks (filter #(and (map? %) (false? (:duplicate %)) (nil? (:error %))
                            (true? (:fugiu? %))) results)
          errs (filter :error results)]
      (is (= 1 (count oks)))
      (is (nil? (battle-app/active-battle s chat player)))
      (is (= 9 (ball-qty s chat player)))
      (is (every? #(contains? #{:encounter_not_found :encounter_not_ready
                                :writer_fence_conflict :stale_writer_fence
                                :insufficient_item}
                              (:error %))
                  errs)))))

(deftest isolamento-entre-jogadores
  (let [s (fresh-store! "enc-iso")
        chat "chat-iso"]
    (boot! s chat "ash")
    (boot! s chat "gary")
    (force-win! s chat "ash" "bid-ash")
    (let [code (try
                 (capture-app/attempt-encounter-capture!
                  s chat "gary"
                  {:operation-id "steal"
                   :battle-id "bid-ash"
                   :bola "pokebola"
                   :roll 0})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (contains? #{:encounter_not_found :encounter_owner_mismatch :battle_save_mismatch
                       :stale_writer_fence :encounter_not_ready}
                     code))
      (is (some? (battle-app/active-battle s chat "ash")))
      (is (nil? (battle-app/active-battle s chat "gary"))))))

(deftest rejeita-apos-encerrado
  (let [s (fresh-store! "enc-closed")
        chat "chat-closed"
        player "yellow"]
    (boot! s chat player)
    (force-win! s chat player "bid-closed")
    (capture-app/attempt-encounter-capture!
     s chat player
     {:operation-id "cap-close"
      :battle-id "bid-closed"
      :bola "pokebola"
      :roll 0})
    (let [code (try
                 (capture-app/attempt-encounter-capture!
                  s chat player
                  {:operation-id "cap-again"
                   :battle-id "bid-closed"
                   :bola "pokebola"
                   :roll 0})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (contains? #{:encounter_not_found :encounter_not_ready} code)))))
