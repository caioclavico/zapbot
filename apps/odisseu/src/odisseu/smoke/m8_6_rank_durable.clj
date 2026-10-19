(ns odisseu.smoke.m8-6-rank-durable
  "Runner M8.6 — rank score-point + durable-op (Platform descartável)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.durability :as dur]
            [odisseu.cutover.rank-durable :as rank]
            [odisseu.cutover.rank-durable-drill :as drill])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest coverage-coins-and-rank-only
  (is (true? (:coins-delta-wired? rank/coverage)))
  (is (true? (:rank-score-wired? rank/coverage)))
  (is (false? (:other-mutations-wired? rank/coverage))))

(deftest gates-remain-blocked
  (let [g (dur/evaluate-m86-gates
           {:coins-delta-ok? true
            :rank-score-ok? true
            :rank-recovery-ok? true
            :bridge-rank-ok? true
            :required-strict-policy-ok? true
            :other-mutations-wired? false
            :independent-failure-domain? false
            :production-journal-deployed? false
            :whatsapp-staging-validated? false})]
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:cutover-ready? g)))
    (is (false? (:global-protection-complete? g)))))

(deftest rank-durable-drill
  (testing "score-point real + recover + isolation + required-strict"
    (let [report (drill/run-all! {:out-dir "target/m8-6"})
          _ (reset! report-atom report)
          by (into {} (map (juxt :scenario :ok?) (:results report)))]
      (is (:ok? report) (pr-str (:results report)))
      (is (true? (get by :normal)))
      (is (true? (get by :retry)))
      (is (true? (get by :volume-loss-replay)))
      (is (true? (get by :required-journal-missing)))
      (is (true? (get by :pokemon-platform-isolation)))
      (is (true? (get by :bridge-http)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report))))))

(defn -main
  [& _]
  (println "=== M8.6 rank score-point durable-op ===")
  (let [summary (run-tests 'odisseu.smoke.m8-6-rank-durable)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom
        by (into {} (map (juxt :scenario identity) (:results report)))
        gates (dur/evaluate-m86-gates
               {:coins-delta-ok? true
                :rank-score-ok? (boolean (get-in by [:normal :ok?]))
                :rank-recovery-ok? (boolean (get-in by [:volume-loss-replay :ok?]))
                :bridge-rank-ok? (boolean (get-in by [:bridge-http :ok?]))
                :required-strict-policy-ok?
                (boolean (get-in by [:required-journal-missing :ok?]))
                :other-mutations-wired? false
                :independent-failure-domain? false
                :production-journal-deployed? false
                :whatsapp-staging-validated? false})]
    (pprint/pprint
     {:milestone "M8.6"
      :at (str (Instant/now))
      :ok? ok?
      :scenarios (count (:results report))
      :coverage (:coverage report)
      :gates gates
      :rollback-recon-available? false
      :cutover-ready? false
      :nota "coins+rank score wired; demais mutações descobertas; sem RPO zero."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
