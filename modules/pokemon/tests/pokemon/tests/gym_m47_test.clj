(ns pokemon.tests.gym-m47-test
  "M4.7 — leituras de ginásio + time/heal na bridge (desafio bloqueado no WhatsApp)."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-reads :as reads]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.gym :as gym-app]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.gym :as gym]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-m47-tests")
(def ^:private storage-rel ".datomic-local/m47-tests")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- mon
  [nome]
  {:nome nome :hp 80 :ataque 50 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 50
   :tipos ["normal"] :nivel 10 :imagem (str "https://example/" nome ".png")
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (doseq [n ["A" "B" "C"]]
    (collection/adicionar-pokemon! s chat player (mon n) 80 nil)))

(defn- fence
  [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(deftest overview-e-desbloqueio
  (let [s (fresh-store! "g47-ov")
        chat "chat-ov"
        player "ash"]
    (boot! s chat player)
    (let [ov (reads/gyms-overview s chat player {})]
      ;; M4.8 habilitou o desafio; M4.7 cobria leituras/time/heal.
      (is (true? (:desafio-habilitado ov)))
      (is (= 5 (count (:gyms ov))))
      (is (true? (:desbloqueado (first (:gyms ov)))))
      (is (false? (:desbloqueado (second (:gyms ov))))))))

(deftest set-team-e-leitura
  (let [s (fresh-store! "g47-tm")
        chat "chat-tm"
        player "misty"]
    (boot! s chat player)
    (let [r (tx/set-gym-team!
             s {:chat-id chat :player-id player :save-id "a"
                :fence-generation (fence s chat player) :indices [0 1 2]})
          team (reads/gym-team s chat player)]
      (is (= [0 1 2] (:indices r)))
      (is (true? (:completo? team)))
      (is (= 3 (count (:membros team))))
      (is (= "A" (get-in team [:membros 0 :nome]))))))

(deftest heal-pontos-do-servidor
  (let [s (fresh-store! "g47-heal")
        chat "chat-heal"
        player "brock"]
    (boot! s chat player)
    (reward-app/admin-seed-reward!
     s {:operation-id "seed-p" :chat-id chat :player-id player :save-id "a"
        :item {:item-id "pocao" :qty 2}})
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (gym-app/resolve-challenge!
     s chat player
     {:operation-id "win-pedra" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Brock"
      :participacao {0 0} :agora 1000})
    ;; desgaste via desafiante derrotado
    (boot! s chat "rival")
    (tx/set-gym-team!
     s {:chat-id chat :player-id "rival" :save-id "a"
        :fence-generation (fence s chat "rival") :indices [0 1 2]})
    (gym-app/resolve-challenge!
     s chat "rival"
     {:operation-id "lose-r" :gym-id "pedra" :venceu? false
      :dia "2026-10-17" :participacao {0 0} :agora 2000})
    (let [code (try
                 (tx/heal-gym-motivation!
                  s {:chat-id chat :player-id player :save-id "a"
                     :fence-generation (fence s chat player)
                     :operation-id "bad-item"
                     :gym-id "pedra" :indice 0 :item "ultra-bola" :agora-ms 3000})
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :unknown_heal_item code)))
    (let [r (tx/heal-gym-motivation!
             s {:chat-id chat :player-id player :save-id "a"
                :fence-generation (fence s chat player)
                :operation-id "heal-ok"
                :gym-id "pedra" :indice 0 :item "pocao" :agora-ms 3000})
          detail (reads/gym-detail s chat player "pedra" {:agora-ms 3000})]
      (is (= 88 (:antes r)))
      (is (= 100 (:depois r)))
      (is (= "pocao" (:item r)))
      (is (some? (get-in detail [:gym :ocupacao]))))))

(deftest a-b-bloqueado-com-defesa
  (let [s (fresh-store! "g47-ab")
        chat "chat-ab"
        player "leaf"]
    (boot! s chat player)
    (tx/set-gym-team!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player) :indices [0 1 2]})
    (gym-app/resolve-challenge!
     s chat player
     {:operation-id "lead" :gym-id "pedra" :venceu? true
      :dia "2026-10-17" :indices [0 1 2] :pedra-roll 0 :nome "Leaf"
      :participacao {0 0} :agora 1000})
    (let [code (try
                 (trainer/switch-save! s chat player "b")
                 :ok
                 (catch Exception ex (err/error-code ex)))]
      (is (= :gym_defense_pending code))
      (is (= "a" (ports/active-save-id s chat player))))))
(deftest catalogo-legado
  (is (= 5 (count gym/ginasios)))
  (is (true? (gym/desbloqueado? #{} "pedra")))
  (is (false? (gym/desbloqueado? #{} "agua"))))
