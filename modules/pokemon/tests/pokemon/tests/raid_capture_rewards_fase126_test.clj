(ns pokemon.tests.raid-capture-rewards-fase126-test
  "Fase 12.6 — captura de raide (RNG), Frambo, recompensas e capacidade."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capture :as capture]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-cap-126")
(def ^:private storage-rel ".datomic-local/raid-cap-126")

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
  {:nome "Onix" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
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
      :item {:item-id "ultra-bola" :qty 10}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-poke-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-grande-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "grande-bola" :qty 5}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-frambo-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "fruta" :qty 3}}))

(defn- open!
  [s chat rid]
  (tx/open-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "open-" rid)
      :raid-id rid :ginasio "pedra" :liga "bronze" :chefe chefe
      :agora-ms 1000}))

(defn- win-raid!
  "Dois jogadores → combate → vitória."
  [s chat rid]
  (open! s chat rid)
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

(deftest chance-frambo-balanceamento
  (testing "regras documentadas: base raide × bola (± frambo 1.5, cap da bola)"
    (is (= 35 (raid/chance-captura "raro")))
    (is (= 8 (raid/chance-captura "lendario")))
    (is (= 70 (capture/chance-com-bola 35 "ultra-bola")))
    (is (= 95 (capture/chance-com-bola-frambo 35 "ultra-bola" true))
        "35×2=70 ×1.5 → 105 cap 95")
    (is (= 16 (capture/chance-com-bola 8 "ultra-bola")))
    (is (= 24 (capture/chance-com-bola-frambo 8 "ultra-bola" true)))
    (is (= 70 (capture/chance-com-bola-frambo 35 "ultra-bola" false)))))

(deftest ultra-bola-sucesso-e-falha-deterministica
  (let [s (fresh-store! "r126-ultra")
        chat "c-u"
        rid "raid-u"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (let [r (win-raid! s chat rid)
          ash (ports/get-save s chat "ash" "a")]
      (is (= "vitoria" (:fase r)))
      (is (= 50 (:coins ash)))
      (is (= 6 (:pe-raids ash))))
    (testing "roll 0 → captura (regressão: default 100 quebrava tudo)"
      (let [cap (tx/raid-capture!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "cap-ok" :bola "ultra-bola" :roll 0
                    :agora-ms 100})]
        (is (true? (:capturou cap)))
        (is (= 70 (:chance cap)))
        (is (false? (:frambo cap)))))
    (testing "idempotência"
      (let [dup (tx/raid-capture!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "cap-ok" :bola "ultra-bola" :roll 0
                    :agora-ms 100})]
        (is (true? (:duplicate dup)))))))

(deftest ultra-bola-falha-no-limite
  (let [s (fresh-store! "r126-fail")
        chat "c-f"
        rid "raid-f"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (win-raid! s chat rid)
    (let [antes (->> (ports/list-inventory s chat "ash" "a")
                     (filter #(= "ultra-bola" (:item-id %)))
                     (map :qty) (reduce + 0))
          cap (tx/raid-capture!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "cap-fail" :bola "ultra-bola" :roll 70
                  :agora-ms 100})
          depois (->> (ports/list-inventory s chat "ash" "a")
                      (filter #(= "ultra-bola" (:item-id %)))
                      (map :qty) (reduce + 0))]
      (is (false? (:capturou cap)))
      (is (= 70 (:chance cap)))
      (is (= (dec antes) depois))
      (is (= 1 (:tentativas cap))))))

(deftest captura-sem-roll-nao-fixa-cem
  "Produção não envia roll; default antigo 100 ⇒ sempre falha."
  (let [s (fresh-store! "r126-rng")
        chat "c-r"
        rid "raid-r"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (win-raid! s chat rid)
    (let [cap (tx/raid-capture!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "cap-rng" :bola "ultra-bola"
                  :agora-ms 100})]
      (is (number? (:roll cap)))
      (is (<= 0 (:roll cap) 99))
      (is (not= 100 (:roll cap))))))

(deftest frambo-prepare-consome-na-captura
  (let [s (fresh-store! "r126-frambo")
        chat "c-fb"
        rid "raid-fb"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (win-raid! s chat rid)
    (let [prep (tx/raid-frambo-prepare!
                s {:chat-id chat :player-id "ash" :save-id "a"
                   :fence-generation (fence s chat "ash")
                   :operation-id "fb-1" :agora-ms 50})
          fruta-antes (->> (ports/list-inventory s chat "ash" "a")
                           (filter #(= "fruta" (:item-id %)))
                           (map :qty) (reduce + 0))]
      (is (true? (:frambo-preparada prep)))
      (is (= 3 fruta-antes) "prepare não consome")
      (let [code (try
                   (tx/raid-frambo-prepare!
                    s {:chat-id chat :player-id "ash" :save-id "a"
                       :fence-generation (fence s chat "ash")
                       :operation-id "fb-2" :agora-ms 51})
                   (catch Exception ex (err/error-code ex)))]
        (is (= :frambo_already_prepared code)))
      (let [cap (tx/raid-capture!
                 s {:chat-id chat :player-id "ash" :save-id "a"
                    :fence-generation (fence s chat "ash")
                    :operation-id "cap-fb" :bola "ultra-bola" :roll 0
                    :agora-ms 100})
            fruta-depois (->> (ports/list-inventory s chat "ash" "a")
                              (filter #(= "fruta" (:item-id %)))
                              (map :qty) (reduce + 0))]
        (is (true? (:capturou cap)))
        (is (true? (:frambo cap)))
        (is (= 95 (:chance cap)))
        (is (= 2 fruta-depois))))))

(deftest frambo-ausente-rejeita-prepare
  (let [s (fresh-store! "r126-nofb")
        chat "c-nf"
        rid "raid-nf"]
    (trainer/create-trainer! s {:chat-id chat :player-id "ash"})
    (trainer/create-trainer! s {:chat-id chat :player-id "misty"})
    (collection/adicionar-pokemon! s chat "ash" mon 200 nil)
    (collection/adicionar-pokemon! s chat "misty" mon 200 nil)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-u-ash" :chat-id chat :player-id "ash" :save-id "a"
        :item {:item-id "ultra-bola" :qty 3}})
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-u-misty" :chat-id chat :player-id "misty" :save-id "a"
        :item {:item-id "ultra-bola" :qty 3}})
    (win-raid! s chat rid)
    (let [code (try
                 (tx/raid-frambo-prepare!
                  s {:chat-id chat :player-id "ash" :save-id "a"
                     :fence-generation (fence s chat "ash")
                     :operation-id "fb-x" :agora-ms 50})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :insufficient_item code)))))

(deftest frambo-combinada-no-capture
  (let [s (fresh-store! "r126-comb")
        chat "c-c"
        rid "raid-c"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (win-raid! s chat rid)
    (let [cap (tx/raid-capture!
               s {:chat-id chat :player-id "ash" :save-id "a"
                  :fence-generation (fence s chat "ash")
                  :operation-id "cap-c" :bola "grande-bola" :roll 0
                  :frambo true :agora-ms 100})]
      (is (true? (:capturou cap)))
      (is (true? (:frambo cap)))
      (is (= (capture/chance-com-bola-frambo 35 "grande-bola" true) (:chance cap))))))

(deftest recompensas-detalhadas-no-golpe-fatal
  (let [s (fresh-store! "r126-rw")
        chat "c-rw"
        rid "raid-rw"]
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
        :operation-id "st" :agora-ms 4})
    (let [last-atk
          (loop [n 0 last nil]
            (let [r (raid-app/get-raid s rid)]
              (if (or (>= n 40) (not= "combate" (:fase r)))
                last
                (let [pid (:vez r)
                      atk (tx/raid-attack!
                           s {:chat-id chat :player-id pid :save-id "a"
                              :fence-generation (fence s chat pid)
                              :operation-id (str "a-" n)
                              :expected-turn-index (:turn-index r)
                              :move-index 0 :agora-ms (+ 10 n)})]
                  (recur (inc n) atk)))))]
      (is (true? (:venceu last-atk)))
      (is (seq (:rewards last-atk)))
      (is (every? #(and (= 50 (:moedas %)) (= 6 (:pe %)) (= 6 (:xp-pokemon %)))
                  (:rewards last-atk)))
      (let [ash (ports/get-save s chat "ash" "a")
            misty (ports/get-save s chat "misty" "a")]
        (is (= 50 (:coins ash)))
        (is (= 50 (:coins misty)))
        (is (= 6 (:pe-raids ash)))
        (is (= 6 (:pe-raids misty)))))))

(deftest captura-ui-exposicao
  (let [s (fresh-store! "r126-ui")
        chat "c-ui"
        rid "raid-ui"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (win-raid! s chat rid)
    (let [dto (reads/active-raid s chat "ash" {:agora-ms 100})]
      (is (true? (get-in dto [:raid :captura-pendente])))
      (is (map? (:captura-ui dto)))
      (is (pos? (get-in dto [:captura-ui :bolas "ultra-bola"] 0)))
      (is (= 3 (get-in dto [:captura-ui :frambo])))
      (is (= 70 (get-in dto [:captura-ui :chances "ultra-bola"]))))))
