(ns pokemon.tests.raid-turn-gym-level-hotfix-test
  "Hotfix: 1º turno no raid-started auto · sem nível mínimo · ginásio por tipo."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid-auto :as domain]
            [pokemon.domain.raid-gym-theme :as gym-theme]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-turn-gym-level-hotfix")
(def ^:private storage-rel ".datomic-local/turn-gym-level-hotfix")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(def ^:private mon-nv1
  {:nome "Magikarp" :hp 40 :ataque 10 :defesa 20 :atq-esp 10 :def-esp 20 :veloc 30
   :tipos ["water"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]})

(def ^:private chefe
  {:nome "Moltres" :species "moltres" :hp 144 :ataque 154 :defesa 144
   :atq-esp 175 :def-esp 140 :veloc 144
   :tipos ["fire" "flying"] :nivel 20 :raridade "lendario"
   :fator-dificuldade 1.0
   :golpes [{:nome-exibicao "Lança-chamas" :tipo "fire" :poder 88 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player mon]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 200 nil))

;;;; Tema por tipo

(deftest venue-cobre-18-tipos
  (is (= 18 (count gym-theme/type-venues)))
  (doseq [t ["normal" "fire" "water" "grass" "electric" "ice" "fighting" "poison"
             "ground" "flying" "psychic" "bug" "rock" "ghost" "dragon" "dark"
             "steel" "fairy"]]
    (is (some? (gym-theme/venue-for-type t)) t)))

(deftest venue-usa-tipo-primario
  (let [v (gym-theme/venue-for-boss {:tipos ["fire" "flying"]})]
    (is (= "fire" (:tipo v)))
    (is (re-find #"Fogo" (:nome v))))
  (let [v (gym-theme/venue-for-boss {:tipos ["psychic"]})]
    (is (= "psychic" (:tipo v)))
    (is (re-find #"Psíquico" (:nome v)))))

(deftest plan-spawn-tematiza-ginasio-pelo-chefe
  (let [sch {:chat-id "c" :enabled true :interval-ms (* 60 60 1000)
             :next-at 1000 :ginasio-index 1 :daily-limit 12 :special-reserve 2
             :revision 1 :recent-bosses [] :last-by-category {}}
        plan (domain/plan-spawn sch nil 1000
                                {:rng (constantly 0.01)
                                 :forced-categoria "lendario"})]
    (is (= :spawn (:acao plan)))
    (let [tipos (get-in plan [:chefe :tipos])
          primary (gym-theme/primary-type tipos)
          expected (:nome (gym-theme/venue-for-type primary))]
      (is (some? primary))
      (is (= expected (:nome-ginasio plan)))
      (is (= primary (:boss-tipo plan)))
      ;; Cursor ginasio-index=1 → água nv.20 (dificuldade preservada)
      (is (= 20 (:nivel-ginasio plan))))))

;;;; Sem nível mínimo

(deftest nivel-1-entra-em-raid-lendaria
  (let [s (fresh-store! "lvl1")
        chat "c-l1"
        rid "raid-l1"]
    (boot! s chat "ash" mon-nv1)
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "op-l1" :raid-id rid
        :ginasio "fogo" :liga "diamante" :chefe chefe :agora-ms 1})
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "j-l1" :collection-index 0 :nome "Ash" :agora-ms 2})
    (is (= 1 (get-in (raid-app/get-raid s rid)
                     [:participantes "ash" :pokemon :nivel])))))

(deftest desmaiado-continua-bloqueado
  (let [s (fresh-store! "inapto")
        chat "c-x"
        rid "raid-x"]
    (boot! s chat "ash" mon-nv1)
    (collection/atualizar-hp-status! s chat "ash" 0 0 "desmaiado")
    (tx/open-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "op-x" :raid-id rid
        :ginasio "pedra" :liga "bronze" :chefe chefe :agora-ms 1})
    (is (= :invalid_raid_pokemon
           (try
             (tx/join-raid!
              s {:chat-id chat :player-id "ash" :save-id "a"
                 :fence-generation (fence s chat "ash")
                 :operation-id "j-x" :use-active? true :nome "Ash" :agora-ms 2})
             (catch Exception ex (err/error-code ex)))))))

;;;; 1º turno no auto-start

(deftest auto-expire-anuncia-turno-e-golpes
  (let [s (fresh-store! "turn")
        chat "c-t"
        agora 1000000]
    (boot! s chat "ash" mon-nv1)
    (boot! s chat "misty"
           (assoc mon-nv1 :nome "Squirtle" :nivel 12
                  :golpes [{:nome-exibicao "Jato d'Água" :tipo "water"
                            :poder 65 :classe :especial}]))
    (raid-auto/upsert-schedule!
     s {:chat-id chat :enabled true :interval-ms 3600000
        :next-at agora :ginasio-index 0 :agora-ms agora})
    (raid-auto/tick! s {:agora-ms agora :global-enabled? true :chat-id chat})
    (let [raid (raid-app/active-raid s chat)
          lobby-end (long (:expira raid))]
      (is (some? raid))
      (tx/join-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "j-a" :auto? true :nome "Ash" :agora-ms (inc agora)})
      (tx/join-raid!
       s {:chat-id chat :player-id "misty" :save-id "a"
          :fence-generation (fence s chat "misty")
          :operation-id "j-m" :auto? true :nome "Misty" :agora-ms (+ agora 2)})
      (let [tick2 (raid-auto/tick! s {:agora-ms lobby-end :global-enabled? true :chat-id chat})
            active (raid-app/active-raid s chat)
            started (some #(when (= "raid-started" (:kind %)) %)
                          (raid-auto/pending-events s {:limit 20}))
            vez0 (:vez active)]
        (is (some #(= :combate (:acao %)) (:expired tick2)))
        (is (= "combate" (:fase active)))
        (is (some? vez0))
        (is (some? started))
        (is (re-find #"RAID INICIADA AUTOMATICAMENTE" (:texto started)))
        (is (re-find #"Vez de" (:texto started)))
        (is (re-find #"raide atacar 1" (:texto started)))
        (is (seq (:mentions started)))
        (is (= vez0 (first (:mentions started))))
        (let [atk (tx/raid-attack!
                   s {:chat-id chat :player-id vez0 :save-id "a"
                      :fence-generation (fence s chat vez0)
                      :operation-id "atk1" :move-index 0
                      :agora-ms (inc lobby-end)})
              vez1 (get-in atk [:raid :vez])]
          (is (some? atk))
          (is (and (some? vez1) (not= vez0 vez1))))))))

(deftest format-started-sem-vez-degrada
  (let [t (domain/format-started {:nome-ginasio "🔥 Fogo" :hp-chefe 10 :hp-max 10
                                  :fase "combate" :chefe {:nome "X"}})]
    (is (re-find #"RAID INICIADA" t))
    (is (re-find #"status" t))))
