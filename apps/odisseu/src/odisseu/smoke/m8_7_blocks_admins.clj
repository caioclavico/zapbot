(ns odisseu.smoke.m8-7-blocks-admins
  "Runner M8.7 — bloqueio/admins durable-op."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.blocks-admins-drill :as drill]
            [odisseu.cutover.blocks-admins-durable :as ba]
            [odisseu.cutover.durability :as dur])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest coverage-partial
  (is (true? (:blocks-admins-wired? ba/coverage)))
  (is (false? (:other-mutations-wired? ba/coverage)))
  (is (some #(= :protected-tested (:status %))
            (get-in ba/coverage-matrix [:blocks-admins])))
  (is (some #(= :implemented-no-journal (:status %))
            (get-in ba/coverage-matrix [:enquetes]))))

(deftest gates-remain-blocked
  (let [g (dur/evaluate-m87-gates
           {:coins-delta-ok? true
            :rank-score-ok? true
            :blocks-admins-ok? true
            :blocks-admins-recovery-ok? true
            :opposing-ops-order-ok? true
            :bridge-blocks-ok? true
            :other-mutations-wired? false
            :independent-failure-domain? false
            :production-journal-deployed? false
            :whatsapp-staging-validated? false})]
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:cutover-ready? g)))
    (is (false? (:global-protection-complete? g)))))

(deftest blocks-admins-drill
  (testing "ops opostas, volume replay, cache após uncertain"
    (let [report (drill/run-all! {:out-dir "target/m8-7"})
          _ (reset! report-atom report)
          by (into {} (map (juxt :scenario :ok?) (:results report)))]
      (is (:ok? report) (pr-str (:results report)))
      (is (true? (get by :normal)))
      (is (true? (get by :opposing-order)))
      (is (true? (get by :volume-replay-order)))
      (is (true? (get by :after-commit-journal-fail)))
      (is (true? (get by :bridge-and-uncertain-cache)))
      (is (true? (get by :required-strict-missing)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report))))))

(defn -main
  [& _]
  (println "=== M8.7 blocks/admins durable-op ===")
  (let [summary (run-tests 'odisseu.smoke.m8-7-blocks-admins)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom
        by (into {} (map (juxt :scenario identity) (:results report)))
        gates (dur/evaluate-m87-gates
               {:coins-delta-ok? true
                :rank-score-ok? true
                :blocks-admins-ok? (boolean (get-in by [:normal :ok?]))
                :blocks-admins-recovery-ok?
                (boolean (get-in by [:volume-replay-order :ok?]))
                :opposing-ops-order-ok?
                (boolean (get-in by [:opposing-order :ok?]))
                :bridge-blocks-ok?
                (boolean (get-in by [:bridge-and-uncertain-cache :ok?]))
                :other-mutations-wired? false
                :independent-failure-domain? false
                :production-journal-deployed? false
                :whatsapp-staging-validated? false})]
    (pprint/pprint
     {:milestone "M8.7"
      :at (str (Instant/now))
      :ok? ok?
      :scenarios (count (:results report))
      :coverage (:coverage report)
      :matrix-summary
      (into {}
            (map (fn [[k vs]]
                   [k (frequencies (map :status vs))])
                 (:coverage-matrix report)))
      :gates gates
      :rollback-recon-available? false
      :cutover-ready? false
      :nota "blocks/admins + coins/rank; demais descobertos; sem RPO zero."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
