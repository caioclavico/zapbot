(ns odisseu.smoke.m8-3-durability
  "Runner M8.3 — durabilidade / journal independente (sintético)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.durability :as dur]
            [odisseu.cutover.durability-drill :as drill])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest strategy-c-chosen-without-kafka
  (is (= :C-backup-plus-journal (:id dur/chosen-strategy)))
  (is (false? (get-in dur/chosen-strategy [:protocol :exactly-once-claimed?])))
  (is (every? #(false? (:kafka-needed? %)) dur/strategy-comparison)))

(deftest gates-keep-cutover-and-recon-blocked-without-prod-journal
  (let [g (dur/evaluate-m83-gates
           {:journal-recon-proven? true
            :dual-loss-still-fatal? true
            :journal-lag-honest? true
            :whatsapp-staging-validated? false
            :production-journal-deployed? false})]
    (is (true? (:journal-recon-proven? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:cutover-ready? g)))))

(deftest full-durability-drill
  (testing "backup+journal reconstrói; lag e dual-loss não"
    (let [report (drill/run-drill! {:out-dir "target/m8-3"})
          _ (reset! report-atom report)
          happy (get-in report [:scenarios :happy])
          lag (get-in report [:scenarios :journal-lag])
          dual (get-in report [:scenarios :dual-loss])]
      (is (:ok? report) (pr-str (select-keys report [:ok? :gates :recovered-with-journal])))
      (is (true? (:reconstructed? happy)))
      (is (true? (:post-lost-before-replay? happy)))
      (is (= ["m83-post-coins" "m83-post-score" "m83-post-block"]
             (:recovered-with-journal report)))
      (is (true? (:lost? lag)))
      (is (true? (:post-lost? dual)))
      (is (true? (:journal-recon-proven? report)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report)))
      (is (false? (:exactly-once-claimed? report))))))

(defn -main
  [& _]
  (println "=== M8.3 durability / independent journal (sintético) ===")
  (let [summary (run-tests 'odisseu.smoke.m8-3-durability)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom]
    (pprint/pprint
     {:milestone "M8.3"
      :at (str (Instant/now))
      :ok? ok?
      :chosen (:id dur/chosen-strategy)
      :journal-recon-proven? (:journal-recon-proven? report)
      :rollback-recon-available? false
      :cutover-ready? false
      :recovered-with-journal (:recovered-with-journal report)
      :lost-journal-lag (:lost-when-journal-lags report)
      :lost-dual-loss (:lost-when-dual-loss report)
      :rpo-ms (get-in report [:rpo-happy :rpo-ms])
      :rto-ms (get-in report [:rto-happy :rto-ms])
      :nota "Journal reconstrói pós-backup se sobreviver. Sem RPO zero. Sem exactly-once."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
