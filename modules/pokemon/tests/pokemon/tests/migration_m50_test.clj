(ns pokemon.tests.migration-m50-test
  "Testes M5.0 — harness Cassandra sintético → Datomic Local."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.bridge-tx :as tx]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.equivalence :as eq]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fix]
            [pokemon.migration.import :as mig]
            [pokemon.migration.pipeline :as pipeline]
            [pokemon.migration.transform :as transform]))

(def ^:private system "odisseu-m50-tests")
(def ^:private storage ".datomic-local/m50-tests")

(defn- boot!
  [db-name]
  (let [client (persistence/client {:system system :storage-dir storage})]
    (persistence/delete-db! client db-name)
    (let [conn (persistence/ensure-db! client db-name)
          s (store/make-store conn)]
      (trainer/bootstrap! s)
      s)))

(deftest fixtures-tamanhos-e-shiny-voltorb
  (let [a (fix/active-team-26)
        hist (fix/historical-team-119)
        b (concat (:equipe hist) (:pc hist))]
    (is (= 26 (count a)))
    (is (= 119 (count b)))
    (is (some #(and (= "Voltorb" (get % "nome")) (true? (get % "shiny"))) a))
    (is (some #(and (= "Voltorb" (get % "nome"))
                    (true? (get % "shiny"))
                    (= "hist-voltorb-shiny-001" (get % "id-pokemon")))
              b))
    (is (empty? (set/intersection
                 (set (map #(get % "id-pokemon") a))
                 (set (map #(get % "id-pokemon") b)))))))

(deftest transform-proveniencia-explicita-e-quarentena
  (let [export (export/synthetic-default)
        ir (transform/transform-export export)
        slots (into {} (map (juxt :slot-id identity) (:saves ir)))]
    (is (= 2 (count (:saves ir))))
    (is (= 26 (get-in slots ["a" :pokemon-count])))
    (is (= 119 (get-in slots ["b" :pokemon-count])))
    (is (= :canonical-active (get-in slots ["a" :provenance :kind])))
    (is (= :historical-independent (get-in slots ["b" :provenance :kind])))
    (is (= "syn-hist-independente-001"
           (get-in slots ["b" :provenance :snapshot-id])))
    (is (some #(= :loja-sem-treinador (:reason %)) (:quarantine ir)))
    (is (some #(= :sourceVersion-ignorado-como-identidade (:reason %))
              (:quarantine ir)))
    ;; Grava fixture auditável
    (let [path "fixtures/m5-0/cassandra-dump.edn"]
      (io/make-parents path)
      (spit path (pr-str (fix/build-cassandra-dump)))
      (is (.exists (io/file path))))))

(deftest import-equivalencia-e-ab-sem-mesclar
  (let [s (boot! "m50-eq")
        export (export/synthetic-default)
        ir (transform/transform-export export)
        batch "batch-eq-1"
        _ (is (nil? (ports/get-player s fix/chat-id fix/player-id)))
        result (mig/import-ir! s ir {:import-batch-id batch})
        eqr (eq/compare-ir s ir)
        col-a (ports/list-collection s fix/chat-id fix/player-id "a")
        col-b (ports/list-collection s fix/chat-id fix/player-id "b")]
    (is (= "a" (:active-save-id result)))
    (is (:ok? eqr) (pr-str eqr))
    (is (= 26 (count col-a)))
    (is (= 119 (count col-b)))
    (is (some #(and (= "Voltorb" (:species %)) (:shiny %)) col-a))
    (let [sw (trainer/switch-save! s fix/chat-id fix/player-id "b")
          sw2 (trainer/switch-save! s fix/chat-id fix/player-id "a")]
      (is (= "a" (get-in sw2 [:player :active-save-id])))
      (is (= 26 (count (ports/list-collection s fix/chat-id fix/player-id "a"))))
      (is (= 119 (count (ports/list-collection s fix/chat-id fix/player-id "b"))))
      (is (not= (set (map :id-pokemon col-a))
                (set (map :id-pokemon
                          (ports/list-collection s fix/chat-id fix/player-id "b"))))))))

(deftest reimport-idempotente
  (let [s (boot! "m50-idem")
        ir (transform/transform-export (export/synthetic-default))
        batch "batch-idem-1"
        r1 (mig/import-ir! s ir {:import-batch-id batch})
        r2 (mig/reimport-same-batch! s ir batch)]
    (is (every? #(= :imported (:status %)) (:results r1)))
    (is (every? #(= :duplicate (:status %)) r2))))

(deftest rejeita-ensure-trainer-antes-do-import
  (let [s (boot! "m50-ensure")
        ir (transform/transform-export (export/synthetic-default))]
    (tx/ensure-trainer! s {:chat-id fix/chat-id :player-id fix/player-id})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"import_player_already_exists"
                          (mig/import-ir! s ir {:import-batch-id "batch-bad"})))))

(deftest rejeita-payload-diferente-mesmo-batch
  (let [s (boot! "m50-mismatch")
        ir (transform/transform-export (export/synthetic-default))
        batch "batch-mis-1"
        _ (mig/import-ir! s ir {:import-batch-id batch})
        tampered (update ir :saves
                         (fn [saves]
                           (mapv (fn [sv]
                                   (if (= "a" (:slot-id sv))
                                     (assoc sv :content-hash "deadbeef" :coins 1)
                                     sv))
                                 saves)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"import_receipt_payload_mismatch"
                          (mig/reimport-same-batch! s tampered batch)))))

(deftest pipeline-completo-backup-restore
  (let [report (pipeline/run-pipeline!
                {:storage-rel ".datomic-local/m50-pipeline"
                 :backup-rel ".datomic-local/m50-pipeline-bak"
                 :system "odisseu-m50-pipeline"
                 :db-name "pipe-1"
                 :out-dir "target/m5-0"
                 :import-batch-id "pipeline-batch-1"})]
    (is (:ok? report) (pr-str (select-keys report [:ok? :equivalence :switch-a-b-a])))
    (is (false? (:m5-complete? report)))
    (is (.exists (io/file "target/m5-0/report.md")))
    (is (.exists (io/file "target/m5-0/ir.edn")))
    (is (= 26 (get-in report [:switch-a-b-a :counts :a-after])))
    (is (= 119 (get-in report [:switch-a-b-a :counts :b-after])))
    (is (false? (get-in report [:switch-a-b-a :merged?])))
    (is (get-in report [:equivalence :after-restore :ok?]))))
