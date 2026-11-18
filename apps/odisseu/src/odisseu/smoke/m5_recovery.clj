(ns odisseu.smoke.m5-recovery
  "Runner M5.1: inventário offline + matching + regressão M5.0."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.migration-m50-test]
            [pokemon.tests.migration-m51-test])
  (:import [java.time Instant]))

(defn- heap-stats
  []
  (let [rt (Runtime/getRuntime)
        mb (fn [n] (long (/ n (* 1024 1024))))]
    {:heap-used-mb (mb (- (.totalMemory rt) (.freeMemory rt)))
     :heap-total-mb (mb (.totalMemory rt))
     :heap-max-mb (mb (.maxMemory rt))}))

(defn -main
  [& _]
  (let [before (assoc (heap-stats) :at (str (Instant/now)))
        s50 (test/run-tests 'pokemon.tests.migration-m50-test)
        s51 (test/run-tests 'pokemon.tests.migration-m51-test)
        after (assoc (heap-stats) :at (str (Instant/now)))
        ok? (and (zero? (:fail s50)) (zero? (:error s50))
                 (zero? (:fail s51)) (zero? (:error s51)))]
    (println "=== M5.1 recovery prep summary ===")
    (pprint/pprint {:ok ok?
                    :m50 s50
                    :m51 s51
                    :memory {:before before :after after}
                    :m5-complete? false
                    :nota "Sem acesso a produção; matching nunca auto-seleciona sob ambiguidade."
                    :authorization-required
                    '[access-oracle-vm query-prod-cassandra export-real-dump
                      disposable-import-real cutover-prod]
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
