(ns pokemon.tests.gym-collection-hp-integrity-a6-test
  "P0: derrota de ginásio NÃO pode zerar HP de Pokémon fora do time 3×3.
  Reprodutor + regressão transacional (Datomic descartável)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-gym-hp-integrity-a6")
(def ^:private storage-rel ".datomic-local/gym-hp-integrity-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- mon
  ([nome hp] (mon nome hp 200))
  ([nome hp atk]
   {:nome nome :species nome :hp hp :ataque atk :defesa 40 :atq-esp atk :def-esp 40
    :veloc 50 :tipos ["normal"] :nivel 10
    :imagem (str "https://example.test/sprites/" nome ".png")
    :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico
              :slug "tackle"}]}))

(defn- npc
  [nome hp]
  (assoc (mon nome hp) :hp-atual hp :status nil :ataque 200))

(defn- fence
  [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(def ^:private hit-rng
  {:accuracy 0 :crit 99 :damage-var 0 :flee 99
   :impede-para 99 :cura-sono 0 :cura-gelo 0 :cura-confusao 0
   :auto-confusao 99 :status 99 :contagio 99})

(defn- grind-until-done!
  [s chat player tag]
  (loop [n 0]
    (let [cur (ports/active-battle s chat player)]
      (if (and cur (:active? cur) (< n 80))
        (do
          (battle-app/confirm-turn!
           s chat player
           {:operation-id (str tag "-" n)
            :battle-id (:battle-id cur)
            :expected-turn-index (:turn-index cur)
            :action :attack
            :move-index 0
            :rng hit-rng
            :dia "2026-11-06"
            :agora (+ (System/currentTimeMillis) n)
            :pedra-roll 0})
          (recur (inc n)))
        n))))

(deftest sync-unitario-derrota-so-time-tres
  (let [colecao (mapv (fn [i]
                        {:id-pokemon (str "p" i)
                         :collection-index i
                         :hp-atual (+ 50 i)
                         :status (when (zero? i) "envenenado")})
                      (range 6))
        battle {:outcome :lose
                :team-x-indices [0 2 4]
                :fainted-x #{0 2}
                :indices-ativos {:x 4}
                :hp {:x 0}
                :status {:x nil}}
        txs (vec (#'store/gym-battle-hp-txs colecao battle))
        synced (#'store/sync-colecao-from-gym-battle colecao battle)]
    (is (= [0 51 0 53 0 55] (mapv :hp-atual synced)))
    (is (nil? (:status (nth synced 0))))
    (is (= 51 (:hp-atual (nth synced 1))) "fora do time intacto")
    (is (= 53 (:hp-atual (nth synced 3))) "fora do time intacto")
    (is (= 55 (:hp-atual (nth synced 5))) "fora do time intacto")
    (is (pos? (count txs)))))

(deftest derrota-preserva-hp-fora-do-time
  (let [s (fresh-store! "lose-box-ok")
        chat "c-lose"
        player "ash"
        ;; atk baixo → perde; HP distintos para detectar wipe da box
        hps [90 80 70 60 55 45]]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (doseq [[i hp] (map-indexed vector hps)]
      (collection/adicionar-pokemon! s chat player (mon (str "M" i) hp 5) hp nil))
    (let [before (ports/list-collection s chat player "a")]
      (is (= 6 (count before)))
      (is (= hps (mapv :hp-atual before))))
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (gym-app/start-challenge!
     s chat player
     {:operation-id "start-lose-box"
      :gym-id "pedra"
      :nome "Ash"
      :dia "2026-11-06"
      :agora (System/currentTimeMillis)
      :candidates [(npc "Geodude" 200) (npc "Sandshrew" 200) (npc "Onix" 200)]})
    (grind-until-done! s chat player "tl")
    (is (nil? (ports/active-battle s chat player)))
    (let [after (ports/list-collection s chat player "a")
          by-idx (fn [i] (some #(when (= i (:collection-index %)) %) after))]
      (is (every? zero? (map :hp-atual [(by-idx 0) (by-idx 1) (by-idx 2)]))
          "time do desafio desmaiado")
      (is (= 60 (:hp-atual (by-idx 3))) "índice 3 preservado")
      (is (= 55 (:hp-atual (by-idx 4))) "índice 4 preservado")
      (is (= 45 (:hp-atual (by-idx 5))) "índice 5 preservado"))))

(deftest vitoria-preserva-nao-participantes
  (let [s (fresh-store! "win-box-ok")
        chat "c-win"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (doseq [i (range 6)]
      (collection/adicionar-pokemon!
       s chat player (mon (str "W" i) (+ 100 i)) (+ 100 i) nil))
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (gym-app/start-challenge!
     s chat player
     {:operation-id "start-win-box"
      :gym-id "pedra"
      :nome "Ash"
      :dia "2026-11-06"
      :agora (System/currentTimeMillis)
      :candidates [(npc "Geodude" 5) (npc "Sandshrew" 5) (npc "Onix" 5)]})
    (grind-until-done! s chat player "tw")
    (let [after (ports/list-collection s chat player "a")
          ;; Time 0–2 saiu para a ocupação; bench W3–W5 permanece com HP intacto.
          bench (filter #(re-find #"^W[345]$" (str (:nome %))) after)]
      (is (some? (ports/get-gym-occupation s chat "pedra")))
      (is (= 3 (count bench)))
      (is (= #{103 104 105} (set (map :hp-atual bench)))
          "bench fora do desafio não foi zerada nem curada"))))

(deftest abandonar-nao-zera-colecao
  (let [s (fresh-store! "abandon-box")
        chat "c-ab"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (doseq [i (range 6)]
      (collection/adicionar-pokemon! s chat player (mon (str "A" i) 70) 70 nil))
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (gym-app/start-challenge!
     s chat player
     {:operation-id "start-ab"
      :gym-id "pedra"
      :nome "Ash"
      :dia "2026-11-06"
      :agora (System/currentTimeMillis)
      :candidates [(npc "Geodude" 200) (npc "Sandshrew" 200) (npc "Onix" 200)]})
    (let [b (ports/active-battle s chat player)]
      (tx/leave-battle!
       s {:chat-id chat :player-id player :save-id "a"
          :fence-generation (fence s chat player)
          :operation-id "leave-ab"
          :battle-id (:battle-id b)
          :agora-ms (System/currentTimeMillis)}))
    (let [after (ports/list-collection s chat player "a")]
      (is (every? #(= 70 (:hp-atual %)) after)
          "abandonar não aplica nocaute em massa"))))

(deftest encode-team-edn-preserva-imagem
  (let [team [(assoc (mon "Onix" 100) :hp-atual 100)
              (assoc (mon "Larvitar" 80) :hp-atual 80)
              (assoc (mon "Snorlax" 120) :hp-atual 120)]
        edn (battle/encode-gym-team-edn team)
        decoded (battle/decode-compact-edn edn)]
    (is (every? :imagem decoded))
    (is (= "https://example.test/sprites/Onix.png" (:imagem (first decoded))))))

(deftest parcial-antes-do-desafio-preservado-no-start
  (let [s (fresh-store! "partial-start")
        chat "c-p"
        player "ash"]
    (trainer/create-trainer! s {:chat-id chat :player-id player})
    (collection/adicionar-pokemon! s chat player (mon "P0" 100) 40 nil)
    (collection/adicionar-pokemon! s chat player (mon "P1" 90) 90 nil)
    (collection/adicionar-pokemon! s chat player (mon "P2" 80) 80 nil)
    (collection/adicionar-pokemon! s chat player (mon "Bench" 70) 70 nil)
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (let [r (gym-app/start-challenge!
             s chat player
             {:operation-id "start-partial"
              :gym-id "pedra"
              :nome "Ash"
              :dia "2026-11-06"
              :agora (System/currentTimeMillis)
              :candidates [(npc "Geodude" 50) (npc "Sandshrew" 50) (npc "Onix" 50)]})
          b (:battle r)]
      (is (= 40 (get-in b [:hp :x])))
      (is (= 70 (:hp-atual (nth (ports/list-collection s chat player "a") 3)))))))
