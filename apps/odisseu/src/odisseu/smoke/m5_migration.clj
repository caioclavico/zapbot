(ns odisseu.smoke.m5-migration
  "Runner M5.0: harness de migração sintética + relatório."
  (:require [clojure.pprint :as pprint]
            [clojure.test :as test]
            [pokemon.tests.migration-m50-test])
  (:import [java.lang.management ManagementFactory]
           [java.time Instant]))

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
        summary (test/run-tests 'pokemon.tests.migration-m50-test)
        after (assoc (heap-stats) :at (str (Instant/now)))
        ok? (and (zero? (:fail summary)) (zero? (:error summary)))]
    (println "=== M5.0 migration harness summary ===")
    (pprint/pprint {:ok ok?
                    :summary summary
                    :memory {:before before :after after}
                    :m5-complete? false
                    :nota "Importação de dados reais fica para etapa autorizada posterior."
                    :java (System/getProperty "java.version")})
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
