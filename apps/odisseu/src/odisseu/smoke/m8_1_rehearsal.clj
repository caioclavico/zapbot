(ns odisseu.smoke.m8-1-rehearsal
  "Runner M8.1 — ensaio offline ponta a ponta (sem produção / WhatsApp)."
  (:require [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing run-tests]]
            [odisseu.cutover.rehearsal :as rehearsal])
  (:import [java.time Instant]))

(def ^:private report-atom (atom nil))

(deftest end-to-end-offline-rehearsal
  (testing "ensaio M8.1 completo"
    (let [report (rehearsal/run-rehearsal! {})
          _ (reset! report-atom report)
          pv (:pokemon-validation-summary report)]
      (is (:ok? report) (pr-str (select-keys report [:ok? :timings :failure-gates
                                                     :pokemon-validation-summary :gates])))
      (is (false? (:cutover-ready? report)))
      (is (false? (:migration-real-completed? report)))
      (is (true? (get-in report [:platform :ok?])))
      (is (true? (get-in report [:platform :all-equivalent?])))
      (is (false? (get-in report [:platform :poller-started?])))
      (is (false? (get-in report [:platform :real-platform-imported?])))
      (is (true? (get-in report [:pokemon :ok?])))
      (is (true? (get-in report [:pokemon :m55-storage-untouched?])))
      (is (= 19 (get-in pv [:counts :collection-a])))
      (is (= 7 (get-in pv [:counts :beds-a])))
      (is (= 119 (get-in pv [:counts :collection-b])))
      (is (= 198 (get-in pv [:coins :a])))
      (is (= 437 (get-in pv [:coins :b])))
      (is (zero? (:id-intersection pv)))
      (is (= 1 (:voltorb-shiny-b pv)))
      (is (true? (:joy-deadlines-preserved? pv)))
      (is (true? (:aba-preserved? pv)))
      (is (true? (:reopen-ok? pv)))
      (is (true? (:restore-ok? pv)))
      (is (true? (get-in report [:failure-gates :ok?])))
      (is (false? (get-in report [:failure-gates :activation-permitted?])))
      (is (false? (get-in report [:gates :go?])))
      (is (some #{:whatsapp-staging-validated} (get-in report [:gates :missing]))))))

(defn -main
  [& _]
  (println "=== M8.1 offline cutover rehearsal ===")
  (let [summary (run-tests 'odisseu.smoke.m8-1-rehearsal)
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))
        report @report-atom]
    (pprint/pprint
     {:milestone "M8.1"
      :at (str (Instant/now))
      :ok? ok?
      :cutover-ready? false
      :timings (:timings report)
      :pokemon-counts (get-in report [:pokemon-validation-summary :counts])
      :coins (get-in report [:pokemon-validation-summary :coins])
      :failure-gates-ok? (get-in report [:failure-gates :ok?])
      :gates-missing (get-in report [:gates :missing])
      :nota "Ensaio offline apenas. Migração real NÃO concluída. Dump 14/10 = evidência."})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
