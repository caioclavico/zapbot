(ns pokemon.tests.battle-leave-test
  "!pk sair — abandono voluntário wild/gym/raid/pvp + concorrência."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.pvp :as pvp-app]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-battle-leave-tests")
(def ^:private storage-rel ".datomic-local/battle-leave-tests")

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
  {:nome "Squirtle" :hp 100 :ataque 200 :defesa 70 :atq-esp 200 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10 :habilidade "torrent"
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe :especial}]})

(def ^:private magikarp
  {:nome "Magikarp" :species "magikarp" :hp 20 :ataque 10 :defesa 20 :atq-esp 10
   :def-esp 20 :veloc 80 :tipos ["water"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "splash" :nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private pikachu
  {:nome "Pikachu" :species "pikachu" :raridade "comum"
   :hp 45 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
   :tipos ["electric"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Choque" :tipo "electric" :poder 40 :classe :especial
             :precisao 100}]})

(def ^:private mon-raid
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 20 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(def ^:private chefe
  {:nome "Snorlax" :hp 100 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 30
   :tipos ["normal"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot!
  [s chat player & {:keys [poke] :or {poke squirtle}}]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (collection/adicionar-pokemon! s chat player poke (:hp poke) nil))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- wctx [s chat player]
  (writer/freeze-writer s chat player))

(defn- leave!
  "Passa agora explícito para não disparar idle-timeout por relógio de parede."
  [s chat player op & {:keys [agora save-id] :or {agora 1500 save-id "a"}}]
  (tx/leave-battle!
   s {:chat-id chat :player-id player :save-id save-id
      :fence-generation (fence s chat player)
      :operation-id op
      :agora-ms agora}))

(defn- err-code [thunk]
  (try (thunk) :ok
       (catch Exception ex (err/error-code ex))))

(deftest dominio-abandon-sem-recompensa
  (let [b {:battle-id "b1" :kind :wild :phase :active :active? true
           :player-id "ash" :chat-id "c"}
        a (battle/abandon b)]
    (is (= :abandoned (:outcome a)))
    (is (false? (:active? a)))
    (is (= {:pokemon-xp 0 :coins 0} (battle/reward-for-outcome :abandoned))))
  (is (= :battle_already_finished
         (err-code #(battle/abandon {:phase :finished :active? false})))))

(deftest dominio-raid-sair-combate-individual
  (let [raid {:raid-id "r1" :fase "combate" :expira 999999
              :ordem ["ash" "misty"] :vez "ash" :turn-index 2
              :participantes {"ash" {:hp 10 :dano 0}
                              "misty" {:hp 20 :dano 1}}}
        r1 (raid/sair-combate raid "ash" 1000)]
    (is (= ["misty"] (:ordem r1)))
    (is (= "misty" (:vez r1)))
    (is (= "combate" (:fase r1)))
    (is (nil? (get-in r1 [:participantes "ash"])))
    (let [r2 (raid/sair-combate r1 "misty" 1001)]
      (is (= "abandonada" (:fase r2)))
      (is (empty? (:ordem r2))))))

(deftest saida-batalha-selvagem
  (let [s (fresh-store! "leave-wild")
        chat "chat-w" player "ash"
        _ (boot! s chat player)
        coins0 (:coins (ports/get-save s chat player "a"))
        col0 (ports/list-collection s chat player "a")
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-w"
                  :battle-id "leave-w1"
                  :pokemon-x squirtle :pokemon-o magikarp
                  :hp-x 100 :hp-o 20 :collection-index 0 :agora 1000})
        bid (get-in started [:battle :battle-id])
        r (leave! s chat player "leave-w1")
        b (ports/get-battle s bid)
        save (ports/get-save s chat player "a")]
    (is (= "left" (:status r)))
    (is (= "wild" (:modality r)))
    (is (= :abandoned (:outcome b)))
    (is (false? (:active? b)))
    (is (nil? (ports/active-battle s chat player)))
    (is (= coins0 (:coins save)) "sem recompensa de moedas")
    (is (= (count col0) (count (ports/list-collection s chat player "a")))
        "Pokémon preservados")
    (testing "comando repetido idempotente"
      (let [dup (leave! s chat player "leave-w1")]
        (is (true? (:duplicate dup)))
        (is (= "left" (:status dup)))))
    (testing "sem batalha ativa"
      (let [none (leave! s chat player "leave-none")]
        (is (= "no_battle" (:status none)))))))

(deftest saida-sem-nunca-ter-batalhado
  ;; Regressão Client API: open-battle-id-fallback não pode usar :find [?id ...].
  (let [s (fresh-store! "leave-virgin")
        chat "chat-v" player "ash"
        _ (boot! s chat player)
        r (leave! s chat player "leave-virgin")]
    (is (= "no_battle" (:status r)))
    (is (nil? (:modality r)))))

(deftest saida-ginasio-sem-vitoria
  (let [s (fresh-store! "leave-gym")
        chat "chat-g" player "ash"
        _ (boot! s chat player)
        _ (doseq [n ["B" "C"]]
            (collection/adicionar-pokemon!
             s chat player (assoc squirtle :nome n) 100 nil))
        _ (tx/set-gym-team!
           s {:chat-id chat :player-id player :save-id "a"
              :fence-generation (fence s chat player)
              :indices [0 1 2]})
        _ (gym-app/start-challenge!
           s chat player
           {:operation-id "gym-start"
            :gym-id "pedra" :nome "Ash" :dia "2026-10-17" :agora 1000
            :candidates [(assoc magikarp :hp-atual 20 :status nil)
                         (assoc magikarp :nome "M2" :hp-atual 20 :status nil)
                         (assoc magikarp :nome "M3" :hp-atual 20 :status nil)]})
        coins0 (:coins (ports/get-save s chat player "a"))
        bid (:battle-id (ports/active-battle s chat player))
        r (leave! s chat player "leave-g1")
        b (ports/get-battle s bid)
        save (ports/get-save s chat player "a")
        ocup (ports/get-gym-occupation s chat "pedra")]
    (is (= "left" (:status r)))
    (is (= "gym" (:modality r)))
    (is (= :abandoned (:outcome b)))
    (is (nil? (ports/active-battle s chat player)))
    (is (= coins0 (:coins save)))
    (is (nil? ocup) "sem ocupação/vitória de ginásio")
    (is (nil? (get (:ginasios save) "pedra")))))

(deftest saida-individual-raid-combate
  (let [s (fresh-store! "leave-raid")
        chat "chat-r"
        _ (boot! s chat "ash" :poke mon-raid)
        _ (boot! s chat "misty" :poke mon-raid)
        rid "raid-leave"
        _ (tx/open-raid!
           s {:chat-id chat :player-id "ash" :save-id "a"
              :fence-generation (fence s chat "ash")
              :operation-id "open-r" :raid-id rid
              :ginasio "pedra" :liga "bronze" :chefe chefe :agora-ms 1000})
        _ (tx/join-raid!
           s {:chat-id chat :player-id "ash" :save-id "a"
              :fence-generation (fence s chat "ash")
              :operation-id "j-a" :collection-index 0 :nome "Ash" :agora-ms 2})
        _ (tx/join-raid!
           s {:chat-id chat :player-id "misty" :save-id "a"
              :fence-generation (fence s chat "misty")
              :operation-id "j-m" :collection-index 0 :nome "Misty" :agora-ms 3})
        _ (tx/start-raid!
           s {:chat-id chat :player-id "ash" :save-id "a"
              :fence-generation (fence s chat "ash")
              :operation-id "st" :agora-ms 4})
        coins-m0 (:coins (ports/get-save s chat "misty" "a"))
        r (leave! s chat "ash" "leave-raid-ash")
        raid (raid-app/get-raid s rid)]
    (is (= "left" (:status r)))
    (is (= "raid" (:modality r)))
    (is (false? (:raid-ended r)))
    (is (= "combate" (:fase raid)))
    (is (= ["misty"] (:ordem raid)))
    (is (nil? (get-in raid [:participantes "ash"])))
    (is (some? (get-in raid [:participantes "misty"])))
    (is (= coins-m0 (:coins (ports/get-save s chat "misty" "a")))
        "outro jogador sem recompensa")
    (let [r2 (leave! s chat "misty" "leave-raid-misty")
          raid2 (raid-app/get-raid s rid)]
      (is (true? (:raid-ended r2)))
      (is (= "abandonada" (:fase raid2))))))

(deftest desistencia-pvp-notifica-adversario
  (let [s (fresh-store! "leave-pvp")
        chat "chat-p"
        _ (boot! s chat "ash" :poke pikachu)
        _ (boot! s chat "misty" :poke pikachu)
        open (pvp-app/open-challenge!
              s chat "ash"
              {:operation-id "pvp-open"
               :collection-index 0
               :pokemon-id (:id-pokemon (first (ports/list-collection s chat "ash" "a")))
               :player-name "Ash"})
        bid (get-in open [:battle :battle-id])
        _ (pvp-app/accept-challenge!
           s chat "misty"
           {:operation-id "pvp-acc"
            :battle-id bid
            :collection-index 0
            :pokemon-id (:id-pokemon (first (ports/list-collection s chat "misty" "a")))
            :player-name "Misty"
            :first-turn-tie :x})
        coins-m0 (:coins (ports/get-save s chat "misty" "a"))
        r (leave! s chat "ash" "leave-pvp-1")
        b (ports/get-battle s bid)
        notes (ports/list-battle-idle-notifications s {:limit 10})]
    (is (= "left" (:status r)))
    (is (= "pvp" (:modality r)))
    (is (true? (:opponent-notified r)))
    (is (= :forfeit-x (:outcome b)))
    (is (false? (:active? b)))
    (is (= coins-m0 (:coins (ports/get-save s chat "misty" "a")))
        "sem prêmio extra ao adversário via leave")
    (is (= 1 (count notes)))
    (is (= "battle-leave" (:kind (first notes))))
    (is (= "misty" (:playerId (first notes))))
    (is (re-find #"adversário abandonou" (:text (first notes))))))

(deftest concorrencia-saida-vs-ataque
  (let [s (fresh-store! "leave-race-atk")
        chat "chat-ra" player "ash"
        _ (boot! s chat player)
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-ra"
                  :battle-id "race-atk"
                  :pokemon-x squirtle :pokemon-o magikarp
                  :hp-x 100 :hp-o 20 :collection-index 0 :agora 1000})
        bid (get-in started [:battle :battle-id])
        left (leave! s chat player "leave-ra")
        code (err-code
              #(ports/confirm-battle-turn!
                s (wctx s chat player)
                {:operation-id "atk-after-leave"
                 :battle-id bid
                 :expected-turn-index 0
                 :action :attack :move-index 0
                 :agora 2000
                 :rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99}}))]
    (is (= "left" (:status left)))
    (is (= :battle_not_active code))
    (is (= :abandoned (:outcome (ports/get-battle s bid))))))

(deftest concorrencia-saida-vs-idle-timeout
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "leave-race-idle")
          chat "chat-ri" player "ash"
          _ (boot! s chat player)
          t0 6000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-ri"
                    :battle-id "race-idle"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          ;; Idle materializado primeiro.
          sweep (ports/expire-idle-battles! s {:agora (+ t0 300000) :limit 4})
          r (leave! s chat player "leave-ri" :agora (+ t0 300001))
          b (ports/get-battle s bid)]
      (is (= 1 (:expired-battles sweep)))
      (is (contains? #{"already_finished" "no_battle"} (:status r)))
      (is (= :idle-timeout (:outcome b))
          "idle já persistido não é sobrescrito")
      ;; Prazo venceu, sweeper ainda não rodou → leave voluntário prevalece.
      (let [s2 (fresh-store! "leave-before-sweep")
            _ (boot! s2 chat player)
            st (ports/start-wild-battle!
                s2 (wctx s2 chat player)
                {:operation-id "start-ri2"
                 :battle-id "race-idle2"
                 :pokemon-x squirtle :pokemon-o magikarp
                 :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
            bid2 (get-in st [:battle :battle-id])
            left (leave! s2 chat player "leave-past-idle" :agora (+ t0 300001))
            sweep2 (ports/expire-idle-battles! s2 {:agora (+ t0 300002) :limit 4})]
        (is (= "left" (:status left)))
        (is (= :abandoned (:outcome (ports/get-battle s2 bid2))))
        (is (zero? (:expired-battles sweep2))
            "já abandonada não entra no sweeper")))))

(deftest persistencia-apos-restart
  (let [db "leave-restart"
        s (fresh-store! db)
        chat "chat-rs" player "ash"
        _ (boot! s chat player)
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-rs"
                  :battle-id "rs-leave"
                  :pokemon-x squirtle :pokemon-o magikarp
                  :hp-x 100 :hp-o 20 :collection-index 0 :agora 1000})
        bid (get-in started [:battle :battle-id])
        _ (leave! s chat player "leave-rs")
        s2 (reopen-store db)
        b (ports/get-battle s2 bid)]
    (is (= :abandoned (:outcome b)))
    (is (false? (:active? b)))
    (is (nil? (ports/active-battle s2 chat player)))
    ;; Ponteiro do jogador já limpo → nova operação reporta sem batalha.
    (is (= "no_battle"
           (:status (leave! s2 chat player "leave-rs-2" :agora 2000))))))

(deftest saida-aguardando-captura
  "Vitória selvagem → aguardando captura → !pk sair abandona sem capturar/recompensa."
  (let [s (fresh-store! "leave-await-cap")
        chat "chat-ac" player "ash"
        _ (boot! s chat player)
        coins0 (:coins (ports/get-save s chat player "a"))
        col0 (ports/list-collection s chat player "a")
        started (ports/start-wild-battle!
                 s (wctx s chat player)
                 {:operation-id "start-ac"
                  :battle-id "leave-ac1"
                  :pokemon-x squirtle :pokemon-o magikarp
                  :hp-x 100 :hp-o 20 :collection-index 0 :agora 1000})
        bid (get-in started [:battle :battle-id])
        win (ports/confirm-battle-turn!
             s (wctx s chat player)
             {:operation-id "turn-ac-win"
              :battle-id bid
              :expected-turn-index 0
              :action :attack :move-index 0
              :agora 1500
              :rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99
                    :contagio 99 :status-golpe 99 :wild-move 0 :impede-para 99}})
        b-win (ports/get-battle s bid)
        r (leave! s chat player "leave-ac1" :agora 1600)
        b (ports/get-battle s bid)
        save (ports/get-save s chat player "a")]
    (is (true? (:aguardando-captura? (:battle win))))
    (is (true? (:aguardando-captura? b-win)))
    (is (= "left" (:status r)))
    (is (= "wild" (:modality r)))
    (is (= :abandoned (:outcome b)))
    (is (false? (:active? b)))
    (is (false? (:aguardando-captura? b)))
    (is (nil? (ports/active-battle s chat player)))
    (is (= coins0 (:coins save)))
    (is (= (count col0) (count (ports/list-collection s chat player "a")))
        "sem captura ao abandonar")))

(deftest saida-preserva-outro-save-slot
  "Leave no save ativo não altera coleção/moedas do outro slot (ex.: recovery-v1)."
  (let [s (fresh-store! "leave-recovery-slots")
        chat "chat-rec" player "ash"
        _ (boot! s chat player)
        _ (ports/create-save! s chat player "recovery-v1")
        _ (trainer/switch-save! s chat player "recovery-v1")
        _ (collection/adicionar-pokemon!
           s chat player (assoc squirtle :nome "RecMon") 100 nil)
        col-a0 (ports/list-collection s chat player "a")
        coins-a0 (:coins (ports/get-save s chat player "a"))
        col-r0 (ports/list-collection s chat player "recovery-v1")
        coins-r0 (:coins (ports/get-save s chat player "recovery-v1"))
        _ (ports/start-wild-battle!
           s (wctx s chat player)
           {:operation-id "start-rec"
            :battle-id "leave-rec1"
            :pokemon-x squirtle :pokemon-o magikarp
            :hp-x 100 :hp-o 20 :collection-index 0 :agora 1000})
        _ (leave! s chat player "leave-rec1" :save-id "recovery-v1")
        col-a1 (ports/list-collection s chat player "a")
        coins-a1 (:coins (ports/get-save s chat player "a"))
        col-r1 (ports/list-collection s chat player "recovery-v1")
        coins-r1 (:coins (ports/get-save s chat player "recovery-v1"))]
    (is (pos? (count col-a0)))
    (is (pos? (count col-r0)))
    (is (= (count col-a0) (count col-a1)) "slot a intacto")
    (is (= coins-a0 coins-a1))
    (is (= (count col-r0) (count col-r1)) "recovery-v1 sem perda de Pokémon")
    (is (= coins-r0 coins-r1))
    (is (nil? (ports/active-battle s chat player)))))
