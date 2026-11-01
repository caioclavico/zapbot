(ns pokemon.tests.raid-save-slots-reconcile-test
  "Fase 5.1 — save-slots não interferem em raide ativa nem em recibos de recompensa."
  (:require [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid :as raid-app]
            [pokemon.application.reward :as reward-app]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.errors :as err]
            [pokemon.domain.raid :as raid]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-raid-save-reconcile")
(def ^:private storage-rel ".datomic-local/raid-save-reconcile")

(defn- fresh!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(def ^:private mon
  {:nome "Snorlax" :hp 220 :ataque 120 :defesa 60 :atq-esp 60 :def-esp 60 :veloc 50
   :tipos ["normal"] :nivel 10 :raridade "raro"
   :golpes [{:nome-exibicao "Body Slam" :tipo "normal" :poder 90 :classe :fisico}]})

(def ^:private chefe
  {:nome "Snorlax" :hp 80 :ataque 40 :defesa 40 :atq-esp 40 :def-esp 40 :veloc 30
   :tipos ["normal"] :nivel 1 :raridade "raro"
   :golpes [{:nome-exibicao "Tackle" :tipo "normal" :poder 40 :classe :fisico}]})

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (collection/adicionar-pokemon! s chat player mon 220 nil)
  (reward-app/admin-seed-reward!
   s {:operation-id (str "seed-" chat "-" player)
      :chat-id chat :player-id player :save-id "a"
      :item {:item-id "ultra-bola" :qty 2}}))

(defn- open-lobby!
  [s chat rid]
  (tx/open-raid!
   s {:chat-id chat :player-id "ash" :save-id "a"
      :fence-generation (fence s chat "ash")
      :operation-id (str "open-" rid)
      :ginasio "pedra" :chefe chefe :agora-ms 1000}))

(deftest switch-save-nao-apaga-lobby-nem-ponteiro
  (let [s (fresh! "rss-switch-lobby")
        chat "chat-sw"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open-lobby! s chat "r-sw")
    (tx/join-raid!
     s {:chat-id chat :player-id "ash" :save-id "a"
        :fence-generation (fence s chat "ash")
        :operation-id "j-ash" :collection-index 0 :nome "Ash" :agora-ms 1001})
    (trainer/switch-save! s chat "ash" "b")
    (testing "raide e inscrição carimbada permanecem"
      (let [active (raid-app/active-raid s chat)
            ash-part (get-in active [:participantes "ash"])]
        (is (= "inscricoes" (:fase active)))
        (is (= "open-r-sw" (:raid-id active)))
        (is (= "a" (:save-id ash-part)))
        (is (= "b" (ports/active-save-id s chat "ash")))))))

(deftest switch-save-bloqueia-ataque-sem-pagar-recompensa
  "Troca de save durante combate: ataque com save errado falha; fence sobe;
   raide permanece sem rewards-paid (sem duplicar economia)."
  (let [s (fresh! "rss-switch-atk")
        chat "chat-atk"
        rid "r-atk"]
    (boot! s chat "ash")
    (boot! s chat "misty")
    (open-lobby! s chat rid)
    (let [raid-id (:raid-id (raid-app/active-raid s chat))]
      (tx/join-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "ja" :collection-index 0 :nome "Ash" :agora-ms 2})
      (tx/join-raid!
       s {:chat-id chat :player-id "misty" :save-id "a"
          :fence-generation (fence s chat "misty")
          :operation-id "jm" :collection-index 0 :nome "Misty" :agora-ms 3})
      (tx/start-raid!
       s {:chat-id chat :player-id "ash" :save-id "a"
          :fence-generation (fence s chat "ash")
          :operation-id "st" :agora-ms 4})
      (let [coins-a0 (:coins (ports/get-save s chat "ash" "a") 0)
            coins-b0 (:coins (ports/get-save s chat "ash" "b") 0)]
        (trainer/switch-save! s chat "ash" "b")
        (let [r (raid-app/get-raid s raid-id)
              code (try
                     (tx/raid-attack!
                      s {:chat-id chat :player-id "ash" :save-id "b"
                         :fence-generation (fence s chat "ash")
                         :operation-id "atk-wrong-save"
                         :expected-turn-index (:turn-index r)
                         :move-index 0 :agora-ms 5})
                     (catch Exception ex (err/error-code ex)))]
          (is (contains? #{:raid_save_mismatch :stale_writer_fence} code))
          (is (= "combate" (:fase (raid-app/get-raid s raid-id))))
          (is (false? (:rewards-paid? (raid-app/get-raid s raid-id))))
          (is (= coins-a0 (:coins (ports/get-save s chat "ash" "a") 0)))
          (is (= coins-b0 (:coins (ports/get-save s chat "ash" "b") 0))))))))

(deftest rename-save-slot-preserva-recibo-de-open
  (let [s (fresh! "rss-rename")
        chat "chat-ren"
        _ (boot! s chat "ash")
        _ (boot! s chat "misty")
        _ (open-lobby! s chat "raid-ren")
        before (raid-app/active-raid s chat)
        _ (tx/create-save-slot!
           s {:chat-id chat :player-id "ash" :save-id "c"
              :display-name "Alt"})
        listed (tx/list-saves s chat "ash")
        _ (tx/rename-save!
           s {:chat-id chat :player-id "ash" :save-id "c"
              :display-name "Alt 2" :operation-id "rename-1"
              :expected-fence-generation (:fence-generation listed)})
        after (raid-app/active-raid s chat)
        open-receipt (ports/get-receipt s "open-raid-ren")]
    (is (= (:raid-id before) (:raid-id after)))
    (is (= "inscricoes" (:fase after)))
    (is (some? open-receipt))
    (is (= "raid-open" (:kind open-receipt)))))
