(ns odisseu.smoke.m8-0-cutover
  "Runner M8.0 — pipeline cutover sintético (sem produção / WhatsApp)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.export :as export]
            [odisseu.cutover.inventory :as inv]
            [odisseu.cutover.pipeline :as pipe]
            [odisseu.cutover.reconcile :as reconcile]
            [odisseu.cutover.rollback :as rollback]
            [odisseu.persistence.datomic :as persistence]
            [pokemon.migration.fixtures :as poke-fix])
  (:import [java.time Instant]))

(defn- fixture [rel]
  (str (clojure.java.io/file (persistence/project-root) rel)))

(deftest inventory-classifies-gaps
  (let [newer (export/load-dump! (fixture "fixtures/m8-0/snapshot-newer.edn"))
        inv* (inv/inventory-dump newer)
        c (:classified inv*)]
    (is (seq (:platform c)))
    (is (seq (:decision-required c)))
    (is (some #{"pokedex-cache"} (map str (:rebuildable c))))
    (is (some #{"pokemon-http-deliveries"} (map str (:ephemeral c))))))

(deftest reconcile-detects-deltas-without-summing
  (let [older (export/load-dump! (fixture "fixtures/m8-0/snapshot-older.edn"))
        newer (export/load-dump! (fixture "fixtures/m8-0/snapshot-newer.edn"))
        r (reconcile/reconcile-dumps older newer)
        ash (get-in r [:pokemon :players "551180000001@c.us"])]
    (is (:go? r))
    (is (:older-is-evidence-only? r))
    (is (= :newer (:import-candidate r)))
    (is (= :modified (:status ash)))
    (is (= [200 350] (get-in ash [:deltas :moedas])))
    (is (false? (:block-import? r)))
    (is (= :added (get-in r [:lembretes-lifecycle "rem-m80-new" :transition])))
    (is (= :unchanged (get-in r [:lembretes-lifecycle "rem-m80-pending" :transition])))
    (is (= "completed" (get-in r [:lembretes-lifecycle "rem-m80-sent" :status])))
    (is (= "cancelled" (get-in r [:lembretes-lifecycle "rem-m80-cancel" :status])))))

(deftest ambiguous-provenance-blocks
  (let [older (export/load-dump! (fixture "fixtures/m8-0/snapshot-older.edn"))
        newer (export/load-dump! (fixture "fixtures/m8-0/snapshot-newer.edn"))
        ;; força ambiguidade: mesmo snapshot-id histórico com conteúdo diferente
        poisoned (update newer :historical_snapshots
                         (fn [snaps]
                           (mapv (fn [s]
                                   (if (= "m8-hist-b-independent-001" (:snapshot-id s))
                                     (assoc-in s [:loja "moedas"] 9999)
                                     s))
                                 snaps)))
        r (reconcile/reconcile-dumps older poisoned)]
    (is (:block-import? r))
    (is (false? (:go? r)))))

(deftest export-requires-synthetic-meta
  (let [f (java.io.File/createTempFile "m80-bad" ".edn")]
    (spit f "{:meta {:foo true} :modules {}}")
    (is (thrown? Exception (export/load-dump! (.getAbsolutePath f))))))

(deftest gates-block-cutover-without-whatsapp
  (let [g (rollback/evaluate-gates
           {:writers-frozen true
            :snapshot-checksum-ok true
            :reconcile-unblocked true
            :import-equivalent true
            :single-writer-plan true
            :whatsapp-staging-validated false
            :rollback-recon-available true
            :authorization true})]
    (is (false? (:go? g)))
    (is (false? (:cutover-ready? g)))
    (is (some #{:whatsapp-staging-validated} (:missing g)))
    (is (false? (:safe-without-recon? (rollback/post-cutover-risk))))))

(deftest full-pipeline-synthetic
  (testing "pipeline completo sintético"
    (let [report (pipe/run-pipeline!
                  {:older-path "fixtures/m8-0/snapshot-older.edn"
                   :newer-path "fixtures/m8-0/snapshot-newer.edn"
                   :out-dir "target/m8-0"
                   :import-batch-id "m80-batch-syn-001"})]
      (is (:ok? report) (pr-str (select-keys report [:platform-import :pokemon-import :gates])))
      (is (false? (:cutover-ready? report)))
      (is (= :duplicate (get-in report [:platform-import :reimport-status])))
      (is (true? (get-in report [:platform-import :all-equivalent?])))
      (is (false? (get-in report [:platform-import :poller-started?])))
      (is (every? #(= :duplicate %) (get-in report [:pokemon-import :reimport-statuses])))
      (is (true? (get-in report [:pokemon-import :equivalence-ok?])))
      (is (true? (get-in report [:pokemon-import :restore-ok?])))
      (is (false? (get-in report [:gates :go?]))))))

(deftest pokemon-two-saves-fixture-available
  (let [dump (poke-fix/build-cassandra-dump)]
    (is (get-in dump [:meta :synthetic]))
    (is (= 1 (count (:historical_snapshots dump))))
    (is (= :canonical-active (get-in dump [:active_provenance :kind])))))

(defn -main
  [& _]
  (println "=== M8.0 cutover prep (sintético; sem produção) ===")
  (let [summary (run-tests 'odisseu.smoke.m8-0-cutover)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (pprint/pprint {:milestone "M8.0"
                    :at (str (Instant/now))
                    :ok? ok?
                    :summary summary
                    :cutover-ready? false
                    :nota "Prep only. WhatsApp staging autenticado ainda não validado."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
