(ns pokemon.tests.egg-auto-hatch-a6-test
  "A6 — nascimento automático: flag, outbox save-stamp, idempotência, A→B→A."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.adventure :as adv]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.collection :as collection]
            [pokemon.application.ports :as ports]
            [pokemon.application.raid-auto :as raid-auto]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.adventure-store :as adventure-store]
            [pokemon.infrastructure.datomic.raid-store :as raid-store]
            [pokemon.infrastructure.datomic.store :as store]))
(def ^:private system "odisseu-egg-auto-hatch-a6")
(def ^:private storage-rel ".datomic-local/egg-auto-hatch-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- fence [s chat player]
  (long (:fence-generation (ports/get-player s chat player) 0)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (adv/ensure-account! s chat player))

(defn- present? [e] (some? (:db/id e)))

(deftest newly-ready-eggs-detects-transition
  (let [ovo (eggs/novo-ovo "e1" :raro 0 "bulbasaur")
        antes (-> (eggs/estado-inicial)
                  (eggs/conceder-ovo ovo)
                  (eggs/incubar "e1" "permanente" 1000 "save-a" 1))
        depois (eggs/aplicar-pontos antes "evt-1" 250)
        ready (egg-activity/newly-ready-eggs antes depois)]
    (is (= 1 (count ready)))
    (is (= "e1" (:egg-id (first ready))))
    (is (egg-activity/ready-egg? (first ready)))
    (is (empty? (egg-activity/newly-ready-eggs depois depois)))))

(deftest aplicar-pontos-zero-nao-nasce
  (let [ovo (assoc (eggs/novo-ovo "e2" :comum 0 "squirtle") :points 99)
        estado (-> (eggs/estado-inicial)
                   (update :eggs conj ovo)
                   (assoc-in [:incubators 0 :active-egg-id] "e2")
                   (assoc-in [:eggs 0 :state] :incubating)
                   (assoc-in [:eggs 0 :incubator-id] "permanente")
                   (assoc-in [:eggs 0 :save-id] "save-a")
                   (assoc-in [:eggs 0 :fence-generation] 1)
                   (assoc-in [:eggs 0 :points] 99))
        depois (eggs/aplicar-pontos estado "evt-z" 0)]
    (is (= 99 (get-in depois [:eggs 0 :points])))
    (is (empty? (egg-activity/newly-ready-eggs estado depois)))))

(deftest auto-hatch-flag-off-by-default-in-test-env
  (is (not (egg-activity/auto-hatch-enabled?))))

(deftest auto-hatch-outbox-carimba-save-id
  (with-redefs [egg-activity/auto-hatch-enabled? (constantly true)]
    (let [ovo (-> (eggs/novo-ovo "e-out" :comum 0 "bulbasaur")
                  (assoc :points 99
                         :state :incubating
                         :incubator-id "permanente"
                         :save-id "a"
                         :fence-generation 1))
          shell (-> (eggs/estado-inicial)
                    (assoc :revision 0 :persisted-receipts #{}
                           :eggs [ovo])
                    (assoc-in [:incubators 0 :active-egg-id] "e-out"))
          built (adventure-store/build-activity-enrich-tx
                 {:writer-ctx {:chat-id "c" :player-id "ash"}
                  :account-shell shell
                  :egg-maps [ovo]
                  :activity-opts {:activity :wild-victory
                                  :event-id "pi-ready-1"
                                  :agora 1000
                                  :suppress-wild-victory-pi? false}
                  :ensure-tx? false})
          outboxes (filterv #(and (map? %)
                                  (= "egg-auto-hatch" (:pokemon-outbox/kind %)))
                            (:tx built))
          texto (:pokemon-outbox/texto (first outboxes))
          parsed (raid-store/outbox->map
                  {:db/id 1
                   :pokemon-outbox/id (:pokemon-outbox/id (first outboxes))
                   :pokemon-outbox/chat-id "c"
                   :pokemon-outbox/kind "egg-auto-hatch"
                   :pokemon-outbox/texto texto
                   :pokemon-outbox/created-at 1000
                   :pokemon-outbox/acked-at nil}
                  present?)]
      (is (= ["e-out"] (get-in built [:result :auto-hatch-queued])))
      (is (= 1 (count outboxes)))
      (is (re-find #"\"saveId\":\"a\"" texto))
      (is (= "a" (:saveId parsed)))
      (is (= "e-out" (:eggId parsed))))))
(deftest auto-hatch-flag-off-nao-enfileira
  (with-redefs [egg-activity/auto-hatch-enabled? (constantly false)]
    (let [ovo (-> (eggs/novo-ovo "e-off" :comum 0 "bulbasaur")
                  (assoc :points 99 :state :incubating :incubator-id "permanente"
                         :save-id "a" :fence-generation 1))
          shell (-> (eggs/estado-inicial)
                    (assoc :revision 0 :persisted-receipts #{} :eggs [ovo])
                    (assoc-in [:incubators 0 :active-egg-id] "e-off"))
          built (adventure-store/build-activity-enrich-tx
                 {:writer-ctx {:chat-id "c" :player-id "ash"}
                  :account-shell shell
                  :egg-maps [ovo]
                  :activity-opts {:activity :wild-victory
                                  :event-id "pi-off-1"
                                  :agora 1000}
                  :ensure-tx? false})]
      (is (empty? (get-in built [:result :auto-hatch-queued])))
      (is (empty? (filter #(and (map? %) (= "egg-auto-hatch" (:pokemon-outbox/kind %)))
                          (:tx built)))))))

(deftest dois-ovos-prontos-mesmo-evento
  (with-redefs [egg-activity/auto-hatch-enabled? (constantly true)]
    (let [mk (fn [id]
               (-> (eggs/novo-ovo id :comum 0 "bulbasaur")
                   (assoc :points 99 :state :incubating :incubator-id id
                          :save-id "a" :fence-generation 1)))
          o1 (mk "egg-a")
          o2 (mk "egg-b")
          shell (-> (eggs/estado-inicial)
                    (eggs/adicionar-incubadora "egg-a" :extra)
                    (eggs/adicionar-incubadora "egg-b" :extra)
                    (assoc :revision 0 :persisted-receipts #{} :eggs [o1 o2])
                    (assoc :incubators
                           [{:incubator-id "egg-a" :kind :extra :uses 3
                             :bonus-points 0 :active-egg-id "egg-a"}
                            {:incubator-id "egg-b" :kind :extra :uses 3
                             :bonus-points 0 :active-egg-id "egg-b"}]))
          built (adventure-store/build-activity-enrich-tx
                 {:writer-ctx {:chat-id "c" :player-id "ash"}
                  :account-shell shell
                  :egg-maps [o1 o2]
                  :activity-opts {:activity :gym-victory
                                  :event-id "pi-duo"
                                  :agora 2000}
                  :ensure-tx? false})
          queued (set (get-in built [:result :auto-hatch-queued]))]
      (is (= #{"egg-a" "egg-b"} queued))
      (is (= 2 (count (filter #(and (map? %)
                                    (= "egg-auto-hatch" (:pokemon-outbox/kind %)))
                              (:tx built))))))))

(deftest hatch-ja-chocado-retorna-duplicate-sem-egg-not-ready
  (let [s (fresh-store! "ah-dup")
        chat "c-dup"
        player "ash"
        t0 4000000
        due (+ t0 (* 3 60 60 1000))]
    (boot! s chat player)
    (tx/grant-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "g-dup" :egg-id "egg-dup" :category :comum
        :species "bulbasaur" :agora-ms t0})
    (tx/incubate-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "i-dup" :egg-id "egg-dup" :agora-ms t0})
    (d/transact (:conn s) {:tx-data [[:db/add [:egg/id "egg-dup"] :egg/points 100]]})
    (let [h1 (tx/hatch-egg!
              s {:chat-id chat :player-id player :save-id "a"
                 :fence-generation (fence s chat player)
                 :operation-id "h-dup-1" :egg-id "egg-dup" :hatch-id "h-dup"
                 :agora-ms due})
          ;; Segunda tentativa com outro operation-id (auto após manual)
          h2 (tx/hatch-egg!
              s {:chat-id chat :player-id player :save-id "a"
                 :fence-generation (fence s chat player)
                 :operation-id "h-dup-2" :egg-id "egg-dup" :hatch-id "h-dup"
                 :agora-ms due})]
      (is (false? (:duplicate h1)))
      (is (true? (:duplicate h2)))
      (is (= (:pokemon-id h1) (:pokemon-id h2)))
      (is (= 1 (count (ports/list-collection s chat player "a"))))
      (let [acc (adv/get-account s chat player)
            permanente (some #(when (= "permanente" (:incubator-id %)) %)
                             (:incubators acc))]
        (is (nil? (:active-egg-id permanente)))))))

(deftest hatch-save-a-b-a-uma-vez
  (let [s (fresh-store! "ah-aba")
        chat "c-aba"
        player "ash"
        t0 5000000
        due (+ t0 (* 3 60 60 1000))]
    (boot! s chat player)
    (tx/grant-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "g-aba" :egg-id "egg-aba" :category :comum
        :species "charmander" :agora-ms t0})
    (tx/incubate-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "i-aba" :egg-id "egg-aba" :agora-ms t0})
    (d/transact (:conn s) {:tx-data [[:db/add [:egg/id "egg-aba"] :egg/points 100]]})
    (trainer/switch-save! s chat player "b")
    (let [code (try
                 (tx/hatch-egg!
                  s {:chat-id chat :player-id player :save-id "b"
                     :fence-generation (fence s chat player)
                     :operation-id "h-aba-b" :egg-id "egg-aba" :agora-ms due})
                 (catch Exception ex (err/error-code ex)))]
      (is (= :egg_save_mismatch code)))
    (is (= 0 (count (ports/list-collection s chat player "a"))))
    (is (= 0 (count (ports/list-collection s chat player "b"))))
    (trainer/switch-save! s chat player "a")
    (let [h1 (tx/hatch-egg!
              s {:chat-id chat :player-id player :save-id "a"
                 :fence-generation (fence s chat player)
                 :operation-id "h-aba-a" :egg-id "egg-aba" :hatch-id "h-aba"
                 :agora-ms due})
          h2 (tx/hatch-egg!
              s {:chat-id chat :player-id player :save-id "a"
                 :fence-generation (fence s chat player)
                 :operation-id "h-aba-a2" :egg-id "egg-aba" :hatch-id "h-aba"
                 :agora-ms due})]
      (is (false? (:duplicate h1)))
      (is (true? (:duplicate h2)))
      (is (= 1 (count (ports/list-collection s chat player "a"))))
      (is (= 0 (count (ports/list-collection s chat player "b")))))))

(deftest colecao-cheia-pending-sem-consumir-incubadora
  (let [s (fresh-store! "ah-full")
        chat "c-full"
        player "ash"
        t0 6000000
        due (+ t0 (* 3 60 60 1000))
        stub {:nome "Rattata" :hp 40 :ataque 30 :defesa 20 :atq-esp 20 :def-esp 20
              :veloc 40 :tipos ["normal"] :nivel 5 :raridade "comum"
              :golpes [{:nome-exibicao "Investida" :tipo "normal" :poder 40 :classe :fisico}]
              :hp-atual 40 :status "normal"}]
    (boot! s chat player)
    (dotimes [i capacity/capacidade-inicial-pokemon]
      (collection/adicionar-pokemon! s chat player (assoc stub :nome (str "R" i)) 40 "normal"))
    (is (= capacity/capacidade-inicial-pokemon
           (count (ports/list-collection s chat player "a"))))
    (tx/grant-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "g-full" :egg-id "egg-full" :category :comum
        :species "squirtle" :agora-ms t0})
    (tx/incubate-egg!
     s {:chat-id chat :player-id player :save-id "a"
        :fence-generation (fence s chat player)
        :operation-id "i-full" :egg-id "egg-full"
        :incubator-id "permanente" :agora-ms t0})
    (d/transact (:conn s) {:tx-data [[:db/add [:egg/id "egg-full"] :egg/points 100]]})
    (let [h (tx/hatch-egg!
             s {:chat-id chat :player-id player :save-id "a"
                :fence-generation (fence s chat player)
                :operation-id "h-full" :egg-id "egg-full" :hatch-id "h-full"
                :agora-ms due})
          egg (adv/get-egg s "egg-full")
          acc (adv/get-account s chat player)
          permanente (some #(when (= "permanente" (:incubator-id %)) %)
                           (:incubators acc))]
      (is (true? (:collection-full h)))
      (is (true? (:pending h)))
      (is (= :delivery-pending (:state egg)))
      (is (some? (:resultado egg)))
      (is (= "egg-full" (:active-egg-id permanente)))
      (is (= capacity/capacidade-inicial-pokemon
             (count (ports/list-collection s chat player "a")))))))

(deftest outbox-datomic-idempotente-e-ack
  (with-redefs [egg-activity/auto-hatch-enabled? (constantly true)]
    (let [s (fresh-store! "ah-outbox")
          chat "c-ob"
          player "ash"
          t0 7000000
          conn (:conn s)]
      (boot! s chat player)
      (tx/grant-egg!
       s {:chat-id chat :player-id player :save-id "a"
          :fence-generation (fence s chat player)
          :operation-id "g-ob" :egg-id "egg-ob" :category :comum
          :species "pikachu" :agora-ms t0})
      (tx/incubate-egg!
       s {:chat-id chat :player-id player :save-id "a"
          :fence-generation (fence s chat player)
          :operation-id "i-ob" :egg-id "egg-ob" :agora-ms t0})
      (d/transact conn {:tx-data [[:db/add [:egg/id "egg-ob"] :egg/points 99]]})
      (let [ak (id/adventure-account-key chat player)
            db (d/db conn)
            e (d/pull db adventure-store/account-pattern [:adventure-account/key ak])
            shell (adventure-store/account-shell e edn/read-string present?)
            egg0 (adv/get-egg s "egg-ob")
            built (adventure-store/build-activity-enrich-tx
                   {:writer-ctx {:chat-id chat :player-id player}
                    :account-shell shell
                    :egg-maps [egg0]
                    :activity-opts {:activity :wild-victory
                                    :event-id "pi-ob-1"
                                    :agora t0}
                    :ensure-tx? false})
            _ (d/transact conn {:tx-data (:tx built)})
            events (raid-auto/pending-events s {:limit 20})
            auto (filterv #(= "egg-auto-hatch" (str (:kind %))) events)
            eid (:id (first auto))]
        (is (= 1 (count auto)))
        (is (= "a" (:saveId (first auto))))
        ;; Mesmo outbox id: upsert / sem segundo evento pendente
        (d/transact conn {:tx-data [{:pokemon-outbox/id eid
                                     :pokemon-outbox/chat-id chat
                                     :pokemon-outbox/kind "egg-auto-hatch"
                                     :pokemon-outbox/texto
                                     "{\"v\":1,\"playerId\":\"ash\",\"eggId\":\"egg-ob\",\"hatchId\":\"hatch:egg-ob\",\"saveId\":\"a\"}"
                                     :pokemon-outbox/created-at (inc t0)}]})
        (is (= 1 (count (filter #(= "egg-auto-hatch" (str (:kind %)))
                                (raid-auto/pending-events s {:limit 20})))))
        (raid-auto/ack-event! s eid (inc t0))
        (is (empty? (filter #(= eid (:id %))
                            (raid-auto/pending-events s {:limit 20}))))))))
