(ns pokemon.tests.battle-idle-test
  "Idle timeout de batalhas — domínio puro + persistência Datomic."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle :as battle]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.errors :as err]
            [pokemon.infrastructure.datomic.battle-idle-store :as idle-store]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-battle-idle-tests")
(def ^:private storage-rel ".datomic-local/battle-idle-tests")

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

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil))

(defn- wctx [s chat player]
  (writer/freeze-writer s chat player))

(deftest dominio-touch-e-expiracao-exata-300s
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (is (= 300 (idle/idle-timeout-segundos)))
    (let [t0 1700000000000
          t (idle/touch t0)]
      (is (= t0 (:last-activity-at t)))
      (is (= (+ t0 300000) (:idle-expires-at t)))
      (is (not (idle/expired? (:idle-expires-at t) (+ t0 299999))))
      (is (true? (idle/expired? (:idle-expires-at t) (+ t0 300000))))
      (is (true? (idle/expired? (:idle-expires-at t) (+ t0 300001)))))))

(deftest dominio-acao-valida-reinicia-prazo
  (let [t0 1000
        t1 (idle/touch t0 300000)
        t2 (idle/touch (+ t0 120000) 300000)]
    (is (= (+ t0 300000) (:idle-expires-at t1)))
    (is (= (+ t0 120000 300000) (:idle-expires-at t2))
        "nova ação válida desloca expires-at")))

(deftest dominio-expire-idempotente-sem-recompensa
  (let [b {:battle-id "b1" :kind :wild :phase :active :active? true
           :player-id "ash" :chat-id "c"}
        e1 (idle/expire-battle b)
        e2 (idle/expire-battle e1)]
    (is (= :idle-timeout (:outcome e1)))
    (is (false? (:active? e1)))
    (is (true? (:idle-expired? e1)))
    (is (= :idle-timeout (:outcome e2)))
    (is (= {:pokemon-xp 0 :coins 0} (battle/reward-for-outcome :idle-timeout)))))

(deftest dominio-receipt-e-notify-ids-estaveis
  (is (= "battle-idle-expire|b1" (idle/receipt-operation-id "b1")))
  (is (= "battle-idle-notify|b1|ash" (idle/notify-id "b1" "ash"))))

(deftest persistencia-expira-exato-300s-e-bloqueia-acao
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "idle-exact")
          chat "chat-idle" player "ash"
          _ (boot! s chat player)
          t0 1700000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-idle-1"
                    :battle-id "idle-b1"
                    :pokemon-x squirtle
                    :pokemon-o magikarp
                    :hp-x 100 :hp-o 20
                    :collection-index 0
                    :agora t0})
          bid (get-in started [:battle :battle-id])
          b (ports/get-battle s bid)]
      (is (= t0 (:last-activity-at b)))
      (is (= (+ t0 300000) (:idle-expires-at b)))
      (let [sweep-early (ports/expire-idle-battles! s {:agora (+ t0 299999) :limit 8})]
        (is (zero? (:expired-battles sweep-early)))
        (is (true? (:active? (ports/get-battle s bid)))))
      (let [sweep (ports/expire-idle-battles! s {:agora (+ t0 300000) :limit 8})
            b2 (ports/get-battle s bid)]
        (is (= 1 (:expired-battles sweep)))
        (is (= :idle-timeout (:outcome b2)))
        (is (false? (:active? b2)))
        (is (true? (:idle-expired? b2)))
        (is (nil? (ports/active-battle s chat player))))
      (let [code (try
                   (ports/confirm-battle-turn!
                    s (wctx s chat player)
                    {:operation-id "turn-after-idle"
                     :battle-id bid
                     :expected-turn-index 0
                     :action :attack
                     :move-index 0
                     :agora (+ t0 300001)
                     :rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99}})
                   :ok
                   (catch Exception ex (err/error-code ex)))]
        (is (= :battle_idle_expired code))))))

(deftest acao-valida-renova-idle-invalida-nao
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "idle-renew")
          chat "chat-r" player "ash"
          _ (boot! s chat player)
          t0 2000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-r"
                    :battle-id "renew-b"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          ;; Ação inválida (stale turn) — não renova.
          stale-code (try
                       (ports/confirm-battle-turn!
                        s (wctx s chat player)
                        {:operation-id "stale-1"
                         :battle-id bid
                         :expected-turn-index 99
                         :action :attack :move-index 0
                         :agora (+ t0 10000)
                         :rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99}})
                       :ok
                       (catch Exception ex (err/error-code ex)))
          b-after-stale (ports/get-battle s bid)
          ;; Ação válida — renova.
          _ (ports/confirm-battle-turn!
             s (wctx s chat player)
             {:operation-id "turn-ok"
              :battle-id bid
              :expected-turn-index 0
              :action :defend
              :agora (+ t0 60000)
              :rng {:accuracy 0 :crit 99 :damage-var 0 :flee 99
                    :impede-para 99}})
          b-ok (ports/get-battle s bid)]
      (is (= :stale_turn_index stale-code))
      (is (= (+ t0 300000) (:idle-expires-at b-after-stale))
          "stale não renova prazo")
      (is (= (+ t0 60000 300000) (:idle-expires-at b-ok))
          "turno válido renova a partir de agora"))))

(deftest jvm-restart-preserva-prazo
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [db "idle-restart"
          s (fresh-store! db)
          chat "chat-rs" player "ash"
          _ (boot! s chat player)
          t0 3000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-rs"
                    :battle-id "rs-b"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          expires (:idle-expires-at (ports/get-battle s bid))
          s2 (reopen-store db)
          b2 (ports/get-battle s2 bid)]
      (is (= expires (:idle-expires-at b2)))
      (let [sweep (ports/expire-idle-battles! s2 {:agora expires :limit 4})]
        (is (= 1 (:expired-battles sweep)))
        (is (= :idle-timeout (:outcome (ports/get-battle s2 bid))))))))

(deftest expire-concorrente-nao-duplica
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "idle-dup")
          chat "chat-d" player "ash"
          _ (boot! s chat player)
          t0 4000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-d"
                    :battle-id "dup-b"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          agora (+ t0 300000)
          r1 (idle-store/expire-battle! (:conn s) bid agora)
          r2 (idle-store/expire-battle! (:conn s) bid agora)
          notes (ports/list-battle-idle-notifications s {:limit 10})]
      (is (false? (:duplicate r1)))
      (is (true? (:duplicate r2)))
      (is (= 1 (count notes))
          "uma notificação por jogador")
      (let [ack1 (ports/ack-battle-idle-notification! s (:id (first notes)))
            ack2 (ports/ack-battle-idle-notification! s (:id (first notes)))
            notes2 (ports/list-battle-idle-notifications s {:limit 10})]
        (is (false? (:duplicate ack1)))
        (is (true? (:duplicate ack2)))
        (is (empty? notes2))))))

(deftest aguardando-captura-tambem-expira-em-300s
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "idle-done")
          chat "chat-done" player "ash"
          _ (boot! s chat player)
          t0 5000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-done"
                    :battle-id "done-b"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          ;; KO do selvagem → aguardando captura (ainda pendente).
          _ (ports/confirm-battle-turn!
             s (wctx s chat player)
             {:operation-id "ko-1"
              :battle-id bid
              :expected-turn-index 0
              :action :attack :move-index 0
              :agora (+ t0 1000)
              :rng {:accuracy 0 :crit 0 :damage-var 0 :flee 99}})
          b (ports/get-battle s bid)
          expires (:idle-expires-at b)
          sweep (ports/expire-idle-battles! s {:agora expires :limit 8})
          b2 (ports/get-battle s bid)]
      (is (true? (:aguardando-captura? b)))
      (is (= 1 (:expired-battles sweep))
          "aguardando-captura também expira no idle de 300s")
      (is (= :idle-timeout (:outcome b2)))
      (is (false? (:aguardando-captura? b2))))))

(deftest force-encerra-todas-abertas-ja
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [s (fresh-store! "idle-force")
          chat "chat-f" player "ash"
          _ (boot! s chat player)
          t0 7000000000000
          started (ports/start-wild-battle!
                   s (wctx s chat player)
                   {:operation-id "start-f"
                    :battle-id "force-b"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          ;; Ainda dentro da janela de 300s — force ignora prazo.
          sweep (ports/expire-idle-battles!
                 s {:agora (+ t0 1000) :limit 8 :force true})
          b (ports/get-battle s bid)]
      (is (= 1 (:expired-battles sweep)))
      (is (true? (:force sweep)))
      (is (= :idle-timeout (:outcome b)))
      (is (false? (:active? b)))
      (is (nil? (ports/active-battle s chat player)))
      (is (= 300 (idle/idle-timeout-segundos))
          "timer padrão permanece 5 minutos"))))
