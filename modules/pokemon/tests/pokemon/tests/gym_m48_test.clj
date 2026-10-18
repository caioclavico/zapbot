(ns pokemon.tests.gym-m48-test
  "M4.8 — combate 3×3 de ginásio + resolve interno (sem venceu? do cliente)."
  (:require [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.battle :as battle-app]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m48-tests")
(def ^:private storage-rel ".datomic-local/m48-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- mon
  ([nome] (mon nome 80 50))
  ([nome hp atk]
   {:nome nome :hp hp :ataque atk :defesa 40 :atq-esp atk :def-esp 40 :veloc 50
    :tipos ["normal"] :nivel 10
    :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico
              :slug "tackle"}]}))

(defn- npc
  [nome hp & {:keys [atk] :or {atk 10}}]
  (assoc (mon nome hp atk) :hp-atual hp :status nil))

(defn- boot!
  [s chat player & {:keys [hp atk] :or {hp 200 atk 200}}]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n hp atk) hp nil)))

(defn- fence
  [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- set-team!
  [s chat player]
  (tx/set-gym-team!
   s {:chat-id chat :player-id player :save-id "a"
      :fence-generation (fence s chat player) :indices [0 1 2]}))

(def ^:private hit-rng
  {:accuracy 0 :crit 99 :damage-var 0 :flee 99
   :impede-para 99 :cura-sono 0 :cura-gelo 0 :cura-confusao 0
   :auto-confusao 99 :status 99 :contagio 99})

(defn- start-npc!
  [s chat player op-id npcs]
  (set-team! s chat player)
  (gym-app/start-challenge!
   s chat player
   {:operation-id op-id
    :gym-id "pedra"
    :nome "Ash"
    :dia "2026-10-17"
    :agora 1000
    :candidates npcs}))

(defn- grind-until-done!
  [s chat player tag & {:keys [max-turns] :or {max-turns 60}}]
  (loop [n 0]
    (let [cur (ports/active-battle s chat player)]
      (if (and cur (:active? cur) (< n max-turns))
        (do
          (battle-app/confirm-turn!
           s chat player
           {:operation-id (str tag "-" n)
            :battle-id (:battle-id cur)
            :expected-turn-index (:turn-index cur)
            :action :attack
            :move-index 0
            :rng hit-rng
            :dia "2026-10-17"
            :agora (+ 2000 n)
            :pedra-roll 0})
          (recur (inc n)))
        n))))

(deftest dominio-troca-apos-nocaute
  (let [b (battle/create-gym-battle
           {:battle-id "d1" :chat-id "c" :player-id "p" :save-id "a"
            :fence-generation 0 :gym-id "pedra" :gym-nome "Pedra"
            :lider-nome "Brock"
            :team-x [(assoc (mon "A" 50 80) :collection-index 0)
                     (assoc (mon "B" 50 80) :collection-index 1)
                     (assoc (mon "C" 50 80) :collection-index 2)]
            :team-o [(npc "G" 5) (npc "S" 40) (npc "O" 40)]})
        b2 (battle/apply-player-turn b {:action :attack :move-index 0} hit-rng)]
    (is (true? (:active? b2)))
    (is (= "S" (get-in b2 [:pokemons :o :nome])))
    (is (= 1 (count (get-in b2 [:reservas :o]))))
    (is (pos? (get-in b2 [:participacao :x 0] 0)))))

(deftest combate-completo-vitoria-e-insignia-unica
  (let [s (fresh-store! "g48-win")
        chat "chat-win"
        player "ash"]
    (boot! s chat player)
    (start-npc! s chat player "start-win"
                [(npc "Geodude" 5) (npc "Sandshrew" 5) (npc "Onix" 5)])
    (is (= :gym (:kind (ports/active-battle s chat player))))
    (grind-until-done! s chat player "turn-w")
    (is (nil? (ports/active-battle s chat player)))
    (let [save (ports/get-save s chat player "a")
          ocup (ports/get-gym-occupation s chat "pedra")]
      (is (= "2026-10-17" (get (:ginasios save) "pedra")))
      (is (some? ocup))
      (is (= player (:pid ocup)))
      (is (= 100 (:coins save 0))))
    ;; Start idempotente: não reabre batalha nem dobra recompensa.
    (let [coins-antes (:coins (ports/get-save s chat player "a"))
          dup (gym-app/start-challenge!
               s chat player
               {:operation-id "start-win"
                :gym-id "pedra"
                :nome "Ash"
                :dia "2026-10-17"
                :agora 1000
                :candidates [(npc "Geodude" 5) (npc "Sandshrew" 5) (npc "Onix" 5)]})]
      (is (true? (:duplicate dup)))
      (is (= coins-antes (:coins (ports/get-save s chat player "a"))))
      (is (nil? (ports/active-battle s chat player))))))

(deftest derrota-sem-ocupacao
  (let [s (fresh-store! "g48-lose")
        chat "chat-lose"
        player "ash"]
    (boot! s chat player :hp 5 :atk 5)
    (start-npc! s chat player "start-lose"
                [(npc "Geodude" 200 :atk 200)
                 (npc "Sandshrew" 200 :atk 200)
                 (npc "Onix" 200 :atk 200)])
    (grind-until-done! s chat player "turn-l")
    (is (nil? (ports/active-battle s chat player)))
    (is (nil? (ports/get-gym-occupation s chat "pedra")))
    (is (nil? (get (:ginasios (ports/get-save s chat player "a")) "pedra")))
    (is (= 1 (:pe-ginasios (ports/get-save s chat player "a") 0)))))

(deftest cliente-nao-forja-vitoria-via-resolve-whatsapp
  "resolve-challenge! existe para harness; bridge WhatsApp não o expõe.
  Vitória só via outcome do motor após turnos."
  (let [s (fresh-store! "g48-forge")
        chat "chat-forge"
        player "ash"]
    (boot! s chat player)
    (set-team! s chat player)
    ;; Harness ainda pode chamar resolve (M3.5) — mas o caminho Node não manda venceu?.
    (let [r (gym-app/resolve-challenge!
             s chat player
             {:operation-id "forge-win" :gym-id "pedra" :venceu? true
              :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Ash"
              :participacao {0 1} :agora 1000})]
      (is (true? (:venceu? r)))
      (is (= :primeira (:premio r))))
    (is (true? (:desafio-habilitado (reads/gyms-overview s chat player {}))))))

(deftest retry-turno-idempotente
  (let [s (fresh-store! "g48-retry")
        chat "chat-retry"
        player "ash"]
    (boot! s chat player)
    (start-npc! s chat player "start-r"
                [(npc "Geodude" 80) (npc "Sandshrew" 80) (npc "Onix" 80)])
    (let [cur (ports/active-battle s chat player)
          req {:operation-id "same-turn"
               :battle-id (:battle-id cur)
               :expected-turn-index 0
               :action :attack
               :move-index 0
               :rng hit-rng}
          a (battle-app/confirm-turn! s chat player req)
          b (battle-app/confirm-turn! s chat player req)]
      (is (false? (:duplicate a)))
      (is (true? (:duplicate b)))
      (is (= 1 (:turn-index (ports/active-battle s chat player)))))))

(deftest concorrencia-stale-turn
  (let [s (fresh-store! "g48-stale")
        chat "chat-stale"
        player "ash"]
    (boot! s chat player)
    (start-npc! s chat player "start-s"
                [(npc "Geodude" 80) (npc "Sandshrew" 80) (npc "Onix" 80)])
    (let [cur (ports/active-battle s chat player)
          _ (battle-app/confirm-turn!
             s chat player
             {:operation-id "t0" :battle-id (:battle-id cur)
              :expected-turn-index 0 :action :attack :move-index 0 :rng hit-rng})
          code (try
                 (battle-app/confirm-turn!
                  s chat player
                  {:operation-id "t-stale" :battle-id (:battle-id cur)
                   :expected-turn-index 0 :action :attack :move-index 0 :rng hit-rng})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :stale_turn_index code)))))

(deftest bloqueia-ativar-save-durante-batalha
  (let [s (fresh-store! "g48-fence")
        chat "chat-fence"
        player "ash"]
    (boot! s chat player)
    (start-npc! s chat player "start-f"
                [(npc "Geodude" 80) (npc "Sandshrew" 80) (npc "Onix" 80)])
    (let [code (try
                 (trainer/switch-save! s chat player "b")
                 (catch Exception ex (err/error-code ex)))]
      (is (= :battle_pending code)))))

(deftest reinicio-preserva-batalha
  (let [db "g48-persist"
        s (fresh-store! db)
        chat "chat-p"
        player "ash"]
    (boot! s chat player)
    (start-npc! s chat player "start-p"
                [(npc "Geodude" 80) (npc "Sandshrew" 80) (npc "Onix" 80)])
    (let [bid (:battle-id (ports/active-battle s chat player))
          client (persistence/client {:system system :storage-dir storage-rel})
          conn (persistence/ensure-db! client db)
          s2 (doto (store/make-store conn) trainer/bootstrap!)
          b2 (ports/active-battle s2 chat player)]
      (is (= bid (:battle-id b2)))
      (is (= :gym (:kind b2)))
      (is (true? (:active? b2))))))

(deftest ab-a-apos-derrota
  "Após derrota (sem ocupação) A↔B↔A funciona; vitória com ocupação bloqueia A↔B."
  (let [s (fresh-store! "g48-ab")
        chat "chat-ab"
        player "ash"]
    (boot! s chat player :hp 5 :atk 5)
    (start-npc! s chat player "start-ab-lose"
                [(npc "Geodude" 200 :atk 200)
                 (npc "Sandshrew" 200 :atk 200)
                 (npc "Onix" 200 :atk 200)])
    (grind-until-done! s chat player "tab-l")
    (is (nil? (ports/active-battle s chat player)))
    (let [r (trainer/switch-save! s chat player "b")]
      (is (= "b" (get-in r [:player :active-save-id]))))
    (let [r2 (trainer/switch-save! s chat player "a")]
      (is (= "a" (get-in r2 [:player :active-save-id]))))
    ;; Vitória ocupa e bloqueia alternância (defesa pendente).
    (boot! s "chat-ab2" "misty")
    (start-npc! s "chat-ab2" "misty" "start-ab-win"
                [(npc "Geodude" 5) (npc "Sandshrew" 5) (npc "Onix" 5)])
    (grind-until-done! s "chat-ab2" "misty" "tab-w")
    (is (some? (ports/get-gym-occupation s "chat-ab2" "pedra")))
    (let [code (try
                 (trainer/switch-save! s "chat-ab2" "misty" "b")
                 (catch Exception ex (err/error-code ex)))]
      (is (= :gym_defense_pending code)))))
