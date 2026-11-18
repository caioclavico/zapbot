(ns pokemon.tests.migration-m53-test
  "Gates M5.3B — fixtures representativas antes do import real."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fix]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.import :as mig]
            [pokemon.migration.inventory :as inv]
            [pokemon.migration.match :as match]
            [pokemon.migration.transform :as transform]))

(def ^:private system "odisseu-m53-tests")
(def ^:private storage ".datomic-local/m53-tests")

(defn- boot! [db-name]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (doto (store/make-store conn) trainer/bootstrap!)]
      s)))

(deftest fixture-buckets-e-ids-unicos
  (let [dump (fix/build-cassandra-dump)
        inv (inv/inventory-dump dump)
        report (match/match-inventory inv)
        ir (transform/transform-export (export/from-fixture-dump dump))
        save-a (first (filter #(= "a" (:slot-id %)) (:saves ir)))
        save-b (first (filter #(= "b" (:slot-id %)) (:saves ir)))
        ids-a (set (map :id-pokemon (:pokemon save-a)))
        ids-b (set (map :id-pokemon (:pokemon save-b)))]
    (is (= :resolved (:status report)))
    (is (= 26 (:pokemon-count save-a)))
    (is (= 119 (:pokemon-count save-b)))
    (is (= 26 (count ids-a)))
    (is (= 119 (count ids-b)))
    (is (empty? (set/intersection ids-a ids-b)))
    (is (= {"equipe" 19 "pc" 0 "enfermaria" 7 "total" 26} (:buckets save-a)))
    (is (= {"equipe" 119 "pc" 0 "enfermaria" 0 "total" 119} (:buckets save-b)))
    (is (= 198 (:coins save-a)))
    (is (= 437 (:coins save-b)))
    (is (some #(and (= :enfermaria (:location %))
                    (some? (:pronto-em %)))
              (:pokemon save-a)))
    (is (some #(and (= "Voltorb" (:species %)) (:shiny %)) (:pokemon save-b)))
    (is (every? :origin-edn (:pokemon save-a)))
    (is (every? :origin-edn (:pokemon save-b)))))

(deftest import-fixture-preserva-location-e-joy
  "M5.5: Joy vira enfermaria-bed; coleção ativa = equipe/pc apenas."
  (let [s (boot! "m53-gate")
        dump (fix/build-cassandra-dump)
        ir (transform/transform-export (export/from-fixture-dump dump))
        m (match/match-inventory (inv/inventory-dump dump))
        _ (is (:ok? (gates/assert-importable! ir m)))
        result (mig/import-ir! s ir {:import-batch-id "m53-gate-batch"
                                     :require-match-resolved? true
                                     :match-report m})
        col-a (ports/list-collection s fix/chat-id fix/player-id "a")
        col-b (ports/list-collection s fix/chat-id fix/player-id "b")
        beds (ports/list-enfermaria s fix/chat-id fix/player-id "a")
        eqv (eq/compare-ir s ir)
        reimp (mig/reimport-same-batch! s ir "m53-gate-batch")]
    (is (= "a" (:active-save-id result)))
    (is (= 19 (count col-a)))
    (is (= 119 (count col-b)))
    (is (= 7 (count beds)))
    (is (every? #(= "equipe" (:location %)) col-a))
    (is (every? #(some? (:pronto-em %)) beds))
    (is (every? #(string? (:id-pokemon (:pokemon %))) beds))
    (is (every? #(string? (:origin-edn %)) col-a))
    (is (some #(and (= "Voltorb" (:species %))
                    (= "hist-voltorb-shiny-001" (:id-pokemon %))
                    (:shiny %))
              col-b))
    (is (= 198 (:coins (ports/get-save s fix/chat-id fix/player-id "a"))))
    (is (= 437 (:coins (ports/get-save s fix/chat-id fix/player-id "b"))))
    (is (:ok? eqv) (pr-str (:saves eqv)))
    (is (zero? (:id-intersection eqv)))
    (is (every? #(= :duplicate (:status %)) reimp))))

(deftest schema-011-nao-quebra-bootstrap
  (let [s (boot! "m53-schema")]
    (is (nil? (ports/get-player s "chat-x" "player-y")))
    (ports/create-player! s "chat-x" "player-y")
    (ports/create-save! s "chat-x" "player-y" "a")
    (is (some? (ports/get-player s "chat-x" "player-y")))
    (is (= 0 (count (ports/list-collection s "chat-x" "player-y" "a"))))))
