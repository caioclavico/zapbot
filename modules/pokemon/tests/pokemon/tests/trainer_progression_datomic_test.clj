(ns pokemon.tests.trainer-progression-datomic-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datomic.client.api :as d]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as bridge-tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.domain.capacity :as capacity]
            [pokemon.domain.identities :as id]
            [pokemon.domain.trainer-rewards :as trainer-rewards]
            [pokemon.domain.xp :as xp]
            [pokemon.infrastructure.datomic.schema :as schema]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.infrastructure.datomic.trainer-progression :as progression]))

(def ^:private system "odisseu-trainer-progression")
(def ^:private storage-rel ".datomic-local/trainer-progression-tests")

(defn- fixture! [suffix]
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        db-name (str "trainer-progression-" suffix "-" (System/nanoTime))]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)
          chat (str "progress-chat-" suffix)
          player (str "progress-player-" suffix)]
      (trainer/bootstrap! s)
      (trainer/create-trainer! s {:chat-id chat :player-id player})
      {:conn conn :store s :chat chat :player player
       :save-key (id/save-key chat player "a")})))

(defn- grant-pe-tx [save-key operation-id pe]
  {:tx [(array-map :operation-receipt/id operation-id
                   :operation-receipt/kind "trainer-pe"
                   :operation-receipt/channel "gameplay"
                   :operation-receipt/payload-hash "test-payload"
                   :operation-receipt/player-key (str/replace save-key #"\|[^|]+$" "")
                   :operation-receipt/save-id "a"
                   :operation-receipt/summary (pr-str {:test true}))
        [:db/add [:save/key save-key] :save/xp-missoes (long pe)]]
   :result {:accepted true}})

(deftest datomic-level-up-rewards-are-atomic-and-repeat-safe
  (let [{:keys [conn save-key]} (fixture! "atomic")
        op "progress-op-atomic"
        built (grant-pe-tx save-key op 50)
        enriched (progression/enrich (d/db conn) built)
        event (first (get-in enriched [:result :trainer-level-ups]))]
    (is (= 4 (:nivel-atual event)))
    (is (= 5 (:xp-atual event)))
    (is (= 3 (count (:niveis event))))
    (is (pos? (count (:items event))))
    (d/transact conn {:tx-data (:tx enriched)})
    (let [save (d/pull (d/db conn) '[*] [:save/key save-key])
          history (d/q '[:find ?level ?payload
                         :in $ ?save
                         :where [?r :trainer-level-reward/save ?save]
                                [?r :trainer-level-reward/level ?level]
                                [?r :trainer-level-reward/reward-edn ?payload]]
                       (d/db conn) (:db/id save))
          receipt (d/pull (d/db conn) '[*] [:operation-receipt/id op])
          summary (edn/read-string (:operation-receipt/summary receipt))
          retry (progression/enrich (d/db conn) built)]
      (is (= 4 (:save/trainer-level save)))
      (is (= 5 (:save/trainer-xp-level-progress save)))
      (is (= #{2 3 4} (set (map first history))))
      (is (every? #(< (count (second %)) 4096) history)
          "cada registro de nível permanece abaixo do limite local de valor Datomic")
      (is (= (:items event) (get-in summary [:trainer-level-up :items])))
      (is (empty? (get-in retry [:result :trainer-level-ups]))
          "reconstruir/repetir a operação não volta a sortear nem a conceder nível"))))

(deftest migration-036-schema-is-additive-and-typed
  (let [{:keys [conn]} (fixture! "schema")
        db (d/db conn)
        attrs [:save/trainer-xp-level-progress
               :trainer-level-reward/key
               :trainer-level-reward/save
               :trainer-level-reward/level
               :trainer-level-reward/operation-id
               :trainer-level-reward/reward-edn]
        schema (mapv #(d/pull db '[:db/ident :db/valueType :db/cardinality :db/unique]
                             [:db/ident %]) attrs)]
    (is (= (set attrs) (set (map :db/ident schema))))
    (is (every? #(= :db.cardinality/one (get-in % [:db/cardinality :db/ident])) schema))
    (is (= :db.type/long (get-in (first schema) [:db/valueType :db/ident])))
    (is (= :db.type/string (get-in (second schema) [:db/valueType :db/ident])))
    (is (= :db.unique/identity (get-in (second schema) [:db/unique :db/ident]))
        "only the reward key has an identity constraint")))

(deftest migration-036-preserves-existing-level-xp-capacities-and-inventory
  (let [client (persistence/client {:system system :storage-dir storage-rel})
        db-name (str "trainer-progression-before-036-" (System/nanoTime))]
    (persistence/delete-db! client db-name)
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (doseq [path (take 35 schema/migration-files)]
        (d/transact conn {:tx-data (persistence/read-edn-resource path)}))
      (d/transact conn {:tx-data [{:save/key "legacy|player|a"
                                   :save/trainer-xp 437
                                   :save/trainer-level 20
                                   :save/expansoes-pokemon-50 2
                                   :save/expansoes-mochila-50 3
                                   :save/capacidade-pokemon-legada 175
                                   :save/capacidade-mochila-legada 190}]})
      (d/transact conn {:tx-data [{:inventory-item/key "legacy|player|a|pokebola"
                                   :inventory-item/save [:save/key "legacy|player|a"]
                                   :inventory-item/item-id "pokebola"
                                   :inventory-item/qty 17}]})
      (let [before (d/pull (d/db conn)
                           '[:save/trainer-xp :save/trainer-level
                             :save/expansoes-pokemon-50 :save/expansoes-mochila-50
                             :save/capacidade-pokemon-legada :save/capacidade-mochila-legada]
                           [:save/key "legacy|player|a"])
            inventory-before (d/pull (d/db conn) '[:inventory-item/qty]
                                     [:inventory-item/key "legacy|player|a|pokebola"])]
        (d/transact conn {:tx-data (persistence/read-edn-resource
                                    "migrations/pokemon/036-trainer-level-rewards.edn")})
        (let [after-schema (d/pull (d/db conn)
                            '[:save/trainer-xp :save/trainer-level
                              :save/expansoes-pokemon-50 :save/expansoes-mochila-50
                              :save/capacidade-pokemon-legada :save/capacidade-mochila-legada
                              :save/trainer-xp-level-progress]
                            [:save/key "legacy|player|a"])
              inventory-after (d/pull (d/db conn) '[:inventory-item/qty]
                                      [:inventory-item/key "legacy|player|a|pokebola"])
              built {:tx [{:operation-receipt/id "migration-036-first-write"
                           :operation-receipt/kind "trainer-pe"
                           :operation-receipt/channel "gameplay"
                           :operation-receipt/payload-hash "migration-036-test"
                           :operation-receipt/player-key "legacy|player"
                           :operation-receipt/save-id "a"
                           :operation-receipt/summary "{}"}
                          [:db/add [:save/key "legacy|player|a"] :save/xp-missoes 105]]
                      :result {:accepted true}}
              progressed (progression/enrich (d/db conn) built)
              _ (d/transact conn {:tx-data (:tx progressed)})
              after-write (d/pull (d/db conn)
                                  '[:save/trainer-xp :save/trainer-level
                                    :save/expansoes-pokemon-50 :save/expansoes-mochila-50
                                    :save/capacidade-pokemon-legada :save/capacidade-mochila-legada
                                    :save/trainer-xp-level-progress]
                                  [:save/key "legacy|player|a"])
              inventory-final (d/pull (d/db conn) '[:inventory-item/qty]
                                      [:inventory-item/key "legacy|player|a|pokebola"])]
          (is (= before (dissoc after-schema :save/trainer-xp-level-progress)))
          (is (nil? (:save/trainer-xp-level-progress after-schema))
              "036 adds an optional residual attribute and does not backfill it")
          (is (= inventory-before inventory-after)
              "inventory entities and quantities survive schema installation")
          (is (= 20 (:save/trainer-level after-schema)))
          (is (= 21 (:save/trainer-level after-write))
              "a legacy save initializes on its historical level, then advances via the progressive cost")
          (is (= 0 (:save/trainer-xp-level-progress after-write)))
          (is (= [2 3 175 190]
                 [(:save/expansoes-pokemon-50 after-write)
                  (:save/expansoes-mochila-50 after-write)
                  (:save/capacidade-pokemon-legada after-write)
                  (:save/capacidade-mochila-legada after-write)])
              "level transitions preserve purchased and legacy capacity fields")
          (is (>= (:inventory-item/qty inventory-final) 17)
              "existing inventory survives level reward allocation"))))))

(deftest inventario-cheio-preserva-premios-pendentes
  (let [{:keys [conn save-key]} (fixture! "full-bag")
        key (id/inventory-item-key save-key "pokebola")]
    (d/transact conn {:tx-data [{:inventory-item/key key
                                 :inventory-item/save [:save/key save-key]
                                 :inventory-item/item-id "pokebola"
                                 :inventory-item/qty 50}]})
    (let [built (grant-pe-tx save-key "progress-op-full-bag" 10)
          enriched (progression/enrich (d/db conn) built)
          event (first (get-in enriched [:result :trainer-level-ups]))]
      (is (seq (:pending event)))
      (is (empty? (:items-no-inventario event)))
      (d/transact conn {:tx-data (:tx enriched)})
      (let [save (d/pull (d/db conn) '[:save/recompensas-pendentes-edn]
                         [:save/key save-key])]
        (is (= (:pending event)
               (edn/read-string (:save/recompensas-pendentes-edn save))))))))

(deftest transator-central-preserva-transacoes-e-anexa-level-up
  (let [{:keys [conn store chat player save-key]} (fixture! "central")
        player-state (ports/get-player store chat player)
        writer {:chat-id chat :player-id player :save-id "a"
                :fence-generation (:fence-generation player-state)}]
    (d/transact conn {:tx-data [[:db/add [:save/key save-key] :save/expansoes-pokemon-50 2]
                                [:db/add [:save/key save-key] :save/expansoes-mochila-50 1]
                                [:db/add [:save/key save-key] :save/capacidade-pokemon-legada 75]
                                [:db/add [:save/key save-key] :save/capacidade-mochila-legada 90]]})
    (ports/add-to-collection!
     store writer {:id-pokemon "central-poke" :species "rattata" :nome "Rattata"
                   :nivel 1 :hp 10 :hp-atual 10 :xp 0})
    (is (= 1 (count (ports/list-collection store chat player "a")))
        "enrichment conserva os datoms da operação original")
    (let [result (ports/apply-gameplay-reward!
                  store writer {:operation-id "central-pe-10" :trainer-xp-delta 10})
          save (ports/get-save store chat player "a")]
      (is (= 2 (:trainer-level save)))
      (is (= 0 (:trainer-xp-level-progress save)))
      (is (= [2 1 75 90]
             [(:expansoes-pokemon-50 save) (:expansoes-mochila-50 save)
              (:capacidade-pokemon-legada save) (:capacidade-mochila-legada save)])
          "level-up does not rewrite purchased or legacy capacities")
      (is (= 150 (capacity/capacidade-pokemon-save save)))
      (is (= 140 (capacity/capacidade-mochila (:trainer-level save)
                                              (:expansoes-mochila-50 save)
                                              (:capacidade-mochila-legada save))))
      (is (= 2 (get-in result [:trainer-level-ups 0 :nivel-atual]))
          "resultado da bridge inclui o evento de level-up"))))

(deftest cartao-raro-e-concedido-uma-vez-pelo-ledger-canonico
  (let [{:keys [conn save-key]} (fixture! "gift-card")
        op (first (filter #(contains? (trainer-rewards/pacote-normal % 2)
                                      "cartao-presente")
                          (map #(str "rare-card-" %) (range 10000))))
        built (grant-pe-tx save-key op 10)
        enriched (progression/enrich (d/db conn) built)
        event (first (get-in enriched [:result :trainer-level-ups]))]
    (is op "seed search finds a deterministic rare-card package")
    (is (= 1 (get (:items event) "cartao-presente")))
    (d/transact conn {:tx-data (:tx enriched)})
    (let [qty (ffirst (d/q '[:find ?qty
                     :in $ ?save
                     :where [?i :inventory-item/save ?save]
                            [?i :inventory-item/item-id "cartao-presente"]
                            [?i :inventory-item/qty ?qty]]
                   (d/db conn) [:save/key save-key]))
          retry (progression/enrich (d/db conn) built)]
      (is (= 1 qty) "cartão chega ao inventário uma vez pela alocação normal")
      (is (empty? (get-in retry [:result :trainer-level-ups]))
          "retry conserva o ledger do nível e não duplica o cartão"))))

(deftest level-up-e-recompensa-original-mesclam-um-datom-por-item
  (let [{:keys [conn save-key]} (fixture! "same-item")
        item-key (id/inventory-item-key save-key "pokebola")
        op (first (filter #(contains? (trainer-rewards/pacote-normal % 2) "pokebola")
                          (map #(str "same-item-" %) (range 100))))
        _ (d/transact conn {:tx-data [{:inventory-item/key item-key
                                       :inventory-item/save [:save/key save-key]
                                       :inventory-item/item-id "pokebola"
                                       :inventory-item/qty 3}]})
        built (update (grant-pe-tx save-key op 10) :tx conj
                      {:inventory-item/key item-key
                       :inventory-item/save [:save/key save-key]
                       :inventory-item/item-id "pokebola"
                       :inventory-item/qty 8})
        enriched (progression/enrich (d/db conn) built)
        rows (filter #(and (map? %) (= item-key (:inventory-item/key %)))
                     (:tx enriched))
        reward-qty (get-in enriched [:result :trainer-level-ups 0 :items "pokebola"])]
    (is (= 1 (count rows)) "o tx final contém um único datom-entidade para o item")
    (is (= (+ 8 reward-qty) (:inventory-item/qty (first rows))))
    (d/transact conn {:tx-data (:tx enriched)})
    (is (= (+ 8 reward-qty)
           (:inventory-item/qty
            (d/pull (d/db conn) '[:inventory-item/qty] [:inventory-item/key item-key])))
        "quantidade original e prêmio de nível são persistidos juntos sem conflito")))

(deftest progressive-upgrade-compatible-rollback-and-reupgrade-preserves-state
  (let [{:keys [conn store chat player save-key]} (fixture! "rollback-cycle")
        _ (ports/apply-gameplay-reward!
           store {:chat-id chat :player-id player :save-id "a"
                  :fence-generation (:fence-generation
                                     (ports/get-player store chat player))}
           {:operation-id "rollback-cycle-first" :trainer-xp-delta 50})
        after-first (ports/get-save store chat player "a")
        ledger-before (d/q '[:find ?level
                             :in $ ?save
                             :where [?r :trainer-level-reward/save ?save]
                                    [?r :trainer-level-reward/level ?level]]
                           (d/db conn) [:save/key save-key])
        inventory-before (d/q '[:find ?item ?qty
                                :in $ ?save
                                :where [?i :inventory-item/save ?save]
                                       [?i :inventory-item/item-id ?item]
                                       [?i :inventory-item/qty ?qty]]
                              (d/db conn) [:save/key save-key])
        ;; A read/write instance built with the migration-aware progression code
        ;; is the only safe rollback target after the first progressive write.
        rollback-compatible-store (store/make-store conn)
        after-compatible-rollback (ports/get-save rollback-compatible-store chat player "a")
        player-state (ports/get-player rollback-compatible-store chat player)
        second-result (ports/apply-gameplay-reward!
                       rollback-compatible-store
                       {:chat-id chat :player-id player :save-id "a"
                        :fence-generation (:fence-generation player-state)}
                       {:operation-id "rollback-cycle-second" :trainer-xp-delta 200})
        after-reupgrade (ports/get-save rollback-compatible-store chat player "a")
        ledger-after (d/q '[:find ?level
                            :in $ ?save
                            :where [?r :trainer-level-reward/save ?save]
                                   [?r :trainer-level-reward/level ?level]]
                          (d/db conn) [:save/key save-key])
        inventory-after (d/q '[:find ?item ?qty
                               :in $ ?save
                               :where [?i :inventory-item/save ?save]
                                      [?i :inventory-item/item-id ?item]
                                      [?i :inventory-item/qty ?qty]]
                             (d/db conn) [:save/key save-key])
        retry-result (ports/apply-gameplay-reward!
                      rollback-compatible-store
                      {:chat-id chat :player-id player :save-id "a"
                       :fence-generation (:fence-generation player-state)}
                      {:operation-id "rollback-cycle-second" :trainer-xp-delta 200})
        after-retry (ports/get-save rollback-compatible-store chat player "a")
        inventory-after-retry (d/q '[:find ?item ?qty
                                     :in $ ?save
                                     :where [?i :inventory-item/save ?save]
                                            [?i :inventory-item/item-id ?item]
                                            [?i :inventory-item/qty ?qty]]
                                   (d/db conn) [:save/key save-key])
        ledger-after-retry (d/q '[:find ?level
                                  :in $ ?save
                                  :where [?r :trainer-level-reward/save ?save]
                                         [?r :trainer-level-reward/level ?level]]
                                (d/db conn) [:save/key save-key])
        legacy-view (xp/progresso-treinador-legado (:trainer-xp after-first))
        inventory-before-map (into {} inventory-before)
        inventory-after-map (into {} inventory-after)
        inventory-after-retry-map (into {} inventory-after-retry)]
    (is (= 4 (:trainer-level after-first)))
    (is (> (:nivel legacy-view) (:trainer-level after-first))
        "the pre-036 binary would over-derive level from cumulative PE; do not use it as rollback")
    (is (= [(:trainer-level after-first) (:trainer-xp-level-progress after-first)]
           [(:trainer-level after-compatible-rollback)
            (:trainer-xp-level-progress after-compatible-rollback)]))
    (is (every? (set (map first ledger-after)) (map first ledger-before))
        "the compatibility rollback preserves all prior per-level reward ledger rows")
    (is (> (:trainer-level after-reupgrade) (:trainer-level after-first)))
    (is (= 9 (:trainer-level after-reupgrade))
        "new PE follows the progressive curve instead of jumping to the legacy-derived level")
    (is (= 8 (count ledger-after)) "all levels 2–9 have exactly one ledger entry")
    (is (every? (fn [[item qty]] (>= (get inventory-after-map item 0) qty))
                inventory-before-map)
        "existing reward quantities remain present after rollback and re-upgrade")
    (is (= inventory-after-map inventory-after-retry-map)
        "retry after rollback/re-upgrade does not grant any item twice")
    (is (= (:trainer-level after-reupgrade) (:trainer-level after-retry)))
    (is (= (:trainer-xp-level-progress after-reupgrade)
           (:trainer-xp-level-progress after-retry)))
    (is (seq (:trainer-level-ups second-result)))
    (is (empty? (:trainer-level-ups retry-result))
        "the receipt retry is acknowledged as a duplicate and does not regrant rewards")
    (is (= ledger-after ledger-after-retry))))

(deftest bridge-dto-propaga-trainer-level-ups-sem-chaves-privadas
  "Regressão: battle/capture DTOs omitiam :trainer-level-ups → mensagem ausente no WhatsApp."
  (is (= [{:nivel-atual 3 :items {"pokebola" 1}}]
         (#'bridge-tx/public-trainer-level-ups
          {:trainer-level-ups [{:nivel-atual 3 :items {"pokebola" 1}
                                :save-key "sk" :operation-id "op"}]})))
  (is (nil? (#'bridge-tx/public-trainer-level-ups {})))
  (is (nil? (#'bridge-tx/public-trainer-level-ups {:trainer-level-ups []}))))
