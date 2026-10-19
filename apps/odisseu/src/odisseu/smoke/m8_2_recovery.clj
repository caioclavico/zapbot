(ns odisseu.smoke.m8-2-recovery
  "Runner M8.2 — recuperação pós-cutover sintético (sem produção)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.recovery :as recovery]
            [odisseu.cutover.recovery-drill :as drill])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest failure-catalog-covers-nine-scenarios
  (is (= 9 (count recovery/failure-catalog)))
  (is (false? (:zero-loss-claimed? recovery/recovery-strategy)))
  (is (false? (:cassandra-auto-rollback? recovery/recovery-strategy))))

(deftest rollback-recon-blocked-when-confirmed-loss
  (let [g (recovery/evaluate-recovery-gates
           {:backup-consistent-validated true
            :restore-proven true
            :single-writer true
            :safe-reprocess true
            :storage-failure-handled true
            :no-silent-divergence true
            :rpo-rto-measured true
            :maintenance-procedure true
            :lost-confirmed-ops 3
            :external-durable-log? false
            :rollback-recon-available true})]
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:zero-loss-proven? g)))
    (is (some #{:rollback-recon-available} (:missing g)))))

(deftest full-recovery-drill
  (testing "backup → post-tx → restore → RPO/perda → manutenção"
    (let [report (drill/run-drill! {:out-dir "target/m8-2"})
          _ (reset! report-atom report)
          primary (:primary-drill report)]
      (is (:ok? report) (pr-str (select-keys report [:ok? :rpo :rto :unrecovered-ops
                                                     :rollback-recon-available?])))
      (is (true? (:pre-survived? primary)))
      (is (true? (:post-lost? primary)))
      (is (seq (:unrecovered-ops report)))
      (is (true? (get-in primary [:reprocess :ok?])))
      (is (true? (get-in primary [:http-lost-retry :ok?])))
      (is (true? (:exit-without-resolve-blocked? primary)))
      (is (false? (:zero-loss-proven? report)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report)))
      (is (some? (get-in report [:rpo :rpo-ms])))
      (is (some? (get-in report [:rto :rto-ms])))
      (is (true? (get-in report [:partial-store :ok?])))
      (is (true? (get-in report [:jvm-reopen :ok?])))
      (is (true? (get-in report [:restore-to-new-dest :ok]))))))

(defn -main
  [& _]
  (println "=== M8.2 post-cutover recovery (sintético) ===")
  (let [summary (run-tests 'odisseu.smoke.m8-2-recovery)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom]
    (pprint/pprint
     {:milestone "M8.2"
      :at (str (Instant/now))
      :ok? ok?
      :zero-loss-proven? false
      :rollback-recon-available? false
      :cutover-ready? false
      :rpo-ms (get-in report [:rpo :rpo-ms])
      :rto-ms (get-in report [:rto :rto-ms])
      :recovered-ops (:recovered-ops report)
      :unrecovered-ops (:unrecovered-ops report)
      :nota "Recuperação com RPO>0 demonstrada. Sem perda zero. Sem cutover."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
