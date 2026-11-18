(ns pokemon.tests.capacity-storage-test
  "Capacidade de coleção: >= cheio, V1 over-capacity, switch slot, expansão, nível.
   Progressão: Pokémon/mochila 50 iniciais; +50 nos marcos; expansões +50/tipo."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.capture :as capture-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.errors :as err]
            [pokemon.domain.shop :as shop]
            [pokemon.infrastructure.datomic.store :as store]))

(deftest dominio-abaixo-igual-acima
  (let [save {:trainer-level 1 :expansoes-pokemon-50 0 :capacidade-pokemon-legada 0}]
    (is (= 50 (capacity/capacidade-pokemon-save save)))
    (is (false? (:cheio? (capacity/resumo-armazenamento-pokemon 49 save))))
    (is (true? (capacity/cabe-pokemon? 49 save)))
    (is (true? (:cheio? (capacity/resumo-armazenamento-pokemon 50 save))))
    (is (false? (capacity/cabe-pokemon? 50 save)) "ocupação == capacidade → bloqueia")
    (let [over (capacity/resumo-armazenamento-pokemon 119 save)]
      (is (true? (:cheio? over)))
      (is (= 69 (:excesso over)))
      (is (zero? (:livres over)))
      (is (false? (capacity/cabe-pokemon? 119 save))))))

(deftest dominio-marcos-progressao
  (testing "Pokémon: 50 base; +50 em 5/15/25/35/45"
    (doseq [[nivel esperado]
            [[1 50] [4 50] [5 100] [10 100] [14 100] [15 150]
             [25 200] [35 250] [45 300] [50 300]]]
      (is (= esperado (capacity/capacidade-pokemon nivel 0 0))
          (str "nível " nivel " → " esperado))))
  (testing "Mochila: 50 base; +50 em 10/20/30/40/50"
    (doseq [[nivel esperado]
            [[1 50] [9 50] [10 100] [19 100] [20 150]
             [30 200] [40 250] [50 300]]]
      (is (= esperado (capacity/capacidade-mochila nivel 0 0))
          (str "mochila nível " nivel " → " esperado))))
  (testing "recovery-v1 nível 10: 119/100"
    (let [save {:trainer-level 10 :trainer-xp 127 :expansoes-pokemon-50 0}
          r (capacity/resumo-armazenamento-pokemon 119 save)]
      (is (= 10 (capacity/nivel-para-capacidade save)))
      (is (= 100 (:capacidade r)))
      (is (= 19 (:excesso r)))
      (is (true? (:cheio? r))))))

(deftest dominio-nivel-e-expansao
  (is (= 100 (capacity/capacidade-pokemon 10 0 0)) "nível 10: 50+50 (marco 5)")
  (is (= 150 (capacity/capacidade-pokemon 10 1 0)) "nível 10 + 1 expansão")
  (is (= 200 (capacity/capacidade-pokemon 1 0 200)) "legado sobrescreve via max")
  (is (= 80 (capacity/capacidade-pokemon 1 0 80))
      "legado 80 > fórmula nv.1 (50) → preserva 80")
  (is (= 100 (capacity/capacidade-pokemon 5 0 80))
      "fórmula nv.5 (100) > legado 80 → usa 100")
  (is (= 150 (capacity/capacidade-pokemon 5 1 0)) "nível 5 + expansão")
  (is (= 100 (capacity/capacidade-mochila 10 0 50)) "mochila nv.10")
  (is (= 150 (capacity/capacidade-mochila 10 1 50)) "mochila + expansão")
  (is (= 225 (capacity/capacidade-mochila 1 1 175)) "legado mochila preservado + expansão")
  (let [save {:trainer-level 10 :trainer-xp 127 :expansoes-pokemon-50 0}]
    (is (= 10 (capacity/nivel-para-capacidade save)))
    (is (= 100 (capacity/capacidade-pokemon-save save)))))

(deftest dominio-nivel-unifica-trainer-level-sem-componentes
  (let [save {:trainer-level 10 :trainer-xp 127}]
    (is (= 100 (:capacidade (capacity/resumo-armazenamento-pokemon 119 save))))
    (is (= 19 (:excesso (capacity/resumo-armazenamento-pokemon 119 save))))))

(def ^:private system "odisseu-capacity-storage")
(def ^:private storage-rel ".datomic-local/capacity-storage-tests")

(defn- fresh! [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- seed-n!
  [s chat player save-id n]
  (dotimes [i n]
    (let [p (ports/get-player s chat player)
          ctx {:chat-id chat :player-id player :save-id save-id
               :fence-generation (:fence-generation p)}]
      (ports/add-to-collection!
       s ctx
       {:id-pokemon (str "p-" save-id "-" (System/nanoTime) "-" i)
        :species "rattata" :nome (str "Rattata-" i)
        :shiny false :nivel 1 :hp 10 :hp-atual 10 :xp 0 :amizade 70}))))

(deftest integracao-cheia-excesso-e-switch-slot
  (let [s (fresh! "cap-v1")
        chat "chat-cap"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (seed-n! s chat player "a" 50)
    (let [coll (reads/collection s chat player "a")
          shop (reads/shop-catalog s chat player)
          tr (reads/trainer s chat player)]
      (is (true? (:colecao-cheia coll)))
      (is (= 50 (:capacidade-pokemon coll)))
      (is (= 50 (:ocupacao-pokemon coll)))
      (is (zero? (:excesso-pokemon coll)))
      (is (true? (:colecao-cheia shop)))
      (is (true? (:colecao-cheia tr))))
    ;; over capacity (preserve — V1-like)
    (seed-n! s chat player "a" 5)
    (let [coll (reads/collection s chat player "a")]
      (is (= 55 (:count coll)))
      (is (= 5 (:excesso-pokemon coll)))
      (is (true? (:colecao-cheia coll)))
      (is (= 55 (count (ports/list-collection s chat player "a")))
          "nenhuma exclusão"))
    (trainer/switch-save! s chat player "b")
    (let [coll-b (reads/collection s chat player nil)
          tr (reads/trainer s chat player)]
      (is (= "b" (:save-id coll-b)))
      (is (false? (:colecao-cheia coll-b)))
      (is (= 0 (:ocupacao-pokemon coll-b)))
      (is (= 0 (:collection-count tr)))
      (is (= "b" (:active-save-id tr))))))

(deftest captura-bloqueada-quando-cheia
  (let [s (fresh! "cap-block")
        chat "chat-block"
        player "misty"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-ball" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pokebola" :qty 5}})
    (seed-n! s chat player "a" 50)
    (let [code (try
                 (capture-app/attempt-capture!
                  s chat player
                  {:operation-id "cap-full-1"
                   :bola "pokebola"
                   :pokemon {:species "pidgey" :nome "Pidgey" :nivel 3
                             :hp 20 :hp-atual 5 :raridade "comum"}
                   :status nil :roll 0.0 :fuga-roll 1.0})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :collection_full code))
      (is (= 50 (count (ports/list-collection s chat player "a")))))))

(deftest expansao-pokemon-aumenta-capacidade
  (let [s (fresh! "cap-exp")
        chat "chat-exp"
        player "brock"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-coins" :chat-id chat :player-id player :save-id "a"
        :coins-delta 500})
    (seed-n! s chat player "a" 50)
    (is (true? (:colecao-cheia (reads/shop-catalog s chat player))))
    (let [p (ports/get-player s chat player)
          bought (tx/buy-item! s {:chat-id chat :player-id player :save-id "a"
                                  :fence-generation (:fence-generation p)
                                  :operation-id "buy-espaco-1"
                                  :item-id "espaco-pokemon" :qty 1})
          shop (reads/shop-catalog s chat player)]
      (is (true? (:expansao? bought)))
      (is (= 1 (:expansoes-pokemon-50 bought)))
      (is (= 100 (:capacidade-pokemon shop)))
      (is (false? (:colecao-cheia shop)) "50/100 após +50")
      (is (true? (:economia-confiavel shop))))))

(deftest economia-unknown-bloqueia-expansao
  (is (false? (shop/economia-confiavel?
               {:origin "offline-import" :slot-id "recovery-v1"
                :legacy-version "unknown" :coins 0})))
  (is (true? (shop/economia-confiavel? {:slot-id "a" :coins 0})))
  (let [code (try
               (shop/validar-compra!
                "espaco-pokemon"
                {:origin "offline-import" :slot-id "recovery-v1" :coins 999}
                [] 0)
               :ok
               (catch Exception ex (err/error-code ex)))]
    (is (= :economy_unreliable code))))

(deftest economia-unknown-bloqueia-qualquer-compra
  "Histórico unknown-pending: spendable 0 não compra (mesmo com placeholder 999)."
  (let [save {:origin "offline-import" :slot-id "recovery-v1" :coins 999}
        code (try
               (shop/validar-compra! "pocao" save [] 0)
               :ok
               (catch Exception ex (err/error-code ex)))]
    (is (= :economy_unreliable code)))
  (testing "crédito operacional comprovado permite validar preço"
    (is (some? (shop/validar-compra!
                "pocao"
                {:origin "offline-import" :slot-id "recovery-v1"
                 :economy-status "unknown-pending" :coins 999
                 :coins-operational 50}
                [] 50)))))
