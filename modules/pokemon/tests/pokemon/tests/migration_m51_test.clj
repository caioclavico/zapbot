(ns pokemon.tests.migration-m51-test
  "Testes M5.1 — inventário offline, matching 26/119, gates, Voltorb histórico."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.application.ports :as ports]
            [pokemon.application.trainer :as trainer]
            [pokemon.infrastructure.datomic.store :as store]
            [pokemon.migration.export :as export]
            [pokemon.migration.fixtures :as fix]
            [pokemon.migration.gates :as gates]
            [pokemon.migration.import :as mig]
            [pokemon.migration.inventory :as inv]
            [pokemon.migration.match :as match]
            [pokemon.migration.recovery :as recovery]
            [pokemon.migration.transform :as transform]))

(deftest historical-119-tem-voltorb-shiny-estavel
  (let [hist (fix/historical-team-119)
        all (concat (:equipe hist) (:pc hist) (:enfermaria hist))
        voltorb (->> all
                     (filter #(and (= "Voltorb" (get % "nome"))
                                   (true? (get % "shiny")))))]
    (is (= 119 (count all)))
    (is (= 119 (count (:equipe hist))))
    (is (= 0 (count (:pc hist))))
    (is (= 1 (count voltorb)))
    (is (= "hist-voltorb-shiny-001" (get (first voltorb) "id-pokemon")))))

(deftest enfermaria-envelope-desembrulha-id
  (let [account {"equipe" [{"id-pokemon" "eq-1" "nome" "Pikachu" "nivel" 5}]
                 "pc" []
                 "enfermaria" [{"pronto-em" 1791929455454
                                "pokemon" {"id-pokemon" "enf-uuid-1"
                                           "nome" "Glimmora"
                                           "nivel" 1
                                           "shiny" false}}]
                 "vitorias-treinador" 0 "pe-ginasios" 0 "pe-raids" 0
                 "xp-insignias" 0 "xp-missoes" 0}
        inv (inv/inventory-dump
             {:meta {:synthetic true}
              :estado_particionado
              [{:modulo "treinador" :particao "c1" :valor {"p1" account}}
               {:modulo "loja" :particao "c1" :valor {"p1" {"moedas" 1 "inventario" {}}}}]})
        cand (first (:candidates inv))
        ir-save (first (:saves (transform/transform-export
                                {:meta {:synthetic true}
                                 :modules {"treinador" {"c1" {"p1" account}}
                                           "loja" {"c1" {"p1" {"moedas" 1 "inventario" {}}}}}
                                 :active_provenance {:kind :canonical-active
                                                     :snapshot-id "t"
                                                     :target-slot "a"
                                                     :chat-id "c1"
                                                     :player-id "p1"}
                                 :historical_snapshots []})))]
    (is (= 2 (:pokemon-count cand)))
    (is (= 0 (:missing-id-count cand)))
    (is (= #{"eq-1" "enf-uuid-1"} (set (:ids cand))))
    (is (= 2 (:pokemon-count ir-save)))
    (is (= :enfermaria (:location (second (:pokemon ir-save)))))
    (is (= "enf-uuid-1" (:id-pokemon (second (:pokemon ir-save)))))
    (is (= 1791929455454 (:pronto-em (second (:pokemon ir-save)))))))

(deftest inventory-e-match-resolvem-sem-autoselect
  (let [dump (fix/build-cassandra-dump)
        path "fixtures/m5-0/cassandra-dump.edn"
        _ (do (io/make-parents path) (spit path (pr-str dump)))
        inventory (inv/inventory-dump dump :path path)
        report (match/match-inventory inventory)]
    (is (pos? (:candidate-count inventory)))
    (is (false? (:auto-selected? report)))
    (is (= :resolved (:status report)) (pr-str report))
    (let [p26 (->> (:profiles report) (filter #(= :save-atual-26 (:profile-id %))) first)
          p119 (->> (:profiles report) (filter #(= :historico-independente-119 (:profile-id %))) first)]
      (is (= 1 (:hit-count p26)))
      (is (= 26 (:pokemon-count (first (:hits p26)))))
      (is (= 1 (:hit-count p119)))
      (is (= 119 (:pokemon-count (first (:hits p119)))))
      (is (some #(= "hist-voltorb-shiny-001" %) (:shiny-ids (first (:hits p119))))))
    (is (true? (:requires-human-confirmation report)))))

(deftest match-ambiguo-nao-escolhe
  (let [dump (fix/build-cassandra-dump)
        ;; Segundo histórico 119 com Voltorb shiny (prefixo exigido) e IDs distintos
        extra-hist (-> (first (:historical_snapshots dump))
                       (assoc :snapshot-id "dup-hist-119")
                       (update :treinador
                               (fn [t]
                                 (-> t
                                     (update "equipe"
                                             (fn [eq]
                                               (mapv (fn [p]
                                                       (let [id (get p "id-pokemon")]
                                                         (assoc p "id-pokemon"
                                                                (if (= "hist-voltorb-shiny-001" id)
                                                                  "hist-voltorb-shiny-002"
                                                                  (str "dup-" id)))))
                                                     eq)))
                                     (update "pc"
                                             (fn [pc]
                                               (mapv #(assoc % "id-pokemon"
                                                             (str "dup-" (get % "id-pokemon")))
                                                     pc)))))))
        dump2 (update dump :historical_snapshots conj extra-hist)
        report (match/match-inventory (inv/inventory-dump dump2))]
    (is (= :ambiguous (:status report)) (pr-str report))
    (is (nil? (:suggested-mapping report)))
    (is (false? (:auto-selected? report)))))

(deftest gates-bloqueiam-match-nao-resolvido
  (let [ir (transform/transform-export (export/synthetic-default))
        bad-match {:status :ambiguous :auto-selected? false :block-reason "ambig"}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"import_blocked_by_gates"
                          (gates/assert-importable! ir bad-match)))
    (let [good (match/match-inventory
                (inv/inventory-dump (fix/build-cassandra-dump)))]
      (is (= :resolved (:status good)))
      (is (:ok? (gates/assert-importable! ir good))))))

(deftest gates-bloqueiam-perda-de-campo
  (let [ir (transform/transform-export (export/synthetic-default))
        broken (update ir :saves
                       (fn [saves]
                         (mapv (fn [s]
                                 (if (= "a" (:slot-id s))
                                   (update s :pokemon
                                           (fn [ps]
                                             (assoc-in (vec ps) [0 :id-pokemon] "")))
                                   s))
                               saves)))
        good (match/match-inventory (inv/inventory-dump (fix/build-cassandra-dump)))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"import_blocked_by_gates"
                          (gates/assert-importable! broken good)))))

(deftest import-com-require-match-ok
  (let [client (persistence/client {:system "odisseu-m51-tests"
                                    :storage-dir ".datomic-local/m51-tests"})
        _ (persistence/delete-db! client "m51-imp")
        conn (persistence/ensure-db! client "m51-imp")
        s (doto (store/make-store conn) trainer/bootstrap!)
        dump (fix/build-cassandra-dump)
        ir (transform/transform-export (export/from-fixture-dump dump))
        m (match/match-inventory (inv/inventory-dump dump))
        result (mig/import-ir! s ir {:import-batch-id "m51-batch"
                                     :require-match-resolved? true
                                     :match-report m})]
    (is (= "a" (:active-save-id result)))
    (is (= 19 (count (ports/list-collection s fix/chat-id fix/player-id "a"))))
    (is (= 7 (count (ports/list-enfermaria s fix/chat-id fix/player-id "a"))))
    (is (= 119 (count (ports/list-collection s fix/chat-id fix/player-id "b"))))
    (is (some #(and (= "Voltorb" (:species %))
                    (= "hist-voltorb-shiny-001" (:id-pokemon %)))
              (ports/list-collection s fix/chat-id fix/player-id "b")))))

(deftest recovery-discovery-e-checksum
  (let [out "target/m5-1"
        disc (recovery/synthetic-discovery! {:out-dir out})]
    (is (= :resolved (:match-status disc)))
    (is (.exists (io/file out "inventory.edn")))
    (is (.exists (io/file out "match.md")))
    (is (= 1 (:verified (recovery/verify-checksums! (:checksums disc)))))
    (is (false? (:auto-selected? disc)))))

(deftest sourceVersion-nao-identifica
  (let [dump (fix/build-cassandra-dump)
        inv (inv/inventory-dump dump)
        with-v1 (filter #(= "1" (str (:source-version %))) (:candidates inv))]
    (is (seq with-v1))
    (is (every? :sourceVersion-not-identity with-v1))
    (is (some #(= :sourceVersion-present (:code %)) (:warnings inv)))))
