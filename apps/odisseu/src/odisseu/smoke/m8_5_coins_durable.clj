(ns odisseu.smoke.m8-5-coins-durable
  "Runner M8.5 — coins-delta real + durable-op (Datomic descartável)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.coins-durable :as coins]
            [odisseu.cutover.coins-durable-drill :as drill]
            [odisseu.cutover.durability :as dur])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest coverage-is-coins-only
  (is (true? (:coins-delta-wired? coins/coverage)))
  (is (false? (:other-mutations-wired? coins/coverage)))
  (is (false? (:independent-failure-domain-configured? coins/coverage))))

(deftest gates-remain-blocked
  (let [g (dur/evaluate-m85-gates
           {:synthetic-protocol-ok? true
            :coins-delta-store-ok? true
            :coins-delta-recovery-ok? true
            :bridge-http-ok? true
            :other-mutations-wired? false
            :independent-failure-domain? false
            :production-journal-deployed? false
            :whatsapp-staging-validated? false})]
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:cutover-ready? g)))
    (is (false? (:global-protection-complete? g)))))

(deftest coins-durable-drill
  (testing "store real + reconciliação + volume loss + bridge"
    (let [report (drill/run-all! {:out-dir "target/m8-5"})
          _ (reset! report-atom report)
          by (into {} (map (juxt :scenario :ok?) (:results report)))]
      (is (:ok? report) (pr-str (:results report)))
      (is (true? (get by :normal)))
      (is (true? (get by :retry-same-op)))
      (is (true? (get by :datomic-ok-journal-fail)))
      (is (true? (get by :volume-loss-replay)))
      (is (true? (get by :bridge-http)))
      (is (true? (get by :maintenance-blocks)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report))))))

(defn -main
  [& _]
  (println "=== M8.5 coins-delta durable-op (store real, sintético) ===")
  (let [summary (run-tests 'odisseu.smoke.m8-5-coins-durable)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom
        by (into {} (map (juxt :scenario identity) (:results report)))
        gates (dur/evaluate-m85-gates
               {:synthetic-protocol-ok? true
                :coins-delta-store-ok? (boolean (get-in by [:normal :ok?]))
                :coins-delta-recovery-ok? (boolean (get-in by [:volume-loss-replay :ok?]))
                :bridge-http-ok? (boolean (get-in by [:bridge-http :ok?]))
                :other-mutations-wired? false
                :independent-failure-domain? false
                :production-journal-deployed? false
                :whatsapp-staging-validated? false})]
    (pprint/pprint
     {:milestone "M8.5"
      :at (str (Instant/now))
      :ok? ok?
      :scenarios (count (:results report))
      :coverage (:coverage report)
      :gates gates
      :rollback-recon-available? false
      :cutover-ready? false
      :nota "coins-delta wired; demais mutações descobertas; sem RPO zero."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
