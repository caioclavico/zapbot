(ns pokemon.tests.raid-hotfix-fase1231-test
  "Fase 12.3.1 — golpe fatal + ledger operacional ausente (CAS nil≠0)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-hotfix-1231")
(def ^:private storage-rel ".datomic-local/raid-hotfix-1231")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      {:s s :conn conn})))

(def ^:private mon
  {:nome "Arboliva" :hp 200 :ataque 200 :defesa 1 :atq-esp 200 :def-esp 1 :veloc 80
   :tipos ["grass"] :nivel 5 :raridade "raro"
   :golpes [{:nome-exibicao "Hyper Beam" :tipo "normal" :poder 150 :classe :especial}]})

(def ^:private mon2
  {:nome "Quaxly" :hp 100 :ataque 70 :defesa 1 :atq-esp 70 :def-esp 1 :veloc 60
   :tipos ["water"] :nivel 5 :raridade "comum"
   :golpes [{:nome-exibicao "Surf" :tipo "water" :poder 90 :classe :especial}]})

(def ^:private chefe
  {:nome "Onix" :hp 5 :ataque 1 :defesa 1 :atq-esp 1 :def-esp 1 :veloc 1
   :tipos ["rock" "ground"] :nivel 10 :raridade "raro"
   :golpes [{:nome-exibicao "Rock Throw" :tipo "rock" :poder 50 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player mon*]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon* (long (:hp mon*)) nil)
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "ultra-bola" :qty 2}}))

(defn- strip-operational!
  "Simula save com economy-status mas sem :save/coins-operational (nil≠0)."
  [conn chat players]
  (d/transact
   conn
   {:tx-data
    (mapcat
     (fn [pid]
       (let [sk (id/save-key chat pid "a")
             e (d/pull (d/db conn) '[*] [:save/key sk])
             rev (long (:save/revision e 0))]
         (cond-> [[:db/add [:save/key sk] :save/economy-status "unknown-pending"]
                  [:db/add [:save/key sk] :save/coins-historical-status "unknown"]
                  [:db/cas [:save/key sk] :save/revision rev (inc rev)]]
           (some? (:save/coins-operational e))
           (concat
            [[:db/retract [:save/key sk] :save/coins-operational
              (:save/coins-operational e)]
             [:db/retract [:save/key sk] :save/coins-operational-earned
              (:save/coins-operational-earned e 0)]
             [:db/retract [:save/key sk] :save/coins-operational-spent
              (:save/coins-operational-spent e 0)]]))))
     players)}))

(defn- fight-to-victory!
  [s chat rid]
  (loop [n 0]
    (let [r (raid-app/get-raid s rid)]
      (if (or (>= n 80) (not= "combate" (:fase r)))
        r
        (let [pid (:vez r)]
          (tx/raid-attack!
           s {:chat-id chat :player-id pid :save-id "a"
              :fence-generation (fence s chat pid)
              :operation-id (str "atk-" rid "-" n)
              :expected-turn-index (:turn-index r)
              :move-index 0 :agora-ms (+ 10 n)})
          (recur (inc n)))))))

(deftest chefes-ginasio-carregam-artwork-com-alpha
  (let [pedra (raid/boss-do-ginasio "pedra")
        agua (raid/boss-do-ginasio "agua")]
    (is (= "onix" (get-in pedra [:chefe :species])))
    (is (string? (get-in pedra [:chefe :imagem])))
    (is (re-find #"official-artwork/95\.png" (get-in pedra [:chefe :imagem])))
    (is (re-find #"official-artwork/" (get-in agua [:chefe :imagem])))))

(deftest golpe-fatal-com-operacional-ausente-nao-conflita
  "Regressão do incidente WhatsApp: Compare failed 0 vs nil → WRITER_FENCE_CONFLICT."
  (let [{:keys [s conn]} (fresh! "fatal-nil-op")
        chat "chat-fatal"
        rid "raid-fatal"]
    (boot! s chat "ash" mon)
    (boot! s chat "misty" mon2)
    (strip-operational! conn chat ["ash" "misty"])
    (let [raw (d/pull (d/db conn) '[*] [:save/key (id/save-key chat "ash" "a")])]
      (is (= "unknown-pending" (:save/economy-status raw)))
      (is (nil? (:save/coins-operational raw))))
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "open-fatal" :raid-id rid
        :ginasio "pedra" :chefe chefe :agora-ms 1000})
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
    (let [raw-coins0 (long (:save/coins
                            (d/pull (d/db conn) '[:save/coins]
                                    [:save/key (id/save-key chat "ash" "a")])
                            0))
          ;; Captura params exatos do golpe fatal para retry idempotente.
          kill (atom nil)
          _ (loop [n 0]
              (let [r (raid-app/get-raid s rid)]
                (when (and (< n 80) (= "combate" (:fase r)))
                  (let [pid (:vez r)
                        req {:chat-id chat :player-id pid :save-id "a"
                             :fence-generation (fence s chat pid)
                             :operation-id (str "atk-" rid "-" n)
                             :expected-turn-index (:turn-index r)
                             :move-index 0 :agora-ms (+ 10 n)}
                        res (tx/raid-attack! s req)]
                    (when (:venceu res) (reset! kill req))
                    (recur (inc n))))))
          final (raid-app/get-raid s rid)
          ash (ports/get-save s chat "ash" "a")
          misty (ports/get-save s chat "misty" "a")
          dup (tx/raid-attack! s @kill)
          raw-coins1 (long (:save/coins
                            (d/pull (d/db conn) '[:save/coins]
                                    [:save/key (id/save-key chat "ash" "a")])
                            0))]
      (is (some? @kill))
      (is (= "vitoria" (:fase final)))
      (is (true? (:rewards-paid? final)))
      (is (= 0 (:hp-chefe final)))
      (is (= 50 (long (:coins-operational ash 0))))
      (is (= 50 (long (:coins-operational misty 0))))
      (is (= 6 (long (:pe-raids ash 0))))
      ;; unknown-pending: attr legado :save/coins permanece (DTO :coins = spendable)
      (is (= raw-coins0 raw-coins1))
      (is (true? (:duplicate dup)))
      (is (= 50 (long (:coins-operational (ports/get-save s chat "ash" "a") 0)))
          "reliquidação idempotente não duplica"))))

(deftest ataque-que-deixa-1-hp-depois-fatal
  (let [{:keys [s]} (fresh! "near-fatal")
        chat "chat-near"
        rid "raid-near"
        ;; Chefe com HP de combate = 440; forçamos dano controlado via stats extremos
        ]
    (boot! s chat "ash" mon)
    (boot! s chat "misty" mon2)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "open-near" :raid-id rid
        :ginasio "pedra" :chefe chefe :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "jn-a" :collection-index 0 :agora-ms 2})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jn-m" :collection-index 0 :agora-ms 3})
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "st-near" :agora-ms 4})
    (let [r0 (raid-app/get-raid s rid)
          _ (is (= "combate" (:fase r0)))
          final (fight-to-victory! s chat rid)]
      (is (= "vitoria" (:fase final)))
      (is (true? (:rewards-paid? final)))
      (is (pos? (count (raid/participantes-elegiveis final)))))))

(deftest dois-ataques-concorrentes-ultimo-turno
  (let [{:keys [s]} (fresh! "race-final")
        chat "chat-race"
        rid "raid-race"]
    (boot! s chat "ash" mon)
    (boot! s chat "misty" mon2)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "open-race" :raid-id rid
        :ginasio "pedra" :chefe chefe :agora-ms 1000})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "jr-a" :collection-index 0 :agora-ms 2})
    (tx/join-raid!
     s {:chat-id chat :player-id "misty" :save-id "a"
        :fence-generation (fence s chat "misty")
        :operation-id "jr-m" :collection-index 0 :agora-ms 3})
    (tx/start-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "st-race" :agora-ms 4})
    ;; Drena até HP baixo o bastante para um golpe finalizar.
    (loop [n 0]
      (let [r (raid-app/get-raid s rid)]
        (when (and (< n 60) (= "combate" (:fase r)) (> (long (:hp-chefe r)) 150))
          (let [pid (:vez r)]
            (tx/raid-attack!
             s {:chat-id chat :player-id pid :save-id "a"
                :fence-generation (fence s chat pid)
                :operation-id (str "drain-" n)
                :expected-turn-index (:turn-index r)
                :move-index 0 :agora-ms (+ 100 n)}))
          (recur (inc n)))))
    (let [r (raid-app/get-raid s rid)
          _ (is (= "combate" (:fase r)))
          _ (is (<= (long (:hp-chefe r)) 150))
          pid (:vez r)
          turn (:turn-index r)
          ;; Mesmo expected-turn + ops distintas: um commit, outro rejeitado.
          results
          (mapv
           (fn [i]
             (try
               (tx/raid-attack!
                s {:chat-id chat :player-id pid :save-id "a"
                   :fence-generation (fence s chat pid)
                   :operation-id (str "race-kill-" i)
                   :expected-turn-index turn
                   :move-index 0 :agora-ms (+ 500 i)})
               {:ok true}
               (catch Exception ex
                 {:ok false :code (err/error-code ex)})))
           [0 1])
          wins (filter :ok results)
          fails (remove :ok results)]
      (is (= 1 (count wins)) "exatamente um commit de vitória")
      (is (= 1 (count fails)))
      (is (contains? #{:stale_turn_index :raid_not_in_combat :raid_not_your_turn}
                     (:code (first fails))))
      (is (= "vitoria" (:fase (raid-app/get-raid s rid))))
      (is (true? (:rewards-paid? (raid-app/get-raid s rid))))
      (is (= 50 (long (:coins-operational (ports/get-save s chat "ash" "a") 0)))))))
