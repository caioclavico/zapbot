(ns pokemon.tests.egg-fixes-a6-test
  "A6 — correções PR #81: pending drain, hatch canônico, PI raid/PvP."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.adventure :as adv]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.egg-activity :as egg-activity]
            [pokemon.domain.egg-hatch :as egg-hatch]
            [pokemon.domain.eggs :as eggs]
            [pokemon.domain.errors :as err]
            [pokemon.domain.identities :as id]
            [pokemon.infrastructure.datomic.adventure-activity :as adventure-activity]
            [pokemon.infrastructure.datomic.store :as store]))

(def ^:private system "odisseu-egg-fixes-a6")
(def ^:private storage-rel ".datomic-local/egg-fixes-a6")

(defn- fresh-store!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage-rel})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(defn- boot! [s chat player]
  (trainer/create-trainer! s {:chat-id chat :player-id player})
  (adv/ensure-account! s chat player))

(deftest hatch-canonico-por-especie
  (doseq [[sp tipos-esperados]
          [["bulbasaur" #{"grass" "poison"}]
           ["charmander" #{"fire"}]
           ["squirtle" #{"water"}]
           ["pikachu" #{"electric"}]]]
    (let [a (egg-hatch/preparar-registro sp (str "id-" sp) (str "hatch-" sp))
          b (egg-hatch/preparar-registro sp (str "id-" sp) (str "hatch-" sp))]
      (is (= a b) sp)
      (is (= tipos-esperados (set (:tipos a))) sp)
      (is (not= ["normal"] (:tipos a)) sp)
      (is (pos? (:hp a)) sp)
      (is (pos? (:ataque a)) sp)
      (is (seq (:golpes a)) sp)
      (is (some #(contains? tipos-esperados (:tipo %)) (:golpes a)) sp)
      (is (= "growth-v1" (:xp-progress-version a)) sp)
      (is (= 1 (:nivel a)) sp)
      (is (= "ovo" (:origem a)) sp)
      (is (map? (:ivs a)) sp)
      (is (contains? a :shiny) sp))))

(deftest hatch-especie-desconhecida-nao-consome
  (is (= :egg_species_data_missing
         (err/error-code
          (try
            (egg-hatch/preparar-registro "zzz-nao-existe-xyz" "id" "h")
            (catch Exception ex ex))))))

(deftest pending-fifo-apos-hatch
  (let [s (fresh-store! "pending-drain")
        chat "c-pend"
        player "ash"
        t0 5000000
        conn (:conn s)]
    (boot! s chat player)
    (doseq [i (range 9)]
      (adv/grant-egg! s chat player
                      {:operation-id (str "g-" i)
                       :egg-id (str "slot-" i)
                       :category :comum
                       :species "pidgey"
                       :agora t0}))
    (is (= 9 (eggs/inventariados-count (adv/get-account s chat player))))
    (adv/incubate! s chat player
                   {:operation-id "inc-0" :egg-id "slot-0"
                    :incubator-id "permanente" :agora t0})
    (let [ak (id/adventure-account-key chat player)
          e (d/pull (d/db conn) '[*] [:adventure-account/key ak])
          shell (edn/read-string (:adventure-account/state-edn e))
          shell' (assoc shell
                        :pending-egg-grants
                        [{:event-id "pend-a" :category :especial
                          :species "dratini"
                          :catalog-version egg-activity/catalog-version}
                         {:event-id "pend-b" :category :raro
                          :species "eevee"
                          :catalog-version egg-activity/catalog-version}]
                        :receipts (set (or (:receipts shell) #{})))
          rev (long (:adventure-account/revision e 0))]
      (d/transact conn
                  {:tx-data [[:db/add [:adventure-account/key ak]
                              :adventure-account/state-edn (pr-str shell')]
                             [:db/cas [:adventure-account/key ak]
                              :adventure-account/revision rev (inc rev)]]}))
    (let [h (adv/hatch! s chat player
                        {:operation-id "h-0" :egg-id "slot-0"
                         :hatch-id "hatch-slot-0"
                         :agora (+ t0 (* 3 60 60 1000))})
          acc2 (adv/get-account s chat player)
          ids (set (map :egg-id (:eggs acc2)))
          pending2 (vec (:pending-egg-grants acc2))]
      (is (false? (:duplicate h)))
      (is (contains? ids "egg:pend-a") "FIFO: primeiro pendente entregue")
      (is (not (contains? ids "egg:pend-b"))
          "segundo permanece pendente — inventário cheio de novo")
      (is (= 1 (count pending2)))
      (is (= "pend-b" (:event-id (first pending2))))
      (is (= 9 (eggs/inventariados-count acc2)))
      (let [h2 (adv/hatch! s chat player
                           {:operation-id "h-0" :egg-id "slot-0"
                            :hatch-id "hatch-slot-0"
                            :agora (+ t0 (* 3 60 60 1000))})]
        (is (true? (:duplicate h2)))
        (is (= 1 (count (filter #(= "egg:pend-a" (:egg-id %))
                                (:eggs (adv/get-account s chat player))))))))))

(deftest materialize-pending-idempotente-e-fifo
  (let [st0 (eggs/estado-inicial)
        st1 (-> st0
                (egg-activity/enqueue-pending-egg
                 (egg-activity/pending-egg "p1" :comum "pidgey"
                                           egg-activity/catalog-version))
                (egg-activity/enqueue-pending-egg
                 (egg-activity/pending-egg "p2" :raro "eevee"
                                           egg-activity/catalog-version)))
        r1 (egg-activity/materialize-pending st1 1000)
        eids (mapv :egg-id (:delivered r1))
        r2 (egg-activity/materialize-pending (:estado r1) 1000)]
    (is (= ["egg:p1" "egg:p2"] eids))
    (is (= "pidgey" (:species (first (:delivered r1)))))
    (is (= "eevee" (:species (second (:delivered r1)))))
    (is (empty? (:delivered r2)))
    (is (empty? (:pending-egg-grants (:estado r1))))))

(deftest detect-raid-rewards-todos-participantes
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "raid-atk-1"
                 :operation-receipt/kind "raid-attack"
                 :operation-receipt/summary (pr-str {:venceu? true})}
        built {:result {:venceu? true
                        :raid {:raid-id "r1" :boss-categoria "epico"}
                        :rewards [{:player-id "ash" :outcome :vitoria}
                                  {:player-id "misty" :outcome :vitoria}
                                  {:player-id "brock" :outcome :vitoria}]}
               :tx [receipt]}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)]
    (is (= 3 (count targets)))
    (is (= #{"ash" "misty" "brock"} (set (map :player-id targets))))
    (is (every? #(= :raid-victory (get-in % [:opts :activity])) targets))
    (is (every? #(= "epico" (get-in % [:opts :raid-category])) targets))))

(deftest detect-raid-derrota
  (let [writer {:chat-id "c" :player-id "ash"}
        receipt {:operation-receipt/id "raid-atk-2"
                 :operation-receipt/kind "raid-attack"
                 :operation-receipt/summary (pr-str {:derrota? true})}
        built {:result {:derrota? true
                        :raid {:raid-id "r2" :categoria "raro"}
                        :rewards [{:player-id "ash" :outcome :derrota}
                                  {:player-id "misty" :outcome :derrota}]}
               :tx [receipt]}
        targets (#'adventure-activity/detect-targets
                 nil writer built receipt 1000)]
    (is (= 2 (count targets)))
    (is (every? #(= :raid-defeat (get-in % [:opts :activity])) targets))))

(deftest detect-pvp-ambos-jogadores
  (let [win (egg-activity/apply-activity
             (eggs/estado-inicial)
             {:event-id "egg-act:pvp:b1:ash" :activity :pvp-victory
              :opponent-id "misty" :agora 9000000})
        lose (egg-activity/apply-activity
              (eggs/estado-inicial)
              {:event-id "egg-act:pvp:b1:misty" :activity :pvp-defeat
               :opponent-id "ash" :agora 9000000})]
    (is (= 12 (:pi-granted win)))
    (is (= 5 (:pi-granted lose)))
    (is (zero? (:pi-granted
                (egg-activity/apply-activity
                 (:estado win)
                 {:event-id "egg-act:pvp:b1:ash" :activity :pvp-victory
                  :opponent-id "misty" :agora 9000000}))))
    (is (zero? (:pi-granted
                (egg-activity/apply-activity
                 (:estado win)
                 {:event-id "egg-act:pvp:b2:ash" :activity :pvp-victory
                  :opponent-id "misty" :agora 9000000}))))))

(deftest pi-raid-por-categoria
  (doseq [[cat expect]
          [["raro" 20] ["epico" 30] ["lendario" 45] ["mitico" 45]]]
    (let [r (egg-activity/apply-activity
             (eggs/estado-inicial)
             {:event-id (str "raid-" cat) :activity :raid-victory
              :raid-category cat :agora 8000000})]
      (is (= expect (:pi-granted r)) cat)))
  (is (= 10 (:pi-granted
             (egg-activity/apply-activity
              (eggs/estado-inicial)
              {:event-id "raid-lose" :activity :raid-defeat
               :agora 8000000})))))
