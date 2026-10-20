(ns pokemon.tests.pvp-a3-test
  "A3 — PvP 1×1: desafio, aceitação, turnos, idempotência e retomada."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.pvp :as pvp-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.pvp :as pvp]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-a3-pvp-tests")
(def ^:private storage-rel ".datomic-local/a3-pvp-tests")

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

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-balls-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-pocao-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pocao" :qty 2}})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-coins-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :coins-delta 50}))

(def ^:private pikachu
  {:nome "Pikachu" :species "pikachu" :raridade "comum"
   :hp 45 :ataque 55 :defesa 40 :atq-esp 50 :def-esp 50 :veloc 90
   :tipos ["electric"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Choque do Trovão" :tipo "electric" :poder 40 :classe :especial
             :precisao 100}
            {:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico
             :precisao 100}]})

(def ^:private squirtle
  {:nome "Squirtle" :species "squirtle" :raridade "comum"
   :hp 44 :ataque 48 :defesa 65 :atq-esp 50 :def-esp 64 :veloc 43
   :tipos ["water"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Jato de Água" :tipo "water" :poder 40 :classe :especial
             :precisao 100}]})

(def ^:private magikarp-fraco
  "HP baixo no nível 20 — entra na faixa ±3 do Hitmonlee/Pikachu."
  {:nome "Magikarp" :species "magikarp" :raridade "comum"
   :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private magikarp-nv5
  {:nome "Magikarp" :species "magikarp" :raridade "comum"
   :hp 20 :ataque 10 :defesa 55 :atq-esp 15 :def-esp 20 :veloc 80
   :tipos ["water"] :nivel 5 :shiny? false
   :golpes [{:nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private hitmonlee-forte
  "Atacante rápido o suficiente para matar Magikarp em 1 hit com RNG fixo."
  {:nome "Hitmonlee" :species "hitmonlee" :raridade "incomum"
   :hp 50 :ataque 120 :defesa 53 :atq-esp 35 :def-esp 110 :veloc 87
   :tipos ["fighting"] :nivel 20 :shiny? false
   :golpes [{:nome-exibicao "Chute Duplo" :tipo "fighting" :poder 60 :classe :fisico
             :precisao 100}]})

(def ^:private rng-hit
  "Precisão sempre acerta; crítico/esquiva off; dano estável."
  {:accuracy 0 :crit 99 :dodge 99 :damage-var 0
   :impede-para 99 :contagio 99 :status-golpe 99
   :cura-sono 99 :cura-gelo 99 :cura-confusao 99 :auto-confusao 99})

(defn- ativo-meta
  [s chat player]
  (let [save (ports/get-save s chat player "a")
        col (ports/list-collection s chat player "a")
        idx (long (:ativo-index save 0))
        poke (nth col idx)]
    {:collection-index idx :pokemon-id (:id-pokemon poke)}))

(defn- open!
  [s chat player op & {:keys [name agora]}]
  (pvp-app/open-challenge!
   s chat player
   (cond-> (merge (ativo-meta s chat player)
                  {:operation-id op :player-name (or name player)})
     agora (assoc :agora agora))))

(defn- accept!
  [s chat player battle-id op & {:keys [name first-turn-tie agora]}]
  (pvp-app/accept-challenge!
   s chat player
   (cond-> (merge (ativo-meta s chat player)
                  {:operation-id op
                   :battle-id battle-id
                   :player-name (or name player)})
     first-turn-tie (assoc :first-turn-tie first-turn-tie)
     agora (assoc :agora agora))))

(defn- turn!
  [s chat player battle expected action op & {:keys [move-index item-id rng]}]
  (let [side (pvp/side-of-player (ports/get-battle s battle) player)]
    (pvp-app/confirm-turn!
     s chat player
     (cond-> {:operation-id op
              :battle-id battle
              :expected-turn-index expected
              :action action
              :actor-side side}
       (some? move-index) (assoc :move-index move-index)
       item-id (assoc :item-id item-id)
       rng (assoc :rng rng)))))
(defn- err-code [thunk]
  (try (thunk) :ok
       (catch Exception ex (err/error-code ex))))

(deftest desafio-aceitacao-recusa-e-restricoes
  (let [db "a3-desafio"
        s (fresh-store! db)
        chat "chat-a3-1"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (boot! s chat "brock")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (collection/adicionar-pokemon! s chat "misty" squirtle 44 nil)
    (collection/adicionar-pokemon! s chat "brock" pikachu 45 nil)

    (testing "desafio válido"
      (let [r (open! s chat "ash" "open-1" :name "Ash")]
        (is (false? (:duplicate r)))
        (is (= :waiting (get-in r [:battle :phase])))
        (is (true? (pvp/aguardando? (:battle r))))
        (is (= "ash" (get-in r [:battle :jogadores :x])))))

    (testing "desafio duplicado (mesmo operation-id)"
      (let [r2 (open! s chat "ash" "open-1" :name "Ash")]
        (is (true? (:duplicate r2)))
        (is (some? (:battle r2)))
        (is (= :waiting (get-in r2 [:battle :phase])))))

    (testing "chat ocupado — segundo desafio"
      (is (= :pvp_chat_busy
             (err-code #(open! s chat "brock" "open-busy")))))

    (testing "aceitação"
      (let [bid (get-in (ports/active-pvp-battle s chat) [:battle-id])
            r (accept! s chat "misty" bid "accept-1" :name "Misty"
                       :first-turn-tie :x)]
        (is (false? (:duplicate r)))
        (is (= :active (get-in r [:battle :phase])))
        (is (= :x (get-in r [:battle :vez])))
        (is (= "misty" (get-in r [:battle :jogadores :o])))))

    (testing "aceitação duplicada"
      (let [bid (get-in (ports/get-battle s
                                          (:battle-id (ports/active-pvp-battle s chat)))
                        [:battle-id])]
        (let [r (accept! s chat "misty" bid "accept-1" :name "Misty"
                         :first-turn-tie :x)]
          (is (true? (:duplicate r)))
          (is (= :active (get-in r [:battle :phase]))))))

    (testing "terceiro não autorizado após batalha ativa"
      (let [bid (:battle-id (ports/active-pvp-battle s chat))]
        (is (= :pvp_not_waiting
               (err-code #(accept! s chat "brock" bid "accept-3rd"))))))

    (testing "recusa / cancelamento pelo desafiante em batalha → forfeit"
      (let [bid (:battle-id (ports/active-pvp-battle s chat))
            r (pvp-app/cancel! s chat "ash"
                               {:operation-id "cancel-1" :battle-id bid})]
        (is (= :forfeit-x (get-in r [:battle :outcome])))
        (is (nil? (ports/active-pvp-battle s chat)))))))

(deftest desafio-a-si-mesmo-e-treinador-ocupado
  (let [s (fresh-store! "a3-self")
        chat "chat-self"]
    (boot! s chat "ash")
    (boot! s chat "gary")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (collection/adicionar-pokemon! s chat "gary" squirtle 44 nil)
    (let [r (open! s chat "ash" "open-self")
          bid (get-in r [:battle :battle-id])]
      (is (= :pvp_self_accept
             (err-code #(accept! s chat "ash" bid "acc-self"))))
      (pvp-app/cancel! s chat "ash" {:operation-id "cancel-self" :battle-id bid}))
    ;; Ash abre; Gary aceita; Ash ocupado não abre segundo desafio no mesmo chat.
    (let [r (open! s chat "ash" "open-2")
          bid (get-in r [:battle :battle-id])]
      (accept! s chat "gary" bid "acc-2" :first-turn-tie :x)
      (is (contains? #{:pvp_chat_busy :battle_already_active}
                     (err-code #(open! s chat "ash" "open-again"))))
      (is (= :battle_already_active
             (err-code #(open! s chat "gary" "open-as-gary")))))))

(deftest nivel-incompativel
  (let [s (fresh-store! "a3-lvl")
        chat "chat-lvl"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil) ; nv 20
    (collection/adicionar-pokemon! s chat "misty" magikarp-nv5 20 nil)
    (let [bid (get-in (open! s chat "ash" "open-lvl") [:battle :battle-id])]
      (is (= :pvp_level_incompatible
             (err-code #(accept! s chat "misty" bid "acc-lvl")))))))

(defn- grind-pvp-until-done!
  [s chat bid max-turns]
  (loop [n 0 last-t nil]
    (let [b (ports/active-pvp-battle s chat)]
      (if (or (nil? b) (not (:active? b)) (>= n max-turns))
        last-t
        (let [vez (:vez b)
              pid (get-in b [:jogadores vez])
              t (turn! s chat pid bid (:turn-index b) :attack (str "atk-loop-" n)
                       :move-index 0 :rng rng-hit)]
          (recur (inc n) t))))))

(deftest combate-turnos-defesa-nocaute-recompensa
  (let [db "a3-combate"
        s (fresh-store! db)
        chat "chat-fight"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" hitmonlee-forte 50 nil)
    (collection/adicionar-pokemon! s chat "misty" magikarp-fraco 20 nil)
    (let [open (open! s chat "ash" "open-f" :name "Ash")
          bid (get-in open [:battle :battle-id])
          acc (accept! s chat "misty" bid "acc-f" :name "Misty" :first-turn-tie :x)
          coins-antes (:coins (ports/get-save s chat "ash" "a") 0)]
      (is (= :x (get-in acc [:battle :vez])))
      (is (= 0 (get-in acc [:battle :turn-index])))

      (testing "ataque fora de turno"
        (is (= :pvp_out_of_turn
               (err-code #(turn! s chat "misty" bid 0 :attack "bad-turn"
                                 :move-index 0 :rng rng-hit)))))

      (let [t1 (turn! s chat "ash" bid 0 :attack "atk-1"
                      :move-index 0 :rng rng-hit)
            b (:battle t1)]
        (is (= :attack (get-in b [:last-action :action])))
        (is (true? (get-in b [:last-action :acertou?])))
        (when (:active? b)
          (is (= 1 (:turn-index b)))
          (is (= :o (:vez b)))))

      (let [fin (grind-pvp-until-done! s chat bid 8)
            outcome (or (get-in fin [:battle :outcome])
                        (:outcome (ports/get-battle s bid)))
            save (ports/get-save s chat "ash" "a")
            bola (some #(when (= "pokebola" (:item-id %)) %)
                       (ports/list-inventory s chat "ash" "a"))]
        (is (contains? #{:x :o} outcome))
        (is (nil? (ports/active-pvp-battle s chat)))
        (when (= :x outcome)
          (is (= (+ coins-antes pvp/moedas-por-vitoria) (:coins save)))
          (is (pos? (:qty bola 0))))))))

(deftest defesa-e-pocao-mid-pvp
  (let [s (fresh-store! "a3-def")
        chat "chat-def"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (collection/adicionar-pokemon! s chat "misty" squirtle 44 nil)
    (let [bid (get-in (open! s chat "ash" "open-d") [:battle :battle-id])]
      (accept! s chat "misty" bid "acc-d" :first-turn-tie :x)
      (let [d1 (turn! s chat "ash" bid 0 :defend "def-1")
            b (:battle d1)]
        (is (= :defend (get-in b [:last-action :action])))
        (is (= :o (:vez b)))
        (is (true? (get-in b [:defendendo :x]))))
      ;; Ash atacou/defendeu; Misty sofre dano de um ataque e cura com poção.
      (turn! s chat "misty" bid 1 :defend "def-m")
      (let [atk (turn! s chat "ash" bid 2 :attack "atk-d" :move-index 0 :rng rng-hit)
            hp-o (get-in atk [:battle :hp :o])]
        (is (< hp-o 44))
        (let [qty-antes (or (->> (ports/list-inventory s chat "misty" "a")
                                 (filter #(= "pocao" (:item-id %)))
                                 first :qty)
                            0)
              t3 (turn! s chat "misty" bid 3 :item "item-1" :item-id "pocao")
              qty-depois (or (->> (ports/list-inventory s chat "misty" "a")
                                  (filter #(= "pocao" (:item-id %)))
                                  first :qty)
                             0)]
          (is (= :item (get-in t3 [:battle :last-action :action])))
          (is (= (dec qty-antes) qty-depois))
          (is (> (get-in t3 [:battle :hp :o]) hp-o)))))))

(deftest idempotencia-retry-e-retomada
  (let [db "a3-idemp"
        s (fresh-store! db)
        chat "chat-id"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (collection/adicionar-pokemon! s chat "misty" squirtle 44 nil)
    (let [bid (get-in (open! s chat "ash" "open-i") [:battle :battle-id])]
      (accept! s chat "misty" bid "acc-i" :first-turn-tie :x)

      (testing "reinício durante batalha"
        (let [s2 (reopen-store db)
              b (ports/active-pvp-battle s2 chat)]
          (is (some? b))
          (is (= :active (:phase b)))
          (is (= bid (:battle-id b)))
          (is (= :x (:vez b)))))

      (let [s2 (reopen-store db)
            t1 (turn! s2 chat "ash" bid 0 :attack "atk-i"
                      :move-index 0 :rng rng-hit)
            hp-o (get-in t1 [:battle :hp :o])
            t1b (turn! s2 chat "ash" bid 0 :attack "atk-i"
                       :move-index 0 :rng rng-hit)]
        (is (true? (:duplicate t1b)))
        (is (= hp-o (get-in t1b [:battle :hp :o])))
        (is (= 1 (get-in t1b [:battle :turn-index])))

        (testing "ação concorrente / índice velho"
          (is (= :stale_turn_index
                 (err-code #(turn! s2 chat "misty" bid 0 :attack "stale"
                                   :move-index 0 :rng rng-hit))))
          (is (= :pvp_out_of_turn
                 (err-code #(turn! s2 chat "ash" bid 1 :attack "wrong-side"
                                   :move-index 0 :rng rng-hit)))))))))

(deftest retomada-desafio-pendente
  (let [db "a3-wait"
        s (fresh-store! db)
        chat "chat-wait"]
    (boot! s chat "ash")
    (collection/adicionar-pokemon! s chat "ash" pikachu 45 nil)
    (open! s chat "ash" "open-w")
    (let [s2 (reopen-store db)
          b (ports/active-pvp-battle s2 chat)]
      (is (true? (pvp/aguardando? b)))
      (boot! s2 chat "misty")
      (collection/adicionar-pokemon! s2 chat "misty" squirtle 44 nil)
      ;; Pikachu (90) > Squirtle (43) — vez :x independente do tie-break.
      (let [r (accept! s2 chat "misty" (:battle-id b) "acc-w" :first-turn-tie :o)]
        (is (= :active (get-in r [:battle :phase])))
        (is (= :x (get-in r [:battle :vez])))))))

(deftest recompensa-uma-vez-no-nocaute
  (let [s (fresh-store! "a3-rew")
        chat "chat-rew"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (collection/adicionar-pokemon! s chat "ash" hitmonlee-forte 50 nil)
    (collection/adicionar-pokemon! s chat "misty" magikarp-fraco 20 nil)
    (let [bid (get-in (open! s chat "ash" "open-r") [:battle :battle-id])]
      (accept! s chat "misty" bid "acc-r" :first-turn-tie :x)
      (let [coins0 (:coins (ports/get-save s chat "ash" "a"))
            t1 (turn! s chat "ash" bid 0 :attack "ko-1"
                      :move-index 0 :rng rng-hit)
            ;; Se não KO, mais um turno do oponente + ash
            fin (if (get-in t1 [:battle :active?])
                  (let [b (:battle t1)
                        pid (get-in b [:jogadores (:vez b)])]
                    (turn! s chat pid bid 1 :attack "ko-2"
                           :move-index 0 :rng rng-hit))
                  t1)
            ;; Retry do mesmo operation-id do KO — payload idêntico (turn 0).
            fin2 (turn! s chat "ash" bid 0 :attack "ko-1"
                        :move-index 0 :rng rng-hit)]
        (is (false? (get-in fin [:battle :active?])))
        (is (= :x (get-in fin [:battle :outcome])))
        (is (true? (:duplicate fin2)))
        (is (= (+ coins0 pvp/moedas-por-vitoria)
               (:coins (ports/get-save s chat "ash" "a"))))))))
