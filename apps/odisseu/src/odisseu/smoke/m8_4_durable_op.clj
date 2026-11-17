(ns odisseu.smoke.m8-4-durable-op
  "Runner M8.4 — endurecimento operacional do journal (sintético)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.durable-crash :as crash]
            [odisseu.cutover.durable-op :as dop]
            [odisseu.cutover.durability :as dur])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest protocol-requires-journal-before-http-success
  (is (contains? (:client-success-requires dop/state-machine) :journal-durable))
  (is (false? (:exactly-once-across-stores? dop/state-machine)))
  (is (false? (:wired-in-stores? dop/domain-coverage))))

(deftest gates-keep-recon-and-cutover-blocked
  (let [g (dur/evaluate-m84-gates
           {:protocol-crash-proven? true
            :stores-wired? false
            :production-journal-deployed? false
            :independent-failure-domain? false
            :whatsapp-staging-validated? false})]
    (is (true? (:protocol-crash-proven? g)))
    (is (false? (:rollback-recon-available? g)))
    (is (false? (:cutover-ready? g)))
    (is (false? (:exactly-once-claimed? g)))
    (is (false? (:rpo-zero-claimed? g)))))

(deftest crash-boundary-drill
  (testing "falhas em cada fronteira; sem HTTP success se journal falhar após Datomic"
    (let [report (crash/run-all! {:out-dir "target/m8-4"})
          _ (reset! report-atom report)
          by-sc (into {} (map (juxt :scenario identity) (:results report)))]
      (is (:ok? report) (pr-str (:results report)))
      (is (true? (get-in by-sc ["after-datomic" :ok?])))
      (is (false? (get-in by-sc ["after-datomic" :http-success?])))
      (is (true? (get-in by-sc ["after-http" :ok?])))
      (is (true? (get-in by-sc ["after-http" :http-success?])))
      (is (false? (:stores-integrated? report)))
      (is (false? (:rollback-recon-available? report)))
      (is (false? (:cutover-ready? report)))
      (is (false? (:exactly-once-claimed? report)))
      (is (false? (:rpo-zero-claimed? report))))))

(defn -main
  [& _]
  (println "=== M8.4 durable-op / crash boundaries (sintético) ===")
  (let [summary (run-tests 'odisseu.smoke.m8-4-durable-op)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom
        gates (dur/evaluate-m84-gates
               {:protocol-crash-proven? (boolean (:all-ok? report))
                :stores-wired? false
                :production-journal-deployed? false
                :independent-failure-domain? false
                :whatsapp-staging-validated? false})]
    (pprint/pprint
     {:milestone "M8.4"
      :at (str (Instant/now))
      :ok? ok?
      :crash-scenarios (count (:results report))
      :all-crash-ok? (:all-ok? report)
      :coverage (:coverage report)
      :gates gates
      :rollback-recon-available? false
      :cutover-ready? false
      :nota (str "Prepare→Datomic→journal-durable→HTTP sob :strict. "
                 "Stores/bridge ainda não wired. Sem RPO zero / exactly-once.")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
