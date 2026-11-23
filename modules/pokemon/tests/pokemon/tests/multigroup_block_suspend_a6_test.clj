(ns pokemon.tests.multigroup-block-suspend-a6-test
  "A6 — suspensão segura sob !bloquear + fail-closed + agenda admin-disabled."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.application.writer :as writer]
            [pokemon.domain.battle-idle :as idle]
            [pokemon.domain.battle :as battle-domain]
            [pokemon.domain.identities :as ids]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.http.bridge :as bridge]))

(def ^:private system "odisseu-mg-block-suspend-a6")
(def ^:private storage-rel ".datomic-local/mg-block-suspend-a6")
(def ^:private grupo-a "grupo-a@g.us")
(def ^:private grupo-b "grupo-b@g.us")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      [s conn])))

(def ^:private squirtle
  {:nome "Squirtle" :hp 100 :ataque 200 :defesa 70 :atq-esp 200 :def-esp 65 :veloc 50
   :tipos ["water"] :nivel 10
   :golpes [{:nome-exibicao "Jato" :tipo "water" :poder 80 :classe :especial}]})

(def ^:private magikarp
  {:nome "Magikarp" :species "magikarp" :hp 20 :ataque 10 :defesa 20 :atq-esp 10
   :def-esp 20 :veloc 80 :tipos ["water"] :nivel 1 :taxa-captura 255
   :golpes [{:slug "splash" :nome-exibicao "Splash" :tipo "normal" :poder 0 :classe :status}]})

(def ^:private mon
  {:nome "Snorlax" :hp 200 :ataque 80 :defesa 50 :atq-esp 50 :def-esp 50 :veloc 40
   :tipos ["normal"] :nivel 8 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 80 :classe :fisico}]})

(defn- boot!
  [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "pokebola" :qty 5}})
  (collection/adicionar-pokemon! s chat player squirtle 100 nil)
  (collection/adicionar-pokemon! s chat player mon 200 nil))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- wctx [s chat player]
  (writer/freeze-writer s chat player))

(deftest caca-bloqueada-5min-depois-desbloqueada-sem-derrota
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "APP_ENV" "staging"} k))]
    (let [[s _] (fresh! "suspend-wild")
          t0 1700000000000
          _ (boot! s grupo-a "ash")
          _ (boot! s grupo-b "misty")
          started (ports/start-wild-battle!
                   s (wctx s grupo-a "ash")
                   {:operation-id "w-a1" :battle-id "wild-a1"
                    :pokemon-x squirtle :pokemon-o magikarp
                    :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid (get-in started [:battle :battle-id])
          started-b (ports/start-wild-battle!
                     s (wctx s grupo-b "misty")
                     {:operation-id "w-b1" :battle-id "wild-b1"
                      :pokemon-x squirtle :pokemon-o magikarp
                      :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
          bid-b (get-in started-b [:battle :battle-id])
          ;; Bloqueia A: suspende prazos (1 TX).
          sus (ports/suspend-chat-gameplay! s grupo-a t0)
          _ (is (true? (:suspended (first (:battles sus)))))
          mid (+ t0 (* 6 60 1000))
          ;; Sweeper: A bloqueado sobrevive; B (não bloqueado) pode expirar após 5min.
          sw1 (ports/expire-idle-battles!
               s {:agora mid :limit 16 :blocked-chat-ids [grupo-a] :blocks-ok? true})
          _ (is (true? (:active? (ports/get-battle s bid))))
          _ (is (false? (:active? (ports/get-battle s bid-b))))
          ;; Passa 5+ min do idle original sem resume → ainda vivo por hold.
          past-original (+ t0 300000 1)
          sw2 (ports/expire-idle-battles!
               s {:agora past-original :limit 16
                  :blocked-chat-ids [grupo-a] :blocks-ok? true})
          _ (is (zero? (:expired-battles sw2)))
          ;; Desbloqueia: retoma com prazo fresco.
          res (ports/resume-chat-gameplay! s grupo-a mid)
          _ (is (true? (:resumed (first (:battles res)))))
          b (ports/get-battle s bid)
          _ (is (true? (:active? b)))
          _ (is (= (+ mid 300000) (:idle-expires-at b)))
          ;; Sem derrota imediata no resume
          sw3 (ports/expire-idle-battles!
               s {:agora mid :limit 16 :blocked-chat-ids [] :blocks-ok? true})]
      (is (zero? (:expired-battles sw3)))
      (is (true? (:active? (ports/get-battle s bid))))
      ;; Sequência/inventário intactos
      (is (pos? (count (ports/list-collection s grupo-a "ash" "a")))))))

(deftest raid-turno-60s-bloqueado-sem-skip
  (with-redefs [idle/environment (fn [k]
                                   ({"POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS" "300"
                                     "POKEMON_RAID_TURN_TIMEOUT_SECONDS" "60"
                                     "APP_ENV" "staging"} k))]
    (let [[s conn] (fresh! "suspend-raid")
          t0 2000000000000
          _ (boot! s grupo-a "ash")
          _ (boot! s grupo-a "misty")
          _ (raid-auto/upsert-schedule!
             s {:chat-id grupo-a :enabled true
                :interval-ms domain/min-interval-ms
                :next-at t0 :ginasio-index 0 :agora-ms t0})
          _ (raid-auto/tick! s {:agora-ms t0 :global-enabled? true :chat-id grupo-a})
          raid0 (raid-app/active-raid s grupo-a)
          _ (tx/join-raid!
             s {:chat-id grupo-a :player-id "ash" :save-id "a"
                :fence-generation (fence s grupo-a "ash")
                :operation-id "j-ash" :collection-index 1 :nome "Ash" :agora-ms (inc t0)})
          _ (tx/join-raid!
             s {:chat-id grupo-a :player-id "misty" :save-id "a"
                :fence-generation (fence s grupo-a "misty")
                :operation-id "j-misty" :collection-index 1 :nome "Misty"
                :agora-ms (+ t0 2)})
          st (tx/start-raid!
              s {:chat-id grupo-a :player-id "ash" :save-id "a"
                 :fence-generation (fence s grupo-a "ash")
                 :operation-id "st-a" :agora-ms (+ t0 3)})
          raid (or (:raid st) (raid-app/active-raid s grupo-a))
          rid (:raid-id raid)
          ;; Streak positiva em ambos: timeout de raid não pode zerar nem um.
          _ (doseq [pid ["ash" "misty"]]
              (d/transact conn {:tx-data
                                [[:db/add [:save/key (ids/save-key grupo-a pid "a")]
                                  :save/sequencia-capturas 4]
                                 [:db/add [:save/key (ids/save-key grupo-a pid "a")]
                                  :save/maior-sequencia-capturas 7]]}))
          _ (is (= "combate" (str (:fase raid))))
          texp (or (:turn-expires-at raid)
                   (:idle-expires-at raid)
                   (+ t0 60000))
          sus (ports/suspend-chat-gameplay! s grupo-a (+ t0 3))
          _ (is (seq (:raids sus)))
          after-sus (raid-app/active-raid s grupo-a)
          _ (is (= "combate" (str (:fase after-sus))))
          _ (when (:turn-expires-at after-sus)
              (is (>= (long (:turn-expires-at after-sus)) (long texp))))
          past-turn (+ (long texp) 1)
          sw (ports/expire-idle-battles!
              s {:agora past-turn :limit 16
                 :blocked-chat-ids [grupo-a] :blocks-ok? true})
          _ (is (zero? (:skipped-turns sw)))
          _ (is (= "combate" (str (:fase (raid-app/active-raid s grupo-a)))))
          mid (+ t0 120000)
          res (ports/resume-chat-gameplay! s grupo-a mid)
          resumed (raid-app/active-raid s grupo-a)]
      (is (seq (:raids res)))
      (is (= "combate" (str (:fase resumed))))
      (when (:turn-expires-at resumed)
        (is (> (long (:turn-expires-at resumed)) mid)))
      (is (zero? (:skipped-turns
                  (ports/expire-idle-battles!
                   s {:agora mid :limit 16 :blocked-chat-ids [] :blocks-ok? true}))))
      (is (= rid (:raid-id resumed)))
      ;; Vencimento após desbloquear: raid termina, saves mantêm streak.
      (let [deadline (:idle-expires-at resumed)]
        (is (some? deadline))
        (when deadline
          (ports/expire-idle-battles! s {:agora (inc (long deadline))
                                         :limit 16 :blocked-chat-ids []
                                         :blocks-ok? true})
          (is (= "idle-timeout" (str (:fase (raid-app/get-raid s rid)))))
          (doseq [pid ["ash" "misty"]]
            (let [save (ports/get-save s grupo-a pid "a")]
              (is (= 4 (:sequencia-capturas save)))
              (is (= 7 (:maior-sequencia-capturas save))))))))))


(deftest fail-closed-blocks-unavailable
  (let [[s _] (fresh! "fail-closed")
        t0 3000
        _ (boot! s grupo-a "ash")
        started (ports/start-wild-battle!
                 s (wctx s grupo-a "ash")
                 {:operation-id "fc-1" :battle-id "fc-b1"
                  :pokemon-x squirtle :pokemon-o magikarp
                  :hp-x 100 :hp-o 20 :collection-index 0 :agora t0})
        bid (get-in started [:battle :battle-id])
        sw (ports/expire-idle-battles!
            s {:agora (+ t0 300000) :limit 8
               :blocked-chat-ids [] :blocks-ok? false})]
    (is (true? (:fail-closed sw)))
    (is (zero? (:expired-battles sw)))
    (is (true? (:active? (ports/get-battle s bid))))
    (let [tick (raid-auto/tick!
                s {:agora-ms (+ t0 300000) :global-enabled? true
                   :blocks-ok? false :blocked-chat-ids []})]
      (is (true? (:fail-closed tick)))
      (is (empty? (:spawned tick))))))

(deftest grupo-a-bloqueado-b-funciona
  (let [[s _] (fresh! "ab-iso")
        agora 8000
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-a :enabled true :interval-ms domain/min-interval-ms
              :next-at agora :agora-ms agora})
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-b :enabled true :interval-ms domain/min-interval-ms
              :next-at agora :agora-ms agora})
        tick (raid-auto/tick!
              s {:agora-ms agora :global-enabled? true
                 :blocked-chat-ids [grupo-a] :blocks-ok? true})
        spawned (set (map :chat-id (filter #(= :spawn (:acao %)) (:spawned tick))))]
    (is (not (contains? spawned grupo-a)))
    (is (contains? spawned grupo-b))))

(deftest agenda-admin-disabled-permanece
  (let [[s _] (fresh! "admin-off")
        _ (trainer/create-trainer! s {:chat-id grupo-a :player-id "ash"})
        _ (tx/choose-starter!
           s {:chat-id grupo-a :player-id "ash"
              :operation-id "st-admin-1" :choice "1"})
        sa0 (raid-auto/get-schedule s grupo-a)
        _ (is (true? (:enabled sa0)))
        ;; Admin desliga
        _ (raid-auto/upsert-schedule!
           s {:chat-id grupo-a :enabled false
              :next-at (:next-at sa0) :agora-ms (System/currentTimeMillis)})
        sa1 (ports/get-raid-auto-schedule s grupo-a)
        ens (raid-auto/ensure-schedule-after-onboarding! s grupo-a)
        recon (raid-auto/reconcile-schedules-for-starters! s)]
    (is (false? (:enabled sa1)))
    (is (= :admin-disabled (:skipped ens)))
    (is (false? (:enabled (raid-auto/get-schedule s grupo-a))))
    (is (some #(= :admin-disabled (:skipped %)) (:results recon)))))

(deftest onboarding-repete-sem-duplicar-agenda
  (let [[s _] (fresh! "onboard-dup")
        _ (trainer/create-trainer! s {:chat-id grupo-a :player-id "ash"})
        c1 (tx/choose-starter!
            s {:chat-id grupo-a :player-id "ash"
               :operation-id "dup-1" :choice "1"})
        sch1 (:next-at (raid-auto/get-schedule s grupo-a))
        c2 (tx/choose-starter!
            s {:chat-id grupo-a :player-id "ash"
               :operation-id "dup-1" :choice "1"})
        ens (raid-auto/ensure-schedule-after-onboarding! s grupo-a)]
    (is (true? (:duplicate c2)))
    (is (true? (:already ens)))
    (is (= sch1 (:next-at (raid-auto/get-schedule s grupo-a))))
    (is (some? (:raid-auto-schedule c1)))))

(deftest resolve-blocked-fail-closed-sem-body
  (let [r (bridge/resolve-blocked-chat-ids nil nil)]
    (is (true? (:ok r)))
    (is (empty? (:ids r))))
  (let [bad (reify odisseu.platform.application.ports/BlockStore
              (get-block-state [_] (throw (ex-info "boom" {})))
              (put-block-state! [_ _] nil)
              (get-chat-block [_ _] nil)
              (set-bot-blocked! [_ _ _ _] nil)
              (block-commands! [_ _ _ _] nil)
              (unblock-commands! [_ _ _ _] nil))
        r (bridge/resolve-blocked-chat-ids bad [])]
    (is (false? (:ok r)))
    (is (true? (:fail-closed? r))))
  (let [bad (reify odisseu.platform.application.ports/BlockStore
              (get-block-state [_] (throw (ex-info "boom" {})))
              (put-block-state! [_ _] nil)
              (get-chat-block [_ _] nil)
              (set-bot-blocked! [_ _ _ _] nil)
              (block-commands! [_ _ _ _] nil)
              (unblock-commands! [_ _ _ _] nil))
        r (bridge/resolve-blocked-chat-ids bad ["grupo-a@g.us"])]
    (is (true? (:ok r)))
    (is (true? (:degraded? r)))
    (is (= ["grupo-a@g.us"] (:ids r)))))

(deftest derrota-selvagem-encerra-sequencia-sem-afetar-recorde
  (let [[s conn] (fresh! "wild-defeat-streak")
        t0 2000000000000
        _ (boot! s grupo-a "ash")
        save-key (ids/save-key grupo-a "ash" "a")]
    (d/transact conn {:tx-data [[:db/add [:save/key save-key] :save/sequencia-capturas 4]
                                [:db/add [:save/key save-key] :save/maior-sequencia-capturas 7]]})
    (ports/start-wild-battle!
     s (wctx s grupo-a "ash")
     {:operation-id "wild-lose-start" :battle-id "wild-lose-b1"
      :pokemon-x squirtle :pokemon-o magikarp
      :hp-x 1 :hp-o 20 :collection-index 0 :agora t0})
    (with-redefs [battle-domain/apply-player-turn
                  (fn [state _ _]
                    (-> state
                        (assoc :active? false :phase :finished
                               :outcome :lose :aguardando-captura? false)
                        (assoc-in [:hp :x] 0)
                        (update :turn-index (fnil inc 0))))]
      (let [r (ports/confirm-battle-turn!
               s (wctx s grupo-a "ash")
               {:operation-id "wild-lose-turn"
                :battle-id "wild-lose-b1" :expected-turn-index 0
                :action :attack :move-index 0 :agora (inc t0)})
            save (ports/get-save s grupo-a "ash" "a")]
        (is (= :lose (get-in r [:battle :outcome])))
        (is (= 0 (:sequencia-capturas save)))
        (is (= 7 (:maior-sequencia-capturas save)))))))
